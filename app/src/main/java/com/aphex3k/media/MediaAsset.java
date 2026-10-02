package com.aphex3k.media;

import androidx.annotation.Nullable;

/**
 * Compact rotation-pool record: only the fields the display pipeline consumes.
 *
 * <p>Backends translate their native asset types into this record so the pool never holds
 * full backend response objects (a 10k-asset catalog is a few MB here, not tens).
 */
public class MediaAsset {
    /** Raw asset id as reported by the backend (e.g. the Immich asset id, or a local UUID). */
    public final String id;
    /** Configured backend instance id this asset came from (e.g. "immich-1", "local"). */
    public final String backendId;
    public final MediaType type;
    /** Video duration in milliseconds, or -1 when unknown. */
    public final int durationMs;
    /**
     * Integrity reference for the original bytes, in the backend's native encoding
     * (Immich: Base64 SHA-1). Null when the backend does not report one.
     */
    @Nullable public final String checksum;
    /**
     * Original file name as reported by the backend, or null. Used for cache-file naming
     * and for the pre-download codec compatibility check.
     */
    @Nullable public final String originalFileName;
    /** Original path as reported by the backend, or null. Secondary source for cache-file naming and the compatibility check. */
    @Nullable public final String originalPath;
    /** Reported byte size of the original, or null when unknown. */
    @Nullable public final Long sizeBytes;
    /**
     * Absolute path of the on-disk file for local (on-device) assets; null for remote assets.
     */
    @Nullable public final String localPath;

    public MediaAsset(String id, String backendId, MediaType type, int durationMs,
                      @Nullable String checksum, @Nullable String originalFileName,
                      @Nullable String originalPath, @Nullable Long sizeBytes,
                      @Nullable String localPath) {
        this.id = id;
        this.backendId = backendId;
        this.type = type;
        this.durationMs = durationMs;
        this.checksum = checksum;
        this.originalFileName = originalFileName;
        this.originalPath = originalPath;
        this.sizeBytes = sizeBytes;
        this.localPath = localPath;
    }

    /**
     * Pipeline-wide unique asset key: "backendId:rawId". Asset ids are only unique within a
     * backend, so the pipeline (cache fallbacks, incompatible tagging, duration lookups) keys
     * everything by this composite string.
     */
    public String key() {
        return backendId + ":" + id;
    }
}
