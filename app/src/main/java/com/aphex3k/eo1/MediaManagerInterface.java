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
}
