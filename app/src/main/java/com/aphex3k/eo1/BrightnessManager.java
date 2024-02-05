package com.aphex3k.eo1;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.Keep;

import java.lang.ref.WeakReference;

@Keep
public class BrightnessManager {

    private boolean shouldTheScreenBeOn = true;

    /**
     * Maximum brightness value (0 to 1)
     */
    protected static final float maxBrightness = 1.0f; //
    /**
     * Minimum brightness value (0 to 1)
     */
    protected float minBrightness = 0.3f;
    /**
     * At what sensor value do we want to reach the maximum screen brightness? Any value equal
     * or higher to maxLux results in maxBrightness returned from this function.
     */
    protected static final Lux maxLux = Lux.OFFICE;
    /**
     * At what sensor value do we want to reach the minimum screen brightness? Any value equal
     * or lower to minLux results in minBrightness returned from this function.
     */
    protected static final Lux minLux = Lux.FULL_MOON;

    private final WeakReference<BrightnessManagerListener> brightnessListener;

    public BrightnessManager(BrightnessManagerListener listener,
                             SensorManager sensorManager)
    {
        this.brightnessListener = new WeakReference<>(listener);

        Sensor mLightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        SensorEventListener sensorEventListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                adjustScreenBrightness(event.values[0]);
            }
            @Override
            public void onAccuracyChanged(Sensor sensor, int i) {
                sensorAccuracyChanged(sensor, i);
            }
        };
        sensorManager.registerListener(sensorEventListener, mLightSensor, SensorManager.SENSOR_DELAY_UI);
    }

    /**
     * We might not want to turn the screen on during quiet period
     * @param onOff If true, turns on the screen. If the screen is already on, does nothing.
     */
    public void setShouldTheScreenBeOn(boolean onOff) {
        if (shouldTheScreenBeOn != onOff) {
            shouldTheScreenBeOn = onOff;

            adjustScreenBrightness(shouldTheScreenBeOn ? minLux.value : 0.0f);
        }
    }

    public boolean getShouldTheScreenBeOn() {
        return shouldTheScreenBeOn;
    }

    /**
     * convenience function to be called when the user taps the on/off button
     */
    public void toggleShouldTheScreenBeOn() {
        setShouldTheScreenBeOn(!shouldTheScreenBeOn);
    }

    /**
     * Translates the sensor light value in Lux to a matching screen brightness
     * @param lightValue the light value in Lux received from the sensor
     */
    private void adjustScreenBrightness(float lightValue){
        BrightnessManagerListener listener = brightnessListener.get();

        if (listener != null) {

            listener.debugInformationProvided(new DebugInformation("Reported Light Value: ", String.valueOf(lightValue)));
            listener.debugInformationProvided(new DebugInformation("Min Brightness: ", String.valueOf(this.minBrightness)));

            if (!shouldTheScreenBeOn) {
                listener.brightnessChanged(0.0f);
            }
            else {
                // See: https://stackoverflow.com/a/51494556/1117968
                final float brightness = (maxBrightness - minBrightness) * (lightValue - minLux.value) / (maxLux.value - minLux.value) + minBrightness;

                listener.debugInformationProvided(new DebugInformation("Calculated Brightness Value: ", String.valueOf(brightness)));

                listener.brightnessChanged(brightness);
            }
        }
    }

    private void sensorAccuracyChanged(Sensor sensor, int i) {
        BrightnessManagerListener listener = this.brightnessListener.get();

        if (listener != null) {
            listener.debugInformationProvided(new DebugInformation(sensor.getName(), String.valueOf(i)));
        }
    }

    /**
     * Every activation of the back button on the EO1 will increase the min brightness level
     */
    public void adjustMinimumBrightness() {
        float newBrightness = this.minBrightness + 0.1f;

        this.minBrightness = newBrightness > maxBrightness ? 0.1f : newBrightness;
    }
}
