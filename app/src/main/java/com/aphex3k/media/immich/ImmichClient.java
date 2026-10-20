package com.aphex3k.media.immich;

import androidx.annotation.Nullable;

import com.aphex3k.eo1.MediaDownloadFailedException;
import com.aphex3k.immichApi.ImmichApiAssetResponse;
import com.vdurmont.semver4j.Semver;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Versioned client for the Immich API.
 *
 * <p>Implementations are <em>frozen</em>: once shipped against a server-version band they are
 * not modified. A breaking Immich API change means a new DTO package
 * ({@code com.aphex3k.immichApi.vN}) plus a new client ({@code ImmichClientVN}) and a band
 * entry in {@link ImmichClientRegistry} — the frozen clients are never edited.
 *
 * <p>One instance = one backend (host + credentials). It holds one Retrofit service, and its
 * session — the bearer token and when that token dies — lives in {@link ImmichSessionStore} for
 * the life of the process, so it outlives both rotation ticks and the instances themselves.
 */
public interface ImmichClient {

    /**
     * Best-effort, unauthenticated probe of {@code GET /api/server/version}.
     *
     * @return the parsed server version, or null when the probe fails or is unparseable.
     */
    @Nullable
    Semver probeServerVersion();

    /**
     * Makes this backend hold a usable token, acquiring one with the configured credentials if it
     * does not already have one. Implementations reuse a live token rather than re-authenticating,
     * so a pool rebuild costs no login; credentials are sent to the auth endpoint only when a token
     * actually has to be minted.
     *
     * @throws com.aphex3k.eo1.AuthenticationFailedException 401, or a login response without
     *         a user id
     * @throws com.aphex3k.eo1.AuthenticationUnavailableException 404, or an empty login body
     * @throws com.aphex3k.eo1.InvalidCredentialsException the user id comes back empty
     */
    void login() throws Exception;

    /** True after a successful {@link #login()}. */
    boolean isLoggedIn();

    /**
     * Fetches the whole catalog: owned albums, shared albums, and the full visible timeline
     * (paginated, 1000/page).
     *
     * <p>Raw, unfiltered DTOs — filtering and mapping to {@code MediaAsset} is the
     * backend's job. Assets that live in an album also appear in the timeline result; this
     * matches the legacy single-host behaviour and is left to the pipeline to consume as-is.
     */
    List<ImmichApiAssetResponse> fetchCatalogAssets() throws IOException;

    /** Opens a stream of the asset's original file. The caller closes it. */
    InputStream originalStream(String assetId) throws IOException, MediaDownloadFailedException;

    /** Opens a stream of the asset's preview thumbnail (JPEG fallback). The caller closes it. */
    InputStream thumbnailStream(String assetId) throws IOException, MediaDownloadFailedException;

    /** Opens a stream of the asset's transcoded playback video. The caller closes it. */
    InputStream videoPlaybackStream(String assetId) throws IOException, MediaDownloadFailedException;

    /**
     * The size {@link #videoPlaybackStream} would deliver, without downloading it — or {@code null}
     * when the server makes that unknown. A backend answers the playback request with the original
     * file whenever nothing is transcoded, so comparing this against the asset's own size is what
     * distinguishes a degraded source worth fetching from a second copy of the bytes already held.
     */
    Long videoPlaybackSize(String assetId) throws IOException, MediaDownloadFailedException;

    /**
     * Finds or creates a tag named {@code tagName} on this server and adds {@code assetId}
     * to it.
     *
     * @throws com.aphex3k.eo1.ImmichApiTagException when the tag could not be listed, found,
     *         created, or applied — always with the server's status line. Nothing about this
     *         call is silent: an unwritten tag must be distinguishable from an unnecessary one.
     */
    void tagAssetIncompatible(String assetId, String tagName) throws Exception;

    /**
     * Lists the raw ids of every asset carrying the tag named {@code tagName} (paginated
     * metadata search filtered by tag id). Empty list when the server has no such tag.
     */
    List<String> listTaggedAssetIds(String tagName) throws IOException;
}
