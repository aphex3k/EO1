package com.aphex3k.eo1;

/**
 * Original Geniatech EO1 frame: soft-touch buttons (F2 screen toggle,
 * F4 minimum brightness) and an ambient light sensor. No power button.
 */
public final class Eo1Capabilities implements HardwareCapabilities {

    @Override
    public String platform() {
        return "EO1";
    }

    @Override
    public boolean supportsLightSensor() {
        return true;
    }

    @Override
    public boolean supportsScreenBrightness() {
        return true;
    }

    @Override
    public boolean supportsBrightnessButton() {
        return true;
    }

    @Override
    public boolean supportsPowerButton() {
        return false;
    }

    @Override
    public int screenToggleKeyCode() {
        return KeyEvent.EO1_TOP_BUTTON;
    }
}
