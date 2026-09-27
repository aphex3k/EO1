package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;

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
}
