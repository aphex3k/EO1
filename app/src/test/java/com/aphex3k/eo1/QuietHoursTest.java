package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class QuietHoursTest {

    private static List<CronExpression> expressions(String... raw) {
        List<CronExpression> out = new ArrayList<>();
        for (String s : raw) {
            out.add(CronExpression.parse(s));
        }
        return out;
    }

    private static Calendar moment(String hourMinute) {
        return moment(4, hourMinute); // 2026-01-04 is a Sunday
    }

    private static Calendar moment(int dayOfMonth, String hourMinute) {
        // Fixed January 2026 date (day 4 = Sunday, day 5 = Monday, day 6 = Tuesday).
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        String[] hm = hourMinute.split(":");
        c.set(2026, 0, dayOfMonth, Integer.parseInt(hm[0]), Integer.parseInt(hm[1]), 0);
        return c;
    }

    @Test
    public void isQuiet_nullOrEmptyListIsNeverQuiet() {
        assertFalse(QuietHours.isQuiet(null, moment("12:00")));
        assertFalse(QuietHours.isQuiet(new ArrayList<CronExpression>(), moment("12:00")));
    }

    @Test
    public void isQuiet_matchesOvernightWindow() {
        List<CronExpression> windows = expressions("* 22-23,0-6 * * *");
        assertTrue(QuietHours.isQuiet(windows, moment("23:30")));
        assertTrue(QuietHours.isQuiet(windows, moment("02:00")));
        assertTrue(QuietHours.isQuiet(windows, moment("06:59")));
        assertFalse(QuietHours.isQuiet(windows, moment("07:00")));
        assertFalse(QuietHours.isQuiet(windows, moment("14:00")));
    }

    @Test
    public void isQuiet_matchesSameDayWindow() {
        List<CronExpression> windows = expressions("* 14-16 * * *");
        assertTrue(QuietHours.isQuiet(windows, moment("14:00")));
        assertTrue(QuietHours.isQuiet(windows, moment("16:59")));
        assertFalse(QuietHours.isQuiet(windows, moment("13:59")));
        assertFalse(QuietHours.isQuiet(windows, moment("17:00")));
    }

    @Test
    public void isQuiet_overlappingWindowsOrTogether() {
        List<CronExpression> windows = expressions("* 22-23,0-6 * * *", "* 5-8 * * *");
        // 05:00 is covered by both windows; the OR must not double-apply or miss.
        assertTrue(QuietHours.isQuiet(windows, moment("05:00")));
        assertTrue(QuietHours.isQuiet(windows, moment("07:30")));
        assertFalse(QuietHours.isQuiet(windows, moment("21:00")));
    }

    @Test
    public void isQuiet_dayRestrictedWindowOnlyQuietsThoseDays() {
        List<CronExpression> windows = expressions("0 14-16 * * 2"); // Tuesdays only
        assertTrue(QuietHours.isQuiet(windows, moment(6, "15:00")));  // Tuesday 2026-01-06
        assertFalse(QuietHours.isQuiet(windows, moment(4, "15:00"))); // Sunday 2026-01-04
    }

    @Test
    public void resolveTimeZone_returnsConfiguredId() {
        assertEquals("America/Los_Angeles", QuietHours.resolveTimeZone("America/Los_Angeles").getID());
    }

    @Test
    public void resolveTimeZone_emptyOrNullUsesDefault() {
        TimeZone def = TimeZone.getDefault();
        assertEquals(def.getID(), QuietHours.resolveTimeZone(null).getID());
        assertEquals(def.getID(), QuietHours.resolveTimeZone("").getID());
        assertEquals(def.getID(), QuietHours.resolveTimeZone("   ").getID());
    }

    @Test
    public void resolveTimeZone_unknownFallsBackToDefault() {
        assertEquals(TimeZone.getDefault().getID(), QuietHours.resolveTimeZone("Not/ARealZone").getID());
    }

    @Test
    public void calendarInTimeZone_usesResolvedZone() {
        assertEquals("America/Los_Angeles",
                QuietHours.calendarInTimeZone("America/Los_Angeles").getTimeZone().getID());
    }
}
