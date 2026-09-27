package com.aphex3k.eo1;

import java.io.File;

public final class MediaTypeHelper {

    private MediaTypeHelper() {
    }

    public static boolean isGifFile(File file) {
        return ".gif".equals(MediaManager.extensionFromFileName(file.getName()));
    }
}
