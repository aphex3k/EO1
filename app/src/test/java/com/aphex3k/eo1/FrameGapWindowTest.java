package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

/**
 * Frame spacing, pinned.
 *
 * <p>This is the instrument half of a stutter report. The app's only per-frame datum was the wall
 * clock of the last frame, which cannot tell a picture held for six seconds at the end of a lap from a
 * clip that stopped painting — and the EO2 dump that motivated {@link VideoLoopSeamRule} shows why
 * that matters: the media clock advanced at exactly 1.00x across the entire hold, so every field in
 * the process described a healthy clip while the wall was frozen. Spacing is the one measurement that
 * cannot be contradicted by a stagefright timer, because it is stamped where pixels reach the surface.
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
    public void aHeldFrameIsRetainedForTheRestOfTheClip() {
        // The point of a high-water mark rather than a window: the interesting gap has scrolled out of
        // any short ring by the time anyone asks, and a sixty second lap gives a poll no better than a
        // few percent chance of landing inside the hold.
        long t = 0L;
        for (int i = 0; i < 30; i++) {
            window.recordFrame(t);
            t += FRAME_MS;
        }
        window.recordFrame(t);            // the last smooth frame, so the hold is exactly 6000
        window.recordFrame(t + 6000L);    // the seam: the wall held its decoded frame
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
    public void aClockSteppingBackwardsIsACorrectionNotAFreeze() {
        // These frames do not run time sync and are power-cycled routinely, so wall time can jump.
        // The correction is recorded as a zero gap — skipping the sample instead would leave the NEXT
        // frame measured from the pre-correction stamp and invent a gap out of nothing.
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
