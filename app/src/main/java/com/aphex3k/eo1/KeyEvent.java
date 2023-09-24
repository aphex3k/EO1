package com.aphex3k.eo1;

import android.view.InputEvent;

public class KeyEvent extends android.view.KeyEvent {

    public static final int EO1_TOP_BUTTON = 132;
    public static final int EO1_BACK_BUTTON = 134;

    public KeyEvent(int action, int code) {
        super(action, code);
    }
}
