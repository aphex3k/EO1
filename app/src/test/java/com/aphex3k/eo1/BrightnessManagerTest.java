package com.aphex3k.eo1;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import org.junit.Test;

public class BrightnessManagerTest {
    @Test
    public void BrightnessManagerTest() throws Exception {

        MockBrightnessListener listener = new MockBrightnessListener();

        SensorManager sensorManager = mock(SensorManager.class);

        BrightnessManager brightnessManager = new BrightnessManager(listener, sensorManager, new Eo1Capabilities());

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

    @Test
    public void registersLightSensorWhenSupported() {
        MockBrightnessListener listener = new MockBrightnessListener();
        SensorManager sensorManager = mock(SensorManager.class);
        Sensor lightSensor = mock(Sensor.class);
        when(sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)).thenReturn(lightSensor);

        new BrightnessManager(listener, sensorManager, new Eo1Capabilities());

        verify(sensorManager).registerListener(any(SensorEventListener.class), eq(lightSensor),
                eq(SensorManager.SENSOR_DELAY_UI));
    }

    @Test
    public void skipsLightSensorRegistrationWhenUnsupported() {
        MockBrightnessListener listener = new MockBrightnessListener();
        SensorManager sensorManager = mock(SensorManager.class);

        new BrightnessManager(listener, sensorManager, new Eo2Capabilities());

        verify(sensorManager, never()).getDefaultSensor(Sensor.TYPE_LIGHT);
        verify(sensorManager, never()).registerListener(any(SensorEventListener.class),
                any(Sensor.class), any(Integer.class));
    }
}
