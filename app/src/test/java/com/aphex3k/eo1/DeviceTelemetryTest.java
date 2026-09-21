package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DeviceTelemetryTest {

    @Test
    public void ipToStringConvertsHostOrderInt() {
        assertEquals("192.168.1.1", DeviceTelemetry.ipToString(0xC0A80101));
        assertEquals("10.0.0.1", DeviceTelemetry.ipToString(0x0A000001));
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
}
