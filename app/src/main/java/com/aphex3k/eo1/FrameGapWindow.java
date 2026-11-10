package com.aphex3k.eo1;

/**
 * The longest interval between two successive composited video frames since the player was pointed at
 * its current source.
 *
 * <p>It exists because the app had exactly one per-frame datum — the wall clock of the last frame
 * ({@code TextureVideoView.getLastFrameAtMs()}) — and that answers only "when did something arrive",
 * read at the instant someone asks. A six-second interval in which nothing new reaches the screen and a
 * clip that simply stopped painting look identical through it unless the reader happens to poll inside
 * the interval, and the one poll that could have caught it (the paint watchdog) cancels itself on the
 * first frame it ever sees. Meanwhile the media clock can keep advancing at exactly 1.00x across the
 * whole interval, so every other field in the process calls the clip healthy. <b>Whether the wall was
 * actually frozen during such an interval is what this class was built to find out, and it has not
 * answered it yet</b> — see the paragraph below on what the counter can and cannot see.
 *
 * <p>Spacing is the missing question, and it is answerable for one subtraction per frame — but it
 * answers a <em>narrower</em> question than it first appears to, and the sentence that used to be here
 * claimed more than the instrument can support. It said a player parked at end of file would show "one
 * gap the length of the hold". It does not: the first hardware run of this class recorded a
 * bit-identical parked position with a last gap of 78 ms and a maximum of 241 ms. The reason is what the
 * stamp actually marks — {@code onSurfaceTextureUpdated} fires when a producer queues a buffer that a
 * draw pass consumes, so it counts <em>buffer arrivals, not changes of picture</em>. A decoder at end
 * of stream that re-queues the same frame reads as smooth, and because {@code mUpdateLayer} is a
 * boolean rather than a counter, several real frames can collapse into one gap.
 *
 * <p>What it does establish, and it is still worth having: whether the surface was receiving anything at
 * all, independent of any clock the player controls. A wide-open gap is positive evidence of a stall.
 * A small one is not proof of motion — so never read a low {@code frameGapMaxMs} as "the picture was
 * changing", and never let it clear a suspicion the media clock raised.
 *
 * <p>High-water rather than a window, deliberately: a clip is one data source, and "has this clip ever
 * held a frame" is the question an operator asks once, at any moment in a sixty second lap. A rolling
 * window would be unreadable for most of that lap — the interesting gap has scrolled out by the time
 * anyone asks about it.
 *
 * <p><b>The price of that choice, and it is a narrower one than it looks:</b> a retained maximum outlives
 * the painting it describes <em>within one data source</em>. Once the surface stops receiving frames the
 * numbers stay exactly where they were, so an unchanged {@link #getMaxGapMs()} says nothing about whether
 * anything is arriving now — read it beside {@link #getLastGapMs()} and the caller's own last-frame stamp,
 * which do move.
 *
 * <p>That retention is a fact about this tracker, not about the HTTP response, and the two are easy to
 * conflate. {@link VideoStateJson} emits both fields only while a clip is provably on the panel and the
 * document still owns the player it read them from, and {@link #reset()} clears the record when the player
 * is pointed at a new source — so {@code /state} cannot quote a video that left the wall, and an absent
 * pair there means "no clip is up", or an engine with no per-frame signal, never "spacing is fine". A
 * consumer asking <em>which</em> content a reported pair belongs to reads {@code video.onScreen} and
 * {@code media.screen}; {@code media.current} names what rotation <em>selected</em> and legitimately
 * differs from the wall during a handoff, so it cannot answer that.
 *
 * <p><b>Clock contract.</b> {@code nowMs} must come from a <em>monotonic</em> clock —
 * {@code SystemClock.elapsedRealtime()}, not the wall clock. These frames are power-cycled routinely
 * and run no time sync (gate 4 of {@code docs/TRUST.md}), so a correction can step the wall clock
 * forward by minutes between two smoothly presented frames, and a high-water mark would keep that
 * number for the rest of the source: an invented stutter that cannot be told from a real one. The
 * caller keeps a separate wall-clock stamp for {@code lastFrameAgeMs}, which genuinely wants the date.
 *
 * <p><b>Suspension.</b> A frame that never arrived because the app took the surface away is not a
 * stall the decoder caused, so {@link #suspendSampling()} must be called whenever the view stops being
 * able to paint. Without it, one trip through quiet hours would record hours of hidden interval as the
 * clip's worst gap — the exact phantom this class exists to avoid producing.
 *
 * <p><b>Thread note.</b> Written on the UI thread from {@code onSurfaceTextureUpdated} — the single
 * writer, which is what makes the read-modify-write in {@link #recordFrame(long)} sound — and read on the
 * web server's threads and the diagnostics collector. Both exported values live in one {@code volatile long},
 * so a reader gets a pair that was published together rather than two values it has to combine correctly:
 * JLS 17.7 makes a volatile long indivisible even on this 32-bit build, which an ordinary long would not be.
 * {@code lastAtMs} and {@code haveStamp} are writer-only and stay plain, and a reader that catches the two
 * mid-update sees a gap without its stamp rather than the reverse, which costs one sample and invents
 * nothing. Nothing here allocates and nothing here can throw, because it runs inside the compositor
 * callback.
 */
public final class FrameGapWindow {

    /** No frame has been recorded since the last {@link #reset()}. */
    public static final int NO_GAP = -1;

    // A flag rather than a sentinel value in lastAtMs: 0 is a legal timestamp, and encoding "no stamp
    // yet" as "the stamp is zero" would silently swallow the first frame of any caller whose clock
    // reads epoch. It also made the first two frames of a clip unreproducible in a test.
    private boolean haveStamp;
    private long lastAtMs;

    /**
     * Both exported values, in one {@code volatile long}: the latest gap in the high half and the retained
     * maximum in the low one.
     *
     * <p>Two {@code volatile int}s cannot be read as a pair, and every scheme that pretends otherwise has to
     * be honoured by each writer <em>and</em> each reader forever. Two such protocols were tried here in
     * sequence — store the maximum before the latest, then read the latest before the maximum and re-derive
     * the greater — and each was broken by the next review round rather than by a test, which is the actual
     * argument for this layout. The race test does see a two-field version fail (see the note in
     * {@code FrameGapWindowTest}), but it sees it only when the writer's pattern happens to open the window,
     * and the first attempt to prove that with a mutation check reported a false negative of its own — the
     * build had not recompiled. An invariant whose violation is invisible to the tooling will be violated.
     * One word, one store, one load, and the question cannot be asked.
     *
     * <p>Atomicity does not depend on the platform width: JLS 17.7 gives a {@code volatile} long read and
     * write the same indivisibility as any other volatile field, which an ordinary long would not have on this
     * 32-bit ARM build. And no object is created, which is what keeps {@link #recordFrame(long)} legal to call
     * from inside the compositor callback.
     */
    private volatile long gaps = pack(NO_GAP, NO_GAP);

    private static long pack(int latest, int max) {
        return ((long) latest << 32) | (max & 0xFFFFFFFFL);
    }

    private static int latestOf(long pair) {
        return (int) (pair >> 32);
    }

    private static int maxOf(long pair) {
        return (int) pair;
    }

    /**
     * Record that a frame reached the surface at {@code nowMs}, on the caller's monotonic clock.
     *
     * <p>The first frame after a {@link #reset()} or a {@link #suspendSampling()} records no interval:
     * the time since the surface was last painting belongs to whatever the app was doing in between,
     * not to a stall inside this clip, and reporting it would make every clip's first poll look like a
     * freeze.
     *
     * <p>A {@code nowMs} at or before the previous one is clamped to a zero gap rather than recorded as
     * a negative one or skipped. It should be unreachable on a monotonic clock; it stays because the
     * stamp is a caller-supplied number, and skipping the sample would leave the <em>next</em> frame
     * measured from the older stamp, inventing the very gap this method exists to refuse.
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
        // One store of one packed pair, so no reader can ever observe a half-applied update. The read of
        // `gaps` here is a read-modify-write, and that is sound only because a single thread — the one that
        // owns the surface — is the writer; see the thread note on the class.
        int previousMax = maxOf(gaps);
        int raised = (previousMax == NO_GAP || gapMs > previousMax) ? gapMs : previousMax;
        gaps = pack(gapMs, raised);
    }

    /**
     * Forget everything. Called when a new data source is set and when the surface goes away, so a
     * clip's record describes only that clip on only that surface.
     */
    public void reset() {
        haveStamp = false;
        lastAtMs = 0L;
        gaps = pack(NO_GAP, NO_GAP);
    }

    /**
     * Drop the timestamp and keep the record.
     *
     * <p>Call it whenever the view stops being able to paint for a reason of the app's own — the panel
     * was hidden for quiet hours, the activity paused, the window went away — so that the first frame
     * after the surface comes back measures nothing and simply re-establishes a stamp. The whole
     * retained maximum is left alone, because a gap that happened during real playback is exactly what
     * the caller is asking about; it is only the interval <em>across</em> the suspension that means
     * nothing.
     *
     * <p>This is the difference between reporting an interval in which the clip stopped presenting and
     * reporting the three hours the frame spent dark.
     */
    public void suspendSampling() {
        haveStamp = false;
    }

    /**
     * The worst frame spacing since the last {@link #reset()}, or {@link #NO_GAP} while fewer than two
     * frames have arrived — which is "the clip has not painted enough to have an opinion", not
     * "spacing is fine".
     *
     * <p><b>Read from the same word as the latest gap, and that is the point.</b> The maximum is >= the
     * latest gap by definition, but two {@code volatile} fields are not one value: a reader sampling between
     * two stores can pair a new latest with the maximum that predates it — {@code frameGapMs: 6000} beside
     * {@code frameGapMaxMs: 33}, a pair no sequence of frames produces, exported by the one field a report is
     * read for. The ownership re-check in {@code VideoStateJson} cannot catch it, because nothing was handed
     * over: the player is the same, the clip is the same, and only the arithmetic between two fields is
     * wrong.
     *
     * <p>It is published together with the latest gap in one word (see {@link #gaps}), which is what makes
     * that a promise rather than a hope. Two {@code volatile int}s can be kept consistent only by a protocol
     * every writer and every reader has to honour forever; the packed pair needs no protocol, allocates
     * nothing on a path that runs inside the compositor callback, and is indivisible on this 32-bit build
     * because JLS 17.7 grants that to a {@code volatile} long.
     *
     * <p>What remains is not a race but a semantic boundary: a caller that reads the maximum here and the
     * latest afterwards gets two samples, and between them a {@link #reset()} can clear the record for a new
     * data source, pairing an old worst with {@link #NO_GAP}. That is a question of ownership — which source
     * the numbers belong to — and {@code VideoStateJson} answers it by discarding every live reading when the
     * panel no longer belongs to the reported clip.
     */
    public int getMaxGapMs() {
        // One load of one word, so the value returned was published together with the latest gap that
        // shares it. Nothing here depends on the order two callers sample in, and that is the property the
        // earlier read-order and store-order protocols could not deliver.
        return maxOf(gaps);
    }

    /** Spacing of the two most recent frames, or {@link #NO_GAP}. */
    public int getLastGapMs() {
        return latestOf(gaps);
    }

}
