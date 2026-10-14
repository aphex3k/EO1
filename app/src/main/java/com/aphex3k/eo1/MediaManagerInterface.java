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
    void tagAssetAsIncompatible(String assetKey);
    void displayThumbnailAsset(Activity activity, String assetKey, boolean isVideo);

    /**
     * Reports that a web page just put on screen failed to render, and asks for the next asset in
     * the rotation pool instead. Page loads fail long after the tick that dispatched them, so this
     * cannot ride the acquire-failure count that governs files; the depth cap in the implementation
     * is what stops an all-web pool of dead pages from advancing forever.
     */
    void webDisplayFailed(Activity activity, String assetKey);
}
