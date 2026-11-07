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
 * <p>Two things hide that boundary. The player's duration is shorter than the media it is holding
 * (23000 ms reported for a 23621 ms file, because the QuickTime {@code mvhd} timescale of 600 cannot
 * divide the frame period evenly), so ending on the player's number truncates frames still running.
 * And past that point the position does not freeze, it creeps
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
 * fourth reading happened to drift past it: about 5.9 s during which the wall held its last decoded
 * frame, then a hard cut to frame 0. Roughly two thirds of that hold is this rule asking the file's
 * length of a position that had stopped advancing forward at all.
 *
 * <p>{@link Tail#REWOUNDED} is the missing test: a reading at or past the player's own duration that is
 * lower than the reading before it has no forward content left to show, and waiting for the file's
 * rounding error of tail on the wall is not a trade worth making. The boundary in that case is the
 * player's duration, which is what the decision reports so the caller's log line names the end it
 * actually acted on.
 *
 * <p>The cost is honest and bounded: a clip whose tail genuinely oscillates backwards while still
 * between the player's duration and the file's length loses that remainder — under a second on the one
 * series this is known from, since the gap is by construction the container's rounding error. What
 * would falsify it is a clip whose {@code mdhd} length is <em>shorter</em> than what the player
 * reports, the mirror of the case above; {@code positionMs >= reportedDurationMs} is what keeps that
 * hypothetical from truncating, and nothing on hardware has shown one.
 *
 * <p><b>Do not "fix" {@link Tail#CREEPING} by requiring a non-negative delta.</b> The unqualified
 * {@code <} is load-bearing in the opposite direction from the one it looks like, and
 * {@link Tail#REWOUNDED} does not retire it. The caller's 8 s stall rung is reachable only on an
 * exactly-equal reading, so a tail that steps backwards is the sole thing that ends the lap once
 * {@code REWOUNDED} cannot speak: a player that reports no duration at all
 * ({@code reportedDurationMs <= 0}, which {@link Tail#REWOUNDED} requires) still gets restarted by
 * creeping against the file's length, and a clamped creeping would leave that clip reading backwards
 * forever with no seam and no stall recovery — an indefinite freeze on the last frame.
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
     * How far past the end a position may sit and still be believed. Measured restarts land 5 to
     * 375 ms past the file's own length; anything orders of magnitude beyond that is a bogus read
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
        /** Advancing below {@link #TAIL_RATE_X100} of wall speed, forwards or not. */
        CREEPING,
        /** At or past the player's own duration and below the previous reading. */
        REWOUNDED
    }

    /**
     * A seam verdict: whether to restart, and which end that decision was taken against.
     */
    public static final class Decision {

        public static final Decision NO_RESTART = new Decision(Tail.NONE, 0L);

        public final Tail tail;

        /**
         * The boundary this decision was taken against, in milliseconds: the file's length for
         * {@link Tail#PARKED} and {@link Tail#CREEPING}, the player's duration for
         * {@link Tail#REWOUNDED}. Zero when nothing fired.
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
        boolean creeping = !parked && previousPositionMs >= 0 && sincePreviousMs > 0
                && (positionMs - previousPositionMs) * 100L < sincePreviousMs * TAIL_RATE_X100;

        // Which end to demand depends on which test fired: a parked position is at the end as far as
        // the player is concerned, and insisting on the file's length there would skip this branch
        // and fall back to the 8 s threshold this whole rule exists to avoid.
        long cycleEndMs = parked ? reportedDurationMs : Math.max((long) reportedDurationMs, fileDurationMs);
        if (cycleEndMs > 0L
                && positionMs >= cycleEndMs
                && positionMs - cycleEndMs <= END_SLACK_MS
                && (parked || creeping)) {
            return new Decision(parked ? Tail.PARKED : Tail.CREEPING, cycleEndMs);
        }

        // The tail that never gets to the file's length because it went backwards instead. It still
        // has to be past the player's own duration, so a wrap (a natively-looping clip whose position
        // returns to 0) cannot be read as one, and inside the same slack bound as the other tests.
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
                && positionMs < previousPositionMs) {
            return new Decision(Tail.REWOUNDED, reportedDurationMs);
        }

        return Decision.NO_RESTART;
    }
}
