package com.aphex3k.media.immich;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aphex3k.eo1.ApiServiceGenerator;
import com.aphex3k.eo1.BackendUnavailableException;
import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.immichApi.ImmichApiAssetResponse;
import com.aphex3k.immichApi.ImmichExifInfo;
import com.aphex3k.immichApi.ImmichType;
import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaBackend;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;
import com.vdurmont.semver4j.Semver;

import java.util.ArrayList;
import java.util.List;

/**
 * Immich implementation of {@link MediaBackend}: one configured host with one
 * versioned client that holds a live authenticated session across rotation ticks.
 *
 * <p>{@link #initialize()} resolves the API-version band and logs in. An explicit
 * pin skips probing and uses the band registered for that version; "auto" probes
 * {@code GET /api/server/version} and, when the probe fails, falls back to best
 * effort on the newest client. A server newer than every known band is served
 * best effort by that newest client and reports a {@link #compatibilityWarning()};
 * only a server too old for any band raises
 * {@link BackendUnavailableException}, disabling this backend.
 */
public class ImmichMediaBackend implements MediaBackend {

    private static final String TAG = "EO1";

    private static final String INCOMPATIBLE_TAG_NAME = "EO1_INCOMPATIBLE";

    private final ConfigurationBackendEntry entry;
    @Nullable private final ApiServiceGenerator.ProgressListener progressListener;
    private ImmichClient client;
    @Nullable private Semver resolvedVersion;
    @Nullable private String compatibilityWarning;

    public ImmichMediaBackend(ConfigurationBackendEntry entry,
                              @Nullable ApiServiceGenerator.ProgressListener progressListener) {
        this.entry = entry;
        this.progressListener = progressListener;
    }

    @Override
    public void initialize() throws Exception {
        compatibilityWarning = null;
        Semver pin = ImmichClientRegistry.parsePin(entry.apiVersion);
        if (pin != null) {
            // Explicit pin: no probe, use the band registered for this version.
            ImmichClientRegistry.Resolution resolution = ImmichClientRegistry.resolve(pin);
            if (resolution == null) {
                throw new BackendUnavailableException("no Immich client implementation for pinned version "
                        + pin + " (supported range " + ImmichClientRegistry.supportedRange() + ")");
            }
            client = instantiate(resolution.client);
            resolvedVersion = pin;
            if (!resolution.exact) {
                compatibilityWarning = outsideBand(pin, resolution.client);
            }
        } else {
            client = instantiate(ImmichClientRegistry.newestClient());
            Semver probed = client.probeServerVersion();
            if (probed != null) {
                ImmichClientRegistry.Resolution resolution = ImmichClientRegistry.resolve(probed);
                if (resolution == null) {
                    throw new BackendUnavailableException("Immich server " + probed + " (" + entry.host
                            + ") is older than the supported range " + ImmichClientRegistry.supportedRange()
                            + "; no client implementation available");
                }
                if (resolution.client != client.getClass()) {
                    client = instantiate(resolution.client);
                }
                resolvedVersion = probed;
                if (!resolution.exact) {
                    compatibilityWarning = outsideBand(probed, resolution.client);
                }
            } else {
                // Probe failed: best effort against the newest band we ship.
                compatibilityWarning = "Could not determine the Immich server version on " + entry.host
                        + "; assuming " + ImmichClientRegistry.supportedRange()
                        + " — some assets may fail.";
            }
        }
        if (compatibilityWarning != null) {
            Log.w(TAG, "Backend '" + entry.id + "': " + compatibilityWarning);
        }
        client.login();
    }

    /** Text for a server newer than any band we know, which we still try to serve. */
    private static String outsideBand(Semver version, Class<? extends ImmichClient> band) {
        return "Immich " + version + " is outside the supported range ("
                + ImmichClientRegistry.supportedRange() + "); continuing best effort with "
                + band.getSimpleName() + " — some assets may fail.";
    }

    @Override
    @Nullable
    public String compatibilityWarning() {
        return compatibilityWarning;
    }

    /**
     * Builds the rotation pool from every image and video the account can see. Nothing here
     * judges whether this device can <em>decode</em> an asset: that is decided by displaying
     * it (original first, then the backend's fallback), never by metadata that may be stale —
     * a regenerated thumbnail or a re-encoded original must come back into the rotation on the
     * next pool rebuild without an app change.
     */
    @Override
    public List<MediaAsset> fetchCatalog() throws Exception {
        List<ImmichApiAssetResponse> raw = client.fetchCatalogAssets();
        List<MediaAsset> assets = new ArrayList<>(raw.size());
        int ineligible = 0;
        for (ImmichApiAssetResponse r : raw) {
            if (isDisplayable(r)) {
                assets.add(toMediaAsset(entry.id, r));
            } else {
                ineligible++;
            }
        }
        if (ineligible > 0) {
            Log.d(TAG, "Catalog '" + entry.id + "': " + ineligible
                    + " entry(s) are not images or videos, or are in the trash");
        }
        return assets;
    }

    @Override
    public MediaSource resolveOriginal(MediaAsset asset) {
        final String rawId = asset.id;
        final ImmichClient c = client;
        return MediaSource.remoteOriginal(() -> c.originalStream(rawId), asset.checksum, asset.sizeBytes);
    }

    @Override
    public MediaSource resolveThumbnailFallback(MediaAsset asset) {
        final String rawId = asset.id;
        final ImmichClient c = client;
        if (asset.type == MediaType.VIDEO) {
            return MediaSource.remoteVideoPlayback(() -> c.videoPlaybackStream(rawId));
        }
        return MediaSource.remoteThumbnail(() -> c.thumbnailStream(rawId));
    }

    /**
     * Flags the asset with {@value #INCOMPATIBLE_TAG_NAME} so the operator can find it in the
     * Immich UI. The tag is <em>not</em> a filter: the app keeps showing the asset and re-tags it
     * every time both the original and the fallback stream fail, so the tag means "this one needs
     * attention in the library" — regenerate its thumbnails, re-encode it, or drop it from the
     * albums this frame reads. Clearing the tag is the operator's call, never the app's.
     */
    @Override
    public void markIncompatible(MediaAsset asset) throws Exception {
        // Runs on the calling thread (the manager's tagging thread) and lets every failure
        // propagate to be reported there; it starts no thread of its own.
        client.tagAssetIncompatible(asset.id, INCOMPATIBLE_TAG_NAME);
    }

    @Override
    @Nullable
    public String describeVersion() {
        String version = resolvedVersion != null
                ? String.valueOf(resolvedVersion)
                : "(unknown, assumed " + ImmichClientRegistry.supportedRange() + ")";
        return "immich " + version + " via " + client.getClass().getSimpleName();
    }

    @Override
    public String getId() {
        return entry.id;
    }

    @Override
    public String getType() {
        return ConfigurationBackendEntry.TYPE_IMMICH;
    }

    /**
     * Pool-membership gate, deliberately silent on codecs: the asset must be an image or a
     * video the account still has (a trashed asset's original URL stops resolving), and that
     * is all. Whether this device can decode it is answered by displaying it.
     */
    public static boolean isDisplayable(@NonNull ImmichApiAssetResponse asset) {
        if (Boolean.TRUE.equals(asset.getIsTrashed())) {
            return false;
        }
        return asset.getType() == ImmichType.IMAGE || asset.getType() == ImmichType.VIDEO;
    }

    /** Pure mapping from an Immich DTO to a compact pipeline record. */
    public static MediaAsset toMediaAsset(String backendId, @NonNull ImmichApiAssetResponse asset) {
        Integer duration = asset.getDuration();
        ImmichExifInfo exif = asset.getExifInfo();
        Long size = exif != null ? exif.getFileSizeInByte() : null;
        Integer width = exif != null ? exif.getExifImageWidth() : null;
        Integer height = exif != null ? exif.getExifImageHeight() : null;
        return new MediaAsset(
                asset.getId(),
                backendId,
                asset.getType() == ImmichType.VIDEO ? MediaType.VIDEO : MediaType.IMAGE,
                duration != null ? duration : -1,
                asset.getChecksum(),
                asset.getOriginalFileName(),
                asset.getOriginalPath(),
                size,
                null,
                width != null ? width : 0,
                height != null ? height : 0);
    }

    /**
     * All {@code ImmichClientVN} implementations share this constructor
     * (host, userid, password, progress listener).
     */
    private ImmichClient instantiate(Class<? extends ImmichClient> band) throws Exception {
        return band.getConstructor(String.class, String.class, String.class,
                ApiServiceGenerator.ProgressListener.class)
                .newInstance(entry.host, entry.userid, entry.password, progressListener);
    }
}
