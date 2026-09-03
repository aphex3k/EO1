package com.aphex3k.eo1;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
