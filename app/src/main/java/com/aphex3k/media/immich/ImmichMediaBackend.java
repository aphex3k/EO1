package com.aphex3k.media.immich;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aphex3k.eo1.ApiServiceGenerator;
import com.aphex3k.eo1.BackendUnavailableException;
import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.eo1.MediaCompatibility;
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
 * effort within the V3 band. Out-of-band versions raise
 * {@link BackendUnavailableException}, disabling only this backend.
 */
public class ImmichMediaBackend implements MediaBackend {

    private static final String TAG = "EO1";

    /** Device memory ceiling: originals larger than this are not fetchable. */
    public static final long MAX_ASSET_BYTES = 1073741824L;

    private static final String INCOMPATIBLE_TAG_NAME = "EO1_INCOMPATIBLE";

    private final ConfigurationBackendEntry entry;
    @Nullable private final ApiServiceGenerator.ProgressListener progressListener;
    private ImmichClient client;
    @Nullable private Semver resolvedVersion;

    public ImmichMediaBackend(ConfigurationBackendEntry entry,
                              @Nullable ApiServiceGenerator.ProgressListener progressListener) {
        this.entry = entry;
        this.progressListener = progressListener;
    }

    @Override
    public void initialize() throws Exception {
        Semver pin = ImmichClientRegistry.parsePin(entry.apiVersion);
        if (pin != null) {
            // Explicit pin: no probe, use the band registered for this version.
            Class<? extends ImmichClient> band = ImmichClientRegistry.clientForVersion(pin);
            if (band == null) {
                throw new BackendUnavailableException("no Immich client implementation for pinned version "
                        + pin + " (supported range " + ImmichClientRegistry.supportedRange() + ")");
            }
            client = instantiate(band);
            resolvedVersion = pin;
        } else {
            client = new ImmichClientV3(entry.host, entry.userid, entry.password, progressListener);
            Semver probed = client.probeServerVersion();
            if (probed != null) {
                Class<? extends ImmichClient> band = ImmichClientRegistry.clientForVersion(probed);
                if (band == null) {
                    throw new BackendUnavailableException("Immich server " + probed + " (" + entry.host
                            + ") is outside the supported range " + ImmichClientRegistry.supportedRange()
                            + "; no client implementation available");
                }
                if (band != client.getClass()) {
                    client = instantiate(band);
                }
                resolvedVersion = probed;
            } else {
                // Probe failed: best effort, assume the V3 band.
                Log.w(TAG, "Backend '" + entry.id + "': server version probe failed; assuming "
                        + ImmichClientRegistry.supportedRange());
            }
        }
        client.login();
    }

    @Override
    public List<MediaAsset> fetchCatalog() throws Exception {
        List<ImmichApiAssetResponse> raw = client.fetchCatalogAssets();
        List<MediaAsset> assets = new ArrayList<>(raw.size());
        int skipped = 0;
        for (ImmichApiAssetResponse r : raw) {
            if (isCompatibleAsset(r)) {
                assets.add(toMediaAsset(entry.id, r));
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            Log.d(TAG, "Catalog '" + entry.id + "': " + skipped
                    + " asset(s) skipped as incompatible with this device");
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

    @Override
    public void markIncompatible(MediaAsset asset) {
        final MediaAsset a = asset;
        final ImmichClient c = client;
        new Thread(() -> {
            try {
                c.tagAssetIncompatible(a.id, INCOMPATIBLE_TAG_NAME);
            } catch (Exception e) {
                Log.w(TAG, "Backend '" + entry.id + "': failed to tag asset " + a.id + " as "
                        + INCOMPATIBLE_TAG_NAME + ": " + e);
            }
        }, "mark-incompatible-" + entry.id).start();
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
     * Device compatibility gate for an Immich asset: exif present, file size within
     * {@link #MAX_ASSET_BYTES}, not trashed, a media type the pipeline can display, and no
     * known-unsupported codec by file name (checked before the original is downloaded).
     */
    public static boolean isCompatibleAsset(@NonNull ImmichApiAssetResponse asset) {
        ImmichExifInfo exif = asset.getExifInfo();
        if (exif == null) {
            return false;
        }
        Long fileSize = exif.getFileSizeInByte();
        if (fileSize == null || fileSize > MAX_ASSET_BYTES) {
            return false;
        }
        if (Boolean.TRUE.equals(asset.getIsTrashed())) {
            return false;
        }
        if (asset.getType() != ImmichType.IMAGE && asset.getType() != ImmichType.VIDEO) {
            return false;
        }
        MediaType type = asset.getType() == ImmichType.VIDEO ? MediaType.VIDEO : MediaType.IMAGE;
        return MediaCompatibility.incompatibleReason(
                type, asset.getOriginalFileName(), asset.getOriginalPath()) == null;
    }

    /** Pure mapping from an Immich DTO to a compact pipeline record. */
    public static MediaAsset toMediaAsset(String backendId, @NonNull ImmichApiAssetResponse asset) {
        Integer duration = asset.getDuration();
        Long size = asset.getExifInfo() != null ? asset.getExifInfo().getFileSizeInByte() : null;
        return new MediaAsset(
                asset.getId(),
                backendId,
                asset.getType() == ImmichType.VIDEO ? MediaType.VIDEO : MediaType.IMAGE,
                duration != null ? duration : -1,
                asset.getChecksum(),
                asset.getOriginalFileName(),
                asset.getOriginalPath(),
                size,
                null);
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
