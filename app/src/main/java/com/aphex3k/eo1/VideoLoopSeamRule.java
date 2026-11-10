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
 * reachable only where the interval since the previous reading is shorter than that duration as well,
 * which is what keeps a clip whose lap fits inside one poll from being restarted every poll: a position
 * that a completed lap would explain is left alone, and {@code aWrapThatAlreadyRanPastTheEndIsNotASeamEither}
 * pins the two sides of that line. Across
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
 * replay, not an observation. Two things would settle it, and the {@code gap=} field is <em>not</em>
 * one of them, for the reason given two paragraphs above. The cheap one is a lap of that clip on a
 * build carrying this rule with the position sampled at every poll rather than only at the seam, which
 * measures the stall directly instead of inferring it from four samples. The one that answers the
 * question as actually asked — did the picture move — needs the panel itself: a viewer, or a frame
 * difference across the interval. Until then this branch is a bound on latency against a stalled media
 * clock, and should not be described as a fix for a visible freeze.
 *
 * <p>The domain claim above holds unconditionally only because the test requires
 * {@code sincePreviousMs > 0}: a <em>backward</em> clock step (these frames run no time sync —
 * {@code docs/TRUST.md} gate 4) would zero that interval, silence {@code CREEPING}, and let
 * {@code REWOUNDED} fire at a position past the file's length, outside the band that bounds its cost.
 *
 * <p><b>Do not "fix" {@link Tail#CREEPING} by requiring a non-negative delta.</b> The unqualified
 * {@code <} is load-bearing in the opposite direction from the one it looks like, and
 * {@link Tail#REWOUNDED} does not retire it. The caller's 8 s stall rung is reachable only on an
 * exactly-equal reading, so a tail that steps backwards is the sole thing that ends the lap once
 * {@code REWOUNDED} cannot speak: a player that reports no duration at all
 * ({@code reportedDurationMs <= 0}, which {@link Tail#REWOUNDED} requires) still gets restarted by
 * creeping against the file's length, and a clamped creeping would leave that clip reading backwards
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
     * Which tail, if any, this poll's position reading constitutes. The word is also what the
     * caller's durable log line prints, so an operator can tell which end a restart was taken
     * against from the line alone.
     */
    public enum Tail {
        /** Media is still advancing normally: leave the lap alone. */
        NONE,
        /** Identical to the previous reading — finished as far as the player is concerned. */
        PARKED,
        /** Advancing below {@link #TAIL_RATE_X100} of wall speed, forwards or not — and not an interval
         *  in which the clip could have completed a lap and come back round. */
        CREEPING,
        /** At or past the player's own duration and below the previous reading, within an interval
         *  shorter than that duration. */
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
         *       concerned, and demanding the probe's number there would skip the branch and leave the
         *       lap to the 8 s threshold.</li>
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
     * @param sincePreviousMs      wall-clock milliseconds since the previous poll
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
        if (cycleEndMs > 0L
                && positionMs >= cycleEndMs
                && positionMs - cycleEndMs <= END_SLACK_MS
                // A creeping reading is only evidence while the media could not have completed a lap and
                // come back round inside the interval -- see the comparison in the REWOUNDED branch, which
                // makes the same argument against its own end. PARKED is exempt: an identical reading needs
                // a wrap to land on the same millisecond, and a clip hung at the end of the file is the
                // failure this whole rule exists to end, so its evidence does not expire with the clock.
                && (parked || (creeping && sincePreviousMs < cycleEndMs))) {
            return new Decision(parked ? Tail.PARKED : Tail.CREEPING, cycleEndMs);
        }

        // The tail that never gets to the file's length because it went backwards instead. It still
        // has to be past the player's own duration, so a wrap that lands back at the start of the lap
        // cannot be read as one, and inside the same slack bound as the other tests.
        //
        // A wrap is ruled out twice over, and the second is the one that matters on a short clip. Asking
        // for a position at or past the player's duration excludes a reading near zero; but a clip whose
        // lap is short next to the poll interval can wrap, run past the player's number again, and be
        // read at any position the test accepts -- a 2200 ms file whose player says 2000, polled 2100 ms
        // after a reading of 2100, legitimately reads 2000, and restarting there cuts a lap that had
        // already begun again. Hence the elapsed bound: to read a position at or past an end B, having
        // started anywhere in the lap, needs at least B ms of media, so an interval of B ms or more makes
        // the reading consistent with a healthy loop and two samples say nothing. Each branch compares
        // against the end it would restart against, which is the B in its own test. On this ROM's floor
        // class that is never a close call -- laps of 14 to 58 s against a 2 s poll -- so the bound costs
        // a genuine tail at most the one late poll it disqualifies. Where polls stay short next to the lap
        // the next one judges it normally; where they do not, two samples carry no evidence either way and
        // the clip is left to loop by itself, which is the conservative direction for a rule whose only
        // action is to cut a lap short.
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
                // And not an elapsed interval the media could have looped inside -- the other half of the
                // two-sample problem above, stated against this branch's own end.
                && sincePreviousMs < (long) reportedDurationMs) {
            return new Decision(Tail.REWOUNDED, reportedDurationMs);
        }

        return Decision.NO_RESTART;
    }
}
