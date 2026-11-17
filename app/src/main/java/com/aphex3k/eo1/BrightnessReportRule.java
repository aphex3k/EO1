package com.aphex3k.eo1;

import java.util.Calendar;
import java.util.Locale;

/**
 * How often a light-sensor reading is worth a line in {@code files/eo1-app.log}.
 *
 * <p>This exists because of a measurement, not a hunch. On three live EO1 frames running release build 19
 * (2026-11-15), between <b>71 % and 97 % of the durable log's bytes were three rows repeated once per sensor
 * sample</b>: {@code Reported Light Value}, {@code Min Brightness}, {@code Calculated Brightness Value}. The
 * sensor fires every ~10-15 s on this hardware, so a 600-line window read through {@code /log/file} covered
 * 36 minutes, and the 256 KiB rolling file rotated every ~4.8-6.5 hours. Everything else the app has to say
 * — a watchdog escalation, a display failure, a crash — was being pushed out of the only record that outlives
 * logcat's ~13 minutes by readings of a lamp that had not changed.
 *
 * <p>The rows themselves came out of {@code BrightnessManager.notifyBrightnessChanged()}, which was reached
 * from {@code onLightValue()} with no change test at all: the sensor path wrote exactly as often as the
 * sensor spoke. A pure "only when the value differs" gate would not have fixed it, because lux on these
 * frames wanders — the measured wander is 1 to 5 lux in a room with a lamp, and every one of those samples
 * differs from the last. So the gate compares <em>quantised</em> values: a decade of lux, and 1/50 of the
 * panel level. What is left is a sampled series of the applied backlight rather than a transcript of the
 * sensor, and the heartbeat bounds how long that series can go without a line.
 *
 * <p>Two absences are deliberate and should not be read as unfinished. There is <b>no rate limiter</b> here
 * and <b>no minimum interval between writes</b>: the reporting period is a property of hardware this app
 * cannot measure, and no constant in this class may be keyed on samples per minute. A lamp crossing decades
 * every 12 s writes every 12 s, and the fold line {@code BrightnessManager} writes alongside it says so. The
 * heartbeat exists for the opposite case — a steady room, which is the one that filled the file.
 *
 * <p>Free of {@code android.*} by design, for the same reason {@code VideoRoutingRule} and
 * {@code VideoLoopSeamRule} are: the decision is the whole product rule, and its caller sits inside a class
 * that needs a {@code SensorManager} and a live window. Pinned directly by
 * {@code BrightnessReportRuleTest}; {@code BrightnessManagerTest} pins that the manager actually asks it.
 */
public final class BrightnessReportRule {

    /**
     * Quantum the panel level is compared at: 1/50 = 0.02 of the 0..1 screen level.
     *
     * <p>Sized against the two things that must not be confused with each other. The measured lamp flicker
     * (1..5 lux on an EO1 at the level the flooded frames were running at) moves the derived level by about
     * 0.019 and therefore stays inside one bucket, which is the whole point; one BACK-button step
     * ({@code (1.0 - 0.3) / 7 = 0.1}) is five buckets, so a press is never invisible. The margin on the
     * first is thin at the dimmest floor — with the curve starting from 0.3 the same 4 lux of wander is
     * 0.0187, which straddles a boundary for some values and not others — and it is the decade gate, not
     * this one, that is supposed to carry a wandering lamp. A frame parked at step 0 in a room whose sensor
     * toggles between the ends of that range can therefore still write per sample; that is a tuning answer
     * ({@code 50} → 25), not a correctness one, and it is stated here rather than assumed away.
     */
    public static final int LEVEL_BUCKETS = 50;

    /**
     * Unconditional re-write of the triple, whatever changed. Milliseconds.
     *
     * <p>This is the worst-case hole in the applied-level time series, and it is the number an operator has
     * to know: a frame whose room never changes now writes nothing about brightness for this long, and the
     * folded line that follows says which samples were skipped rather than hiding that they were.
     */
    public static final long HEARTBEAT_MS = 15L * 60L * 1000L;

    /**
     * The key of the one line the board gains from the gate: what the samples that produced no line of their
     * own said, over the interval they were suppressed. Declared here because {@code BrightnessManager}
     * writes it and {@code DebugBoard.FRAME_SCOPED} lists it; the two must stay in step.
     */
    public static final String FOLDED_KEY = "brightness samples folded";

    /**
     * Bottom of the lux range, and the clamp for anything darker or unreadable.
     *
     * <p>{@code Lux.MOONLESS_OVERCAST.value} — a reference to that enum is deliberately avoided so this class
     * reads as the pure numeric function it is, with no project type in its signature. If the two ever
     * disagree, the bucket of a dark sensor changes, which is a reporting artefact rather than a display one.
     */
    private static final float FLOOR_OF_LUX = 0.0001f;

    /**
     * The panel level as a bucket. A dark panel reports 0.0 by definition (see
     * {@code BrightnessManager.currentBrightness()}), and 0.0 is not a measurement of anything, so it gets
     * bucket 0 — the same bucket every dark sample lands in, which is what keeps a quiet-hours frame from
     * writing a level line every sample on the strength of the level it is not applying.
     */
    public static int levelBucket(float level) {
        if (level <= 0.0f) {
            return 0;
        }
        return (int) Math.floor(level * LEVEL_BUCKETS);
    }

    /**
     * {@code floor(log10(max(lux, FLOOR_OF_LUX)))}. A decade, because lux is perceptually logarithmic and
     * because the measured wander (1..5) is inside one decade while a lamp switching on is not.
     *
     * <p>The clamp matters more than it looks: these sensors report {@code 0.0} in the dark and have been
     * observed to report negatives, and {@code log10} of either is {@code -Infinity}, which floors to
     * {@code Integer.MIN_VALUE} — a value that means "never written" in the manager's state and would
     * otherwise collide with it.
     */
    public static int luxBucket(float lux) {
        float clamped = lux < FLOOR_OF_LUX ? FLOOR_OF_LUX : lux;
        return (int) Math.floor(Math.log10(clamped));
    }

    /**
     * The whole gate. {@code lastLuxBucket}/{@code lastLevelBucket} are the buckets of the values of the
     * triple <em>last written</em> (not the last sample seen); {@code lastWriteAtElapsedMs} is 0 until the
     * first write.
     *
     * <p>{@code nowElapsedMs} is {@code SystemClock.elapsedRealtime()} from the caller — never wall time,
     * because a forward clock correction would manufacture a heartbeat and a backward one would suppress one.
     * These frames do no time sync (AGENTS.md's gate 4) and are routinely power-cycled, so the wall clock is
     * not a monotonic one; the JVM test stub for {@code SystemClock} is {@code nanoTime()}-based, which is
     * the same shape.
     *
     * <p>Order is part of the contract: "never written" comes first so a frame that has said nothing about
     * brightness since it started cannot be silenced by a bucket comparison, and the screen edge comes before
     * the value comparisons so the event that changes what the values <em>mean</em> is never reasoned about
     * through them.
     */
    public static boolean shouldWrite(int lastLuxBucket, int luxBucket,
                                      int lastLevelBucket, int levelBucket,
                                      boolean lastScreenOn, boolean screenOn,
                                      long lastWriteAtElapsedMs, long nowElapsedMs) {
        if (lastWriteAtElapsedMs == 0L) {
            return true;
        }
        if (screenOn != lastScreenOn) {
            return true;
        }
        if (levelBucket != lastLevelBucket) {
            return true;
        }
        if (luxBucket != lastLuxBucket) {
            return true;
        }
        return nowElapsedMs - lastWriteAtElapsedMs >= HEARTBEAT_MS;
    }

    /**
     * The folded-window line, or {@code ""} when {@code count == 0}. One physical line, no trailing newline.
     *
     * <pre>
     * "12 folded since 15:55:38: lux 0.80-5.20, level 0.31-0.33"
     * "12 folded since 15:55:38: lux 0.80-5.20, level 0.31 unchanged"
     * </pre>
     *
     * <p>{@code count} is the number of samples that produced no line of their own, so a reader who sees a
     * gap between two triples knows how much of it was duplicates and what its extremes were. The range
     * separator is an ASCII hyphen: this string goes into a line whose format is
     * {@code yyyy-MM-dd HH:mm:ss I [EO1] <key>: <value>}, and an en-dash in a grep-able record is a gift to
     * nobody.
     *
     * <p>{@code epochMs} is the wall time the window opened — wall time is correct <em>here</em> and only
     * here, because it is a label a human reads against a log file whose own stamps are wall time. The
     * interval decision above never uses it.
     */
    public static String foldedLine(int count, long windowOpenedEpochMs,
                                    float minLux, float maxLux,
                                    float minLevel, float maxLevel) {
        if (count <= 0) {
            return "";
        }
        String levelPart;
        if (levelBucket(minLevel) == levelBucket(maxLevel)) {
            levelPart = String.format(Locale.US, "level %.2f unchanged", minLevel);
        } else {
            levelPart = String.format(Locale.US, "level %.2f-%.2f", minLevel, maxLevel);
        }
        return String.format(Locale.US, "%d folded since %s: lux %.2f-%.2f, %s",
                count, clockText(windowOpenedEpochMs), minLux, maxLux, levelPart);
    }

    /**
     * {@code "HH:mm:ss"} from epoch ms in the default zone; {@code ""} for a negative value.
     *
     * <p>{@code Calendar} rather than a shared {@code SimpleDateFormat} because the formatter is not
     * thread-safe and a static one would make this class's purity a lie; one allocation per folded line,
     * which is per flush, which is at worst per sensor sample of a suppressed window.
     */
    public static String clockText(long epochMs) {
        if (epochMs < 0L) {
            return "";
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(epochMs);
        return String.format(Locale.US, "%02d:%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND));
    }

    private BrightnessReportRule() {
    }
}
