package com.aphex3k.eo1;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.Keep;

import java.lang.ref.WeakReference;

@Keep
public class BrightnessManager {

    /** Read from the UI thread, the kiosk watchdog's timer, and {@code /state}'s worker. */
    private volatile boolean shouldTheScreenBeOn = true;

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

    /**
     * What the last <em>written</em> triple said, in the quantised terms
     * {@link BrightnessReportRule#shouldWrite} compares. Buckets rather than values, because the values
     * wander: the measured 1..5 lux of a room with a lamp differs at every sample and changes nothing.
     *
     * <p>{@code Integer.MIN_VALUE} for both buckets means "no triple has ever been written", which the
     * rule's own first clause answers before it looks at them; {@link #lastReportAtElapsedMs} is the
     * authoritative version of that fact (0 == never) and these two are only its operands.
     * {@link #lastReportedScreenOn} starts false because {@link #shouldTheScreenBeOn} starts true, so the
     * first sample writes on the "never written" clause rather than on a phantom edge.
     */
    private int lastReportedLuxBucket = Integer.MIN_VALUE;
    private int lastReportedLevelBucket = Integer.MIN_VALUE;
    private boolean lastReportedScreenOn = false;

    /**
     * When the triple was last written, on {@code SystemClock.elapsedRealtime()}. 0 means never, which is
     * what makes a frame that has said nothing about brightness since it started unable to stay silent.
     *
     * <p>Package-private rather than private for one reason: {@code BrightnessManagerTest} back-dates it to
     * exercise the heartbeat, and a manager with a setter for its own reporting state would be a second
     * product surface nobody calls. Same monotonic clock the rule's interval clause needs, for the same
     * reason AGENTS.md's gate 4 gives: no time sync on these frames.
     */
    long lastReportAtElapsedMs = 0L;

    /**
     * The samples that produced no line of their own, since the last one that did.
     *
     * <p>Folded rather than dropped, so the gate above costs a reader no information about the interval it
     * skipped: a suppressed window still reaches the log as one line naming its count and its min/max lux and
     * level. Only duplicates are lost, which is the whole point.
     *
     * <p><b>These fields are main-thread-only, and that is an invariant, not an accident.</b> Every entry
     * point that reaches the fold runs on the main thread: the sensor listener is registered without a
     * {@code Handler} (so Android delivers it on the registering thread's looper, which is the main looper,
     * because the registering constructor is reached from {@code MainActivity.onCreate});
     * {@link #setShouldTheScreenBeOn} is reached from the quiet-hours timer only through
     * {@code handler.post(applyScheduledScreenState)}, from the {@code SCREEN_ON}/{@code SCREEN_OFF}
     * receiver's {@code onReceive} (registered without a handler, so the main looper), and from
     * {@code toggleScreenOn}; and {@link #raiseMinimumBrightnessStep} is reached from {@code EventManager}
     * key dispatch and from the web page's {@code brightness} action, which {@code MainActivity} hands to
     * {@code runOnUiThread} along with every other web task. So there is no lock and no {@code volatile} here
     * deliberately. If a future caller arrives off the main thread, the fix is to post it to the main looper
     * — <em>not</em> to synchronise the fold, which would put the sensor path behind the UI thread that
     * {@code MainActivity} already blocks on a wedged display path.
     */
    private int foldedCount = 0;
    private long foldedOpenedEpochMs = 0L;
    private float foldedMinLux = Float.MAX_VALUE, foldedMaxLux = -Float.MAX_VALUE;
    private float foldedMinLevel = Float.MAX_VALUE, foldedMaxLevel = -Float.MAX_VALUE;

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

    /**
     * A sensor reading: remember it, re-derive the level on <em>every</em> sample (the panel follows the room,
     * which is what {@code BrightnessManagerTest}'s monotonicity sweep pins), and ask
     * {@link BrightnessReportRule} whether this sample is worth a line.
     *
     * <p>The gate is here and nowhere else: it is the sensor path that floods — 71-97 % of
     * {@code eo1-app.log}'s bytes on the three frames measured on 2026-11-15 — and an operator's press must
     * never be rate-limited, because a press is the event the log exists to record.
     *
     * <p>Two shapes in here are load-bearing and both invite being "simplified" away.
     *
     * <p><b>The listener call is reached on every sample, gated by nothing.</b> Not by moving it into
     * {@link #notifyBrightnessChanged()}: that method is also the event path, and gating it would gate the
     * presses with it. {@code BrightnessManagerTest#everySensorSampleStillReachesThePanel} holds this, and
     * nothing in the suite held it before — a gate placed above the level application would silently dim every
     * EO1 with a green build.
     *
     * <p><b>One read of the monotonic clock</b>, into {@code nowElapsedMs}, used both as the interval operand
     * and as the stamp stored for the next one. Two reads would let a write store a stamp from a later moment
     * than the interval it was judged on, which shortens the next heartbeat by the gap between them.
     */
    void onLightValue(float lightValue) {
        this.lastLightValue = lightValue;

        final long nowElapsedMs = android.os.SystemClock.elapsedRealtime();
        final boolean lit = shouldTheScreenBeOn;
        final float brightness = currentBrightness();
        final int luxBucket = BrightnessReportRule.luxBucket(lightValue);
        final int levelBucket = BrightnessReportRule.levelBucket(brightness);

        if (BrightnessReportRule.shouldWrite(lastReportedLuxBucket, luxBucket,
                lastReportedLevelBucket, levelBucket,
                lastReportedScreenOn, lit,
                lastReportAtElapsedMs, nowElapsedMs)) {
            lastReportedLuxBucket = luxBucket;
            lastReportedLevelBucket = levelBucket;
            lastReportedScreenOn = lit;
            lastReportAtElapsedMs = nowElapsedMs;
            writeTripleAndFold(brightness);
        } else {
            // Suppressed: it gets no line of its own, so it goes into the one that stands in for it.
            noteSample(lightValue, brightness);
        }

        final BrightnessManagerListener listener = brightnessListener.get();
        if (listener != null) {
            listener.brightnessChanged(brightness);
        }
    }

    /**
     * One line, then the triple. Ordered summary-then-current so a reader looking at a level line and
     * wondering what happened since the last one finds the answer immediately above it rather than in
     * whatever came before the window.
     *
     * <p>It does not go through {@link #notifyBrightnessChanged()} even though the three rows are the same,
     * because that method is the event writer and the two paths owe different things to the fold: this one
     * closes a window of samples, an event merely interrupts one.
     */
    private void writeTripleAndFold(float brightness) {
        reportFoldedLine();
        writeTripleRows(brightness);
    }

    /** The three rows, under the key names and in the float format the log has always carried. */
    private void writeTripleRows(float brightness) {
        report("Reported Light Value", String.valueOf(lastLightValue));
        report("Min Brightness", String.valueOf(minBrightness));
        report("Calculated Brightness Value", String.valueOf(brightness));
    }

    /**
     * Adds a sample to the suppressed window, opening it (and stamping its start on the wall clock, which is
     * a label here, never an interval) on the first one.
     */
    private void noteSample(float lux, float level) {
        if (foldedCount == 0) {
            foldedOpenedEpochMs = System.currentTimeMillis();
        }
        foldedCount++;
        if (lux < foldedMinLux) {
            foldedMinLux = lux;
        }
        if (lux > foldedMaxLux) {
            foldedMaxLux = lux;
        }
        if (level < foldedMinLevel) {
            foldedMinLevel = level;
        }
        if (level > foldedMaxLevel) {
            foldedMaxLevel = level;
        }
    }

    /** The window's line, and the window is gone either way — {@code ""} when nothing was suppressed. */
    private String takeFoldedLine() {
        String line = BrightnessReportRule.foldedLine(foldedCount, foldedOpenedEpochMs,
                foldedMinLux, foldedMaxLux, foldedMinLevel, foldedMaxLevel);
        clearFold();
        return line;
    }

    private void reportFoldedLine() {
        String line = takeFoldedLine();
        if (!line.isEmpty()) {
            report(BrightnessReportRule.FOLDED_KEY, line);
        }
    }

    private void clearFold() {
        foldedCount = 0;
        foldedOpenedEpochMs = 0L;
        foldedMinLux = Float.MAX_VALUE;
        foldedMaxLux = -Float.MAX_VALUE;
        foldedMinLevel = Float.MAX_VALUE;
        foldedMaxLevel = -Float.MAX_VALUE;
    }

    /**
     * States the rule's operands from a line that was just written, and closes the window. Called from the
     * event path, which writes a triple the fold never contained — the sensor path sets the same four fields
     * inline, because there the buckets are already in hand from the decision.
     */
    private void resetFoldAfterWrite(int luxBucket, int levelBucket, boolean lit) {
        lastReportedLuxBucket = luxBucket;
        lastReportedLevelBucket = levelBucket;
        lastReportedScreenOn = lit;
        lastReportAtElapsedMs = android.os.SystemClock.elapsedRealtime();
        clearFold();
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

    /**
     * The <em>event</em> writer: a screen edge or an operator's press. Signature, key names, value formats
     * and the unconditional {@code brightnessChanged} call are exactly what they were, because both callers
     * depend on them; what is new is that an event also closes the fold window it lands inside.
     *
     * <p>A press mid-window both explains itself and says what the room was doing since the last line, which
     * is the pair of facts an operator asking "it dimmed by itself" needs together. The gate never applies
     * here — a press is not a sample and cannot be rate-limited — and {@link #lastReportAtElapsedMs} is
     * re-stamped so the heartbeat measures from this line rather than from one it replaced.
     */
    private void notifyBrightnessChanged() {
        BrightnessManagerListener listener = brightnessListener.get();

        if (listener == null) {
            return;
        }

        final float brightness = currentBrightness();

        reportFoldedLine();
        listener.debugInformationProvided(new DebugInformation("Reported Light Value", String.valueOf(lastLightValue)));
        listener.debugInformationProvided(new DebugInformation("Min Brightness", String.valueOf(minBrightness)));
        listener.debugInformationProvided(new DebugInformation("Calculated Brightness Value", String.valueOf(brightness)));

        resetFoldAfterWrite(BrightnessReportRule.luxBucket(lastLightValue),
                BrightnessReportRule.levelBucket(brightness), shouldTheScreenBeOn);

        listener.brightnessChanged(brightness);
    }

    /**
     * Records a light sensor's accuracy change, under the sensor's own name.
     *
     * <p>{@code ofFrame} is not decoration: the key here is computed, so {@code DebugBoard.FRAME_SCOPED}
     * cannot list it, and an unlisted key is attributed to whatever the panel holds at the moment of
     * writing. A sensor reading is about this machine's light sensor and nothing else, and a row that
     * vanished on the next rotation would answer no question — the accuracy event happens once per boot,
     * long before an operator asks why the panel is dim.
     */
    private void sensorAccuracyChanged(Sensor sensor, int i) {
        BrightnessManagerListener listener = this.brightnessListener.get();

        if (listener != null) {
            listener.debugInformationProvided(DebugInformation.ofFrame(sensor.getName(), String.valueOf(i)));
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
