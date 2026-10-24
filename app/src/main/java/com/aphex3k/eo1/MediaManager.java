package com.aphex3k.eo1;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.Nullable;

import com.aphex3k.immichApi.ImmichType;
import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaBackend;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;
import com.aphex3k.media.immich.ImmichMediaBackend;
import com.aphex3k.media.local.LocalMediaBackend;
import com.aphex3k.media.web.WebsiteBackend;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.HttpUrl;

/**
 * Drives rotation across every configured {@link MediaBackend}.
 *
 * <p>The pool holds compact {@link MediaAsset} records from all backends (N Immich hosts, an
 * optional local uploads backend, and any number of web-page backends). Each rotation tick consumes
 * one record, asks its backend for the original bytes (local file or download with integrity
 * checks), and hands the result to the UI. A web record is the exception: it has no bytes, so it
 * goes straight to the browser view and its failure is reported back later through
 * {@link #webDisplayFailed}. A per-backend failure disables only that backend for the cycle; all
 * assets failing after a refetch surfaces {@link NoMediaFoundException} as today.
 *
 * <p>After a successful display, the next pool record is prefetched in a background thread
 * ({@link #prefetchNext}) so the fixed-interval tick normally finds it already cached and the
 * on-screen display duration is independent of download time.
 */
public class MediaManager implements MediaManagerInterface, ApiServiceGenerator.ProgressListener {

    private static final String TAG = "EO1";

    /** Device memory ceiling for downloaded originals (mirrors {@code ImmichMediaBackend}). */
    public static final long MAX_ASSET_BYTES = 1073741824L;

    /**
     * Transient download failures (truncated streams, timeouts) are retried this many times
     * per acquire; integrity-check failures consume the same budget.
     */
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;

    /**
     * One rotation tick gives up after this many consecutive acquire failures and defers to
     * the next tick instead of sweeping (and possibly refetching) the whole pool.
     */
    private static final int MAX_CONSECUTIVE_ACQUIRE_FAILURES = 3;

    /**
     * How many dead web pages one rotation cycle will walk past. Page failures arrive long after
     * the tick that dispatched them ({@link #webDisplayFailed}), so the acquire-failure cap above
     * cannot cover them; without a cap, a pool of nothing but unreachable pages advances forever.
     */
    private static final int MAX_WEB_FALLBACKS_PER_CYCLE = 3;

    /**
     * Serializes cache-file writes so a download stream and the UI-side cache removal can never
     * truncate or read each other's file.
     */
    private static final ReentrantLock downloadMutex = new ReentrantLock();

    /** Prevents overlapping showNextImage worker threads (timer + retry + web control). */
    private final AtomicBoolean showNextInFlight = new AtomicBoolean(false);
    private final WeakReference<SettingsManager> settingsManager;
    private final WeakReference<MediaManagerListener> listener;
    private final WeakReference<ApiServiceGenerator.ProgressListener> downloadProgressListener;
    private final MediaCacheManager mediaCacheManager;

    /** Merged rotation pool; the worker thread consumes it with {@link #poolCursor}. */
    private final ArrayList<MediaAsset> pool = new ArrayList<>();
    /**
     * Keys of {@link #pool}, kept in step by every pool mutation. Concurrent because it is the only
     * safe way to ask "is this asset in the rotation" from a thread that is not the rotation worker —
     * the pool itself is read and written on that worker alone.
     */
    private final Set<String> poolKeys =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private int poolCursor;
    /**
     * Asset key pinned for the next rotation tick by {@link #queueAssetDisplay}; consumed (cleared)
     * by that tick's {@link #applyAssetPin}. Null when nothing is pending.
     */
    private final AtomicReference<String> pinnedAssetKey = new AtomicReference<>();
    /** Backends of the current pool, keyed by configured backend id. */
    private final ConcurrentHashMap<String, MediaBackend> backendById = new ConcurrentHashMap<>();
    /** Live backends of the current pool generation; closed on the next rebuild. */
    private final ArrayList<MediaBackend> generation = new ArrayList<>();
    /** Video durations keyed by asset key ("backendId:rawId"); read from the UI thread. */
    private final ConcurrentHashMap<String, Integer> videoDurationMsByKey = new ConcurrentHashMap<>();
    /**
     * Asset keys whose original was byte-verified as undecodable on this device (e.g. HEIF
     * content served under a .jpg name). Remembers the verdict across pool rebuilds for the
     * life of this process, so the original is downloaded once and every later rotation of
     * that asset goes straight to the backend's fallback source. Forgotten on restart, which
     * is the point: a re-encoded original gets tried again.
     */
    private final Set<String> knownIncompatibleKeys =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /**
     * Asset keys whose video asks for more sustained macroblock throughput than this device has.
     *
     * <p>Kept apart from {@link #knownIncompatibleKeys} on purpose, and it must stay that way: these
     * files are decodable, they are only decoded too slowly, so nothing here may be discarded and
     * nothing may be tagged. Same lifetime by design — forgotten on restart, so a server that later
     * produces an encode, or a device that gets a faster replacement, is re-judged from the bytes.
     */
    private final Set<String> tooDemandingVideoKeys =
            Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
    /**
     * Video assets whose degraded source turns out to be the original file itself, because the
     * server has nothing transcoded for them. Remembered so the two verdicts cannot chase each other:
     * the throughput check wants to route away from the original, and this says there is nowhere to
     * route to. Without it every cycle would re-probe the endpoint, re-add the throughput verdict,
     * re-probe, and download the same 158 MB in a circle.
     */
    private final Set<String> noTranscodeVideoKeys =
            Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
    /**
     * Video assets that were handed to a player at every rung and put nothing on the screen.
     *
     * <p>Deliberately <em>not</em> written to the backend as a tag, unlike every other verdict here.
     * The fourcc tier asserts something about the file, and this asserts only that this device's
     * decoder produced no pixels from it — a claim the sample description can look clean while the
     * profile, level or sustained throughput says otherwise. Flagging such an asset would tell the
     * operator their library is broken when all they own is a black panel, and a wrongly-written tag is
     * visible in Immich long after the frame that made it is gone. So the mark is in-memory, forgotten on
     * restart, and its only effect is that the asset is shown as its still preview instead.
     */
    private final Set<String> renderSilentVideoKeys =
            Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
    /**
     * Backend warnings already shown, keyed by backend id, config fingerprint and the warning
     * text itself. A failing pool makes {@link #rebuildPool} run again on every tick, so
     * without this a degraded backend would toast once per rotation interval. Keying on the
     * text rather than clearing the set means the same backend speaks up again when its
     * situation changes — the server upgraded, or the version probe starting to fail.
     */
    private final Set<String> warningsShown =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile String currentPlaybackPath;
    /** Last successfully displayed asset; read on the UI thread by /state. */
    private volatile MediaAsset currentAsset;
    /** Dead web pages walked past so far in this rotation cycle; see {@link #MAX_WEB_FALLBACKS_PER_CYCLE}. */
    private volatile int webFallbackDepth;
    /**
     * The last catalog fetch of each backend, as
     * {@code ok|failed|degraded at <wall clock> — <detail>, N asset(s)}.
     *
     * <p>{@link #rebuildPool}'s two unhappy exits reach only {@code Log.e}/{@code Log.w}. On a
     * sideloaded frame {@code READ_LOGS} is signature-level, so those lines land nowhere a person can
     * read — which left "the server answered with nothing displayable" and "the server never answered"
     * indistinguishable after the fact. This map is the difference, and it is what the diagnostics
     * report reads.
     */
    private final ConcurrentHashMap<String, String> backendLastFetch = new ConcurrentHashMap<>();
    /** Pool rebuilds that raised. Separates "the library is empty" from "the pool keeps failing". */
    private volatile int rebuildFailureCount;
    /** Acquire failures in the tail of the current sweep, and when the last one landed. */
    private volatile int acquireFailureRun;
    private volatile long lastAcquireFailureAtMs;
    /**
     * Acquire failures since the process started, next to the run above: a run of 2 on a frame that
     * has failed 20 000 times is a healthy retry, and the same 2 is the whole story on a frame that
     * has never failed before this minute.
     */
    private volatile long acquireFailureCount;
    /** Cache dir of the rotation currently in flight; guards removeFromCache's location check. */
    private volatile File mediaCacheDir;
    private volatile boolean poolStale;
    private volatile String currentConfigurationFingerprint = "";
    /**
     * One-record-ahead warm-up: after each successful display the next pool record is
     * staged in the background ({@link #prefetchNext}). Package-private so the cycling
     * tests can switch it off for exact download-count assertions.
     */
    volatile boolean prefetchEnabled = true;

    /**
     * Delay between retry attempts of a transient download failure. Package-private so the
     * cycling tests can zero it out.
     */
    volatile long downloadRetryBackoffMs = 1000L;

    /** Remote downloads currently in flight; gates progress forwarding to the UI. */
    private final AtomicInteger activeDownloads = new AtomicInteger(0);

    private final Random random;
    @Nullable private final List<MediaBackend> fixedBackends;
    @Nullable private final Configuration fixedConfiguration;

    public MediaManager(MediaManagerListener listener, SettingsManager settingsManager,
                        ApiServiceGenerator.ProgressListener downloadProgressListener) {
        this(listener, settingsManager, downloadProgressListener, new MediaCacheManager());
    }

    public MediaManager(MediaManagerListener listener, SettingsManager settingsManager,
                        ApiServiceGenerator.ProgressListener downloadProgressListener,
                        MediaCacheManager mediaCacheManager) {
        this(listener, settingsManager, downloadProgressListener, mediaCacheManager,
                new Random(), null, null);
    }

    // Test seam: explicit backends + deterministic RNG, no SettingsManager/Android involvement.
    MediaManager(MediaManagerListener listener, List<MediaBackend> backends,
                 Configuration configuration, Random random, MediaCacheManager mediaCacheManager) {
        this(listener, null, null, mediaCacheManager, random, backends, configuration);
    }

    private MediaManager(MediaManagerListener listener, SettingsManager settingsManager,
                         ApiServiceGenerator.ProgressListener downloadProgressListener,
                         MediaCacheManager mediaCacheManager, Random random,
                         @Nullable List<MediaBackend> fixedBackends,
                         @Nullable Configuration fixedConfiguration) {
        this.listener = new WeakReference<>(listener);
        this.settingsManager = new WeakReference<>(settingsManager);
        this.downloadProgressListener = new WeakReference<>(downloadProgressListener);
        this.mediaCacheManager = mediaCacheManager != null ? mediaCacheManager : new MediaCacheManager();
        this.random = random != null ? random : new Random();
        this.fixedBackends = fixedBackends;
        this.fixedConfiguration = fixedConfiguration;
    }

    // ---------------------------------------------------------------- rotation

    @Override
    public void showNextImage(Activity activity) {
        startRotation(activity, false);
    }

    /**
     * @param afterWebFailure {@code true} when this rotation was triggered by a page that failed to
     *        render: it continues the current cycle's web-fallback count instead of starting a new
     *        cycle and resetting it.
     */
    private void startRotation(final Activity activity, final boolean afterWebFailure) {
        MediaManagerListener mediaManagerListener = this.listener.get();
        if (mediaManagerListener == null) {
            Log.i(TAG, "showNextImage: no listener, abort");
            return;
        }
        if (!showNextInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "showNextImage: already in flight, skip parallel start");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    showNextImageLocked(activity.getCacheDir(),
                            UploadedMedia.dirFor(activity),
                            new UiPoster() {
                                @Override
                                public void post(Runnable action) {
                                    activity.runOnUiThread(action);
                                }
                            },
                            false, afterWebFailure);
                } finally {
                    showNextInFlight.set(false);
                }
            }
        }).start();
    }

    /**
     * Called from the UI thread when the kiosk browser reports that the page it was handed did not
     * render. Advances to the next asset of the same rotation cycle rather than holding a blank
     * panel, up to {@link #MAX_WEB_FALLBACKS_PER_CYCLE} dead pages per cycle.
     */
    @Override
    public void webDisplayFailed(Activity activity, String assetKey) {
        if (consumeWebFallbackSlot(assetKey)) {
            startRotation(activity, true);
        }
    }

    /**
     * Decides whether a page that failed to render may advance the rotation, and counts it if so.
     * Split out from {@link #webDisplayFailed} so the cap is testable without an {@code Activity}.
     */
    boolean consumeWebFallbackSlot(String assetKey) {
        MediaAsset shown = currentAsset;
        if (shown == null || shown.type != MediaType.WEB || !Objects.equals(assetKey, shown.key())) {
            Log.i(TAG, "webDisplayFailed: ignoring report for " + assetKey
                    + " (no longer the displayed web asset)");
            return false;
        }
        if (webFallbackDepth >= MAX_WEB_FALLBACKS_PER_CYCLE) {
            Log.w(TAG, "webDisplayFailed: " + webFallbackDepth + " pages failed this cycle; "
                    + "waiting for the next tick instead of sweeping the pool again");
            return false;
        }
        webFallbackDepth++;
        Log.i(TAG, "webDisplayFailed: " + shown.key() + " did not render, advancing ("
                + webFallbackDepth + "/" + MAX_WEB_FALLBACKS_PER_CYCLE + " this cycle)");
        return true;
    }

    /**
     * UI-thread dispatcher; {@code Activity::runOnUiThread} in production, direct execution in
     * tests.
     */
    interface UiPoster {
        void post(Runnable action);
    }

    /** A tick that starts here rather than walking on from a failed page: resets the fallback count. */
    void showNextImageLocked(File cacheDir, File uploadsDir, UiPoster ui, boolean refetched) {
        showNextImageLocked(cacheDir, uploadsDir, ui, refetched, false);
    }

    /**
     * One rotation tick. Runs on the caller's thread (production: rotation worker; tests: the
     * test thread). Consumes records from the merged pool until one resolves to something
     * displayable: a playable file, or — for a {@link MediaType#WEB} record — a page address.
     *
     * @param afterWebFailure true when this sweep continues a cycle that a failed page walked on
     *        from; keeps {@link #webFallbackDepth} counting instead of resetting it
     */
    void showNextImageLocked(File cacheDir, File uploadsDir, UiPoster ui, boolean refetched,
                             boolean afterWebFailure) {
        if (!afterWebFailure) {
            webFallbackDepth = 0;
        }
        mediaCacheDir = cacheDir;
        Configuration configuration = loadConfiguration();

        if (pool.isEmpty() || poolStale || !fingerprint(configuration).equals(currentConfigurationFingerprint)) {
            rebuildPool(configuration, uploadsDir, ui);
        }
        refreshLocalUploads();
        if (pool.isEmpty()) {
            Log.e(TAG, "showNextImage: no media found (no assets from any configured backend)");
            postException(ui, new NoMediaFoundException());
            return;
        }
        applyAssetPin();

        MediaAsset acquired = null;
        File playbackFile = null;
        String playbackUrl = null;
        int consecutiveAcquireFailures = 0;
        boolean failureCapHit = false;
        while (poolCursor < pool.size()) {
            if (consecutiveAcquireFailures >= MAX_CONSECUTIVE_ACQUIRE_FAILURES) {
                failureCapHit = true;
                break;
            }
            MediaAsset asset = pool.get(poolCursor);
            poolCursor++;
            if (asset.type == MediaType.WEB) {
                // Nothing to acquire: a page has no bytes on this device. The browser view on the
                // UI thread is what finds out whether it actually rendered.
                playbackUrl = resolvePageUrl(asset);
                if (playbackUrl != null) {
                    acquired = asset;
                    break;
                }
                continue;
            }
            try {
                playbackFile = acquireAsset(asset, cacheDir);
            } catch (Exception e) {
                Log.w(TAG, "showNextImage: asset " + asset.key() + " failed", e);
                postException(ui, e);
                consecutiveAcquireFailures++;
                acquireFailureRun = consecutiveAcquireFailures;
                acquireFailureCount++;
                lastAcquireFailureAtMs = System.currentTimeMillis();
                continue;
            }
            if (playbackFile != null) {
                acquired = asset;
                acquireFailureRun = 0;
                break;
            }
        }

        if (acquired == null) {
            if (failureCapHit) {
                // Stream trouble, not a stale pool: deferring to the next tick avoids a
                // refetch plus second sweep of the same failing assets on this tick.
                Log.w(TAG, "showNextImage: " + MAX_CONSECUTIVE_ACQUIRE_FAILURES
                        + " consecutive acquire failures; deferring to next tick");
                return;
            }
            if (!refetched) {
                // The whole remaining pool failed: refetch every backend's catalog once, retry.
                Log.w(TAG, "showNextImage: all assets failed, refetching backend catalogs");
                rebuildPool(configuration, uploadsDir, ui);
                if (pool.isEmpty()) {
                    Log.e(TAG, "showNextImage: no media found after refetch");
                    postException(ui, new NoMediaFoundException());
                    return;
                }
                showNextImageLocked(cacheDir, uploadsDir, ui, true, afterWebFailure);
                return;
            }
            Log.e(TAG, "showNextImage: all assets failed after refetch, deferring to next tick");
            postException(ui, new NoMediaFoundException());
            return;
        }

        // A page has no path; null keeps it out of the cache-eviction guard.
        currentPlaybackPath = playbackFile != null ? playbackFile.getAbsolutePath() : null;
        currentAsset = acquired;
        if (acquired.type == MediaType.VIDEO && acquired.durationMs > 0) {
            videoDurationMsByKey.put(acquired.key(), acquired.durationMs);
        }
        final MediaAsset shown = acquired;
        final File shownFile = playbackFile;
        final String shownUrl = playbackUrl;
        ui.post(new Runnable() {
            @Override
            public void run() {
                MediaManagerListener mediaManagerListener = listener.get();
                if (mediaManagerListener == null) {
                    return;
                }
                if (shown.type == MediaType.IMAGE) {
                    mediaManagerListener.displayPicture(shownFile, shown.key());
                } else if (shown.type == MediaType.VIDEO) {
                    mediaManagerListener.displayVideo(shownFile, shown.key());
                } else if (shown.type == MediaType.WEB) {
                    mediaManagerListener.displayWebsite(shownUrl, shown.key());
                } else {
                    Log.e(TAG, "showNextImage: no display path for media type " + shown.type);
                }
            }
        });
        prefetchNext(cacheDir);
    }

    /**
     * Stages the next pool record for the upcoming tick: runs it through the same
     * {@link #acquireAsset} pipeline as the display path, in a background daemon thread, so
     * its bytes land in the exact cache file + integrity sidecar the next tick checks. This
     * keeps the fixed-interval display off the network: on a healthy link the next tick is a
     * cache hit and the switch is effectively instant.
     *
     * <p>Strictly best-effort and side-effect free for the pipeline: it never advances
     * {@link #poolCursor}, never fetches catalogs, and never posts to the listener — a failure
     * is logged only and the next tick simply re-acquires normally. The record is snapshotted
     * on the rotation worker (the only thread that mutates {@link #pool}) before the thread
     * starts, so the prefetch thread never reads the pool concurrently.
     *
     * <p>A {@link MediaType#WEB} next asset is skipped outright: there is nothing to stage, and
     * pre-loading it would render a page nobody asked to see yet.
     */
    private void prefetchNext(File cacheDir) {
        if (!prefetchEnabled || poolCursor >= pool.size()) {
            return;
        }
        final MediaAsset next = pool.get(poolCursor);
        if (next.type == MediaType.WEB) {
            return;
        }
        final File dir = cacheDir;
        Thread prefetcher = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    acquireAsset(next, dir, false);
                    Log.i(TAG, "prefetch: " + next.key() + " ready for next tick");
                } catch (Exception e) {
                    Log.i(TAG, "prefetch: " + next.key() + " not staged, next tick will fetch: " + e);
                }
            }
        }, "media-prefetch");
        prefetcher.setDaemon(true);
        prefetcher.start();
    }

    // ---------------------------------------------------------------- pool

    /**
     * Rebuilds the merged pool: one backend per valid config entry, each fetched in its own
     * try/catch so a failing backend only loses its own assets this cycle.
     */
    private void rebuildPool(Configuration configuration, File uploadsDir, UiPoster ui) {
        closeGeneration();
        pool.clear();
        poolKeys.clear();
        poolCursor = 0;
        backendById.clear();
        final String generationFingerprint = fingerprint(configuration);

        for (MediaBackend backend : buildGeneration(configuration, uploadsDir)) {
            String backendId = backend.getId();
            if (backendById.containsKey(backendId)) {
                Log.w(TAG, "rebuildPool: duplicate backend id '" + backendId + "', skipping later entry");
                continue;
            }
            try {
                backend.initialize();
            } catch (Exception e) {
                Log.e(TAG, "rebuildPool: backend '" + backendId + "' initialization failed", e);
                // A backend that cannot come up makes rebuildPool run on every tick, so without the
                // same once-per-thing guard the degraded path above uses, an unreachable server or
                // a wrong password would toast for the rest of the frame's life. Keyed on the root
                // cause's class: what actually changed between one failing tick and the next.
                String reason = "init:" + rootCause(e).getClass().getName();
                if (warningsShown.add(backendId + '@' + generationFingerprint + '@' + reason)) {
                    postException(ui, new BackendUnavailableException(e));
                }
                rebuildFailureCount++;
                noteBackendFetch(backendId, "init-failed", 0,
                        rootCause(e).getClass().getName() + ": "
                                + clampFetchDetail(String.valueOf(e.getMessage())));
                continue;
            }
            // A backend that came up degraded still belongs in the rotation; tell the user
            // once per thing that changed rather than once per tick.
            String warning = backend.compatibilityWarning();
            String warnSuffix = warning == null ? "" : " | " + clampFetchDetail(warning);
            if (warning != null) {
                if (warningsShown.add(backendId + '@' + generationFingerprint + '@' + warning)) {
                    Log.w(TAG, "rebuildPool: backend '" + backendId + "' is degraded: " + warning);
                    postWarning(ui, "Media backend '" + backendId + "': " + warning);
                }
            }
            try {
                List<MediaAsset> catalog = backend.fetchCatalog();
                int count = 0;
                if (catalog != null) {
                    // An asset in an album is also in the timeline result, so one backend's catalog
                    // can name the same picture twice. The pool is a pass consumed in order, so a
                    // second entry is not a second copy of the photo — it is that photo coming up
                    // twice while the rest of the library waits. Guarded the way the per-tick local
                    // rescan already guards, and only within this backend: two hosts serving the same
                    // asset id hold different keys, and both belong in the rotation.
                    for (MediaAsset asset : catalog) {
                        if (poolKeys.add(asset.key())) {
                            pool.add(asset);
                            count++;
                        }
                    }
                    int repeated = catalog.size() - count;
                    if (repeated > 0) {
                        Log.d(TAG, "rebuildPool: backend '" + backendId + "' listed " + repeated
                                + " asset(s) more than once; each takes one slot in the pass");
                    }
                    seedVideoDurations(catalog);
                }
                backendById.put(backendId, backend);
                generation.add(backend);
                String version = backend.describeVersion();
                Log.i(TAG, "rebuildPool: backend '" + backendId + "' (" + backend.getType() + ")"
                        + (version != null ? " " + version : "")
                        + " contributed " + count + " asset(s)");
                noteBackendFetch(backendId, warning == null ? "ok" : "ok, degraded", count,
                        backend.getType() + (version == null ? "" : " " + version) + warnSuffix);
            } catch (Exception e) {
                Log.e(TAG, "rebuildPool: backend '" + backendId + "' catalog fetch failed", e);
                postException(ui, new BackendUnavailableException(e));
                rebuildFailureCount++;
                noteBackendFetch(backendId, "failed", 0, e.getClass().getSimpleName() + ": "
                        + clampFetchDetail(String.valueOf(e.getMessage())) + warnSuffix);
            }
        }

        currentConfigurationFingerprint = generationFingerprint;
        poolStale = false;
        if (!pool.isEmpty()) {
            Collections.shuffle(pool, random);
            Log.i(TAG, "rebuildPool: merged pool holds " + pool.size() + " asset(s) across "
                    + backendById.size() + " backend(s)");
        }
    }

    private List<MediaBackend> buildGeneration(Configuration configuration, File uploadsDir) {
        List<MediaBackend> generation = new ArrayList<>();
        if (fixedBackends != null) {
            generation.addAll(fixedBackends);
            return generation;
        }
        for (ConfigurationBackendEntry entry : configuration.backendsOrEmpty()) {
            if (!entry.enabled) {
                Log.i(TAG, "rebuildPool: backend entry '" + entry.id + "' (" + entry.type
                        + ") is disabled, skipping");
                continue;
            }
            if (!entry.isValid()) {
                Log.w(TAG, "rebuildPool: backend entry '" + entry.id + "' (" + entry.type
                        + ") is incomplete, skipping");
                continue;
            }
            MediaBackend backend = null;
            if (entry.isLocal()) {
                backend = new LocalMediaBackend(entry.id, uploadsDir);
            } else if (entry.isImmich()) {
                backend = new ImmichMediaBackend(entry, this);
            } else if (entry.isWeb()) {
                backend = new WebsiteBackend(entry);
            }
            if (backend == null) {
                Log.w(TAG, "rebuildPool: unknown backend type '" + entry.type + "' for '"
                        + entry.id + "', skipping");
            } else {
                generation.add(backend);
            }
        }
        return generation;
    }

    private void closeGeneration() {
        for (MediaBackend backend : generation) {
            try {
                backend.close();
            } catch (Exception e) {
                Log.w(TAG, "rebuildPool: closing backend '" + backend.getId() + "' failed", e);
            }
        }
        generation.clear();
    }

    /**
     * Local uploads are re-scanned every tick so a web upload joins the rotation on the next
     * interval without a restart or pool rebuild. New ids are appended to the pool tail and the
     * unshown tail is reshuffled so they mix in; already-picked assets are never touched.
     */
    private void refreshLocalUploads() {
        for (MediaBackend backend : generation) {
            if (!ConfigurationBackendEntry.TYPE_LOCAL.equalsIgnoreCase(backend.getType())) {
                continue;
            }
            List<MediaAsset> current;
            try {
                current = backend.fetchCatalog();
            } catch (Exception e) {
                Log.w(TAG, "refreshLocalUploads: rescan of backend '" + backend.getId() + "' failed", e);
                continue;
            }
            if (current == null) {
                continue;
            }
            int added = 0;
            for (MediaAsset asset : current) {
                if (poolKeys.add(asset.key())) {
                    pool.add(asset);
                    added++;
                }
            }
            if (added > 0 && poolCursor < pool.size()) {
                int tailSize = pool.size() - poolCursor;
                List<MediaAsset> tail = new ArrayList<>(pool.subList(poolCursor, pool.size()));
                Collections.shuffle(tail, random);
                for (int i = 0; i < tailSize; i++) {
                    pool.set(poolCursor + i, tail.get(i));
                }
                Log.i(TAG, "refreshLocalUploads: " + added + " new local upload(s) added to rotation");
            }
        }
    }

    // ---------------------------------------------------------------- by-asset display

    /**
     * Asks that one specific rotation asset be what the next tick displays — the by-ID control
     * {@code ?action=next} cannot offer, because a shuffled thousand-asset pool gives no way to land
     * on a chosen record. Only the request is made here: {@link #applyAssetPin} resolves it on the
     * rotation worker after that tick has fetched its catalogs, so no pool record is touched from the
     * calling thread and a request that arrives before this process's first tick is still honored.
     * Everything the tick then does is the ordinary rotation path, fallback ladder included.
     *
     * @param assetKey the pipeline key, {@code "backendId:rawId"} — the form {@code /state}'s
     *                 {@code media.current.key} reports
     * @return {@code false} when the key is empty or this generation's pool is known not to hold it.
     *         A pool that has never been fetched is accepted on trust, and {@link #applyAssetPin}
     *         reports it in the log if it turns out not to contain the asset.
     */
    boolean queueAssetDisplay(String assetKey) {
        if (assetKey == null || assetKey.isEmpty()) {
            return false;
        }
        if (!poolKeys.isEmpty() && !poolKeys.contains(assetKey)) {
            Log.w(TAG, "queueAssetDisplay: " + assetKey + " is not in the rotation pool ("
                    + poolKeys.size() + " asset(s))");
            return false;
        }
        pinnedAssetKey.set(assetKey);
        return true;
    }

    /**
     * Drops a pending request no tick is going to consume — the caller knows that when the screen is
     * meant to be off and the rotation loop is standing down. Compares before clearing, so an
     * abandoned dispatch cannot cancel a later request's pin; a pin left set would otherwise put that
     * asset on screen at the next real rotation, hours later and unprompted.
     */
    void cancelAssetDisplay(String assetKey) {
        if (assetKey != null && pinnedAssetKey.compareAndSet(assetKey, null)) {
            Log.i(TAG, "cancelAssetDisplay: dropped pending display of " + assetKey);
        }
    }

    /**
     * Moves the pinned record to {@link #poolCursor} so the sweep takes it first. A swap, not an
     * insertion: the record that was next in line takes the pinned one's place, so it still comes up
     * in this cycle instead of being skipped.
     *
     * <p>With the whole pool already consumed, the cursor restarts at the head — otherwise a pinned
     * asset that had its turn earlier in the cycle would lose to the refetch that begins a fresh,
     * reshuffled generation, and the request would display something else.
     */
    private void applyAssetPin() {
        String key = pinnedAssetKey.getAndSet(null);
        if (key == null) {
            return;
        }
        int pinned = -1;
        for (int i = 0; i < pool.size(); i++) {
            if (key.equals(pool.get(i).key())) {
                pinned = i;
                break;
            }
        }
        if (pinned < 0) {
            Log.w(TAG, "showNextImage: pinned asset " + key + " is not in this cycle's pool");
            postDebugInformation("Show", "Asset not in the rotation pool: " + key);
            return;
        }
        int at = poolCursor;
        if (at >= pool.size()) {
            at = 0;
            poolCursor = 0;
        }
        MediaAsset wasNext = pool.get(at);
        pool.set(at, pool.get(pinned));
        pool.set(pinned, wasNext);
        Log.i(TAG, "showNextImage: pinned asset " + key + " takes this tick");
    }

    private void seedVideoDurations(List<MediaAsset> catalog) {
        for (MediaAsset asset : catalog) {
            if (asset.type == MediaType.VIDEO && asset.durationMs > 0) {
                videoDurationMsByKey.put(asset.key(), asset.durationMs);
            }
        }
    }

    /**
     * Backend list changed since the current pool was built? Marks the pool stale so the next
     * tick refetches; unrelated setting changes (quiet hours, interval) do not.
     */
    public void invalidatePoolIfStale() {
        String fp = fingerprint(loadConfiguration());
        if (!fp.equals(currentConfigurationFingerprint)) {
            Log.i(TAG, "invalidatePoolIfStale: backend configuration changed; pool will be refetched");
            poolStale = true;
        }
    }

    private Configuration loadConfiguration() {
        if (fixedConfiguration != null) {
            return fixedConfiguration;
        }
        SettingsManager settings = settingsManager.get();
        if (settings != null) {
            settings.loadConfiguration();
            Configuration c = settings.getConfiguration();
            if (c != null) {
                return c;
            }
        }
        return new Configuration();
    }

    /** SHA-1 over the valid backend entries' type/id/enabled/host/userid/apiVersion/password. */
    private static String fingerprint(Configuration configuration) {
        StringBuilder sb = new StringBuilder();
        for (ConfigurationBackendEntry e : configuration.backendsOrEmpty()) {
            if (!e.isValid()) {
                continue;
            }
            sb.append(e.type).append('|')
                    .append(e.id).append('|')
                    .append(e.enabled).append('|')
                    .append(e.host).append('|')
                    .append(e.userid).append('|')
                    .append(e.apiVersion).append('|')
                    .append(e.password).append('\n');
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-1")
                    .digest(sb.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            // Fingerprint only needs to change with the config; the raw text still does.
            return sb.toString();
        }
    }

    private void postWarning(UiPoster ui, String message) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                MediaManagerListener mediaManagerListener = listener.get();
                if (mediaManagerListener != null) {
                    mediaManagerListener.warnUser(message);
                }
            }
        });
    }

    /** The innermost cause — the part of a wrapped failure that distinguishes one from another. */
    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }

    private void postException(UiPoster ui, Exception e) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                MediaManagerListener mediaManagerListener = listener.get();
                if (mediaManagerListener != null) {
                    mediaManagerListener.handleException(e);
                }
            }
        });
    }

    // ---------------------------------------------------------------- acquisition

    /**
     * Resolves one record to a playable file. The original is always the first attempt; only
     * when it cannot be used — byte-verified undecodable, or physically out of reach of this
     * device (bigger than its cache, a video with a coded side beyond the panel) — does the
     * asset fall back to the backend's degraded source. Null (not an exception) when the asset
     * should be skipped this cycle: the backend declines it (deleted file, no source), or its
     * original is unusable and the backend has no fallback.
     */
    private File acquireAsset(MediaAsset asset, File cacheDir) throws Exception {
        return acquireAsset(asset, cacheDir, true);
    }

    /**
     * {@code reportProgress=false} is used by the background prefetch so its download does not
     * move the UI progress bar ({@link #clientProgressUpdates} gates on {@link #activeDownloads}).
     */
    private File acquireAsset(MediaAsset asset, File cacheDir, boolean reportProgress) throws Exception {
        MediaBackend backend = backendById.get(asset.backendId);
        if (backend == null) {
            Log.w(TAG, "acquireAsset: no backend for asset " + asset.key());
            return null;
        }
        if (noTranscodeVideoKeys.contains(asset.key())) {
            // Taking the original because there is nothing to degrade to must not mean quietly
            // tolerating a duplicate left on disk by the run that worked that out.
            discardByteIdenticalFallback(asset, cacheDir);
        }
        if (!originalUsable(asset)) {
            return acquireResolvedFallback(asset, backend, cacheDir, reportProgress);
        }
        MediaSource source = backend.resolveOriginal(asset);
        if (source == null) {
            return null;
        }
        if (source.isLocal()) {
            return source.localFile;
        }
        File original;
        try {
            original = acquireRemoteFile(asset, source, cacheDir, reportProgress);
        } catch (MediaDownloadFailedException e) {
            if (e.getCause() != null) {
                // Something on the wire failed, not this asset: reporting it to the rotation is
                // right, and falling back would spend 1000 thumbnail requests on a dead network.
                throw e;
            }
            MediaSource fallback = backend.resolveThumbnailFallback(asset);
            if (fallback == null || fallback.opener == null) {
                // Nothing left to try: report the failure to the rotation rather than bury an
                // asset that is both unobtainable and has no degraded source.
                throw e;
            }
            Log.w(TAG, "acquireAsset: " + asset.key() + " original is unobtainable ("
                    + e.getMessage() + "); falling back");
            return acquireFallbackSource(asset, fallback, cacheDir, reportProgress);
        }
        if (original == null) {
            return acquireResolvedFallback(asset, backend, cacheDir, reportProgress);
        }
        return original;
    }

    /**
     * Whether an original is worth a download attempt at all. What this can settle without the
     * bytes: more of them than the cache can hold, a video with a coded side beyond the panel, or
     * a verdict already reached on this asset's bytes since the last restart — which for a video
     * is the only pre-download codec knowledge the pipeline ever gets, since no backend reports a
     * codec and a container extension is a claim rather than a fact. Everything else gets
     * displayed and judged by the decoder, which is the only thing that can be right.
     */
    private boolean originalUsable(MediaAsset asset) {
        String key = asset.key();
        if (knownIncompatibleKeys.contains(key)) {
            Log.i(TAG, "acquireAsset: " + key + " original verified undecodable earlier this"
                    + " run; going straight to the fallback source");
            return false;
        }
        if (asset.localPath != null) {
            // Already on disk: no download to avoid and no cache to overflow, so the decoder gets
            // the bytes and decides. Its verdict is filed as a sidecar report, not a silent skip.
            // The throughput verdict is not consulted here either: a local upload has no degraded
            // source to be routed to, so rerouting it would mean not showing it at all.
            return true;
        }
        if (tooDemandingVideoKeys.contains(key) && !noTranscodeVideoKeys.contains(key)) {
            Log.i(TAG, "acquireAsset: " + key + " exceeds this device's decode throughput;"
                    + " starting at the fallback source");
            return false;
        }
        if (asset.sizeBytes != null && asset.sizeBytes > MAX_ASSET_BYTES) {
            Log.w(TAG, "acquireAsset: " + key + " original is " + asset.sizeBytes
                    + " bytes, beyond this device's " + MAX_ASSET_BYTES
                    + " byte ceiling; routing to the fallback source");
            return false;
        }
        String oversized = MediaCompatibility.incompatibleVideoResolution(
                asset.type, asset.width, asset.height);
        if (oversized != null) {
            Log.w(TAG, "acquireAsset: " + key + " - " + oversized
                    + "; routing to the fallback source");
            return false;
        }
        return true;
    }

    /**
     * Notes that an asset's original is decodable but asks for more sustained decode throughput than
     * this device has, so every later acquisition of it starts at the backend's fallback source.
     *
     * <p>Returning true only means "descend now". {@link #acquireAsset} already falls through to the
     * fallback when obtaining an original yields null, so a verdict taken on bytes already in hand
     * costs one download and never a slow display cycle — which is the whole reason this is decided
     * against the file rather than from catalog metadata that never carries a frame rate.
     *
     * <p>The original is deliberately <em>not</em> discarded, unlike every other verdict in this
     * class. If the server has no encode, its fallback endpoint serves these same bytes, and keeping
     * the cached copy is what stops the frame fetching 158 MB twice over to display one clip.
     *
     * @param probe the probe already run on the file for the codec check, reused here so a video's
     *              {@code moov} is read once per acquisition rather than twice
     */
    private boolean noteVideoOverThroughputBudget(MediaAsset asset,
                                                  @Nullable VideoHeaderProbe.Probe probe) {
        if (asset.type != MediaType.VIDEO || probe == null || noTranscodeVideoKeys.contains(asset.key())) {
            return false;
        }
        String reason = MediaCompatibility.incompatibleVideoThroughput(
                asset.type, probe.codedWidth, probe.codedHeight, probe.stats.fpsMilli);
        if (reason == null) {
            return false;
        }
        tooDemandingVideoKeys.add(asset.key());
        Log.w(TAG, "acquireRemoteFile: " + asset.key() + " - " + reason
                + "; starting it at the fallback source, original kept cached");
        return true;
    }

    /**
     * Takes the original because this asset has no transcode, and makes sure no byte-identical
     * fallback survives to say otherwise.
     *
     * <p>A duplicate under the {@code _fb} name is pure loss twice over: it doubles the bytes on a
     * device with about a gigabyte of free space, and because the cache keys eviction on file name it
     * competes for that space with files that decode differently. It also buys nothing — those bytes
     * are the same stream the original already failed to keep up with. So it is not merely avoided
     * going forward: an existing one, left by an earlier build or by a probe the server could not
     * answer, is deleted here.
     *
     * <p>Remembering the asset in {@link #noTranscodeVideoKeys} is what keeps this from looping. The
     * throughput verdict says "go to the fallback", the fallback says "there isn't one", and without
     * the mark each would re-arm the other every cycle.
     */
    @Nullable
    private File useOriginalWithoutTranscode(MediaAsset asset, MediaBackend backend, File cacheDir,
                                             boolean reportProgress, @Nullable String why)
            throws Exception {
        noTranscodeVideoKeys.add(asset.key());
        discardByteIdenticalFallback(asset, cacheDir);
        if (why != null) {
            Log.i(TAG, "acquireAsset: " + asset.key() + " - " + why);
        }
        MediaSource original = backend.resolveOriginal(asset);
        if (original == null) {
            return null;
        }
        return original.isLocal() ? original.localFile
                : acquireRemoteFile(asset, original, cacheDir, reportProgress);
    }

    /**
     * Deletes a fallback artifact that turns out to be the original's bytes under another name.
     *
     * <p>Called separately from the paths that discover this, because both verdicts live only for the
     * life of the process: an asset already marked as having no transcode goes straight to the
     * original and would never again walk the branch that removes a duplicate an earlier run wrote.
     * A file whose length differs from the asset's is a real transcode and is left alone — the point
     * is to remove copies, not to discard the degraded source the gates exist to reach.
     */
    private void discardByteIdenticalFallback(MediaAsset asset, File cacheDir) {
        if (asset.type != MediaType.VIDEO) {
            return;
        }
        File duplicate = new File(cacheDir, cacheFileName(asset.id, asset.originalFileName,
                asset.originalPath, ImmichType.VIDEO, true));
        if (!duplicate.isFile()) {
            return;
        }
        if (asset.sizeBytes != null && duplicate.length() != asset.sizeBytes) {
            return;
        }
        Log.i(TAG, "acquireAsset: " + asset.key() + " removing byte-identical fallback "
                + duplicate.getName() + " (" + duplicate.length() + " B) from cache");
        deleteCachedFile(duplicate);
    }

    /** The backend's degraded source for an asset, or a quiet skip when it has none. */
    @Nullable
    private File acquireResolvedFallback(MediaAsset asset, MediaBackend backend, File cacheDir,
                                         boolean reportProgress) throws Exception {
        MediaSource fallback = backend.resolveThumbnailFallback(asset);
        if (fallback == null || fallback.opener == null) {
            Log.w(TAG, "acquireAsset: " + asset.key() + " - no fallback source, skipping");
            return null;
        }
        // Both video gates route here assuming the degraded source is the smaller one. A server with
        // nothing transcoded answers /video/playback with the ORIGINAL file, so that assumption is
        // simply false, and paying it off means fetching 158 MB twice to display the same slow clip
        // twice — measured on an EO2, where the cache ended up holding the asset's bytes under two
        // names. When the endpoint reports its answer is the asset's own size there is nothing to
        // degrade to, so take the original and remember that, which is also what stops the
        // throughput verdict and this probe from driving each other round and round.
        if (fallback.isVideoPlayback() && fallback.expectedBytes != null
                && asset.sizeBytes != null
                && fallback.expectedBytes.longValue() == asset.sizeBytes.longValue()) {
            return useOriginalWithoutTranscode(asset, backend, cacheDir, reportProgress,
                    "the degraded source is the original byte for byte (" + asset.sizeBytes
                            + " B); no transcode exists");
        }
        return acquireFallbackSource(asset, fallback, cacheDir, reportProgress);
    }

    /**
     * Acquires an already-resolved degraded source. A failure that belongs to this asset rather
     * than to the wire is the ladder's last rung falling over: the original was already given up
     * on, so the asset is flagged. The display-error path flags the same case when the fallback
     * arrives but will not decode.
     */
    @Nullable
    private File acquireFallbackSource(MediaAsset asset, MediaSource fallback, File cacheDir,
                                       boolean reportProgress) throws Exception {
        try {
            File artifact = acquireRemoteFile(asset, fallback, cacheDir, reportProgress);
            if (artifact == null) {
                return null;
            }
            // The probe is the cheap way to avoid a fallback that is only a copy of the original;
            // this is the guarantee that one does not survive when the probe could not answer. A
            // server with nothing transcoded answers the degraded request with the original bytes
            // under a different name, and the file is worthless in that shape whatever it cost to
            // obtain, so it is deleted rather than kept and reasoned about later.
            if (asset.type == MediaType.VIDEO && fallback.isVideoPlayback()
                    && asset.sizeBytes != null && artifact.length() == asset.sizeBytes) {
                MediaBackend backend = backendById.get(asset.backendId);
                if (backend == null) {
                    return artifact;
                }
                return useOriginalWithoutTranscode(asset, backend, cacheDir, reportProgress,
                        "the fetched degraded source is the original byte for byte ("
                                + artifact.length() + " B); discarding the duplicate");
            }
            // A server with nothing transcoded answers the degraded request with the original
            // bytes, so this rung can be exactly as undecodable as the first one. Handing it to a
            // player would paint a black frame and call that success: it is the ladder running
            // out, which is decided here rather than left to a decoder that may never complain.
            String unusable = MediaCompatibility.incompatibleReasonForFile(asset.type, artifact);
            if (unusable != null) {
                Log.w(TAG, "acquireAsset: " + asset.key() + " fallback source is " + unusable
                        + "; flagging it as incompatible with this device");
                deleteCachedFile(artifact);
                knownIncompatibleKeys.add(asset.key());
                tagAssetAsIncompatible(asset.key());
                return null;
            }
            return artifact;
        } catch (MediaDownloadFailedException e) {
            if (e.getCause() != null) {
                throw e;
            }
            Log.w(TAG, "acquireAsset: " + asset.key() + " fallback is unobtainable ("
                    + e.getMessage() + "); flagging it as incompatible with this device");
            tagAssetAsIncompatible(asset.key());
            return null;
        }
    }

    /**
     * Resolves a {@link MediaType#WEB} asset to the page address its backend holds for it. Deliberately
     * outside {@link #acquireAsset}: a page has no bytes, so it must never reach the download, cache
     * or integrity paths.
     */
    @Nullable
    private String resolvePageUrl(MediaAsset asset) {
        MediaBackend backend = backendById.get(asset.backendId);
        if (backend == null) {
            Log.w(TAG, "resolvePageUrl: no backend for asset " + asset.key());
            return null;
        }
        MediaSource source = backend.resolveOriginal(asset);
        if (source == null || source.kind != MediaSource.Kind.REMOTE_URL || source.url == null) {
            Log.w(TAG, "resolvePageUrl: backend '" + asset.backendId
                    + "' gave no page address for " + asset.key());
            return null;
        }
        return source.url;
    }

    /**
     * Downloads (or verifies the cache for) a remote asset, original or fallback alike. A cached
     * file is reused only after an integrity check — checksum and size against the backend's
     * reference where the backend reports one (originals), otherwise against the hex sidecar
     * ({@code <file>.sha1}) written when the bytes landed; fresh bytes are digested in flight.
     * Mismatched files are discarded and re-fetched. A fallback lives under its own cache name,
     * so a degraded stream never overwrites the original it stands in for.
     *
     * <p>Returns {@code null} when an original's bytes turn out to be a format this device cannot
     * decode (e.g. HEIF content served under a {@code .jpg} name): the file is discarded and the
     * asset key is remembered in {@link #knownIncompatibleKeys} so the original is not
     * re-downloaded for the life of this process. Only originals are byte-checked this way — a
     * fallback that cannot be decoded is reported by the display path, which flags the asset.
     */
    private File acquireRemoteFile(MediaAsset asset, MediaSource source, File cacheDir)
            throws MediaDownloadFailedException, IOException {
        return acquireRemoteFile(asset, source, cacheDir, true);
    }

    /**
     * {@code reportProgress=false} (background prefetch) leaves {@link #activeDownloads}
     * untouched so the download does not drive the UI progress bar.
     */
    private File acquireRemoteFile(MediaAsset asset, MediaSource source, File cacheDir,
                                   boolean reportProgress)
            throws MediaDownloadFailedException, IOException {
        boolean fallback = source.isFallback();
        ImmichType type = asset.type == MediaType.VIDEO ? ImmichType.VIDEO : ImmichType.IMAGE;
        File cacheFile = new File(cacheDir,
                cacheFileName(asset.id, asset.originalFileName, asset.originalPath, type, fallback));

        if (cacheFile.exists() && cacheFile.length() > 0) {
            if (MediaIntegrity.isCacheFileUsable(cacheFile, source.expectedChecksum, source.expectedBytes)) {
                if (fallback) {
                    return cacheFile;
                }
                VideoHeaderProbe.Probe probe = asset.type == MediaType.VIDEO
                        ? VideoHeaderProbe.probe(cacheFile) : null;
                String reason = MediaCompatibility.incompatibleReasonForFile(
                        asset.type, cacheFile, probe);
                if (reason == null) {
                    if (noteVideoOverThroughputBudget(asset, probe)) {
                        return null;
                    }
                    return cacheFile;
                }
                Log.w(TAG, "acquireRemoteFile: cached " + cacheFile.getName() + " is " + reason
                        + "; discarding it and falling back for " + asset.key());
                deleteCachedFile(cacheFile);
                knownIncompatibleKeys.add(asset.key());
                return null;
            }
            Log.w(TAG, "acquireRemoteFile: cached " + cacheFile.getName()
                    + " failed integrity check, discarding and re-downloading");
            deleteCachedFile(cacheFile);
        }

        long bytesNeeded = source.expectedBytes != null && source.expectedBytes > 0
                ? source.expectedBytes
                : MAX_ASSET_BYTES;
        if (!mediaCacheManager.ensureSpace(cacheDir, bytesNeeded, protectedCachePaths(null))) {
            throw new MediaDownloadFailedException("Insufficient cache space for asset download");
        }

        if (reportProgress) {
            activeDownloads.incrementAndGet();
        }
        try {
            for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
                if (attempt > 1) {
                    try {
                        Thread.sleep(downloadRetryBackoffMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MediaDownloadFailedException(e);
                    }
                }
                final InputStream body;
                try {
                    body = source.opener.open();
                } catch (MediaDownloadFailedException e) {
                    throw e;
                } catch (IOException e) {
                    if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                        Log.w(TAG, "acquireRemoteFile: open of " + asset.key()
                                + " failed, attempt " + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS + ": " + e);
                        continue;
                    }
                    throw new MediaDownloadFailedException(e);
                } catch (Exception e) {
                    throw new MediaDownloadFailedException(e);
                }

                final byte[] digest;
                downloadMutex.lock();
                try (InputStream in = body) {
                    digest = MediaIntegrity.writeStreamToFile(cacheFile, in);
                } catch (IOException e) {
                    deleteCachedFile(cacheFile);
                    if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                        Log.w(TAG, "acquireRemoteFile: download of " + asset.key()
                                + " truncated mid-stream, attempt " + attempt + "/"
                                + MAX_DOWNLOAD_ATTEMPTS + ": " + e);
                        continue;
                    }
                    throw e;
                } finally {
                    downloadMutex.unlock();
                }

                if (MediaIntegrity.isDownloadValid(cacheFile, digest, fallback,
                        source.expectedChecksum, source.expectedBytes)) {
                    try {
                        MediaIntegrity.writeSidecar(cacheFile, MediaIntegrity.toHex(digest));
                    } catch (IOException e) {
                        Log.w(TAG, "acquireRemoteFile: failed to write integrity sidecar for "
                                + cacheFile.getName(), e);
                    }
                    if (!fallback) {
                        VideoHeaderProbe.Probe probe = asset.type == MediaType.VIDEO
                                ? VideoHeaderProbe.probe(cacheFile) : null;
                        String reason = MediaCompatibility.incompatibleReasonForFile(
                                asset.type, cacheFile, probe);
                        if (reason != null) {
                            Log.w(TAG, "acquireRemoteFile: " + cacheFile.getName() + " is " + reason
                                    + "; discarding it and falling back for " + asset.key());
                            deleteCachedFile(cacheFile);
                            knownIncompatibleKeys.add(asset.key());
                            return null;
                        }
                        if (noteVideoOverThroughputBudget(asset, probe)) {
                            return null;
                        }
                    }
                    return cacheFile;
                }

                Log.w(TAG, "acquireRemoteFile: download of " + cacheFile.getName()
                        + " failed integrity check, attempt " + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS);
                deleteCachedFile(cacheFile);
            }
        } finally {
            if (reportProgress) {
                activeDownloads.decrementAndGet();
            }
        }

        throw new MediaDownloadFailedException("Downloaded asset " + asset.key() + " failed integrity check");
    }

    // ---------------------------------------------------------------- cache maintenance

    /**
     * Cache file name for a media file.
     *
     * <p>Uses the raw asset id (never the "backendId:rawId" key): the ownership guard in
     * {@link MediaCacheManager} only recognises id-shaped names, and per-host UUIDs make raw ids
     * unique in practice; any rare cross-host collision is caught by the integrity gate, which
     * discards a cached file whose checksum does not match.
     */
    static String cacheFileName(String uuid, String originalFileName, String originalPath,
                                ImmichType type, boolean fallback) {
        if (fallback) {
            // Marked, because an original named ".jpg" would otherwise cache the thumbnail under
            // its own file name and the two sources would evict each other every rotation.
            return uuid + "_fb." + defaultExtension(type);
        }

        String extension = extensionFromFileName(originalFileName);
        if (extension == null) {
            extension = extensionFromFileName(originalPath);
        }
        if (extension == null) {
            extension = "." + defaultExtension(type);
        }
        return uuid + extension;
    }

    /** Extracts a safe lower-case ".ext" (1-10 alphanumeric chars) from the last path component. */
    static String extensionFromFileName(String input) {
        if (input == null) {
            return null;
        }
        int slash = Math.max(input.lastIndexOf('/'), input.lastIndexOf('\\'));
        String base = slash >= 0 ? input.substring(slash + 1) : input;
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            return null;
        }
        String candidate = base.substring(dot + 1).toLowerCase();
        if (candidate.matches("[a-z0-9]{1,10}")) {
            return "." + candidate;
        }
        return null;
    }

    private static String defaultExtension(ImmichType type) {
        return type == ImmichType.VIDEO ? "mp4" : "jpg";
    }

    /** Deletes a cache file and its integrity sidecar. No-op for files the cache manager does not own. */
    private void deleteCachedFile(File file) {
        if (file == null || !MediaCacheManager.isOwnedMediaCacheFile(file)) {
            return;
        }
        MediaIntegrity.deleteSidecar(file);
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "deleteCachedFile: could not delete " + file.getAbsolutePath());
        }
    }

    @Override
    public void removeFromCache(final File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        // The cache dir changes per-activity in theory; only remove from the dir we know about.
        if (mediaCacheDir != null && !file.getParentFile().getAbsolutePath().equals(mediaCacheDir.getAbsolutePath())) {
            Log.i(TAG, "removeFromCache: refusing, " + file + " not in " + mediaCacheDir);
            return;
        }
        if (!MediaCacheManager.isOwnedMediaCacheFile(file)) {
            Log.i(TAG, "removeFromCache: refusing, " + file + " not owned media cache file");
            return;
        }

        try {
            downloadMutex.lock();
            MediaIntegrity.deleteSidecar(file);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "removeFromCache: could not delete " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "removeFromCache: " + file.getAbsolutePath(), e);
        } finally {
            downloadMutex.unlock();
        }
    }

    /**
     * True when {@code file} is a complete local media file the rotation pipeline would reuse
     * as a cache hit: non-empty and digest-matching when an integrity sidecar exists. The
     * fallback path uses this to replay a failed video's original instead of re-downloading
     * the same bytes through the backend's fallback stream.
     */
    public boolean isUsableLocalVideo(File file) {
        return MediaIntegrity.isCacheFileUsable(file, null, null);
    }

    /** Paths the cache manager must never evict while a download is reserving space. */
    private Set<String> protectedCachePaths(File extra) {
        Set<String> paths = new HashSet<>();
        if (currentPlaybackPath != null) {
            paths.add(currentPlaybackPath);
        }
        if (extra != null) {
            paths.add(extra.getAbsolutePath());
        }
        return paths;
    }

    // ---------------------------------------------------------------- asset-key operations

    /**
     * Shows a preview thumbnail (image) or a /video/playback stream (video) for an asset whose
     * original failed to display. This is the second and last attempt: a backend with no
     * fallback source, or a fallback that cannot be fetched, flags the asset as incompatible.
     */
    @Override
    public void displayThumbnailAsset(Activity activity, String assetKey, boolean isVideo) {
        displayFallbackSource(activity, assetKey, isVideo, true);
    }

    /**
     * @param tagOnDeadEnd false when the asset reaching this rung is not evidence about the file. The
     *                     ladder's own terminal rung passes true; the render-silent still passes false,
     *                     because a frame that produced no pixels has already been judged by this
     *                     device once and a server that cannot answer with a preview is not the user's
     *                     problem to be tagged for.
     */
    private void displayFallbackSource(final Activity activity, final String assetKey,
                                       final boolean isVideo, final boolean tagOnDeadEnd) {
        final String key = assetKey;
        new Thread(new Runnable() {
            @Override
            public void run() {
                MediaBackend backend = backendForKey(key);
                if (backend == null) {
                    Log.w(TAG, "displayFallbackSource: no backend for asset " + key);
                    return;
                }
                MediaAsset asset = assetFromKey(key, isVideo);
                MediaSource source;
                try {
                    source = backend.resolveThumbnailFallback(asset);
                } catch (Exception e) {
                    Log.w(TAG, "displayFallbackSource: " + key, e);
                    postDebugInformation("Thumbnail", "Failed to get thumbnail for asset: " + key);
                    if (tagOnDeadEnd) {
                        tagAssetAsIncompatible(key);
                    }
                    return;
                }
                if (source == null || source.opener == null) {
                    Log.i(TAG, "displayFallbackSource: backend '" + backend.getId()
                            + "' has no fallback for " + key);
                    if (tagOnDeadEnd) {
                        tagAssetAsIncompatible(key);
                    }
                    return;
                }
                try {
                    mediaCacheDir = activity.getCacheDir();
                    File thumbnail = acquireRemoteFile(asset, source, activity.getCacheDir());
                    final File shown = thumbnail;
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            MediaManagerListener mediaManagerListener = listener.get();
                            if (mediaManagerListener == null) {
                                return;
                            }
                            if (isVideo) {
                                mediaManagerListener.displayFallbackVideo(shown, key);
                            } else {
                                mediaManagerListener.displayFallbackPicture(shown, key);
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "displayFallbackSource: " + key, e);
                    postDebugInformation("Thumbnail", "Failed to get thumbnail for asset: " + key);
                    if (tagOnDeadEnd) {
                        tagAssetAsIncompatible(key);
                    }
                }
            }
        }, "thumbnail-" + key).start();
    }

    /**
     * Whether this asset's video is known to put nothing on this device's screen. True only for the
     * life of the process; a restart re-judges it from the pixels.
     */
    public boolean isRenderSilent(String assetKey) {
        return assetKey != null && renderSilentVideoKeys.contains(assetKey);
    }

    /**
     * Records that a video produced no frame at every rung of the ladder. Never tags anything — see
     * {@link #renderSilentVideoKeys} for why this verdict is different from the rest.
     *
     * @return true the first time this key is recorded, so the caller can log once and skip otherwise
     */
    public boolean noteRenderSilent(String assetKey) {
        if (assetKey == null) {
            return false;
        }
        if (!renderSilentVideoKeys.add(assetKey)) {
            return false;
        }
        Log.w(TAG, "noteRenderSilent: " + assetKey + " put nothing on screen at every rung; showing its"
                + " still preview instead (not tagging it — this says nothing about the file, and is"
                + " forgotten on restart)");
        postDebugInformation("video", "render-silent " + assetKey);
        return true;
    }

    /** Displays a render-silent video as its still preview. Idempotent; records nothing. */
    public void showRenderSilentStill(Activity activity, String assetKey) {
        displayFallbackSource(activity, assetKey, false, false);
    }

    /**
     * Marks an asset with the backend's incompatible tag (Immich: find-or-create
     * EO1_INCOMPATIBLE). Local backends no-op. No-op for unknown keys.
     *
     * <p>Called only after both the original and the fallback source failed to display, and
     * repeated on every rotation that fails: the tag exists to surface the asset in the Immich
     * UI for the operator to fix (regenerate its thumbnails) or take out of the frame's albums,
     * so a stale tag must never retire an asset the app would happily show again. The app does
     * not read this tag back as a filter, and re-tagging an already-tagged asset is a no-op
     * server-side.
     */
    @Override
    public void tagAssetAsIncompatible(final String assetKey) {
        if (isRenderSilent(assetKey)) {
            // Refused on purpose, and checked here rather than at each call site because this is the
            // one verdict that must never reach the server. A render-silent clip is displayed as its
            // still preview from here on, and that still is itself displayed through the ladder's
            // terminal rung — so without this gate, a preview Glide cannot decode would tag an asset
            // whose video this device merely failed to draw, on every rotation that reaches it.
            Log.i(TAG, "tagAssetAsIncompatible: " + assetKey + " is held as render-silent on this"
                    + " device, not incompatible with it; leaving the backend untagged");
            postDebugInformation("Incompatible", "skipped " + assetKey
                    + " — render-silent verdict, deliberately never tagged");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                MediaBackend backend = backendForKey(assetKey);
                if (backend == null) {
                    Log.w(TAG, "tagAssetAsIncompatible: no backend for asset " + assetKey);
                    postDebugInformation("Incompatible", "No backend for asset: " + assetKey);
                    return;
                }
                try {
                    backend.markIncompatible(assetFromKey(assetKey, false));
                    Log.i(TAG, "tagAssetAsIncompatible: flagged " + assetKey + " in backend '"
                            + backend.getId() + "'");
                } catch (Exception e) {
                    // Reported through the listener, so it reaches /logs and eo1-app.log and not
                    // only the 13-minute logcat buffer: a tag the server refused is otherwise
                    // indistinguishable from an asset that never needed one.
                    Log.w(TAG, "tagAssetAsIncompatible: " + assetKey, e);
                    postDebugInformation("Incompatible", "could not flag " + assetKey + " in '"
                            + backend.getId() + "': " + e);
                }
            }
        }, "tag-incompatible-" + assetKey).start();
    }

    private MediaBackend backendForKey(String key) {
        if (key == null) {
            return null;
        }
        int colon = key.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return backendById.get(key.substring(0, colon));
    }

    /** Rebuilds the minimal record a fallback/tagging call needs from an opaque asset key. */
    private static MediaAsset assetFromKey(String key, boolean isVideo) {
        int colon = key.indexOf(':');
        String rawId = colon > 0 ? key.substring(colon + 1) : key;
        String backendId = colon > 0 ? key.substring(0, colon) : "";
        return new MediaAsset(rawId, backendId, isVideo ? MediaType.VIDEO : MediaType.IMAGE,
                -1, null, null, null, null, null, 0, 0);
    }

    private void postDebugInformation(String key, String value) {
        MediaManagerListener mediaManagerListener = listener.get();
        if (mediaManagerListener != null) {
            mediaManagerListener.debugInformationProvided(new DebugInformation(key, value));
        }
    }

    // ---------------------------------------------------------------- misc accessors

    /** Last successfully displayed asset, or null before the first display. */
    public MediaAsset getCurrentAsset() {
        return currentAsset;
    }

    /** Duration in ms of a previously-shown video, or -1 when unknown. */
    public int getVideoDurationMs(String assetKey) {
        if (assetKey == null) {
            return -1;
        }
        Integer ms = videoDurationMsByKey.get(assetKey);
        return ms != null ? ms : -1;
    }

    /**
     * Remaining assets in the current pool.
     *
     * <p>Named for what it returns: the tail of this pass, not the size of the library. A pool of
     * 40 000 assets with 3 left reads as "3" here, which is the difference between a thin library
     * and a frame that is rotating normally.
     */
    int rotationListSize() {
        return Math.max(0, pool.size() - poolCursor);
    }

    /** Assets in the rotation pool, from the concurrent key set. Safe to call from any thread. */
    public int poolKeyCount() {
        return poolKeys.size();
    }

    /**
     * Assets left in this pass. The cursor belongs to the rotation worker and is not volatile, so
     * this may answer a little behind it — which is what a report wants anyway.
     */
    public int poolRemaining() {
        return rotationListSize();
    }

    /** How far the current cycle has walked past dead web pages. */
    public int webFallbackDepth() {
        return webFallbackDepth;
    }

    /** Acquire failures in the tail of the current sweep. */
    public int acquireFailureRun() {
        return acquireFailureRun;
    }

    /** Acquire failures since the process started. */
    public long acquireFailureCount() {
        return acquireFailureCount;
    }

    public long lastAcquireFailureAtMs() {
        return lastAcquireFailureAtMs;
    }

    /** Pool rebuilds that raised since the process started. */
    public int rebuildFailureCount() {
        return rebuildFailureCount;
    }

    /**
     * Per-backend account of the last catalog fetch, newest answer wins. Keys are backend ids from
     * configuration, so this names a host only as the operator already named it.
     */
    public java.util.Map<String, String> backendFetchRecords() {
        return new java.util.LinkedHashMap<>(backendLastFetch);
    }

    /**
     * The four verdict sets, as counts. Keys are deliberately withheld: a library of 40 000 assets
     * would otherwise spend half a report's budget listing uuids, and the number is what answers
     * "has this frame quietly given up on a slice of the library". Counts only is also what keeps a
     * throughput verdict — which is a statement about this decoder, not about the user's files — from
     * reading like one.
     */
    public String verdictCounts() {
        return "incompatible=" + knownIncompatibleKeys.size()
                + " tooDemanding=" + tooDemandingVideoKeys.size()
                + " noTranscode=" + noTranscodeVideoKeys.size()
                + " renderSilent=" + renderSilentVideoKeys.size();
    }

    private void noteBackendFetch(String backendId, String status, int count, String detail) {
        backendLastFetch.put(backendId, status + " at "
                + DiagnosticsReport.localTime(System.currentTimeMillis())
                + " — " + detail + ", " + count + " asset(s)");
    }

    /** Keeps one exception message from out-growing the record it belongs to. */
    private static String clampFetchDetail(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= 200 ? one : one.substring(0, 200) + "...";
    }

    @Override
    public void clientProgressUpdates(long bytesRead, long contentLength, boolean done,
                                      @Nullable HttpUrl url) {
        // Forward only while a download is actually in flight: local playback and catalog
        // fetches must not move the UI progress bar.
        if (activeDownloads.get() <= 0) {
            return;
        }
        ApiServiceGenerator.ProgressListener progressListener = downloadProgressListener.get();
        if (progressListener != null) {
            progressListener.clientProgressUpdates(bytesRead, contentLength, done, url);
        }
    }
}
