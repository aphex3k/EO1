package com.aphex3k.eo1;

/**
 * The longest interval between two successive composited video frames since the player was pointed at
 * its current source.
 *
 * <p>It exists because the app had exactly one per-frame datum — the wall clock of the last frame
 * ({@code TextureVideoView.getLastFrameAtMs()}) — and that answers only "when did something arrive",
 * read at the instant someone asks. A frame held on the wall for six seconds and a clip that simply
 * stopped painting look identical through it unless the reader happens to poll inside the hold, and
 * the one poll that could have caught it (the paint watchdog) cancels itself on the first frame it
 * ever sees. Meanwhile the media clock can keep advancing at exactly 1.00x across the whole hold, so
 * every other field in the process says the clip is healthy while the wall is frozen.
 *
 * <p>Spacing is the missing question, and it is answerable for one subtraction per frame: a player
 * that presents frames unevenly shows it directly, and a player parked at end of file shows it as one
 * gap the length of the hold. Unlike the media clock this cannot be lied about by a stagefright timer
 * thread, because it is stamped where pixels actually reach the surface.
 *
 * <p>High-water rather than a window, deliberately: a clip is one data source, and "has this clip ever
 * held a frame" is the question an operator asks once, at any moment in a sixty second lap. A rolling
 * window would be unreadable for most of that lap — the interesting gap has scrolled out by the time
 * anyone asks about it.
 *
 * <p><b>The price of that choice, and it is a real one:</b> a high-water mark outlives the painting it
 * describes. Once the surface stops receiving frames the numbers stay exactly where they were, so
 * these fields belong to the clip named by {@code video.path}, which is not necessarily the thing on
 * the wall — a frame gap read while a photo is displayed is a report about the last video, and
 * measured this way it looks like a live reading. Read them together with {@code lastFrameAgeMs} (which
 * counts video frames only, and so is already large for any photo) and with {@code media.current.type}.
 * Nothing else in the {@code video} block advertises that it is stale, and a retained pair of ordinary
 * spacings is indistinguishable from a live one, so a poller that reads only these two fields can watch
 * a still image for minutes and believe it is timing laps.
 *
 * <p><b>Thread note.</b> Written on the UI thread from {@code onSurfaceTextureUpdated}, read on the
 * web server's threads and the diagnostics collector. The gap fields are {@code int} specifically so
 * they are single-copy: the JVM guarantees an unpadded {@code int} read sees a whole value, while a
 * non-volatile {@code long} may be read as a mix of two halves, and a torn gap would surface as an
 * enormous one — a phantom freeze on a frame that is fine. {@code lastAtMs} and {@code haveStamp} are
 * only ever touched by the writer, and a reader that catches the two mid-update sees a gap without its
 * stamp rather than the reverse, which costs one sample and invents nothing. Nothing here allocates
 * and nothing here can throw, because it runs inside the compositor callback.
 */
public final class FrameGapWindow {

    /** No frame has been recorded since the last {@link #reset()}. */
    public static final int NO_GAP = -1;

    // A flag rather than a sentinel value in lastAtMs: 0 is a legal timestamp, and encoding "no stamp
    // yet" as "the stamp is zero" would silently swallow the first frame of any caller whose clock
    // reads epoch. It also made the first two frames of a clip unreproducible in a test.
    private boolean haveStamp;
    private long lastAtMs;
    private int lastGapMs = NO_GAP;
    private int maxGapMs = NO_GAP;

    /**
     * Record that a frame reached the surface at {@code nowMs}.
     *
     * <p>The first frame after a {@link #reset()} records no interval: the time since the previous
     * source ended is the gap between two clips, not a stall inside this one, and reporting it would
     * make every clip's first poll look like a freeze.
     *
     * <p>A {@code nowMs} earlier than the previous one is clamped to a zero gap rather than recorded
     * as a negative one or skipped. These frames are routinely power-cycled and do not run time sync
     * (gate 4 of {@code docs/TRUST.md}), so a backwards clock step is a correction, not a stall — and
     * skipping it would leave the <em>next</em> frame measuring from the pre-correction stamp and
     * invent a gap instead.
     */
    public void recordFrame(long nowMs) {
        long previous = lastAtMs;
        boolean hadStamp = haveStamp;
        lastAtMs = nowMs;
        haveStamp = true;
        if (!hadStamp) {
            return;
        }
        long gap = nowMs - previous;
        int gapMs = gap <= 0L ? 0 : (gap > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) gap);
        lastGapMs = gapMs;
        if (maxGapMs == NO_GAP || gapMs > maxGapMs) {
            maxGapMs = gapMs;
        }
    }

    /**
     * Forget everything. Called when a new data source is set and when the surface goes away, so a
     * clip's record describes only that clip on only that surface.
     */
    public void reset() {
        haveStamp = false;
        lastAtMs = 0L;
        lastGapMs = NO_GAP;
        maxGapMs = NO_GAP;
    }

    /**
     * The worst frame spacing since the last {@link #reset()}, or {@link #NO_GAP} while fewer than two
     * frames have arrived — which is "the clip has not painted enough to have an opinion", not
     * "spacing is fine".
     */
    public int getMaxGapMs() {
        return maxGapMs;
    }

    /** Spacing of the two most recent frames, or {@link #NO_GAP}. */
    public int getLastGapMs() {
        return lastGapMs;
    }
}
