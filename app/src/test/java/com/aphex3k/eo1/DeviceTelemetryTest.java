package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

public class DeviceTelemetryTest {

    @Test
    public void ipToStringUsesDeviceByteOrder() {
        // On this device the int from WifiInfo.getIpAddress() is in host (little-endian) byte
        // order: the first IP octet is in the least-significant byte, so ipToString reads LSB-first.
        assertEquals("192.168.1.174", DeviceTelemetry.ipToString(0xAE01A8C0)); // real device IP
        assertEquals("10.0.0.1", DeviceTelemetry.ipToString(0x0100000A));
        assertEquals("0.0.0.0", DeviceTelemetry.ipToString(0));
        assertEquals("255.255.255.255", DeviceTelemetry.ipToString(0xFFFFFFFF));
    }

    @Test
    public void humanizeUptime() {
        assertEquals("0h 0m 0s", DeviceTelemetry.humanizeUptime(0L));
        assertEquals("1d 1h 1m 5s", DeviceTelemetry.humanizeUptime(90065000L));
        assertEquals("1h 0m 0s", DeviceTelemetry.humanizeUptime(3600000L));
        assertEquals("0h 1m 30s", DeviceTelemetry.humanizeUptime(90000L));
    }

    @Test
    public void storageReportsTotalAndUsableBytesOfThePartition() {
        // The EO2's /data, measured with `adb shell df` on serial 20061229, 2026-11-11.
        JsonObject o = DeviceTelemetry.storageJson(5347704832L, 756903936L);
        assertEquals(5347704832L, o.get("totalBytes").getAsLong());
        assertEquals(756903936L, o.get("availBytes").getAsLong());
        // Quoted beside the free space so a reader can tell how close the frame is to evicting.
        assertEquals(MediaCacheManager.SAFETY_MARGIN_BYTES, o.get("marginBytes").getAsLong());
    }

    @Test
    public void anUnreadableFilesystemIsAbsentFromStorage() {
        // A zero free-space reading is what a partition full of media already says; an unreadable path
        // must not arrive under the same shape, or "no room" and "cannot tell" are one sentence.
        JsonObject o = DeviceTelemetry.storageJson(-1L, -1L);
        assertFalse(o.has("totalBytes"));
        assertFalse(o.has("availBytes"));
        assertFalse(o.has("marginBytes"));
    }

    @Test
    public void storageAnsweringZeroFreeIsStillReported() {
        JsonObject o = DeviceTelemetry.storageJson(5347704832L, 0L);
        assertEquals(0L, o.get("availBytes").getAsLong());
        assertTrue(o.has("marginBytes"));
    }
}
