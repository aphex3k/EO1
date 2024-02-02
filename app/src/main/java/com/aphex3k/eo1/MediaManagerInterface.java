package com.aphex3k.eo1;

import android.app.Activity;

import java.io.File;

public interface MediaManagerInterface {
    void removeFromCache(File file);
    void showNextImage(Activity activity);
}
