package com.aphex3k.media.flickr;

import android.util.Log;

import androidx.annotation.Nullable;

import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaBackend;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    private final ConfigurationBackendEntry entry;
    private final FlickrClient client;
    private final String cdnBase;

    /** Non-null after a successful {@link #initialize()}. */
    @Nullable private FlickrClient.Target target;
    @Nullable private String compatibilityWarning;

    /** What the last catalog build counted, reported through {@link #describeVersion()}. */
    private int lastPhotoCount;
    private int lastVideoCount;
    private int lastUnbuildableCount;
    private boolean lastBuildTruncated;

    public FlickrMediaBackend(ConfigurationBackendEntry entry) {
        this(entry, FlickrClient.REST_ORIGIN, FlickrUrls.DEFAULT_CDN_BASE);
    }

    /**
     * @param restOrigin REST endpoint origin — a test substitutes a local server's.
     * @param cdnOrigin  image host origin, for the same reason. A Flickr entry carries no {@code host}
     *        field and the {@code server} component of a photo URL is constrained by the cache-name
     *        pattern, so neither could hold a mock address.
     */
    public FlickrMediaBackend(ConfigurationBackendEntry entry, String restOrigin, String cdnOrigin) {
        this.entry = entry;
        this.cdnBase = cdnOrigin;
        this.client = new FlickrClient(entry.apiKey, restOrigin);
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
        int photos = 0;
        int videos = 0;
        int unbuildable = 0;
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
        return MediaSource.remoteOriginal(openerFor(url), null, null);
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
            if (lastBuildTruncated) {
                sb.append(", capped at ").append(FlickrClient.MAX_ASSETS);
            }
        }
        return sb.toString();
    }

    /** Nothing here holds a socket, a session or a file, so there is nothing to close. */
    @Override
    public void close() {
    }
}
