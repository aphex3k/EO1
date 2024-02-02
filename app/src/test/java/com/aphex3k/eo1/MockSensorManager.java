package com.aphex3k.eo1;

import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import org.mockito.Mockito;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

class MockSensorManager implements BrightnessSensorManager {

    private WeakReference<SensorEventListener> listener;
    MockSensorManager() {    }

    @Override
    public Sensor getDefaultSensor(int typeLight) {
        return null;
    }

    @Override
    public void registerListener(SensorEventListener listener, Sensor mLightSensor, int sensorDelayUi) {
        this.listener = new WeakReference<>(listener);
    }

    public void updateBrightness(float value) throws Exception {
        this.listener.get().onSensorChanged(getBrightnessSensorEventWithValue(value));
    }

    /**
     * See https://stackoverflow.com/questions/34530865/how-to-mock-motionevent-and-sensorevent-for-unit-testing-in-android
     * @param value
     * @return
     * @throws Exception
     */
    private SensorEvent getBrightnessSensorEventWithValue(float value) throws Exception {

        SensorEvent sensorEvent = Mockito.mock(SensorEvent.class);

        Field sensorField = SensorEvent.class.getField("sensor");
        sensorField.setAccessible(true);
        Sensor sensor = Mockito.mock(Sensor.class);
        when(sensor.getType()).thenReturn(Sensor.TYPE_LIGHT);
        sensorField.set(sensorEvent, sensor);

        Field valuesField = SensorEvent.class.getField("values");
        valuesField.setAccessible(true);

        float[] values = new float[1];
        values[0] = value;
        valuesField.set(sensorEvent, values);

        return sensorEvent;
    }
}
