package com.aphex3k.media;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * A media source that supplies assets to the rotation pipeline.
 *
 * <p>Mandatory: backends must be able to report their configured id/type, enumerate their
 * catalog, and resolve an asset's original bytes. Optional methods add capabilities; the
 * pipeline treats a {@code null}/no-op result as "feature not supported" and skips it
 * gracefully instead of crashing or blocking rotation.
 *
 * <p>All blocking work is expected to happen on the rotation worker thread; backends are
 * built fresh per pool rebuild and may hold long-lived state (e.g. an authenticated HTTP
 * session) across rotation ticks within one pool generation.
 */
public interface MediaBackend {

    /** Configured instance id, e.g. "immich-1" or "local". Must be stable across rebuilds. */
    String getId();

    /** Backend type, e.g. "immich" or "local" (mirrors the config entry's {@code type}). */
    String getType();

    /**
     * Prepares the backend for catalog fetches (e.g. version resolution + login for remote
     * backends). No-op for backends with nothing to prepare (local uploads).
     *
     * @throws Exception on any preparation failure; the pipeline then skips this backend
     *         for the current cycle and reports the failure to the listener.
     */
    default void initialize() throws Exception {
    }

    /**
     * Enumerates every displayable asset this backend currently offers.
     *
     * <p>Blocking; runs on the rotation worker thread. Implementations should re-scan their
     * source so the result always reflects current state (e.g. a fresh directory listing, a
     * re-paginated catalog fetch).
     *
     * @throws Exception on any fetch failure; the pipeline then skips this backend for the
     *         current cycle and reports the failure to the listener.
     */
    List<MediaAsset> fetchCatalog() throws Exception;

    /**
     * Resolves an asset's original bytes. Called on the rotation worker thread when the
     * pipeline is about to display this asset.
     *
     * @return the source to display, or {@code null} when the asset is currently unusable
     *         (e.g. deleted on disk) — the pipeline then skips to the next asset.
     */
    @Nullable
    MediaSource resolveOriginal(MediaAsset asset);

    /**
     * Optional: resolves a degraded fallback for an asset whose original failed to display
     * (e.g. Immich preview thumbnail or /video/playback stream). The pipeline calls this
     * from the display-error path; the returned source is always re-fetched (never cached).
     *
     * @return the fallback source, or {@code null} when this backend has no fallback.
     */
    @Nullable
    default MediaSource resolveThumbnailFallback(MediaAsset asset) {
        return null;
    }

    /**
     * Optional: marks an asset as incompatible in the backend's own system (e.g. Immich tag).
     * Fired fire-and-forget from the display-error path; implementations must not block or
     * throw — failures are logged inside the implementation. No-op when unsupported.
     */
    default void markIncompatible(MediaAsset asset) {
    }

    /**
     * Optional: human-readable version/compatibility description for logging and /state
     * (e.g. "immich 3.2.2 via ImmichClientV3"). Null when the backend has no version
     * concept.
     */
    @Nullable
    default String describeVersion() {
        return null;
    }

    /**
     * Optional: releases any resources the backend holds. No-op when there is nothing to
     * close.
     */
    default void close() {
    }
}
