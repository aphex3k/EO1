package com.aphex3k.eo1;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

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

    /**
     * Resolve a configuration timezone id. Empty/unknown ids fall back to the device default
     * (TimeZone.getTimeZone returns GMT for unrecognized ids).
     */
    static TimeZone resolveTimeZone(@Nullable String id) {
        if (id == null || id.trim().isEmpty()) {
            return TimeZone.getDefault();
        }
        String trimmed = id.trim();
        TimeZone tz = TimeZone.getTimeZone(trimmed);
        if ("GMT".equals(tz.getID())
                && !"GMT".equalsIgnoreCase(trimmed)
                && !"UTC".equalsIgnoreCase(trimmed)
                && !trimmed.toUpperCase(Locale.US).startsWith("GMT")
                && !trimmed.toUpperCase(Locale.US).startsWith("UTC")) {
            return TimeZone.getDefault();
        }
        return tz;
    }

    static Calendar calendarInTimeZone(@Nullable String id) {
        return Calendar.getInstance(resolveTimeZone(id));
    }
}
