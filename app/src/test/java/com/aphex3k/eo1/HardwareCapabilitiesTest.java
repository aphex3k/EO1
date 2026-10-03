package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HardwareCapabilitiesTest {

    @Test
    public void detectsEo2ByDevice() {
        assertEo2(HardwareCapabilitiesFactory.detect("eo2", "EO2"));
    }

    @Test
    public void detectsEo2ByModelCaseInsensitive() {
        assertEo2(HardwareCapabilitiesFactory.detect(null, "EO2"));
    }

    @Test
    public void deviceOnlyIsEnough() {
        assertEo2(HardwareCapabilitiesFactory.detect("eo2", "some-other-model"));
    }

    @Test
    public void unknownDeviceDefaultsToEo1() {
        // Values from the EO2's stale recovery log — not the live product props.
        assertEo1(HardwareCapabilitiesFactory.detect("stvm8b", "Quad-Core Enjoy TV Box"));
    }

    @Test
    public void nullBuildValuesDefaultToEo1() {
        assertEo1(HardwareCapabilitiesFactory.detect(null, null));
    }

    @Test
    public void eo1HasSoftTouchButtonsAndLightSensor() {
        assertEo1(new Eo1Capabilities());
    }

    @Test
    public void eo2HasOnlyTheOsPowerButton() {
        assertEo2(new Eo2Capabilities());
    }

    private void assertEo1(HardwareCapabilities caps) {
        assertEquals("EO1", caps.platform());
        assertTrue(caps.supportsLightSensor());
        assertTrue(caps.supportsScreenBrightness());
        assertTrue(caps.supportsBrightnessButton());
        assertFalse(caps.supportsPowerButton());
        assertEquals(KeyEvent.EO1_TOP_BUTTON, caps.screenToggleKeyCode());
    }

    private void assertEo2(HardwareCapabilities caps) {
        assertEquals("EO2", caps.platform());
        assertFalse(caps.supportsLightSensor());
        assertFalse(caps.supportsScreenBrightness());
        assertFalse(caps.supportsBrightnessButton());
        assertTrue(caps.supportsPowerButton());
        assertEquals(android.view.KeyEvent.KEYCODE_POWER, caps.screenToggleKeyCode());
    }
}
