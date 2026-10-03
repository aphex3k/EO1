package com.aphex3k.eo1;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;

public class EventManagerTest {

    private EventManagerListener listener;
    private EventManager eo1;
    private EventManager eo2;

    @Before
    public void setUp() {
        listener = mock(EventManagerListener.class);
        eo1 = new EventManager(listener, new Eo1Capabilities());
        eo2 = new EventManager(listener, new Eo2Capabilities());
    }

    @Test
    public void eo1TopButtonTogglesScreen() {
        assertTrue(eo1.onKeyDown(KeyEvent.KEYCODE_F2));
        verify(listener, times(1)).toggleScreenOn();
    }

    @Test
    public void eo1BackButtonRaisesBrightness() {
        assertTrue(eo1.onKeyDown(KeyEvent.KEYCODE_F4));
        verify(listener, times(1)).adjustMinimumBrightness();
    }

    @Test
    public void eo1BothButtonsInQuickSuccessionChecksUpdates() {
        eo1.onKeyDown(KeyEvent.KEYCODE_F2);
        eo1.onKeyDown(KeyEvent.KEYCODE_F4);
        verify(listener, times(1)).toggleScreenOn();
        verify(listener, times(1)).checkForUpdates();
        verify(listener, never()).adjustMinimumBrightness();
    }

    @Test
    public void eo1SpaceShowsNextImage() {
        assertTrue(eo1.onKeyDown(KeyEvent.KEYCODE_SPACE));
        verify(listener, times(1)).showNextImage();
    }

    @Test
    public void eo1COpensConfiguration() {
        assertTrue(eo1.onKeyDown(KeyEvent.KEYCODE_C));
        verify(listener, times(1)).showConfigurationUI();
    }

    @Test
    public void unknownKeyReturnsFalse() {
        assertFalse(eo1.onKeyDown(KeyEvent.KEYCODE_D));
        verifyNoInteractions(listener);
    }

    @Test
    public void eo2SoftTouchButtonsAreDead() {
        assertFalse(eo2.onKeyDown(KeyEvent.KEYCODE_F2));
        assertFalse(eo2.onKeyDown(KeyEvent.KEYCODE_F4));
        verifyNoInteractions(listener);
    }

    @Test
    public void eo2PowerKeyIsNotHandledByTheApp() {
        // The OS intercepts KEY_POWER on the EO2; even if it reached the app it must not act on it.
        assertFalse(eo2.onKeyDown(KeyEvent.KEYCODE_POWER));
        verifyNoInteractions(listener);
    }

    @Test
    public void eo2SpaceStillShowsNextImage() {
        assertTrue(eo2.onKeyDown(KeyEvent.KEYCODE_SPACE));
        verify(listener, times(1)).showNextImage();
    }

    @Test
    public void eo2CStillOpensConfiguration() {
        assertTrue(eo2.onKeyDown(KeyEvent.KEYCODE_C));
        verify(listener, times(1)).showConfigurationUI();
    }
}
