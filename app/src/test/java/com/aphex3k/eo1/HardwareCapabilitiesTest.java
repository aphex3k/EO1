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

    /**
     * These strings are real EO2 hardware — from an EO2's stale recovery log, not the live product
     * props — and they contain neither "eo1" nor "eo2". Before EO1 was identified positively this box
     * fell through to the EO1 profile, which was harmless while the two differed only in buttons. Once
     * "EO1" carries "this silicon has no vendor player", the same fallthrough would switch TsPlayer off
     * on an EO2 for a spelling reason, with no error anywhere. An unidentified frame therefore gets the
     * full-feature profile, and loses nothing by being unknown.
     */
    @Test
    public void unknownDeviceKeepsEveryEngineRatherThanInheritingEo1() {
        HardwareCapabilities caps = HardwareCapabilitiesFactory.detect("stvm8b", "Quad-Core Enjoy TV Box");
        assertTrue("an unrecognised board must not lose TsPlayer to a string comparison",
                caps.supportsTsPlayer());
        assertEquals("generic", caps.platform());
        // Everything else stays the historical EO1-like profile: this box has buttons and a sensor.
        assertTrue(caps.supportsLightSensor());
        assertTrue(caps.supportsBrightnessButton());
    }

    @Test
    public void detectsEo1Positively() {
        assertEo1(HardwareCapabilitiesFactory.detect("EO1", "EO1"));
        assertEo1(HardwareCapabilitiesFactory.detect(null, "eo1"));
    }

    @Test
    public void nullBuildValuesKeepTheFullFeatureProfile() {
        // Unidentifiable is not "EO1". Both frames' buttons/sensors behave as the EO1's here, but the
        // engine stays on: an EO1 identification that cannot be made must not silently cost a frame
        // the player its board may well have.
        HardwareCapabilities caps = HardwareCapabilitiesFactory.detect(null, null);
        assertEquals("generic", caps.platform());
        assertTrue(caps.supportsTsPlayer());
        assertTrue(caps.supportsLightSensor());
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
        // The point of the whole change: EO1 and EO2 are different silicon, and the Amlogic player
        // belongs to the EO2's board.
        assertFalse("TsPlayer is not an option on EO1 hardware", caps.supportsTsPlayer());
        assertTrue(caps.supportsLightSensor());
        assertTrue(caps.supportsScreenBrightness());
        assertTrue(caps.supportsBrightnessButton());
        assertFalse(caps.supportsPowerButton());
        assertEquals(KeyEvent.EO1_TOP_BUTTON, caps.screenToggleKeyCode());
    }

    private void assertEo2(HardwareCapabilities caps) {
        assertEquals("EO2", caps.platform());
        assertTrue(caps.supportsTsPlayer());
        assertFalse(caps.supportsLightSensor());
        // The panel takes a brightness value; only the button is missing. Gating the brightness
        // action on this flag is what pinned every EO2 to the default floor.
        assertTrue(caps.supportsScreenBrightness());
        assertFalse(caps.supportsBrightnessButton());
        assertTrue(caps.supportsPowerButton());
        assertEquals(android.view.KeyEvent.KEYCODE_POWER, caps.screenToggleKeyCode());
    }
}
