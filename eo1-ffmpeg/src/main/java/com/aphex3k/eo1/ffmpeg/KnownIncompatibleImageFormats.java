package com.aphex3k.eo1.ffmpeg;

import java.util.Locale;

/**
 * Still-image formats that Glide/API 19 cannot reliably decode; convert via FFmpeg first.
 */
public final class KnownIncompatibleImageFormats {

    private static final String[] INCOMPATIBLE_TOKENS = {
            "heic", "heif", "mif1", "msf1", "heim", "heis", "hevm", "hevs"
    };

    private KnownIncompatibleImageFormats() {
    }

    public static boolean isKnownIncompatible(String codecOrFormat) {
        if (codecOrFormat == null || codecOrFormat.isEmpty()) {
            return false;
        }
        String normalized = codecOrFormat.toLowerCase(Locale.US);
        for (String token : INCOMPATIBLE_TOKENS) {
            if (normalized.equals(token) || normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isHeicExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".heic") || lower.endsWith(".heif");
    }
}
