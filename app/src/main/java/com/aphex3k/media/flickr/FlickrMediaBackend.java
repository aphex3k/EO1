package com.aphex3k.media.flickr;

import android.util.Log;

import androidx.annotation.Nullable;

import android.util.Log;

import com.aphex3k.eo1.ConfigurationBackendEntry;
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
    private int lastForgottenCount;
    private int lastCapDrops;
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
        this.retention = new FlickrRetention(stateRoot == null ? null
                : new File(new File(stateRoot, "flickr"), entry.id + ".json"));
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
        lastCapDrops = retention.trimToCap();
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
        retention.noteHeld(asset.id, time.nowMs());
        return MediaSource.remoteOriginal(openerFor(url), null, null);
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
        return MediaSource.remoteThumbnail(openerFor(url));
    }

    /**
     * One opener for both rungs. It performs no I/O of its own and does no probing: a probe would sit
     * between an asset and its own cached bytes, because the pipeline asks for the source before it
     * looks in the cache.
     */
    private MediaSource.Opener openerFor(final String url) {
        return new MediaSource.Opener() {
            @Override
            public InputStream open() throws Exception {
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
                sb.append(", ").append(lastForgottenCount).append(" forgotten");
            }
            if (lastCapDrops > 0) {
                // Said out loud: these are cached files the ledger can no longer account for. An
                // unaccounted file must never be quietly assumed deleted.
                sb.append(", ledger capped (").append(lastCapDrops).append(")");
            }
        }
        if (!retention.isDurable()) {
            sb.append(", retention in-process only");
            if (lastBuildTruncated) {
                sb.append(", capped at ").append(FlickrClient.MAX_ASSETS);
            }
        }
        return sb.toString();
    }

    /** Nothing here holds a socket, a session or a file beyond the ledger, which saves itself. */
    @Override
    public void close() {
        retention.save();
    }

    /**
     * Honours a removal request: {@code assetId} stops being shown and its bytes go, or {@code null}
     * means the whole stream. The mark is durable and exempt from the ledger's cap, because a forgotten
     * row that aged out of the file would let the photo come back.
     */
    @Override
    public int forget(@Nullable String assetId) {
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
        retention.save();
        return 1;
    }
}
