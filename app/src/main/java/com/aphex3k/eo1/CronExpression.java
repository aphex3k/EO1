package com.aphex3k.eo1;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.util.Calendar;

/**
 * A 5-field cron expression (minute hour day-of-month month day-of-week) evaluated against
 * wall-clock minutes. Supported subset per field: {@code *}, a value, a range {@code a-b},
 * comma lists of these, and steps ({@code *&#47;n}, {@code a-b/n}). Numbers only — no
 * SUN/MAR names, no {@code ?}, {@code L}, {@code W}, {@code #}. Day-of-week accepts 0–7 with
 * 0 and 7 both meaning Sunday. When both day-of-month and day-of-week are restricted
 * (neither field starts with {@code *}), a day matches when EITHER matches (classic Vixie
 * cron). Pure java.util — no Android dependencies, unit-testable on the JVM.
 */
@Keep
public final class CronExpression {

    private final String raw;
    private final boolean[] minuteMask = new boolean[60];
    private final boolean[] hourMask = new boolean[24];
    private final boolean[] domMask = new boolean[32];
    private final boolean[] monthMask = new boolean[13];
    private final boolean[] dowMask = new boolean[7];
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronExpression(String raw,
                           boolean[] minuteMask,
                           boolean[] hourMask,
                           boolean[] domMask,
                           boolean[] monthMask,
                           boolean[] dowMask,
                           boolean domRestricted,
                           boolean dowRestricted) {
        this.raw = raw;
        System.arraycopy(minuteMask, 0, this.minuteMask, 0, 60);
        System.arraycopy(hourMask, 0, this.hourMask, 0, 24);
        System.arraycopy(domMask, 0, this.domMask, 0, 32);
        System.arraycopy(monthMask, 0, this.monthMask, 0, 13);
        System.arraycopy(dowMask, 0, this.dowMask, 0, 7);
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    /**
     * Parses a 5-field cron expression.
     *
     * @return the parsed expression, or null when the text is not a valid expression in
     *         the supported subset.
     */
    @Nullable
    public static CronExpression parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 5) {
            return null;
        }
        boolean[] minute = new boolean[60];
        boolean[] hour = new boolean[24];
        boolean[] dom = new boolean[32];
        boolean[] month = new boolean[13];
        boolean[] dow = new boolean[7];
        if (!parseField(fields[0], minute, 0, 59, false)
                || !parseField(fields[1], hour, 0, 23, false)
                || !parseField(fields[2], dom, 1, 31, false)
                || !parseField(fields[3], month, 1, 12, false)
                || !parseField(fields[4], dow, 0, 7, true)) {
            return null;
        }
        // Vixie star flag: a field starting with "*" (including "*/n") is unrestricted
        // for the dom/dow OR rule.
        return new CronExpression(trimmed, minute, hour, dom, month, dow,
                !fields[2].startsWith("*"), !fields[4].startsWith("*"));
    }

    /** One comma-separated item list: each item is "*", "n", "a-b", optionally "/step". */
    private static boolean parseField(String field, boolean[] mask, int min, int max, boolean sevenIsSunday) {
        if (field.isEmpty()) {
            return false;
        }
        String[] items = field.split(",");
        for (String item : items) {
            if (!parseItem(item, mask, min, max, sevenIsSunday)) {
                return false;
            }
        }
        return true;
    }

    private static boolean parseItem(String item, boolean[] mask, int min, int max, boolean sevenIsSunday) {
        String rangePart = item;
        int step = 1;
        int slash = item.indexOf('/');
        if (slash >= 0) {
            rangePart = item.substring(0, slash);
            String stepText = item.substring(slash + 1);
            if (!isDigits(stepText) || Integer.parseInt(stepText) <= 0) {
                return false;
            }
            step = Integer.parseInt(stepText);
            // Steps apply to "*" and ranges only, not to a single value.
            if (!rangePart.equals("*") && rangePart.indexOf('-') < 0) {
                return false;
            }
        }
        int lo;
        int hi;
        if (rangePart.equals("*")) {
            lo = min;
            hi = max;
        } else {
            int dash = rangePart.indexOf('-');
            if (dash < 0) {
                if (!isDigits(rangePart)) {
                    return false;
                }
                lo = hi = Integer.parseInt(rangePart);
            } else {
                String a = rangePart.substring(0, dash);
                String b = rangePart.substring(dash + 1);
                if (!isDigits(a) || !isDigits(b)) {
                    return false;
                }
                lo = Integer.parseInt(a);
                hi = Integer.parseInt(b);
                if (lo > hi) {
                    return false;
                }
            }
        }
        if (lo < min || hi > max) {
            return false;
        }
        for (int v = lo; v <= hi; v += step) {
            int target = (sevenIsSunday && v == 7) ? 0 : v;
            mask[target] = true;
        }
        return true;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** True when this expression matches the minute the given calendar points at. */
    public boolean matches(Calendar calendar) {
        int minute = calendar.get(Calendar.MINUTE);
        if (!minuteMask[minute]) {
            return false;
        }
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        if (!hourMask[hour]) {
            return false;
        }
        int month = calendar.get(Calendar.MONTH) + 1;
        if (!monthMask[month]) {
            return false;
        }
        int dom = calendar.get(Calendar.DAY_OF_MONTH);
        // Calendar: SUNDAY=1..SATURDAY=7 → cron: 0=Sunday..6=Saturday.
        int dow = (calendar.get(Calendar.DAY_OF_WEEK) - 1) % 7;
        if (domRestricted && dowRestricted) {
            return domMask[dom] || dowMask[dow];
        }
        if (domRestricted) {
            return domMask[dom];
        }
        if (dowRestricted) {
            return dowMask[dow];
        }
        return true;
    }

    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }

    /** True when the minute field selects every minute (i.e. the whole hours are active). */
    public boolean isAllMinutes() {
        for (int m = 0; m < 60; m++) {
            if (!minuteMask[m]) {
                return false;
            }
        }
        return true;
    }

    /** True when the minute field selects exactly minute 0 (the whole hour). */
    public boolean isMinuteZeroOnly() {
        for (int m = 0; m < 60; m++) {
            boolean selected = minuteMask[m];
            if (selected && m != 0) {
                return false;
            }
            if (!selected && m == 0) {
                return false;
            }
        }
        return true;
    }

    public boolean isDomUnrestricted() {
        return !domRestricted;
    }

    public boolean isMonthUnrestricted() {
        for (int m = 1; m <= 12; m++) {
            if (!monthMask[m]) {
                return false;
            }
        }
        return true;
    }

    public boolean isDowUnrestricted() {
        return !dowRestricted;
    }

    /** The hours (0–23) selected by the hour field, in ascending order. */
    public int[] matchedHours() {
        int count = 0;
        for (int h = 0; h < 24; h++) {
            if (hourMask[h]) {
                count++;
            }
        }
        int[] hours = new int[count];
        int i = 0;
        for (int h = 0; h < 24; h++) {
            if (hourMask[h]) {
                hours[i++] = h;
            }
        }
        return hours;
    }
}
