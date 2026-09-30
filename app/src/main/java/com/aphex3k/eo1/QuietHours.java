package com.aphex3k.eo1;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

@Keep
public final class QuietHours {

    private QuietHours() {
    }

    /** The screen should be off when any configured cron expression matches the given moment. */
    static boolean isQuiet(@Nullable List<CronExpression> expressions, Calendar now) {
        if (expressions == null) {
            return false;
        }
        for (CronExpression expression : expressions) {
            if (expression.matches(now)) {
                return true;
            }
        }
        return false;
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
