package com.aphex3k.eo1;

import android.annotation.SuppressLint;

import androidx.annotation.Keep;

import java.lang.ref.WeakReference;
import java.util.Date;

@Keep
public class EventManager {

    private final WeakReference<EventManagerListener> listener;
    private int lastKeyCode;
    private Date lastKeyCodeDate;

    public EventManager(EventManagerListener listener) {
        this.listener = new WeakReference<>(listener);
    }

    @SuppressLint("InvalidWakeLockTag")
    public void onKeyDown(int keyCode) {

        // Trigger update check if both buttons have been pressed "at the same time"
        if (
                ((keyCode == KeyEvent.EO1_TOP_BUTTON && lastKeyCode == KeyEvent.EO1_BACK_BUTTON) ||
                        (keyCode == KeyEvent.EO1_BACK_BUTTON && lastKeyCode == KeyEvent.EO1_TOP_BUTTON))
                        && ((new Date()).getTime() - lastKeyCodeDate.getTime() < 250))
        {
            listener.get().checkForUpdates();
        }

        lastKeyCode = keyCode;
        lastKeyCodeDate = new Date();

        if (keyCode == KeyEvent.KEYCODE_C) {
            listener.get().showConfigurationUI();
        }
        else if (keyCode == KeyEvent.KEYCODE_SPACE) {
            listener.get().showNextImage();
        }
        else if (keyCode == KeyEvent.EO1_TOP_BUTTON) {
            listener.get().toggleScreenOn();
        }
        else if (keyCode == KeyEvent.EO1_BACK_BUTTON) {
            listener.get().adjustBrightness();
        }
    }
}
