package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Frame spacing, pinned.
 *
 * <p>This is the instrument half of a stutter report. The app's only per-frame datum was the wall
 * clock of the last frame, which cannot tell an interval in which nothing new reached the screen from a
 * clip that stopped painting — and the EO2 dump that motivated {@link VideoLoopSeamRule} shows why the
 * distinction matters: across ~5.9 s of wall the position advanced 255 ms, and every clock-based field
 * described a healthy clip while it did. Spacing is the one measurement no stagefright timer can
 * contradict, because it is stamped at a draw pass rather than derived from elapsed time — but it says
 * whether buffers arrived, not whether the picture changed. See {@link FrameGapWindow} for the whole
 * distinction; the short version is that <b>whether the wall was frozen is still an open question</b>,
 * and this instrument exists to answer it rather than to have answered it.
 *
 * <p>What is asserted here is the semantics an operator reads off {@code /state}: what "no record"
 * means, what a reset discards, and the two clock hazards of a device that is power-cycled and never
 * time-synced (gate 4 of {@code docs/TRUST.md}). A wrong answer here is worse than the gap it fills —
 * a phantom multi-second freeze on a frame that is fine would send an operator reinstalling roots or
 * re-hanging the panel.
 */
public class FrameGapWindowTest {

    /** Roughly 30 fps: this hardware's ordinary spacing. */
    private static final long FRAME_MS = 33L;

    private FrameGapWindow window;

    @Before
    public void setUp() {
        window = new FrameGapWindow();
    }

    @Test
    public void oneFrameIsNotYetAnOpinion() {
        // "Two frames have not arrived" must not read as "spacing is perfect", which is what a zero
        // would say. It is the difference between an instrument and an absence of one.
        assertEquals(FrameGapWindow.NO_GAP, window.getMaxGapMs());
        window.recordFrame(1000L);
        assertEquals(FrameGapWindow.NO_GAP, window.getMaxGapMs());
        assertEquals(FrameGapWindow.NO_GAP, window.getLastGapMs());

        window.recordFrame(1033L);
        assertEquals(33, window.getMaxGapMs());
        assertEquals(33, window.getLastGapMs());
    }

    @Test
    public void aLongQuietIntervalIsRetainedForTheRestOfTheClip() {
        // The point of a high-water mark rather than a window: the interesting gap has scrolled out of
        // any short ring by the time anyone asks, and a sixty second lap gives a poll no better than a
        // few percent chance of landing inside the hold.
        long t = 0L;
        for (int i = 0; i < 30; i++) {
            window.recordFrame(t);
            t += FRAME_MS;
        }
        window.recordFrame(t);            // the last smooth frame, so the hold is exactly 6000
        window.recordFrame(t + 6000L);    // a 6 s interval with nothing arriving: what must be recorded
        window.recordFrame(t + 6033L);

        assertEquals(6000, window.getMaxGapMs());
        assertEquals("recent smoothness must not erase the hold", FRAME_MS, window.getLastGapMs());
    }

    @Test
    public void theFirstFrameOfANewSourceIsNotAGap() {
        // Otherwise every clip's first read reports the gap since the previous clip ended as a freeze
        // inside this one, which is the exact false positive this class has to avoid.
        window.recordFrame(1000L);
        window.recordFrame(1033L);
        window.reset();

        window.recordFrame(9000L);
        assertEquals(FrameGapWindow.NO_GAP, window.getMaxGapMs());
        window.recordFrame(9033L);
        assertEquals(33, window.getMaxGapMs());
    }

    @Test
    public void resetClearsTheWorstAsWellAsTheLast() {
        window.recordFrame(0L);
        window.recordFrame(5000L);
        assertEquals(5000, window.getMaxGapMs());

        window.reset();
        assertEquals(FrameGapWindow.NO_GAP, window.getMaxGapMs());
        assertEquals(FrameGapWindow.NO_GAP, window.getLastGapMs());
    }

    @Test
    public void aHiddenWindowIsNotAFreeze() {
        // The reason suspendSampling exists. turnScreenOff() sets this view INVISIBLE for quiet hours
        // and an INVISIBLE TextureView composites nothing at all, so without suspension the first frame
        // after the panel returns reports the entire dark window as the clip's worst gap -- and a
        // high-water mark keeps it there for the rest of the source. That is the difference between
        // measuring a six second hold and measuring three hours.
        window.recordFrame(0L);
        window.recordFrame(33L);
        window.recordFrame(6033L);                     // a real hold during playback
        assertEquals(6000, window.getMaxGapMs());

        window.suspendSampling();
        long afterAnHourInTheDark = 6033L + 3_600_000L;
        window.recordFrame(afterAnHourInTheDark);
        assertEquals("the hidden interval must not become the worst gap", 6000, window.getMaxGapMs());
        assertEquals("nor the most recent one", 6000, window.getLastGapMs());

        window.recordFrame(afterAnHourInTheDark + FRAME_MS);
        assertEquals("playback resumes without a phantom gap", (int) FRAME_MS, window.getLastGapMs());
        assertEquals("and the real hold survives it", 6000, window.getMaxGapMs());
    }

    @Test
    public void suspensionBeforeTwoFramesIsStillNoOpinion() {
        // Suspension must not manufacture a record where none existed -- "no record" and "smooth" are
        // different answers and both have to stay expressible.
        window.recordFrame(0L);
        window.suspendSampling();
        window.recordFrame(5000L);
        assertEquals(FrameGapWindow.NO_GAP, window.getMaxGapMs());
        assertEquals(FrameGapWindow.NO_GAP, window.getLastGapMs());
    }

    @Test
    public void aClockSteppingBackwardsIsACorrectionNotAFreeze() {
        // Callers owe FrameGapWindow a monotonic stamp, so a backwards delta should be unreachable;
        // the guard stays because the stamp is a caller-supplied number. It is recorded as a zero gap
        // rather than skipped, because skipping would leave the NEXT frame measured from the older
        // stamp and invent the very gap the branch refuses.
        window.recordFrame(10000L);
        window.recordFrame(10033L);
        assertEquals(33, window.getMaxGapMs());

        window.recordFrame(4000L);        // the clock jumps back
        assertEquals(0, window.getLastGapMs());
        assertEquals("a correction must not lower the clip's worst", 33, window.getMaxGapMs());

        window.recordFrame(4033L);
        assertEquals(33, window.getLastGapMs());
        assertEquals(33, window.getMaxGapMs());
    }

    @Test
    public void absurdSpacingIsCappedRatherThanWrapped() {
        // The stored field is an int so a reader on another thread cannot see half of a long. A gap
        // beyond int range is physically impossible and arrives only from a corrected clock, so it
        // saturates instead of reappearing as a negative or tiny number.
        window.recordFrame(1000L);
        window.recordFrame(1000L + 3_000_000_000L);
        assertEquals(Integer.MAX_VALUE, window.getMaxGapMs());
    }

    @Test
    public void thePublishedPairNeverClaimsALatestAboveTheWorst() {
        // Whatever the sequence, the two exported numbers have to describe a frame history that could have
        // happened. A latest above the retained maximum is not a measurement of anything: it is two stores
        // read between one and the other, and it is the one shape of lie this class could tell that
        // contradicts the same document two fields later.
        long t = 0L;
        for (int i = 0; i < 2000; i++) {
            t += (i % 3) * 900L;
            window.recordFrame(t);
            assertTrue("latest " + window.getLastGapMs() + " above max " + window.getMaxGapMs(),
                    window.getMaxGapMs() >= window.getLastGapMs());
        }
    }

    /** Samples per race round. Twelve rounds cost ~50 ms and detect both bugs on every run measured. */
    private static final int RACE_SAMPLES = 250000;

    /** Rounds per pattern. One long pass is not enough — see the measurement in the test below. */
    private static final int RACE_ROUNDS = 12;

    @Test(timeout = 30000L)
    public void aConcurrentReaderSeesAPairThatCouldHaveHappened() throws Exception {
        // Both exported values now live in one volatile long, so this is a guard, not the proof: it fails if
        // the class ever goes back to two independently-written fields, which is the shape that made the
        // invariant depend on how the threads happened to interleave. Against such a version, freshly
        // compiled, this pair of patterns was measured at 830,999 violations with 12 of 12 rounds seeing at
        // least one (growing gaps) and 63,059 with 11 of 12 (a new maximum every other frame); against the
        // packed field, zero violations in all 24 rounds.
        //
        // Both patterns stay because neither reaches every shape of that bug. Gaps that only grow put a new
        // maximum in front of every read, which exposes a writer that stores the latest first but never a
        // getter that samples the maximum before its own latest read — that one needs the maximum to recede
        // relative to a later sample, which is what the short frames in the second pattern do. And rounds
        // rather than one long pass, because a single 4,000,000-sample sweep missed the second shape in one
        // run of six. The asymmetry of any race test still holds — a failure is a proof, a pass only says
        // the window did not open this time — which is exactly why the guarantee now lives in one machine
        // word instead of in this method.
        for (int pattern = 0; pattern < 2; pattern++) {
            for (int round = 0; round < RACE_ROUNDS; round++) {
                assertFalse("pattern " + pattern + " round " + round + ": a reader paired a latest gap above "
                        + "the maximum that should bound it", tornPairObserved(pattern));
            }
        }
    }

    /** One writer raising gaps while one reader samples the pair; true if it ever sees an impossible pair. */
    private static boolean tornPairObserved(final int pattern) throws Exception {
        final FrameGapWindow shared = new FrameGapWindow();
        final boolean[] violated = {false};
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                long t = 0L;
                long step = 1L;
                for (int i = 0; i < RACE_SAMPLES; i++) {
                    step += 3L;
                    t += (pattern == 0 || i % 2 == 0) ? step : 7L;
                    shared.recordFrame(t);
                }
            }
        });
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < RACE_SAMPLES; i++) {
                    int latest = shared.getLastGapMs();
                    int max = shared.getMaxGapMs();
                    if (latest > max) {
                        violated[0] = true;
                    }
                }
            }
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        return violated[0];
    }

    @Test
    public void theWorstIsMonotonicWithinASource() {
        window.recordFrame(0L);
        window.recordFrame(100L);
        assertEquals(100, window.getMaxGapMs());
        window.recordFrame(120L);
        assertEquals(100, window.getMaxGapMs());
        window.recordFrame(300L);
        assertEquals(180, window.getMaxGapMs());
    }
}
