package com.aphex3k.eo1;

import androidx.annotation.Keep;

@Keep
public class KeyEvent extends android.view.KeyEvent {

    public static final int EO1_TOP_BUTTON = KEYCODE_F2;
    public static final int EO1_BACK_BUTTON = KEYCODE_F4;
    public static final int PS4_CIRCLE = KEYCODE_BUTTON_C;
    public static final int PS4_SQUARE = KEYCODE_BUTTON_A;
    public static final int PS4_TRIANGLE = KEYCODE_BUTTON_X;
    public static final int PS4_CROSS = KEYCODE_BUTTON_B;

    public KeyEvent(int action, int code) {
        super(action, code);
    }
}
