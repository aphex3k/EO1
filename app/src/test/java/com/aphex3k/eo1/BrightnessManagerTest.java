package com.aphex3k.eo1;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.hardware.SensorManager;

import org.mockito.Mockito;

public class BrightnessManagerTest {
    @org.junit.Test
    public void BrightnessManagerTest() throws Exception {

        MockBrightnessListener listener = new MockBrightnessListener();

        SensorManager sensorManager = Mockito.mock(SensorManager.class);

        BrightnessManager brightnessManager = new BrightnessManager(listener, sensorManager);

        assertTrue(brightnessManager.maxBrightness > 0.0f &&
                brightnessManager.maxBrightness <= 1.0 &&
                brightnessManager.maxBrightness > brightnessManager.minBrightness);

        assertTrue(brightnessManager.minBrightness < 1.0f &&
                brightnessManager.minBrightness >= 0.0 &&
                brightnessManager.minBrightness < brightnessManager.maxBrightness);

        assertTrue(brightnessManager.minLux.value < brightnessManager.maxLux.value);

        assertTrue("The brightness manager should assume the screen is on to begin with",
                brightnessManager.getShouldTheScreenBeOn());

        brightnessManager.adjustMinimumBrightness();

        brightnessManager.toggleShouldTheScreenBeOn();

        assertFalse(brightnessManager.getShouldTheScreenBeOn());
    }
}
