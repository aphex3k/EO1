package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;

/**
 * The gate that decides when a light-sensor reading earns a line in {@code files/eo1-app.log}.
 *
 * <p>On three live EO1 frames (release build 19, 2026-11-15) between 71 % and 97 % of that file's bytes were
 * three rows repeated once per sensor sample, and a 600-line read of it covered 36 minutes. The gate below is
 * the reason those rows are now a minority of the file. It is pinned as a truth table rather than through the
 * manager because the manager needs a {@code SensorManager} and a live window;
 * {@code BrightnessManagerTest#theSensorPathDoesNotWriteThreeRowsPerSample} is what ties this rule to the
 * path that was flooding.
 *
 * <p>Two cases the file's own history got wrong are asserted explicitly: the measured lux wander (1..5 lux)
 * must not write, which is why the comparison is quantised at all; and a dark panel's level of 0.0 must not be
 * a time series, which is why the level's bucket is 0 there whatever the flag-pair would otherwise imply.
 */
public class BrightnessReportRuleTest {

    /** A stamp that is not the "never written" one, for every test that is not about the first write. */
    private static final long WRITTEN_AT = 1_000_000L;

    /** Well inside the heartbeat, so a test that expects silence is not merely expecting a short interval. */
    private static final long ONE_SAMPLE_LATER = WRITTEN_AT + 250L;

    /** The bucket pair of a steady reading, from the frames that flooded (level ~0.61, lux 1..5). */
    private static final int STEADY_LUX_BUCKET = BrightnessReportRule.luxBucket(3.0f);
    private static final int STEADY_LEVEL_BUCKET = 30;

    /**
     * The manager's arguments with the values this frame's steady reading produces, so a test only has to
     * change the axis it is about.
     */
    private static boolean writes(int lastLux, int lux, int lastLevel, int level,
                                  boolean lastScreenOn, boolean screenOn, long writtenAt) {
        return BrightnessReportRule.shouldWrite(lastLux, lux, lastLevel, level,
                lastScreenOn, screenOn, writtenAt, ONE_SAMPLE_LATER);
    }

    // ---------------------------------------------------------------- the first write

    /**
     * A frame that has said nothing about brightness since it started must not be kept silent by a bucket
     * comparison — the case the {@code Integer.MIN_VALUE} initialisers would otherwise produce, since
     * {@code Integer.MIN_VALUE} is also what a sensor reading of exactly zero would floor to without the
     * clamp in {@code luxBucket}.
     */
    @Test
    public void aFrameThatHasNeverWrittenAlwaysWrites() {
        int[] buckets = new int[]{Integer.MIN_VALUE, 0, 7, 30, 1500};
        boolean[] flags = new boolean[]{false, true};
        for (int lastLux : buckets) {
            for (int lux : buckets) {
                for (int lastLevel : buckets) {
                    for (int level : buckets) {
                        for (boolean lastScreenOn : flags) {
                            for (boolean screenOn : flags) {
                                assertTrue("never written must outrank every value comparison",
                                        BrightnessReportRule.shouldWrite(lastLux, lux, lastLevel, level,
                                                lastScreenOn, screenOn, 0L, ONE_SAMPLE_LATER));
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- what does not count as a change

    @Test
    public void anIdenticalSampleWritesNothing() {
        assertFalse(writes(STEADY_LUX_BUCKET, STEADY_LUX_BUCKET,
                STEADY_LEVEL_BUCKET, STEADY_LEVEL_BUCKET, true, true, WRITTEN_AT));
    }

    /**
     * The measured defect, stated as a number: the room light on these frames wanders between 1 and 5 lux,
     * and the previous code wrote all three rows at every one of those samples. A plain equality gate would
     * still write every sample, which is why the comparison is a decade. The bucket equality the gate rests
     * on is asserted inline so a future re-quantisation fails here rather than in a log file.
     */
    @Test
    public void aLuxWanderInsideOneDecadeWritesNothing() {
        assertEquals("1 and 5 lux are one decade apart in name only — the gate sees one bucket",
                BrightnessReportRule.luxBucket(1.0f), BrightnessReportRule.luxBucket(5.0f));

        int luxAt1 = BrightnessReportRule.luxBucket(1.0f);
        int luxAt5 = BrightnessReportRule.luxBucket(5.0f);

        // The first sample writes because nothing has been written yet; every one after it is the case
        // the flood consisted of.
        assertTrue(BrightnessReportRule.shouldWrite(STEADY_LUX_BUCKET, luxAt1,
                STEADY_LEVEL_BUCKET, STEADY_LEVEL_BUCKET, true, true, 0L, ONE_SAMPLE_LATER));
        assertFalse(BrightnessReportRule.shouldWrite(STEADY_LUX_BUCKET, luxAt5,
                STEADY_LEVEL_BUCKET, STEADY_LEVEL_BUCKET, true, true, WRITTEN_AT, ONE_SAMPLE_LATER));
        assertFalse(BrightnessReportRule.shouldWrite(luxAt5, luxAt1,
                STEADY_LEVEL_BUCKET, STEADY_LEVEL_BUCKET, true, true, WRITTEN_AT, ONE_SAMPLE_LATER));
    }

    /**
     * A level that moved by less than one bucket is the same level. 0.3039 and 0.3085 are what 1 lux and
     * 2 lux derive on the measured curve with the floor at 0.3 — a difference of 0.0046, inside the
     * {@code LEVEL_BUCKETS} quantum of 0.02 by construction.
     */
    @Test
    public void aLevelWanderInsideOneBucketWritesNothing() {
        int low = BrightnessReportRule.levelBucket(0.3039f);
        int high = BrightnessReportRule.levelBucket(0.3085f);

        assertEquals("0.0046 of level is not a change", low, high);
        assertFalse(BrightnessReportRule.shouldWrite(STEADY_LUX_BUCKET, STEADY_LUX_BUCKET,
                low, high, true, true, WRITTEN_AT, ONE_SAMPLE_LATER));
    }

    // ---------------------------------------------------------------- what does

    /** A lamp switching on crosses a decade, and that is a fact about the room worth a line. */
    @Test
    public void aDecadeCrossingWrites() {
        assertTrue("5 lux -> 50 lux", writes(STEADY_LUX_BUCKET, BrightnessReportRule.luxBucket(50.0f),
                STEADY_LEVEL_BUCKET, STEADY_LEVEL_BUCKET, true, true, WRITTEN_AT));
    }

    /** The other axis alone: the same light, a level somebody moved. */
    @Test
    public void aLevelBucketChangeWritesEvenWithUnchangedLight() {
        assertTrue(writes(STEADY_LUX_BUCKET, STEADY_LUX_BUCKET, 30, 35, true, true, WRITTEN_AT));
    }

    /**
     * The panel lighting up or going dark is an event, not a value change — and in the going-dark direction it
     * is the only one the level can report, because a dark panel's level is 0.0 whatever the room says.
     */
    @Test
    public void aScreenEdgeWritesEvenWithUnchangedLightAndLevel() {
        assertTrue("wake", writes(STEADY_LUX_BUCKET, STEADY_LUX_BUCKET,
                STEADY_LEVEL_BUCKET, STEADY_LEVEL_BUCKET, false, true, WRITTEN_AT));
        assertTrue("sleep", writes(STEADY_LUX_BUCKET, STEADY_LUX_BUCKET,
                STEADY_LEVEL_BUCKET, STEADY_LEVEL_BUCKET, true, false, WRITTEN_AT));
    }

    /** A steady room still says something every {@link BrightnessReportRule#HEARTBEAT_MS}. */
    @Test
    public void theHeartbeatWritesWhatNoChangeWould() {
        long now = WRITTEN_AT + BrightnessReportRule.HEARTBEAT_MS;

        assertFalse("one millisecond short is not yet a heartbeat",
                BrightnessReportRule.shouldWrite(STEADY_LUX_BUCKET, STEADY_LUX_BUCKET,
                        STEADY_LEVEL_BUCKET, STEADY_LEVEL_BUCKET, true, true,
                        WRITTEN_AT, now - 1L));
        assertTrue(BrightnessReportRule.shouldWrite(STEADY_LUX_BUCKET, STEADY_LUX_BUCKET,
                STEADY_LEVEL_BUCKET, STEADY_LEVEL_BUCKET, true, true, WRITTEN_AT, now));
    }

    /**
     * While the panel is dark its level is 0.0 by definition, so the level axis cannot move and must not
     * pretend to: the only things that reach a line in the dark are a decade of light and the edge that ends
     * the dark. This is the whole of why the fold exists — a quiet-hours frame would otherwise be the one
     * case where "nothing changed" and "nothing happened" are the same sentence.
     */
    @Test
    public void theDarkPanelIsNotATimeSeries() {
        assertEquals("a dark panel has no level to report", 0, BrightnessReportRule.levelBucket(0.0f));

        assertFalse("same decade, dark: nothing",
                BrightnessReportRule.shouldWrite(BrightnessReportRule.luxBucket(2.0f),
                        BrightnessReportRule.luxBucket(4.0f),
                        0, 0, false, false, WRITTEN_AT, ONE_SAMPLE_LATER));
        assertTrue("the decade still moves it",
                BrightnessReportRule.shouldWrite(BrightnessReportRule.luxBucket(2.0f),
                        BrightnessReportRule.luxBucket(200.0f),
                        0, 0, false, false, WRITTEN_AT, ONE_SAMPLE_LATER));
        assertTrue("so does the panel coming back",
                BrightnessReportRule.shouldWrite(BrightnessReportRule.luxBucket(2.0f),
                        BrightnessReportRule.luxBucket(4.0f),
                        0, 0, false, true, WRITTEN_AT, ONE_SAMPLE_LATER));
    }

    // ---------------------------------------------------------------- the folded window

    /** An epoch whose clock text the test can state independently of the rule's formatter. */
    private static long epochAt(int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, 2026);
        calendar.set(Calendar.MONTH, Calendar.NOVEMBER);
        calendar.set(Calendar.DAY_OF_MONTH, 15);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static String clockOf(long epochMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(epochMs);
        return String.format(Locale.US, "%02d:%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND));
    }

    @Test
    public void aFoldedWindowNamesItsCountItsSpanAndItsExtremes() {
        long opened = epochAt(15, 55, 38);

        assertEquals("12 folded since " + clockOf(opened) + ": lux 0.80-5.20, level 0.31-0.33",
                BrightnessReportRule.foldedLine(12, opened, 0.8f, 5.2f, 0.31f, 0.33f));
    }

    /** The level half of the range collapses to a word when both ends are the same level. */
    @Test
    public void aFoldedWindowWithAnUnchangedLevelSaysSo() {
        long opened = epochAt(15, 55, 38);

        assertEquals("12 folded since " + clockOf(opened) + ": lux 0.80-5.20, level 0.31 unchanged",
                BrightnessReportRule.foldedLine(12, opened, 0.8f, 5.2f, 0.31f, 0.314f));
    }

    @Test
    public void foldedLineIsEmptyWhenNothingWasFolded() {
        assertEquals("", BrightnessReportRule.foldedLine(0, epochAt(9, 0, 0),
                0.8f, 5.2f, 0.31f, 0.33f));
    }

    // ---------------------------------------------------------------- the two quantisers

    @Test
    public void clockTextRendersHoursMinutesSeconds() {
        // epochAt and clockText both speak the JVM's default zone, so the rendered text is this frame's
        // local time whatever zone the suite runs in — only the date part would have needed a zone.
        assertEquals("09:05:07", BrightnessReportRule.clockText(epochAt(9, 5, 7)));
        assertEquals("00:00:00", BrightnessReportRule.clockText(epochAt(0, 0, 0)));
        assertEquals("23:59:59", BrightnessReportRule.clockText(epochAt(23, 59, 59)));
    }

    @Test
    public void clockTextIsEmptyForAnUnknownTime() {
        assertEquals("", BrightnessReportRule.clockText(-1L));
        assertEquals("", BrightnessReportRule.clockText(Long.MIN_VALUE));
    }

    /**
     * The clamp is not decoration: these sensors report 0.0 in the dark and negatives have been seen, and
     * without a floor {@code log10} answers {@code -Infinity}, which {@code (int) Math.floor} turns into
     * {@code Integer.MIN_VALUE} — the manager's own "never written" sentinel.
     */
    @Test
    public void aDarkOrUnreadableSensorSharesOneBucket() {
        assertEquals(BrightnessReportRule.luxBucket(0.0f), BrightnessReportRule.luxBucket(-50.0f));
        assertEquals(BrightnessReportRule.luxBucket(0.0000001f), BrightnessReportRule.luxBucket(0.0f));
        assertTrue(BrightnessReportRule.luxBucket(0.0f) > Integer.MIN_VALUE);
    }

    /**
     * One BACK-button step is 0.1 of the level, which is five buckets — so a press can never be quantised
     * away. Asserted as "at least four" rather than exactly five because {@code 0.7f} is 0.69999998807, and
     * a step landing on the far side of that boundary reads as 34 rather than 35. What must never happen is
     * two consecutive steps sharing a bucket, and that is what the loop pins for every step this build has.
     */
    @Test
    public void oneButtonStepIsNeverQuantisedAway() {
        int previous = -1;
        for (int step = BrightnessManager.MIN_STEP; step <= BrightnessManager.MAX_STEP; step++) {
            float floorAtStep = BrightnessManager.minBrightnessFloor
                    + (BrightnessManager.maxBrightness - BrightnessManager.minBrightnessFloor)
                    * step / (float) BrightnessManager.MAX_STEP;
            int bucket = BrightnessReportRule.levelBucket(floorAtStep);
            assertTrue("step " + step + " must not read as step " + (step - 1), bucket != previous);
            if (previous >= 0) {
                assertTrue("and it is several buckets away, not one", Math.abs(bucket - previous) >= 4);
            }
            previous = bucket;
        }
    }
}
