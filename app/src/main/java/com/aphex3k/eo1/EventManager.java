package com.aphex3k.eo1;

import static android.view.KeyEvent.KEYCODE_C;
import static android.view.KeyEvent.KEYCODE_SPACE;

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

        EventManagerListener eventManagerListener = this.listener.get();

        if (eventManagerListener == null) {
            return;
        }

        // Trigger update check if both buttons have been pressed "at the same time"
        if (
                ((keyCode == KeyEvent.EO1_TOP_BUTTON && lastKeyCode == KeyEvent.EO1_BACK_BUTTON) ||
                        (keyCode == KeyEvent.EO1_BACK_BUTTON && lastKeyCode == KeyEvent.EO1_TOP_BUTTON))
                        && ((new Date()).getTime() - lastKeyCodeDate.getTime() < 250))
        {
            eventManagerListener.checkForUpdates();
        }

        lastKeyCode = keyCode;
        lastKeyCodeDate = new Date();

        if (keyCode == KEYCODE_C) {
            eventManagerListener.showConfigurationUI();
        }
        else if (keyCode == KEYCODE_SPACE) {
            eventManagerListener.showNextImage();
        }
        else if (keyCode == KeyEvent.EO1_TOP_BUTTON) {
            eventManagerListener.toggleScreenOn();
        }
        else if (keyCode == KeyEvent.EO1_BACK_BUTTON) {
            eventManagerListener.adjustBrightness();
        } else if (keyCode == android.view.KeyEvent.KEYCODE_S) {
            eventManagerListener.openSystemSettings();
        }
    }
}
