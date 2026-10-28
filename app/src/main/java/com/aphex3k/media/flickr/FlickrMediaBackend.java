package com.aphex3k.media.flickr;

import android.util.Log;

import androidx.annotation.Nullable;

import android.util.Log;

import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.eo1.MediaDownloadFailedException;
import com.aphex3k.eo1.MediaManagerInterface;
import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaBackend;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A Flickr photostream or album as a rotation source, read with an application key and nothing else.
 *
 * <p>The shape of this backend is decided by two facts that are not visible in its own code. First,
 * Flickr reports no byte size anywhere a listing can see it, and the pipeline caches by size — see
 * {@link FlickrUrls} for why the ladder rungs are therefore fixed tiers rather than whatever the
 * response happened to offer. Second, {@link #resolveThumbnailFallback} is reached from the
 * display-error path with a record rebuilt from the asset key alone, so the degraded URL is computed
 * from {@link MediaAsset#id} and from no other field — which is also what makes it identical on both of
 * the paths that call it.
 *
 * <p>What it deliberately does not do:
 * <ul>
 *   <li><b>Tag anything.</b> {@code markIncompatible} stays the interface default. A {@code 410} from a
 *       Flickr size says something about the <em>account's</em> subscription, never about whether this
 *       device can decode the file, and a durable verdict about someone's library that a later server
 *       change could not undo is exactly what the project rules forbid.</li>
 *   <li><b>Call the API per tick.</b> One catalog build per pool generation, and no request inside
 *       {@code resolveOriginal}. The issue this backend answers reports that only three or four frames
 *       could run at once on one key: traffic that scales with devices <em>is</em> the failure, so the
 *       traffic shape is the feature.</li>
 *   <li><b>Pretend a video is one.</b> Flickr documents no downloadable video file — no {@code .mp4}
 *       template anywhere, and {@code photos.getSizes} lists no video size — so a video asset enters the
 *       pool as its poster still. Claiming {@link MediaType#VIDEO} for a JPEG URL would also name its
 *       cache file {@code <id>.mp4}, which the file-level check would judge undecodable by its container
 *       and then flag forever.</li>
 * </ul>
 */
public class FlickrMediaBackend implements MediaBackend {

    private static final String TAG = "EO1";

    /** A wall clock, swappable in tests: the TTL is measured in days and must survive a restart. */
    interface TimeSource {
        long nowMs();
    }

    private static final TimeSource SYSTEM_CLOCK = new TimeSource() {
        @Override
        public long nowMs() {
            return System.currentTimeMillis();
        }
    };

    private final ConfigurationBackendEntry entry;
    private final FlickrClient client;
    private final String cdnBase;
    @Nullable private final MediaManagerInterface manager;
    private final FlickrRetention retention;
    @Nullable private final File stateRoot;
    private int lastForgottenCount;
    private int lastLedgerOverflow;
    private long lastMaintenanceMs;
    private TimeSource time = SYSTEM_CLOCK;

    /** Non-null after a successful {@link #initialize()}. */
    @Nullable private FlickrClient.Target target;
    @Nullable private String compatibilityWarning;

    /** What the last catalog build counted, reported through {@link #describeVersion()}. */
    private int lastPhotoCount;
    private int lastVideoCount;
    private int lastUnbuildableCount;
    private boolean lastBuildTruncated;

    public FlickrMediaBackend(ConfigurationBackendEntry entry) {
        this(entry, FlickrClient.REST_ORIGIN, FlickrUrls.DEFAULT_CDN_BASE, null, null);
    }

    /**
     * @param stateRoot per-install directory from which this backend derives its own state store, or
     *        {@code null} when the caller has none to offer. A backend with no state root cannot keep
     *        the promises {@link FlickrRetention} exists for across a restart, so it says so in
     *        {@link #describeVersion()} rather than looking healthy while retaining nothing.
     * @param manager   how cached bytes get deleted; only the manager knows the current cache dir.
     */
    public FlickrMediaBackend(ConfigurationBackendEntry entry, @Nullable File stateRoot,
                              @Nullable MediaManagerInterface manager) {
        this(entry, FlickrClient.REST_ORIGIN, FlickrUrls.DEFAULT_CDN_BASE, stateRoot, manager);
    }

    /**
     * @param restOrigin REST endpoint origin — a test substitutes a local server's.
     * @param cdnOrigin  image host origin, for the same reason. A Flickr entry carries no {@code host}
     *        field and the {@code server} component of a photo URL is constrained by the cache-name
     *        pattern, so neither could hold a mock address.
     */
    public FlickrMediaBackend(ConfigurationBackendEntry entry, String restOrigin, String cdnOrigin) {
        this(entry, restOrigin, cdnOrigin, null, null);
    }

    FlickrMediaBackend(ConfigurationBackendEntry entry, String restOrigin, String cdnOrigin,
                       @Nullable File stateRoot, @Nullable MediaManagerInterface manager) {
        this.entry = entry;
        this.cdnBase = cdnOrigin;
        this.manager = manager;
        this.client = new FlickrClient(entry.apiKey, restOrigin);
        this.stateRoot = stateRoot;
        // Null stays null: a relative path would write the ledger wherever the process happens to be,
        // and isDurable() would then claim persistence the device never got.
        this.retention = new FlickrRetention(stateRoot == null ? null : ledgerFile(stateRoot, entry.id));
    }

    /** Test seam for the idle window. */
    void setTimeSource(TimeSource timeSource) {
        this.time = timeSource;
    }

    @Override
    public String getId() {
        return entry.id;
    }

    @Override
    public String getType() {
        return ConfigurationBackendEntry.TYPE_FLICKR;
    }

    @Override
    public void initialize() throws Exception {
        retention.load();
        // An operator who removed a Flickr source has not thereby forgiven the photos it cached: the
        // idle window and the privacy record have to be honoured by whoever is running now, not only by
        // the backend that wrote the ledger. So the first thing a Flickr backend does is sweep every
        // ledger in the directory, including its own.
        sweepOtherLedgers();
        target = client.resolve(entry.source);
        // Everything this says is true before any request is made, which is the only time the method is
        // read: MediaManager calls compatibilityWarning() one step before fetchCatalog(), so a warning
        // derived from a catalog would arrive a whole generation late — about 100 days on a large
        // stream. Observations belong in describeVersion(), which is read after the fetch.
        compatibilityWarning = "Reading Flickr with an application key only: public photos marked Safe,"
                + " and never anything above 1024px.";
        Log.i(TAG, "Flickr '" + entry.id + "' target nsid=" + target.nsid + " album="
                + (target.photosetId == null ? "(stream)" : target.photosetId));
    }

    /**
     * Pages the stream into compact records. Bounded by {@link FlickrClient#MAX_ASSETS} rather than by a
     * page count, because a page ceiling at 100 per page would license a pool of hundreds of thousands
     * of records held for the life of the process on a device with about a gigabyte free. A build that
     * hit the ceiling says so through {@link #describeVersion()} instead of looking complete.
     */
    @Override
    public List<MediaAsset> fetchCatalog() throws Exception {
        FlickrClient.Target current = target;
        if (current == null) {
            throw new IllegalStateException("Flickr backend '" + entry.id + "' used before initialize");
        }
        List<MediaAsset> assets = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        int photos = 0;
        int videos = 0;
        int unbuildable = 0;
        int forgotten = 0;
        boolean truncated = false;

        int page = 1;
        int pages = 1;
        do {
            FlickrClient.Page result = current.photosetId == null
                    ? client.publicPhotos(current.nsid, page)
                    : client.photosetPhotos(current.nsid, current.photosetId, page);
            pages = result.pages;
            for (FlickrClient.Photo photo : result.photos) {
                String assetId = FlickrUrls.assetId(photo.server, photo.photoId, photo.secret);
                if (assetId == null) {
                    // Without a server or a secret no URL can be built from the id alone, which is the
                    // only form both ladder rungs can rely on. Counting it is honest; guessing is not.
                    unbuildable++;
                    continue;
                }
                if (retention.isForgotten(assetId)) {
                    // An owner asked for this one to stop being shown. Filtering it here, rather than
                    // deleting its bytes and letting the next tick download it again, is what makes a
                    // removal request hold past the day it was made.
                    forgotten++;
                    continue;
                }
                listed.add(assetId);
                retention.noteListed(assetId);
                if ("video".equalsIgnoreCase(photo.media)) {
                    videos++;
                } else {
                    photos++;
                }
                // width/height come from the 1024px rendition when the response carried them and are 0
                // otherwise; sizeBytes and checksum stay null because Flickr does not report either,
                // which is safe only because the pipeline reserves what fits rather than a ceiling.
                assets.add(new MediaAsset(assetId, entry.id, MediaType.IMAGE, -1, null,
                        assetId + ".jpg", null, null, null, photo.width, photo.height));
                if (assets.size() >= FlickrClient.MAX_ASSETS) {
                    truncated = true;
                    break;
                }
            }
            if (truncated) {
                break;
            }
            page++;
        } while (page <= pages);

        // Privacy first: bytes for photos this listing no longer describes are gone. getPublicPhotos is
        // the authority on visibility, so an entry vanishing from a completed build means deleted or
        // made private, and the terms want that reflected as soon as reasonably possible.
        for (String gone : retention.vanishedFrom(listed, !truncated)) {
            dropCached(gone);
        }
        // Then age: a photo idle past the retention window loses its cached copy, and displaying it
        // again re-fetches it — which is also how a tier the account has since lost gets rediscovered.
        for (String stale : retention.expired(time.nowMs())) {
            dropCached(stale);
            retention.forgetBytes(stale);
        }
        lastForgottenCount = forgotten;
        lastLedgerOverflow = retention.trimToCap();
        retention.save();
        lastPhotoCount = photos;
        lastVideoCount = videos;
        lastUnbuildableCount = unbuildable;
        lastBuildTruncated = truncated;
        if (unbuildable > 0) {
            Log.w(TAG, "Flickr '" + entry.id + "': " + unbuildable
                    + " listing entries carried no server or secret and were skipped");
        }
        return assets;
    }

    @Override
    @Nullable
    public MediaSource resolveOriginal(MediaAsset asset) {
        String url = FlickrUrls.originalUrl(asset.id, cdnBase);
        if (url == null) {
            return null;
        }
        if (retention.isForgotten(asset.id)) {
            // Unreachable in normal use — the catalog filters these first — but a rung that could be
            // reached for a forgotten photo must not put its bytes back on the device.
            Log.w(TAG, "Flickr '" + entry.id + "': asked to display a forgotten photo, skipping");
            return null;
        }
        // Asking for the source is as close as this layer comes to "this photo was shown", and the
        // idle window is meant to track exactly that. Renewal is deliberately not done for a photo the
        // last completed catalog no longer lists; that case is handled by vanishedFrom above.
        long nowMs = time.nowMs();
        retention.noteHeld(asset.id, nowMs);
        runMaintenance(nowMs);
        return MediaSource.remoteOriginal(openerFor(url, asset.id), null, null);
    }

    /**
     * Frees one asset's bytes through the manager, which is the only thing that knows the current cache
     * directory and applies the ownership guards. No path is ever assembled here.
     */
    private void dropCached(String assetId) {
        MediaManagerInterface sink = manager;
        if (sink == null) {
            return;
        }
        sink.dropCachedAsset(entry.id, assetId);
    }

    /**
     * The degraded rung: 640px where the original is 1024px. Reads only {@link MediaAsset#id} — the
     * acquisition path hands over a real asset and the display-error path hands over one rebuilt from a
     * key with every other field empty, and a rule that consulted the difference would put two different
     * artifacts under one {@code <id>_fb.jpg} name with no checksum to tell them apart.
     */
    @Override
    @Nullable
    public MediaSource resolveThumbnailFallback(MediaAsset asset) {
        String url = FlickrUrls.fallbackUrl(asset.id, cdnBase);
        if (url == null) {
            return null;
        }
        if (retention.isForgotten(asset.id)) {
            // The display-error path reaches this rung directly, through displayFallbackSource, and it
            // can be reached for a photo that was on screen when the removal arrived. Honouring the mark
            // only in resolveOriginal would therefore let the degraded rung re-download exactly the
            // photo an owner asked to have removed.
            Log.w(TAG, "Flickr '" + entry.id + "': fallback requested for a forgotten photo, skipping");
            return null;
        }
        return MediaSource.remoteThumbnail(openerFor(url, asset.id));
    }

    /**
     * One opener for both rungs. It performs no I/O of its own and does no probing: a probe would sit
     * between an asset and its own cached bytes, because the pipeline asks for the source before it
     * looks in the cache.
     */
    private MediaSource.Opener openerFor(final String url, final String assetId) {
        return new MediaSource.Opener() {
            @Override
            public InputStream open() throws Exception {
                // Checked at open, not only when the source was resolved: a removal can land between the
                // two, and a fetch already in flight would otherwise put the bytes back after the delete.
                if (retention.isForgotten(assetId)) {
                    throw new MediaDownloadFailedException("Flickr asset was forgotten at "
                            + "the operator's request");
                }
                return client.openBytes(url);
            }
        };
    }

    @Override
    @Nullable
    public String compatibilityWarning() {
        return compatibilityWarning;
    }

    /**
     * What this generation's catalog actually proved. This is the only string a backend that returned
     * normally can still influence: {@code MediaManager} reads it after {@code fetchCatalog} and
     * concatenates it into both the log line and the {@code /logs} fetch record. Kept short, because on
     * the success path it bypasses the record's own truncation.
     */
    @Override
    @Nullable
    public String describeVersion() {
        StringBuilder sb = new StringBuilder("flickr REST (key-only)");
        if (lastPhotoCount + lastVideoCount > 0) {
            sb.append(", ").append(lastPhotoCount).append(" photo(s)");
            if (lastVideoCount > 0) {
                sb.append(", ").append(lastVideoCount).append(" video(s) as stills");
            }
            if (lastUnbuildableCount > 0) {
                sb.append(", ").append(lastUnbuildableCount).append(" unlistable");
            }
            if (lastForgottenCount > 0) {
                sb.append(", ").append(lastForgottenCount).append(" withheld on request");
            }
        }
        // Everything below is a caveat, and each is reported whether or not the one above it fired.
        if (lastBuildTruncated) {
            sb.append(", capped at ").append(FlickrClient.MAX_ASSETS);
        }
        if (lastLedgerOverflow > 0) {
            // Said out loud: these are cached files the ledger had to stop accounting for, and an
            // unaccounted file must never be quietly assumed deleted.
            sb.append(", ").append(lastLedgerOverflow).append(" beyond ledger cap");
        }
        if (!retention.isDurable()) {
            sb.append(", retention in-process only");
        }
        return sb.toString();
    }

    /**
     * The ledger path for one backend id, with the id hex-encoded.
     *
     * <p>An id is configuration text, and {@code Configuration.normalize} only trims it — a document
     * that names this backend {@code ../../configuration} would otherwise be joined straight into a
     * path, and the ledger's save would then be writing somewhere the frame does not expect, including
     * over its own settings. Hex keeps the name inside the directory by construction and stays
     * recognizable in a log line.
     */
    static java.io.File ledgerFile(java.io.File stateRoot, String backendId) {
        return new java.io.File(new java.io.File(stateRoot, "flickr"),
                FlickrUrls.hex(backendId == null ? "" : backendId) + ".json");
    }

    /**
     * Sweeps every ledger in this install's Flickr state directory, not just this backend's.
     *
     * <p>Removing or disabling a source does not withdraw the promises its cached photos carry. Whoever
     * is running now has to honour them, or the bytes of a stream nobody reads any more sit on the device
     * past every window that was supposed to bound them.
     */
    private void sweepOtherLedgers() {
        java.io.File root = stateRoot;
        if (root == null) {
            return;
        }
        java.io.File dir = new java.io.File(root, "flickr");
        java.io.File[] ledgers = dir.isDirectory() ? dir.listFiles() : null;
        if (ledgers == null) {
            return;
        }
        long nowMs = time.nowMs();
        for (java.io.File ledger : ledgers) {
            if (ledger.equals(retention.fileForSweep())) {
                continue;
            }
            FlickrRetention other = new FlickrRetention(ledger);
            other.load();
            for (String stale : other.expired(nowMs)) {
                MediaManagerInterface sink = manager;
                if (sink != null) {
                    sink.dropCachedAsset("flickr", stale);
                }
                other.forgetBytes(stale);
            }
            try {
                other.save();
            } catch (Exception e) {
                Log.w(TAG, "Flickr: could not persist sweep of " + ledger.getName() + ": " + e);
            }
        }
    }

    /**
     * Applies the idle window where time is actually passing, rather than only when a catalog is built.
     *
     * <p>{@code MediaManager} rebuilds a healthy pool only once it has been consumed, so on a 20,000-photo
     * stream at the default five-minute interval a rebuild is roughly 69 days away — and a sweep tied to
     * the rebuild would let a photo idle far past its window. Paced so it costs nothing on a busy frame.
     */
    private void runMaintenance(long nowMs) {
        if (nowMs - lastMaintenanceMs < MAINTENANCE_INTERVAL_MS) {
            return;
        }
        lastMaintenanceMs = nowMs;
        boolean changed = false;
        for (String stale : retention.expired(nowMs)) {
            dropCached(stale);
            retention.forgetBytes(stale);
            changed = true;
        }
        if (changed) {
            try {
                retention.save();
            } catch (Exception e) {
                Log.w(TAG, "Flickr retention sweep could not be persisted: " + e);
            }
        }
    }

    private static final long MAINTENANCE_INTERVAL_MS = 60L * 60L * 1000L;

    /**
     * Flushes the ledger one last time. A failure here is logged rather than thrown — the interface's
     * {@code close} reports nothing, and by the time a generation is retired every removal mark was
     * already persisted by the call that made it. What is lost is bookkeeping, not a promise.
     */
    @Override
    public void close() {
        try {
            retention.save();
        } catch (Exception e) {
            Log.w(TAG, "Flickr '" + entry.id + "': final ledger flush failed", e);
        }
    }

    /**
     * Honours a removal request: {@code assetId} stops being shown and its bytes go, or {@code null}
     * means the whole stream. The mark is durable and exempt from the ledger's cap, because a forgotten
     * row that aged out of the file would let the photo come back.
     */
    @Override
    public int forget(@Nullable String assetId) throws java.io.IOException {
        if (assetId == null) {
            int count = 0;
            for (String known : retention.knownIds()) {
                retention.markForgotten(known);
                dropCached(known);
                count++;
            }
            retention.save();
            return count;
        }
        retention.markForgotten(assetId);
        dropCached(assetId);
        // Throws if the mark could not be persisted. Reporting success on an unpersisted mark would mean
        // the removal lapses at the next restart, which is the one clause this path exists to satisfy.
        retention.save();
        return 1;
    }
}
