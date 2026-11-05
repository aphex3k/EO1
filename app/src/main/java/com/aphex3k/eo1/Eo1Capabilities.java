package com.aphex3k.eo1;

/**
 * Original EO1 frame: soft-touch buttons (F2 screen toggle, F4 minimum brightness) and an ambient
 * light sensor. No power button.
 *
 * <p>This used to be the profile for <em>everything</em> the factory did not recognise as an EO2, and
 * it no longer is: the EO1 and the EO2 carry significantly different silicon, and the Amlogic player
 * belongs to the EO2's board, so {@link #supportsTsPlayer()} is false here. Unrecognised hardware gets
 * {@link GenericCapabilities} instead, so a frame whose {@code Build} strings merely look unusual does
 * not inherit an EO1's hardware limitations.
 */
public class Eo1Capabilities implements HardwareCapabilities {

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

    /** Different silicon from the EO2; the vendor player does not apply to this board. */
    @Override
    public boolean supportsTsPlayer() {
        return false;
    }
}
