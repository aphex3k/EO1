package com.aphex3k.eo1;

/**
 * When one lap of a looping video is over, stated apart from the player that has to notice it.
 *
 * <p>It exists as a separate type for the same reason {@link VideoRoutingRule} does: the decision is
 * the whole of the product rule, and it was unreachable from a JVM test because it sat inline in
 * {@code MainActivity.runVideoWatchdogCheck}. That block had no coverage at all, which is how a
 * revision could ship a boundary condition measured only on paper — commit {@code 4386f8b} said so
 * in its own message ("the part not yet measured on hardware: the EO2 is inside quiet hours") — and
 * three weeks later a real dump showed the boundary arriving seconds late. Everything here is static
 * and free of Android, so both recorded position series can be pinned directly.
 *
 * <h3>Why there is a rule at all</h3>
 *
 * <p>A clip whose last frames sit past the duration the player reports never raises
 * {@code onCompletion}, so {@code setLooping(true)} has no end-of-stream to loop on and {@code play()}
 * is a no-op while the player still believes it is {@code PLAY} — on an EO2 that read as a decoder hung
 * for 8.4 s every lap. The platform player therefore cannot be left to loop itself, and a poll of the
 * position is the only detector. It has to answer "is this the end" from a number the player does not
 * report reliably.
 *
 * <p>Two things hide that boundary. The player's duration is shorter than the media it is holding, and
 * the reason is not the container: <b>this ROM's {@code MediaPlayer} floors a clip's length to the
 * whole second.</b> Nine of nine looping clips in a live EO2's durable log report exactly
 * {@code floor(fileMs / 1000) * 1000} — 23000 for 23621, 31000 for 31041, 37000 for 37270, 45000 for
 * 45541, 55000 for 55855, 58000 for 58485 — so the remainder is under one second by construction and
 * measured at 41 ms to 855 ms across those nine, and ending on the player's number truncates frames
 * still running by up to that. (An earlier
 * account blamed the QuickTime {@code mvhd} timescale for not dividing the frame period evenly. That
 * was inherited from the first fix in this area, and the nine measurements contradict it: a timescale
 * artefact would not land on a whole second nine times running.)
 *
 * <p>And past that point the position does not freeze, it creeps
 * ({@code 23011, 23169, 23374, 23879} ms) — so "did not move between polls" is never true either, and
 * every change-based test re-arms the stall threshold instead of firing.
 *
 * <p>Hence: the end is the greater of the player's duration and the file's own length where a probe
 * could read one, and "no headway" means stopped OR creeping. Which end to demand depends on which
 * test fired — a position that has not moved at all is at the end as far as the player is concerned,
 * and some files stop dead at the shortened number without ever passing it, so demanding the file's
 * length there would skip this branch and leave the 8 s threshold to notice.
 *
 * <h3>The third tail, and why the stricter end cannot be the only test</h3>
 *
 * <p>A position parked at end of file does not sit bit-still either. Measured on a live EO2 across the
 * last polls of a 55855 ms file whose player reports 55000:
 * {@code 55756, 55049, 55023, 56011} — it steps <em>backwards</em> by 707 ms, then 26 ms, then
 * forward again. The first two readings satisfy {@link Tail#CREEPING} (a negative delta is below any
 * positive threshold) but both sit <em>below</em> the demanded 55855, so nothing restarted until the
 * fourth reading happened to drift past it: about 5.9 s of wall during which the position advanced
 * 255 ms in total, then a hard cut to frame 0. Two of those three polls were spent below the file's
 * length, which is what this rule asks for.
 *
 * <p><b>What that interval was is not known, and the earlier wording here overstated it.</b> "The wall
 * held its last decoded frame" was inferred from position readings; no presentation data exists for
 * that clip — it predates the frame-gap instrument. And that instrument has since recorded no interval
 * above 345 ms anywhere, including on a lap whose position was bit-identical between polls (whose own
 * maximum was 241 ms), which is the direct evidence that it counts buffers reaching a draw pass rather
 * than changes of picture. It therefore cannot settle the question in either direction. What is measured
 * is a stall of the media timeline — about 0.04x across those polls, against 1.00x through the body of
 * the same lap; whether the panel went along with it is an open question, and the branch has to be
 * justified without leaning on it. ({@code rate=} printed beside the restart is not the media clock: it
 * is {@code MainActivity.measurePlaybackRate}'s 5 s window, which re-anchors on a backwards step, so it
 * never spanned the stall it appears to describe.)
 *
 * <p>{@link Tail#REWOUNDED} is the missing test: a reading at or past the player's own duration that is
 * <em>lower</em> than the reading before it. Such a position is not advancing toward the file's length,
 * so waiting for it buys polls rather than content. The boundary in that case is the player's duration,
 * which is what the decision reports so the caller's log line names the end it actually acted on.
 *
 * <p>The cost is bounded by construction and now measured. The branch is reachable only where
 * {@code fileMs > reportedDurationMs} and the position sits between the two, so the most it can
 * withhold is the sub-second remainder the player floors away — the design series loses 806 ms. It is
 * reachable only where a completed lap could not have produced the reading either: the media time such a
 * lap needs — costed from the previous reading, across the file's end, and back to the current position —
 * has to exceed the interval between the two samples. Not merely exceed the player's duration, which is
 * only that cost's lower bound; a guard taken from the end alone suppresses real tails in the band between
 * the two, and did. {@code aWrapThatAlreadyRanPastTheEndIsNotASeamEither} pins both sides of the line the
 * exact cost draws, and {@code aShortClipTailIsStillRestartedAtTheRealPollInterval} pins the case the
 * lower bound got wrong. Across
 * 22 laps of a {@code player=37000 file=37270} clip on live hardware it fired on 4, cutting 150, 150,
 * 157 and 266 ms (723 ms total, every one inside that 270 ms band). The sibling {@link Tail#PARKED}
 * branch has always cut up to 855 ms on the same fleet, so this is not a new kind of loss.
 *
 * <p>The gain is the part still owed. On that clip, laps ending {@code REWOUNDED} averaged 40.75 s
 * against 40.56 s for laps ending {@code CREEPING} — no shortening, and of the wrong sign — because
 * both thresholds sit inside one 2 s poll, so the same poll qualifies either way and only the printed
 * word changes. The benefit exists only for tails like the 55855 above, where the position hovers
 * <em>below</em> the file's length for several polls and {@code CREEPING}'s gate can never be met;
 * replaying that recorded series through this rule restarts it two polls (~4 s) sooner, which is a
 * replay, not an observation — and what the corpus actually holds for that clip is one traced lap, not no
 * data. Three seam lines across two sessions: two dated, 21:43:06 and 21:44:09 on 2026-11-06
 * ({@code parked pos=55000}, then {@code creeping pos=56011}) in the durable log, and one in the logcat dump
 * the operator pasted on 2026-11-08. That dump also carries seven untimed {@code getCurrentPosition}
 * samples — {@code 49720, 51728, 53743, 55756, 55049, 55023, 56011} — and their consecutive media deltas,
 * 2008, 2015, 2013 ms, identify them as this watchdog's own 2 s polls running at 1.004x to 1.008x (an
 * external {@code /state} poll would step ~5.25 s of media, and nothing in this app samples faster). So one
 * lap is traced at full resolution: its body keeps real time, and its last three polls — about 6 s of
 * wall — advance the media 255 ms. That makes roughly 5.7 s of that lap's ~7 s deficit the crawl this branch
 * exists to end, which is the strongest support the benefit case has, and it says the replay was never the
 * weak part of the argument; {@code n=1} is. Zero laps of this clip have run on a build carrying this rule,
 * and getting one means that clip reaching the panel again on a frame that rotates a shuffled pool. Two things would settle it, and the {@code gap=} field is <em>not</em>
 * one of them, for the reason given two paragraphs above. The cheap one is a lap of that clip on a
 * build carrying this rule with the position sampled at every poll rather than only at the seam, which
 * measures the stall directly instead of inferring it from four samples. The one that answers the
 * question as actually asked — did the picture move — needs the panel itself: a viewer, or a frame
 * difference across the interval. Until then this branch is a bound on latency against a stalled media
 * clock, and should not be described as a fix for a visible freeze.
 *
 * <p>The domain claim above holds unconditionally only because the test requires
 * {@code sincePreviousMs > 0}: a <em>backward</em> clock step would zero that interval, silence
 * {@code CREEPING}, and let {@code REWOUNDED} fire at a position past the file's length, outside the band
 * that bounds its cost. It can also leave the interval <em>positive and shortened</em>, which is the case
 * {@code > 0} cannot see and which is equally fatal to the wrap test — so the caller measures the interval on
 * {@code SystemClock.elapsedRealtime()} (gate 4 of {@code docs/TRUST.md}: these frames are power-cycled and
 * run no time sync). Both defences stay, in that order of reliance: the monotonic clock is what makes the
 * interval trustworthy, and this rule's own {@code > 0} test is what keeps a rule honest even when a caller
 * forgets. A contract a function cannot check is a comment, not a guarantee — {@code
 * aBackwardsClockStepDoesNotManufactureARewind} is why the type still holds it.
 *
 * <p><b>Do not "fix" {@link Tail#CREEPING} by requiring a non-negative delta.</b> The unqualified
 * {@code <} is load-bearing in the opposite direction from the one it looks like, and
 * {@link Tail#REWOUNDED} does not retire it. The caller's 8 s stall rung is reachable only on an
 * exactly-equal reading, so a tail that steps backwards is the sole thing that ends the lap once
 * {@code REWOUNDED} cannot speak: a player that reports no duration at all
 * ({@code reportedDurationMs <= 0}, which {@link Tail#REWOUNDED} requires) still gets restarted by
 * creeping against the file's length — for any clip whose player reports more than one poll interval, and
 * that is the scope of the guarantee: at or below it the wrap test can go silent for want of evidence, as
 * its own paragraph says, and this sentence does not extend to those readings. A clamped creeping would leave that clip reading backwards
 * forever with no seam and no stall recovery: the lap would never end and its media clock would never
 * advance again, whatever the panel happens to be showing at the time.
 * {@code backwardsTailWithoutAPlayerDurationStillRestarts} pins it.
 *
 * <p>What {@link Tail#REWOUNDED} did change is why the backwards step matters. Before it, the
 * negative delta was the only thing guaranteeing a restart at all; now it is the guarantee for the
 * no-player-duration case, and the reason both tests must stay.
 */
public final class VideoLoopSeamRule {

    private VideoLoopSeamRule() {
    }

    /**
     * Media progress below this (x100) counts as making no headway. Past the end of a clip the
     * position inches rather than stopping — measured at 0.00x to 0.02x — so anything above this is
     * still genuinely playing and must not be cut short.
     */
    public static final int TAIL_RATE_X100 = 50;

    /**
     * How far past the end a position may sit and still be believed. Measured restarts land 51 to
     * 707 ms past the file's own length across 22 laps of one clip (5 to 375 ms in the earlier sample);
     * anything orders of magnitude beyond that is a bogus read
     * from a player that has not settled, not a clip that has finished. A freshly created player has
     * been observed reporting values near the epoch, and acting on one every poll would turn the loop
     * into a restart storm — so this bound is applied to every tail test, including
     * {@link Tail#REWOUNDED}, <em>and to both readings of it</em>: a previous position the caller
     * recorded but this rule never believed is not evidence that the current one moved backwards.
     */
    public static final int END_SLACK_MS = 30000;

    /**
     * The width of the band in which "could a lap have completed?" has no answer, because every quantity the
     * question is asked with is a truncated integer millisecond.
     *
     * <p>{@code getCurrentPosition()} returns whole milliseconds and floors, {@code elapsedRealtime()} does
     * the same to the interval, and the cost of a wrap is built from all of them — so a clip that wrapped
     * 2000.7 ms ago, whose true positions were 2199.4 and 2000.1, reads as a 2000 ms interval against a cost
     * of 2001 ms, and the verdict is decided by which way the fractions fell. Two milliseconds covers one
     * quantum on each side of the comparison, which is the most the arithmetic can be wrong by.
     *
     * <p>Forgiving it costs a genuine tail 2 ms of latency — the next poll, 2 s away, decides it regardless
     * — and buys the difference between a rule and a coin toss on the class of clip where the cost is near
     * one poll to begin with. On a 37 s lap the two are not in the same universe of magnitude, which is why
     * this never moves a measured case.
     */
    public static final int QUANTIZATION_MS = 2;

    /**
     * Which tail, if any, this poll's position reading constitutes. The word is also what the
     * caller's durable log line prints, so an operator can tell which end a restart was taken
     * against from the line alone.
     */
    public enum Tail {
        /** Media is still advancing normally: leave the lap alone. */
        NONE,
        /** Identical to the previous reading — finished as far as the player is concerned. */
        PARKED,
        /** Advancing below {@link #TAIL_RATE_X100} of wall speed, forwards or not — and at a reading no
         *  completed lap could have produced. */
        CREEPING,
        /** At or past the player's own duration, below the previous reading, and at a reading no completed
         *  lap could have produced. */
        REWOUNDED
    }

    /**
     * A seam verdict: whether to restart, and which end that decision was taken against.
     */
    public static final class Decision {

        public static final Decision NO_RESTART = new Decision(Tail.NONE, 0L);

        public final Tail tail;

        /**
         * The end this decision was taken against, in milliseconds — and the number the caller prints
         * as {@code end=} in the durable seam line, so an operator reading a frame's history infers the
         * clip's shape from this. It is <em>not</em> always the file's length:
         *
         * <ul>
         *   <li>{@link Tail#PARKED} and {@link Tail#REWOUNDED}: the player's duration. A reading that
         *       has stopped moving, or stepped backwards, is at the end as far as the player is
         *       concerned, and demanding the probe's number there would skip the branch. Only the
         *       PARKED half of that has a backstop behind it — the 8 s stall threshold is reached solely
         *       on an exactly-equal reading, so skipping a REWOUNDED leaves the lap to nothing.</li>
         *   <li>{@link Tail#CREEPING}: the greater of the two. That is the file's length only when the
         *       probe read a <em>longer</em> one — the {@code 55855} file against a {@code 55000}
         *       player. Where the player's number is the longer one, as measured on the EO2's square
         *       clip ({@code 15000} reported for a {@code 14114} file), a creeping tail restarts
         *       against the player's duration and this field says so.</li>
         * </ul>
         *
         * Zero when nothing fired.
         */
        public final long boundaryMs;

        private Decision(Tail tail, long boundaryMs) {
            this.tail = tail;
            this.boundaryMs = boundaryMs;
        }

        public boolean restarts() {
            return tail != Tail.NONE;
        }

        /** Lower-case word for the log line, matching the wording it has always printed. */
        public String reasonWord() {
            switch (tail) {
                case PARKED:
                    return "parked";
                case CREEPING:
                    return "creeping";
                case REWOUNDED:
                    return "rewound";
                default:
                    return "none";
            }
        }
    }

    /**
     * Decide whether this poll is the end of the lap.
     *
     * <p>Every argument is a value the caller has already read for other reasons, so asking the rule
     * adds no I/O to the poll. Note that {@code previousPositionMs} is the position from the previous
     * poll and must be read before the caller's bookkeeping overwrites it; a value of -1 means "no
     * previous reading" (just restarted, or just armed) and rules out both tail tests that need one.
     *
     * @param positionMs           position read this poll
     * @param previousPositionMs   position read last poll, or a negative value when there was none
     * @param sincePreviousMs      milliseconds between the two position readings, measured on a
     *                             <em>monotonic</em> clock ({@code SystemClock.elapsedRealtime()}). Two
     *                             properties are required of it, and each has already cost a round: it has to
     *                             be an <em>upper</em> bound on how much media could have played between the
     *                             readings — taken from the <em>start</em> of the previous position query to
     *                             the <em>end</em> of this one, because a value read back from a player can be
     *                             as old as the call that produced it — and it must not be able to shrink on
     *                             its own, because these frames are power-cycled and run no time sync
     *                             ({@code docs/TRUST.md} gate 4), so a wall-clock correction can step it back
     *                             between two samples and leave it positive. Every error in the shortening
     *                             direction reads to the wrap test below as proof that no lap could have
     *                             completed, and the rule then discards a lap that had already begun again.
     *                             The margin only matters where a lap is near one poll, which is the same
     *                             class the wrap cost is decisive for; on a 37 s clip against a 2 s poll
     *                             neither effect reaches the boundary.
     * @param reportedDurationMs   what the player says the clip is, or {@code <= 0} when unknown
     * @param fileDurationMs       what the file says, from the {@code mdhd}/{@code mvhd} probe, or
     *                             {@code <= 0} when the probe could not read one
     * @return whether to restart, and the boundary it was decided against
     */
    public static Decision evaluate(int positionMs, int previousPositionMs, long sincePreviousMs,
                                    int reportedDurationMs, long fileDurationMs) {
        boolean parked = positionMs == previousPositionMs;

        // Media advanced less than half as fast as the clock across one poll interval. Measured per
        // poll rather than from the rate window, which needs 5 s to fill and so left the seam open
        // for seconds even after the crawl had begun. The delta is deliberately not clamped to zero:
        // see the class note on why a backwards step must count.
        // The cast is before the subtraction, not after: this rule deliberately reasons about readings
        // it does not believe (an epoch-scale position is in its own test data), and a difference
        // computed in int and then promoted would have to stay inside the range where no overflow can
        // occur. Widening first makes that a property of the expression rather than an argument about
        // what values can reach here.
        boolean creeping = !parked && previousPositionMs >= 0 && sincePreviousMs > 0
                && ((long) positionMs - previousPositionMs) * 100L < sincePreviousMs * TAIL_RATE_X100;

        // Which end to demand depends on which test fired: a parked position is at the end as far as
        // the player is concerned, and insisting on the file's length there would skip this branch
        // and fall back to the 8 s threshold this whole rule exists to avoid.
        long cycleEndMs = parked ? reportedDurationMs : Math.max((long) reportedDurationMs, fileDurationMs);

        // Where a native wrap could happen: the later of the two identities, because the player's number is
        // a floor of the media and a decoder does not wrap at a duration it merely rounded down to.
        long fileEndMs = Math.max((long) reportedDurationMs, fileDurationMs);

        // The media time a completed lap would need in order to produce THIS reading from the previous one:
        // run from the previous position to the end, wrap, then run to where the position is now. A previous
        // reading already at or past the end wraps immediately, so that leg is clamped, not subtracted.
        // This is the quantity the ambiguity is decided by, and the end alone is only its lower bound -- on
        // the 2200 ms file whose player says 2000, read at 2100 and then at 2000, a wrap costs
        // (2200 - 2100) + 2000 = 2100 ms, so a 2000 ms poll does NOT explain the reading and a bound taken
        // from the end alone would suppress a real tail. The first version of this guard did exactly that.
        long wrapMediaMs = (previousPositionMs >= fileEndMs ? 0L : fileEndMs - (long) previousPositionMs)
                + (long) positionMs;
        // `sincePreviousMs` is a long and QUANTIZATION_MS an int, so this addition is done in long
        // arithmetic; the interval can be enormous (an arming that never stamped, a three-minute pause), and
        // widening first keeps that from overflowing into a comparison that silently says the opposite.
        boolean aWrapCouldExplainIt = previousPositionMs >= 0
                && sincePreviousMs + QUANTIZATION_MS >= wrapMediaMs;

        if (cycleEndMs > 0L
                && positionMs >= cycleEndMs
                && positionMs - cycleEndMs <= END_SLACK_MS
                // PARKED is exempt from the wrap test, and the exemption does fire on healthy playback —
                // not as a rare coincidence but whenever the media advance between two polls is a whole
                // multiple of the lap: the phase then never moves and every reading is bit-equal. At the
                // caller's ~2 s poll that means laps of roughly 1 s to 2.6 s (905 such lap/interval pairs
                // in that band; enumerate the moving branches and they score zero false restarts there
                // while PARKED scores all of them), plus any lap at any interval that happens to be an
                // exact multiple. What it costs each time is at most fileDurationMs - reportedDurationMs,
                // under a second, and only where the file is longer than the player's number.
                //
                // The exemption is kept anyway, because the reading it cannot distinguish is also the only
                // signal that a decoder has hung at end of file across a long gap — which is what this rule
                // exists to end, and the 8 s stall rung behind it is reachable on nothing else. Testing the
                // exemption instead of taking it would silence the hang detector for the one class where
                // the two cases are genuinely indistinguishable, so the trade is stated here rather than
                // assumed away.
                && (parked || (creeping && !aWrapCouldExplainIt))) {
            return new Decision(parked ? Tail.PARKED : Tail.CREEPING, cycleEndMs);
        }

        // The tail that never gets to the file's length because it went backwards instead. It still
        // has to be past the player's own duration, so a wrap that lands back at the start of the lap
        // cannot be read as one, and inside the same slack bound as the other tests.
        //
        // A wrap is ruled out twice over, and the second is the one that matters on a short clip. Asking for
        // a position at or past the player's duration excludes a reading near zero; but a clip whose lap is
        // short next to the poll interval can wrap, run past the player's number again, and be read at any
        // position this test accepts -- a 2200 ms file whose player says 2000, polled 2100 ms after a
        // reading of 2100, legitimately reads 2000, and restarting there cuts a lap that had already begun
        // again. Hence <code>aWrapCouldExplainIt</code>, computed above from the previous reading and the
        // file's end rather than from the duration this branch compares positions against — and forgiven
        // {@link #QUANTIZATION_MS} on top, because every term in that cost is a truncated integer
        // millisecond, so the boundary it draws is finer than the instruments that reach it.
        //
        // What that leaves unsaid is a limit of the instrument rather than a judgement, and it should not be
        // softened -- nor keyed to the wrong quantity. The guard goes quiet for a READING, not for a clip:
        // once the previous sample sits at or past the file's end the cost collapses to the position, so a
        // past-end tail is silenced only while its position stays within {@code sincePreviousMs +
        // QUANTIZATION_MS}; as soon as it drifts past that, the rule restarts it again, however short the
        // lap is. What is true of a clip whose lap is at or below one poll is that such readings exist and
        // recur every poll. The caller re-posts at VIDEO_WATCHDOG_POLL_MS = 2000 ms and can only overshoot,
        // and this branch prices its positions against the PLAYER's floored duration, so under the ROM's
        // floor law (reported = floor(file/1000)*1000) the silently-suppressed class runs to a file of
        // 2999 ms at the nominal poll -- not "about 2 s", and a player that floored harder would widen it.
        // For those readings what is left is PARKED (exempt, and it does fire on a harmonic loop -- see its
        // own note) and, for the mirror class where the player's number is the longer of the two, the
        // platform player's onCompletion loop. Nothing else judges a moving-position tail there: the 8 s
        // stall rung fires only on an exactly-equal reading, and any creep at all re-arms its clock.
        //
        // On the class this rule was written for the test is never close: laps of 14 to 58 s against a 2 s
        // poll, where the media time a wrap would cost is more than an order of magnitude beyond one
        // interval. Every recorded tail on live hardware restarts under this bound exactly as it did
        // without it, which is what the suite pins.
        //
        // The bound is applied to the PREVIOUS reading as well, and that is not symmetry for its own
        // sake: the caller stores whatever the position getter returned, whether or not this rule
        // believed it. A single epoch-scale reading would otherwise make the next legitimate one look
        // like a step backwards and cut a tail that is still playing -- 23049 following MAX_VALUE is a
        // 2.1-billion-millisecond "rewind" that never happened. CREEPING needs no such guard because
        // it cannot fire without the current reading already being past an end.
        if (reportedDurationMs > 0
                && previousPositionMs >= 0
                && previousPositionMs - (long) reportedDurationMs <= END_SLACK_MS
                && positionMs >= reportedDurationMs
                && positionMs - (long) reportedDurationMs <= END_SLACK_MS
                && positionMs < previousPositionMs
                // Without a real elapsed interval, "lower than before" is a statement about the clock,
                // not the media: a backward step kills CREEPING and would leave this test as the only
                // thing firing, at positions the cost bound above does not cover.
                && sincePreviousMs > 0
                // And not a reading a completed lap could have produced -- the other half of the two-sample
                // problem above, costed from the previous reading rather than from this branch's end.
                && !aWrapCouldExplainIt) {
            return new Decision(Tail.REWOUNDED, reportedDurationMs);
        }

        return Decision.NO_RESTART;
    }
}
