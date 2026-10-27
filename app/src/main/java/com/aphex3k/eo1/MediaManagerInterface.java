package com.aphex3k.eo1;

import android.app.Activity;

import java.io.File;

/**
 * UI-facing surface of {@link MediaManager}. Asset identifiers are opaque
 * {@code "backendId:rawId"} keys — callers never need to know which backend
 * an asset came from.
 */
public interface MediaManagerInterface {
    void removeFromCache(File file);

    /**
     * Deletes the cached bytes of one asset of one backend, by id rather than by path: only the manager
     * knows which cache directory is current, and a backend must never reconstruct that name. The same
     * guards {@link #removeFromCache(File)} applies — inside the current cache dir, and an owned cache
     * file name — so a retention sweep cannot reach anything it was not given.
     *
     * <p>Matching is a prefix on an id this backend surfaced, not a directory glob. A Flickr asset id is
     * deliberately hex-and-dash, the same alphabet as an Immich UUID, and a pattern that could not tell
     * them apart would let one backend's privacy eviction delete another's originals.
     *
     * @return how many files were found for that id; zero means nothing was cached, which is a normal
     *         answer and not a failure
     */
    int dropCachedAsset(String backendId, String rawId);

    /**
     * Tells a backend to stop showing and holding one asset, or everything it can reach, for as long as
     * this install exists. This is the honoured form of a service's removal request: a delete that the
     * next catalog build undoes would violate the clause rather than satisfy it.
     *
     * @param assetKey the {@code "backendId:rawId"} key of one asset, or {@code null} for the whole
     *        backend
     * @return how many assets the backend stopped holding; zero means it has no such obligation, or the
     *         backend is not in the current generation
     */
    int forgetAssets(String backendId, String assetKey);

    void showNextImage(Activity activity);

    /**
     * Flags an asset in its backend as incompatible with this device (Immich: the
     * {@code EO1_INCOMPATIBLE} tag). Call it once the asset's fallback display failed too: the
     * tag exists to surface the asset for the operator, and the app never reads it back as a
     * filter, so re-tagging an already-tagged asset is expected, not an error.
     */
    void tagAssetAsIncompatible(String assetKey);

    /**
     * Second rung of the display ladder: shows the backend's degraded source (preview thumbnail,
     * transcoded stream) for an asset whose original failed to display. Backends without a
     * fallback source flag the asset from here, which is that asset's second failure.
     */
    void displayThumbnailAsset(Activity activity, String assetKey, boolean isVideo);

    /**
     * Reports that a web page just put on screen failed to render, and asks for the next asset in
     * the rotation pool instead. Page loads fail long after the tick that dispatched them, so this
     * cannot ride the acquire-failure count that governs files; the depth cap in the implementation
     * is what stops an all-web pool of dead pages from advancing forever.
     */
    void webDisplayFailed(Activity activity, String assetKey);
}
