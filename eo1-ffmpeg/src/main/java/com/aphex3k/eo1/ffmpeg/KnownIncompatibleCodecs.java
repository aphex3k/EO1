package com.aphex3k.eo1.ffmpeg;

import java.util.Locale;

public final class KnownIncompatibleCodecs {

    private static final String[] INCOMPATIBLE = {
            "hevc", "h265", "hvc1", "hev1",
            "vp9", "vp09",
            "av1", "av01"
    };

    private KnownIncompatibleCodecs() {
    }

    public static boolean isKnownIncompatible(String codecName) {
        if (codecName == null || codecName.isEmpty()) {
            return false;
        }
        String normalized = codecName.toLowerCase(Locale.US);
        for (String codec : INCOMPATIBLE) {
            if (normalized.equals(codec) || normalized.startsWith(codec)) {
                return true;
            }
        }
        return false;
    }
}
