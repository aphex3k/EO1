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
     * Where {@link #minimumBrightnessStep} came from: {@code "prefs"} (read off disk), {@code "web"}
     * or {@code "button"} (moved by an operator since), {@code "lastKnownGood"} (this process could
     * not read the store and is using a value it read earlier), or {@code "default"} (nothing
     * answered, so the frame is on the floor). The distinction is the whole point: a frame sitting at
     * 0.3 because nobody ever adjusted it and a frame that lost its level are the same number and
     * different facts.
     */
    private volatile String stepSource = "default";

    /**
     * Whether {@link #minimumBrightnessStep} is a fact about this frame or a placeholder for one.
     * False only after a store that would not answer: the next step must then re-read and grow from
     * what is actually stored, because a guess grown and written back is how a bad read becomes
     * permanent. The first press clears it: a level somebody chose is a fact even when the store
     * refused to keep it.
     */
    private volatile boolean stepAuthoritative = false;

    /**
     * The last level change this process made, for {@code /state}: from, to, who, when, durable.
     * Volatile as a group because {@code /state} renders them on the web server's thread while the
     * press that fills them happens on the UI thread, and a record that exists to answer "who moved
     * this" must not be able to mix one press's {@code to} with the last press's {@code via}.
     * {@code changedAtMs} of 0 means no press has happened in this process, so {@code durable}
     * before that point describes nothing.
     */
    private volatile int changedFromStep = MIN_STEP;
    private volatile int changedToStep = MIN_STEP;
    private volatile String changedVia = "none";
    private volatile long changedAtMs = 0L;
    private volatile boolean changedDurably = true;

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

        restoreStep();

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

    /** Where the current step came from: see {@link #stepSource}. */
    public String stepSource() {
        return stepSource;
    }

    /** Whether the level this manager holds was read from disk rather than inferred. */
    public boolean isStepAuthoritative() {
        return stepAuthoritative;
    }

    public int changedFromStep() {
        return changedFromStep;
    }

    public int changedToStep() {
        return changedToStep;
    }

    public String changedVia() {
        return changedVia;
    }

    /** Epoch ms of the last step change this process made; 0 when it has made none. */
    public long changedAtMs() {
        return changedAtMs;
    }

    public boolean changedDurably() {
        return changedDurably;
    }

    /** One line for the debug overlay, logcat and — what matters here — {@code eo1-app.log}. */
    private void report(String key, String value) {
        BrightnessManagerListener listener = this.brightnessListener.get();

        if (listener != null) {
            listener.debugInformationProvided(new DebugInformation(key, value));
        }
    }

    /**
     * Reads the level back from the store, once, at construction — and records <em>how</em> that read
     * answered. "Nobody has ever adjusted this frame" and "the store would not say" both end at
     * {@link #MIN_STEP} as a number; only the second is a failure, and only the second may be
     * silently grown from later.
     */
    private void restoreStep() {
        if (store == null) {
            this.stepSource = "default";
            this.stepAuthoritative = false;
            this.minimumBrightnessStep = MIN_STEP;
        } else {
            int stored = store.readMinimumBrightnessStep();
            if (stored >= MIN_STEP) {
                this.stepSource = "prefs";
                this.stepAuthoritative = true;
                this.minimumBrightnessStep = clampStep(stored);
            } else if (stored == SettingsManager.STEP_ABSENT) {
                this.stepSource = "default";
                this.stepAuthoritative = false;
                this.minimumBrightnessStep = MIN_STEP;
            } else {
                int cached = store.lastKnownGoodBrightnessStep();
                this.stepSource = cached >= MIN_STEP ? "lastKnownGood" : "default";
                this.stepAuthoritative = false;
                this.minimumBrightnessStep = clampStep(cached);
            }
        }
        this.minBrightness = floorBrightness(this.minimumBrightnessStep);
        this.changedFromStep = this.minimumBrightnessStep;
        this.changedToStep = this.minimumBrightnessStep;
    }

    /**
     * Every activation of the brightness control raises the floor one step; past the top it wraps
     * back to the floor. The wrap reaching {@link #MIN_STEP} is a destination an operator chose by
     * pressing again, not a reset — which is why the change is recorded with who asked for it
     * ({@code /state}'s {@code lastChange} and one line in {@code eo1-app.log}) rather than prevented.
     *
     * <p>The new level is persisted before it is applied, so a frame that dies a second later still
     * comes back where the user left it.
     *
     * <p>Reachable on a frame with no brightness <em>button</em> — the EO2's control is the web
     * page's, which is the documented replacement for the soft keys it lacks. Nothing in this method
     * is platform-specific, so nothing here asks.
     *
     * @param via what moved it — {@code "button"}, {@code "web"}, or anything else that proves to be
     *            the source. Recorded, not trusted for permission.
     * @return true when the new level was written down. A {@code false} means the panel changed and
     *         the next boot will not remember it, which the caller has to say out loud.
     */
    public boolean raiseMinimumBrightnessStep(String via) {
        final int base = steppedBase();
        this.minimumBrightnessStep = base >= MAX_STEP ? MIN_STEP : base + 1;
        this.minBrightness = floorBrightness(this.minimumBrightnessStep);

        boolean durable = store != null && store.setMinimumBrightnessStep(this.minimumBrightnessStep);
        // A press makes the level chosen, not guessed, whatever the store did with it — and
        // unconditionally so: gating this on {@code durable} leaves a store that answers neither
        // read nor write growing every press from the same stale cache, and the button stops
        // moving the panel on the exact gesture the field report uses to recover a dim frame.
        // Whether the choice outlived this process is a separate fact, reported as {@code durable}.
        this.stepAuthoritative = true;

        this.changedFromStep = base;
        this.changedToStep = this.minimumBrightnessStep;
        this.changedVia = via;
        this.changedAtMs = System.currentTimeMillis();
        this.changedDurably = durable;
        this.stepSource = via;

        report("brightness step", this.changedFromStep + " -> " + this.changedToStep + " via " + via
                + (durable ? "" : " (NOT PERSISTED)"));

        notifyBrightnessChanged();
        return durable;
    }

    /**
     * The step to grow from. Normally the one this manager holds; after a read that could not be
     * trusted, whatever the store says now instead, so a value this process guessed at can never be
     * written back as though it had been chosen.
     */
    private int steppedBase() {
        if (stepAuthoritative || store == null) {
            return this.minimumBrightnessStep;
        }
        int stored = store.readMinimumBrightnessStep();
        if (stored == SettingsManager.STEP_ABSENT) {
            // The store answered this time and it genuinely holds nothing, which is a confirmed
            // floor rather than a failure to know one.
            this.stepAuthoritative = true;
            return MIN_STEP;
        }
        if (stored >= MIN_STEP) {
            this.stepAuthoritative = true;
            return clampStep(stored);
        }
        int cached = store.lastKnownGoodBrightnessStep();
        report("brightness step", "store would not answer; stepping from "
                + (cached >= MIN_STEP ? "the last known level " + cached : "the floor")
                + " — this frame has no confirmed level");
        return cached >= MIN_STEP ? clampStep(cached) : this.minimumBrightnessStep;
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

    /**
     * A step outside this build's range — one written by a future build with more of them — goes to
     * the nearest end, not to the floor. Sending it to {@link #MIN_STEP} would make a level the
     * operator set at 1.0 read as 0.3 after an update, and the next tap would persist that, so the
     * one value nobody chose is the one value a stray number must never become. The sentinel answers
     * from {@link SettingsManager} are negative, so they land here and leave on the floor, which is
     * what an unreadable or never-written store means.
     */
    private static int clampStep(int step) {
        if (step < MIN_STEP) {
            return MIN_STEP;
        }
        return step > MAX_STEP ? MAX_STEP : step;
    }
}
