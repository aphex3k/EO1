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
    private final HashSet<String> poolKeys = new HashSet<>();
    private int poolCursor;
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
                continue;
            }
            if (playbackFile != null) {
                acquired = asset;
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
                postException(ui, new BackendUnavailableException(e));
                continue;
            }
            // A backend that came up degraded still belongs in the rotation; tell the user
            // once per thing that changed rather than once per tick.
            String warning = backend.compatibilityWarning();
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
                    count = catalog.size();
                    for (MediaAsset asset : catalog) {
                        pool.add(asset);
                        poolKeys.add(asset.key());
                    }
                    seedVideoDurations(catalog);
                }
                backendById.put(backendId, backend);
                generation.add(backend);
                String version = backend.describeVersion();
                Log.i(TAG, "rebuildPool: backend '" + backendId + "' (" + backend.getType() + ")"
                        + (version != null ? " " + version : "")
                        + " contributed " + count + " asset(s)");
            } catch (Exception e) {
                Log.e(TAG, "rebuildPool: backend '" + backendId + "' catalog fetch failed", e);
                postException(ui, new BackendUnavailableException(e));
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
     * Whether an original is worth a download attempt at all. Codec guesses from the file name
     * are not part of this: only what this device physically cannot handle — more bytes than
     * its cache can hold, a video with a coded side beyond the panel, or bytes already verified
     * undecodable since the last restart. Everything else gets displayed and judged by the
     * decoder, which is the only thing that can be right.
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
            return true;
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

    /** The backend's degraded source for an asset, or a quiet skip when it has none. */
    @Nullable
    private File acquireResolvedFallback(MediaAsset asset, MediaBackend backend, File cacheDir,
                                         boolean reportProgress) throws Exception {
        MediaSource fallback = backend.resolveThumbnailFallback(asset);
        if (fallback == null || fallback.opener == null) {
            Log.w(TAG, "acquireAsset: " + asset.key() + " - no fallback source, skipping");
            return null;
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
            return acquireRemoteFile(asset, fallback, cacheDir, reportProgress);
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
                String reason = MediaCompatibility.incompatibleReasonForFile(asset.type, cacheFile);
                if (reason == null) {
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
                        String reason = MediaCompatibility.incompatibleReasonForFile(asset.type, cacheFile);
                        if (reason != null) {
                            Log.w(TAG, "acquireRemoteFile: " + cacheFile.getName() + " is " + reason
                                    + "; discarding it and falling back for " + asset.key());
                            deleteCachedFile(cacheFile);
                            knownIncompatibleKeys.add(asset.key());
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
        final String key = assetKey;
        new Thread(new Runnable() {
            @Override
            public void run() {
                MediaBackend backend = backendForKey(key);
                if (backend == null) {
                    Log.w(TAG, "displayThumbnailAsset: no backend for asset " + key);
                    return;
                }
                MediaAsset asset = assetFromKey(key, isVideo);
                MediaSource source;
                try {
                    source = backend.resolveThumbnailFallback(asset);
                } catch (Exception e) {
                    Log.w(TAG, "displayThumbnailAsset: " + key, e);
                    postDebugInformation("Thumbnail", "Failed to get thumbnail for asset: " + key);
                    tagAssetAsIncompatible(key);
                    return;
                }
                if (source == null || source.opener == null) {
                    Log.i(TAG, "displayThumbnailAsset: backend '" + backend.getId()
                            + "' has no fallback for " + key);
                    tagAssetAsIncompatible(key);
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
                    Log.w(TAG, "displayThumbnailAsset: " + key, e);
                    postDebugInformation("Thumbnail", "Failed to get thumbnail for asset: " + key);
                    tagAssetAsIncompatible(key);
                }
            }
        }, "thumbnail-" + key).start();
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

    /** Remaining assets in the current pool. */
    int rotationListSize() {
        return Math.max(0, pool.size() - poolCursor);
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
