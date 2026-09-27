package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.TimeZone;

public class QuietHoursTest {

    @Test
    public void isConfigured_returnsFalseWhenDisabled() {
        assertFalse(QuietHours.isConfigured(-1, 14));
        assertFalse(QuietHours.isConfigured(23, -1));
        assertFalse(QuietHours.isConfigured(-1, -1));
    }

    @Test
    public void isConfigured_returnsTrueForValidHours() {
        assertTrue(QuietHours.isConfigured(0, 0));
        assertTrue(QuietHours.isConfigured(23, 14));
    }

    @Test
    public void isInQuietHours_overnightWindow() {
        assertFalse(QuietHours.isInQuietHours(23, 14, 14));
        assertFalse(QuietHours.isInQuietHours(23, 14, 22));
        assertTrue(QuietHours.isInQuietHours(23, 14, 23));
        assertTrue(QuietHours.isInQuietHours(23, 14, 0));
        assertTrue(QuietHours.isInQuietHours(23, 14, 13));
    }

    @Test
    public void isInQuietHours_sameDayWindow() {
        assertFalse(QuietHours.isInQuietHours(14, 17, 13));
        assertFalse(QuietHours.isInQuietHours(14, 17, 17));
        assertTrue(QuietHours.isInQuietHours(14, 17, 14));
        assertTrue(QuietHours.isInQuietHours(14, 17, 16));
    }

    @Test
    public void isInQuietHours_disabled() {
        assertFalse(QuietHours.isInQuietHours(-1, 14, 2));
        assertFalse(QuietHours.isInQuietHours(23, -1, 23));
        assertFalse(QuietHours.isInQuietHours(10, 10, 10));
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
