package com.aphex3k.eo1;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;

public interface BrightnessSensorManager {
    Sensor getDefaultSensor(int typeLight);

    void registerListener(SensorEventListener listener, Sensor mLightSensor, int sensorDelayUi);
}
