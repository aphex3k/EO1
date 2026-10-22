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
     * The brightness of the BACK button's lowest step, and what a frame with no stored level shows.
     */
    protected static final float minBrightnessFloor = 0.3f;
    /**
     * At what sensor value do we want to reach the maximum screen brightness? Any value equal
     * or higher to maxLux results in maxBrightness returned from this function.
     */
    protected static final Lux maxLux = Lux.TRAIN_STATION;
    /**
     * At what sensor value do we want to reach the minimum screen brightness? Any value equal
     * or lower to minLux results in minBrightness returned from this function.
     */
    protected static final Lux minLux = Lux.FULL_MOON;

    /**
     * The level the BACK button cycles is a step index, evenly spaced from
     * {@link #minBrightnessFloor} to {@link #maxBrightness} — eight steps, so 0.3, 0.4 … 1.0.
     * A step rather than a float because adding 0.1f per tap drifts, and because the wrap target
     * has to be expressible as "the floor" instead of a second literal that can disagree with it.
     */
    protected static final int MIN_STEP = 0;
    protected static final int MAX_STEP = 7;

    /**
     * The floor the ambient-light curve interpolates up from. Always {@link #floorBrightness} of
     * {@link #minimumBrightnessStep}; nothing else assigns it.
     */
    protected float minBrightness = floorBrightness(MIN_STEP);

    private int minimumBrightnessStep = MIN_STEP;

    /**
     * The most recent light reading the curve used. Starts at {@link #minLux} so a platform with no
     * usable sensor (the EO2, and the EO1 before its first reading) sits exactly on the floor
     * instead of at whatever the hardware last happened to report.
     */
    private float lastLightValue = minLux.value;

    private final SettingsManager store;
    private final SensorManager sensorManager;
    private final SensorEventListener lightSensorListener;

    private final WeakReference<BrightnessManagerListener> brightnessListener;

    /**
     * @param store where the user's level is kept, so it survives a restart or an Activity
     *              re-creation. May be null (tests, early startup), in which case the level is
     *              the floor and never persisted.
     */
    public BrightnessManager(BrightnessManagerListener listener,
                             SensorManager sensorManager,
                             HardwareCapabilities capabilities,
                             SettingsManager store)
    {
        this.brightnessListener = new WeakReference<>(listener);
        this.store = store;
        this.sensorManager = sensorManager;

        this.minimumBrightnessStep = clampStep(store != null ? store.getMinimumBrightnessStep() : MIN_STEP);
        this.minBrightness = floorBrightness(this.minimumBrightnessStep);

        Sensor registeredSensor = null;
        SensorEventListener sensorEventListener = null;

        if (capabilities.supportsLightSensor()) {
            registeredSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

            sensorEventListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    onLightValue(event.values[0]);
                }
                @Override
                public void onAccuracyChanged(Sensor sensor, int i) {
                    sensorAccuracyChanged(sensor, i);
                }
            };

            sensorManager.registerListener(sensorEventListener, registeredSensor,
                    SensorManager.SENSOR_DELAY_UI);
        }

        this.lightSensorListener = sensorEventListener;
    }

    /**
     * We might not want to turn the screen on during quiet period
     * @param onOff If true, turns on the screen. If the screen is already on, does nothing.
     */
    public void setShouldTheScreenBeOn(boolean onOff) {
        if (shouldTheScreenBeOn != onOff) {
            shouldTheScreenBeOn = onOff;
            notifyBrightnessChanged();
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
     * The one question every caller has: what brightness should the panel be at right now.
     * The user's step is a floor, and ambient light raises the level above it on a straight line
     * between {@link #minLux} and {@link #maxLux}. Clamped at both ends, so the curve can bring the
     * panel back down as a room darkens — the ratchet this replaced could only ever make it
     * brighter, which meant a bright afternoon lasted all night.
     */
    public float currentBrightness() {
        if (!shouldTheScreenBeOn) {
            return 0.0f;
        }

        // See: https://stackoverflow.com/a/51494556/1117968
        final float brightness = (maxBrightness - minBrightness) * (lastLightValue - minLux.value)
                / (maxLux.value - minLux.value) + minBrightness;

        return Math.min(maxBrightness, Math.max(minBrightness, brightness));
    }

    /** The brightness the user's current step puts the floor at. */
    public float minimumBrightness() {
        return minBrightness;
    }

    public int getMinimumBrightnessStep() {
        return minimumBrightnessStep;
    }

    public float lastLightValue() {
        return lastLightValue;
    }

    /**
     * Every activation of the brightness control raises the floor one step; past the top it wraps
     * back to the floor. The new level is persisted before it is applied, so a frame that dies a
     * second later still comes back where the user left it.
     *
     * <p>Reachable on a frame with no brightness <em>button</em> — the EO2's control is the web
     * page's, which is the documented replacement for the soft keys it lacks. Nothing in this method
     * is platform-specific, so nothing here asks.
     *
     * @return true when the new level was written down. A {@code false} means the panel changed and
     *         the next boot will not remember it, which the caller has to say out loud.
     */
    public boolean raiseMinimumBrightnessStep() {
        this.minimumBrightnessStep = this.minimumBrightnessStep >= MAX_STEP ? MIN_STEP : this.minimumBrightnessStep + 1;
        this.minBrightness = floorBrightness(this.minimumBrightnessStep);

        boolean durable = store != null && store.setMinimumBrightnessStep(this.minimumBrightnessStep);

        notifyBrightnessChanged();
        return durable;
    }

    /** A sensor reading: remember it, and let the listener re-derive the level from it. */
    void onLightValue(float lightValue) {
        this.lastLightValue = lightValue;
        notifyBrightnessChanged();
    }

    /**
     * Stops the sensor listener. The Activity is rebuilt in onCreate, and without this every
     * re-creation leaves a listener registered that holds the dead Activity and keeps writing
     * brightness on its behalf.
     */
    public void shutdown() {
        if (lightSensorListener != null) {
            sensorManager.unregisterListener(lightSensorListener);
        }
    }

    private void notifyBrightnessChanged() {
        BrightnessManagerListener listener = brightnessListener.get();

        if (listener == null) {
            return;
        }

        final float brightness = currentBrightness();

        listener.debugInformationProvided(new DebugInformation("Reported Light Value", String.valueOf(lastLightValue)));
        listener.debugInformationProvided(new DebugInformation("Min Brightness", String.valueOf(minBrightness)));
        listener.debugInformationProvided(new DebugInformation("Calculated Brightness Value", String.valueOf(brightness)));

        listener.brightnessChanged(brightness);
    }

    private void sensorAccuracyChanged(Sensor sensor, int i) {
        BrightnessManagerListener listener = this.brightnessListener.get();

        if (listener != null) {
            listener.debugInformationProvided(new DebugInformation(sensor.getName(), String.valueOf(i)));
        }
    }

    private static float floorBrightness(int step) {
        return minBrightnessFloor + (maxBrightness - minBrightnessFloor) * step / (float) MAX_STEP;
    }

    /** A step from an older or newer build is not this build's business: fall back to the floor. */
    private static int clampStep(int step) {
        return step < MIN_STEP || step > MAX_STEP ? MIN_STEP : step;
    }
}
