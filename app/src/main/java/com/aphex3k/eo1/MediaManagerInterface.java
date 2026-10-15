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
