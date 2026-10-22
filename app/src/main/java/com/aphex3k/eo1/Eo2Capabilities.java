package com.aphex3k.eo1;

/**
 * Geniatech EO2 frame: no soft-touch buttons and no light sensor. The physical
 * power button toggles the panel at OS level; the app reconciles its screen
 * state via display broadcasts.
 *
 * <p>It is <em>not</em> "no adjustable brightness", and reading it that way is what pinned every EO2
 * to the 0.3 floor: the panel takes a brightness value like any other, it simply has no button and
 * no sensor, so its level is the floor and the floor is whatever the operator last set from the web
 * page. {@link #supportsBrightnessButton()} carries the missing button; the brightness action itself
 * is ungated and reaches this frame through that page.
 */
public final class Eo2Capabilities implements HardwareCapabilities {

    @Override
    public String platform() {
        return "EO2";
    }

    @Override
    public boolean supportsLightSensor() {
        return false;
    }

    @Override
    public boolean supportsScreenBrightness() {
        // The panel accepts a brightness value; it is the button that is missing. Returning false
        // here was read as permission to run the brightness action, and froze this frame's floor at
        // the default with the web page's Brightness+ reporting "fired".
        return true;
    }

    @Override
    public boolean supportsBrightnessButton() {
        return false;
    }

    @Override
    public boolean supportsPowerButton() {
        return true;
    }

    @Override
    public int screenToggleKeyCode() {
        return android.view.KeyEvent.KEYCODE_POWER;
    }
}
