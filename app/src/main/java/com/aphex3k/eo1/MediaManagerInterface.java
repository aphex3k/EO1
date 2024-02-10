package com.aphex3k.eo1;

import android.app.Activity;

import org.jetbrains.annotations.NotNull;

import java.io.File;

public interface MediaManagerInterface {
    void removeFromCache(File file);
    void showNextImage(Activity activity);
    void tagAssetAsIncompatible(String assetId);
    void displayThumbnailAsset(Activity activity, String assetId, Boolean isVideo);
}
