package com.aphex3k.eo1;

import androidx.annotation.Keep;

@Keep
public final class QuietHours {

    private QuietHours() {
    }

    static boolean isConfigured(int start, int end) {
        return start >= 0 && end >= 0;
    }

    static boolean isInQuietHours(int startHour, int endHour, int hourOfDay) {
        if (!isConfigured(startHour, endHour)) {
            return false;
        }
        if (startHour == endHour) {
            return false;
        }
        if (startHour < endHour) {
            return hourOfDay >= startHour && hourOfDay < endHour;
        }
        return hourOfDay >= startHour || hourOfDay < endHour;
    }
}
