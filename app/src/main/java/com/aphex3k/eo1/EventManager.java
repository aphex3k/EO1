package com.aphex3k.eo1;

import static android.view.KeyEvent.KEYCODE_C;
import static android.view.KeyEvent.KEYCODE_SPACE;
import static android.view.KeyEvent.KEYCODE_U;
import static com.aphex3k.eo1.KeyEvent.PS4_CIRCLE;

import android.annotation.SuppressLint;

import androidx.annotation.Keep;

import java.lang.ref.WeakReference;
import java.util.Date;

@Keep
public class EventManager {

    private final WeakReference<EventManagerListener> listener;
    private final HardwareCapabilities capabilities;
    private int lastKeyCode;
    private Date lastKeyCodeDate;

    public EventManager(EventManagerListener listener, HardwareCapabilities capabilities) {
        this.listener = new WeakReference<>(listener);
        this.capabilities = capabilities;
    }

    public boolean onKeyDown(int keyCode) {

        EventManagerListener eventManagerListener = this.listener.get();

        if (eventManagerListener == null) {
            return false;
        }
        // Trigger update check if both soft-touch buttons have been pressed "at the same time"
        if (capabilities.supportsBrightnessButton()
                && ((keyCode == KeyEvent.EO1_TOP_BUTTON && lastKeyCode == KeyEvent.EO1_BACK_BUTTON) ||
                        (keyCode == KeyEvent.EO1_BACK_BUTTON && lastKeyCode == KeyEvent.EO1_TOP_BUTTON))
                        && ((new Date()).getTime() - lastKeyCodeDate.getTime() < 250))
        {
            eventManagerListener.checkForUpdates();
            return true;
        }

        lastKeyCode = keyCode;
        lastKeyCodeDate = new Date();

        if (keyCode == KEYCODE_C) {
            eventManagerListener.showConfigurationUI();
        }
        else if (keyCode == KEYCODE_SPACE || keyCode == PS4_CIRCLE) {
            eventManagerListener.showNextImage();
        }
        else if (!capabilities.supportsPowerButton() && keyCode == capabilities.screenToggleKeyCode()) {
            // On the EO2 the toggle is KEY_POWER, which the OS handles; the app reconciles
            // via display-state broadcasts instead.
            eventManagerListener.toggleScreenOn();
        }
        else if (capabilities.supportsBrightnessButton() && keyCode == KeyEvent.EO1_BACK_BUTTON) {
            eventManagerListener.adjustMinimumBrightness();
        } else if (keyCode == android.view.KeyEvent.KEYCODE_S) {
            eventManagerListener.openSystemSettings();
        } else if (keyCode == KEYCODE_U) {
            eventManagerListener.openUpdateWebsite();
        }
        else {
            return false;
        }
        return true;
    }
}
