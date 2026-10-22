package com.aphex3k.eo1;

/**
 * Hardware capabilities of the frame device the app runs on.
 *
 * <p>The EO1 and EO2 expose very different input and sensor hardware: the
 * EO1's soft-touch buttons and ambient light sensor do not exist on the EO2,
 * and the EO2's physical power button is intercepted by the OS and never
 * reaches the app. Callers gate their behaviour on these flags instead of
 * model-specific branching; unsupported features simply do not fire.
 */
public interface HardwareCapabilities {

    /** Short platform name for logs and {@code /state} (e.g. "EO1"). */
    String platform();

    /** Ambient light sensor available (drives automatic screen brightness). */
    boolean supportsLightSensor();

    /**
     * The display accepts an explicit brightness value — i.e. writing
     * {@code WindowManager.LayoutParams.screenBrightness} does something. This is a property of the
     * panel, <em>not</em> of any control that asks for it: both frames answer true, and the app
     * already writes the attribute on an EO2 every rotation tick — what it has never been allowed to
     * write there is anything other than the default. Nothing should gate the brightness
     * <em>action</em> on this flag; {@link #supportsBrightnessButton()} is the flag that says whether
     * a device has a control to raise.
     */
    boolean supportsScreenBrightness();

    /**
     * A physical or soft-touch button exists that raises the minimum brightness, so the key is worth
     * listening for. Absent on the EO2, whose only brightness control is the web page's — which is
     * the documented replacement for the soft keys it lacks, and reaches the same action ungated.
     */
    boolean supportsBrightnessButton();

    /**
     * A physical power button toggles the display at OS level and the app never
     * sees the key event; display state must be tracked through
     * {@code ACTION_SCREEN_ON} / {@code ACTION_SCREEN_OFF} instead.
     */
    boolean supportsPowerButton();

    /**
     * Keycode emitted by the on-device screen toggle control. The EO1 top
     * soft-touch button is {@link KeyEvent#EO1_TOP_BUTTON}; the EO2 toggle is
     * {@code android.view.KeyEvent.KEYCODE_POWER}, which the system handles —
     * the value is reported for completeness, the app never receives it.
     */
    int screenToggleKeyCode();
}
