package com.aphex3k.eo1;

/**
 * Geniatech EO2 frame: no soft-touch buttons, no light sensor, no
 * user-adjustable brightness. The physical power button toggles the panel at
 * OS level; the app reconciles its screen state via display broadcasts.
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
        return false;
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
