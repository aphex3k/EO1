package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When a lap of a looping video is over, pinned directly — including against the two position series
 * this was measured from, and never on paper.
 *
 * <p>This file exists for the reason {@link VideoRoutingTest} does, with a sharper edge: the rule had
 * never been tested <em>and</em> its author said so. Commit {@code 4386f8b} introduced the boundary
 * this asserts, and closed its own message with "the part not yet measured on hardware: the EO2 is
 * inside quiet hours". Three weeks later a live EO2 dump showed what the unmeasured half does — a
 * 55855 ms clip whose player reports 55000 held its last decoded frame for about 5.9 s every lap,
 * reported on the seam line as {@code rate=1.00x}, because the media clock really was keeping wall
 * time while nothing new reached the surface. Every number below is read off that dump or off the
 * series {@code 4386f8b} recorded in its own message.
 *
 * <p>The pair of tests that matters most is the pair that pulls in opposite directions: a tail that
 * steps backwards is finished and must restart ({@link #heldTailAtThePlayerEndRestartsTwoPollsEarlier}),
 * and a tail that creeps <em>forwards</em> toward the file's true length is still showing frames and
 * must not ({@link #protectedCrawlIsNotCutShort}). Only the direction of the step separates them.
 */
public class VideoLoopSeamRuleTest {

    /** The real poll interval ({@code MainActivity.VIDEO_WATCHDOG_POLL_MS}); the tests are per-poll. */
    private static final long POLL_MS = 2000L;

    /** The clip from the EO2 dump: file 55855 ms, player reports 55000 ms. */
    private static final int EO2_PLAYER_MS = 55000;
    private static final long EO2_FILE_MS = 55855L;

    /** The clip {@code 4386f8b} was written for: file 23621 ms, player reports 23000 ms. */
    private static final int CRAWL_PLAYER_MS = 23000;
    private static final long CRAWL_FILE_MS = 23621L;

    @Test
    public void heldTailAtThePlayerEndRestartsTwoPollsEarlier() {
        // The last four position readings of the dumped lap, verbatim: 55756, 55049, 55023, 56011.
        // The clip's real content ends at 55855, and the wall holds its last frame the whole time.
        assertFalse("still advancing toward the end",
                VideoLoopSeamRule.evaluate(53743, 51728, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS).restarts());
        assertFalse("2013 ms of media in 2000 ms of clock is playing, not finished",
                VideoLoopSeamRule.evaluate(55756, 53743, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS).restarts());

        // This is the poll that used to do nothing: 55049 sits below the demanded 55855, so the file's
        // length was asked of a position that had stopped moving forward. It is now the seam.
        VideoLoopSeamRule.Decision held =
                VideoLoopSeamRule.evaluate(55049, 55756, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS);
        assertTrue("a position that stepped backwards past the player's end is the end of the lap",
                held.restarts());
        assertEquals(VideoLoopSeamRule.Tail.REWOUNDED, held.tail);
        assertEquals("the log line must name the end it acted on, not the unreachable one",
                EO2_PLAYER_MS, held.boundaryMs);

        assertEquals(VideoLoopSeamRule.Tail.REWOUNDED,
                VideoLoopSeamRule.evaluate(55023, 55049, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS).tail);

        // Where the old rule fired: two polls and roughly four seconds later, by drift alone. Kept as
        // an assertion so the surviving branch is visibly the late one rather than the only one.
        VideoLoopSeamRule.Decision drift =
                VideoLoopSeamRule.evaluate(56011, 55023, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS);
        assertEquals(VideoLoopSeamRule.Tail.CREEPING, drift.tail);
        assertEquals(EO2_FILE_MS, drift.boundaryMs);
    }

    @Test
    public void protectedCrawlIsNotCutShort() {
        // 4386f8b's own series: past the shortened duration the position inches FORWARD
        // (23011, 23169, 23374, 23879) at roughly 0.02x, and those are frames still being shown. A
        // test that fired here would truncate the last 0.6 s — the exact fault that revision exists
        // to prevent, and the reason REWOUNDED demands a backwards step rather than a slow one.
        assertFalse(VideoLoopSeamRule.evaluate(23011, 22900, POLL_MS, CRAWL_PLAYER_MS, CRAWL_FILE_MS).restarts());
        assertFalse(VideoLoopSeamRule.evaluate(23169, 23011, POLL_MS, CRAWL_PLAYER_MS, CRAWL_FILE_MS).restarts());
        assertFalse(VideoLoopSeamRule.evaluate(23374, 23169, POLL_MS, CRAWL_PLAYER_MS, CRAWL_FILE_MS).restarts());

        // ... and the crawl does reach the file's true length, where the original branch still fires.
        VideoLoopSeamRule.Decision done =
                VideoLoopSeamRule.evaluate(23879, 23374, POLL_MS, CRAWL_PLAYER_MS, CRAWL_FILE_MS);
        assertTrue(done.restarts());
        assertEquals(VideoLoopSeamRule.Tail.CREEPING, done.tail);
        assertEquals(CRAWL_FILE_MS, done.boundaryMs);
    }

    @Test
    public void backwardsTailWithoutAPlayerDurationStillRestarts() {
        // Why CREEPING's delta is deliberately not clamped to zero. A player that answers 0 for its
        // duration disqualifies REWOUNDED, which needs one, so a stepping-backwards tail above the
        // file's length would have no test left: the 8 s stall rung behind the seam only ever sees an
        // exactly-equal reading. Requiring headway to be non-negative therefore trades a false
        // positive for an indefinite freeze on the last frame.
        VideoLoopSeamRule.Decision tail =
                VideoLoopSeamRule.evaluate(23850, 23900, POLL_MS, 0, CRAWL_FILE_MS);
        assertTrue(tail.restarts());
        assertEquals(VideoLoopSeamRule.Tail.CREEPING, tail.tail);
        assertEquals(CRAWL_FILE_MS, tail.boundaryMs);
    }

    @Test
    public void parkedAtTheShortenedNumberIsStillTheEnd() {
        // Some files stop dead at the player's rounded-down number without ever passing it, so a
        // parked reading is judged against that number and not against the file's length — demanding
        // the latter here would skip the branch and hand the lap to the 8 s threshold.
        VideoLoopSeamRule.Decision parked =
                VideoLoopSeamRule.evaluate(23000, 23000, POLL_MS, CRAWL_PLAYER_MS, CRAWL_FILE_MS);
        assertEquals(VideoLoopSeamRule.Tail.PARKED, parked.tail);
        assertEquals(CRAWL_PLAYER_MS, parked.boundaryMs);
    }

    @Test
    public void ordinaryPlaybackNeverRestarts() {
        // The body of the dumped lap, positions 1..27. Every delta is ~2000 ms of media per 2000 ms
        // of clock, and none of it is near either end. If this restarts, every clip on a frame is cut.
        int[] series = {941, 3518, 5447, 7531, 9571, 11580, 13578, 15588, 17594, 19593, 21599, 23613,
                25585, 27596, 29592, 31605, 33616, 35679, 37652, 39705, 41665, 43734, 45706, 47721,
                49720, 51728, 53743};
        int previous = -1;
        for (int position : series) {
            assertFalse("no restart mid-lap at pos=" + position,
                    VideoLoopSeamRule.evaluate(position, previous, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS)
                            .restarts());
            previous = position;
        }
    }

    @Test
    public void aNativeWrapIsNotASeam() {
        // setLooping(true) does wrap inside the player for some files: position returns to ~0 with a
        // huge negative delta, which CREEPING alone would read as a tail. It is the position-vs-end
        // test that separates a wrap from a hold, so REWOUNDED must not fire on it either — a second
        // seekTo(0) on a lap the player already rewound would be a visible double play.
        assertFalse(VideoLoopSeamRule.evaluate(300, 54500, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS).restarts());
    }

    @Test
    public void anUnsettledReadingIsNeverBelieved() {
        // Two bogus shapes a freshly created player has actually produced. The slack bound carries the
        // first; the second is caught by demanding an end at all, since a small position is not near
        // any end however slowly it moves.
        assertFalse("epoch-scale reading",
                VideoLoopSeamRule.evaluate(Integer.MAX_VALUE, 300, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS)
                        .restarts());
        assertFalse("near-zero reading",
                VideoLoopSeamRule.evaluate(120, 0, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS).restarts());
        assertFalse("a rewind to a bogus past must not look like a held tail",
                VideoLoopSeamRule.evaluate(5000, EO2_PLAYER_MS, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS)
                        .restarts());
    }

    @Test
    public void aCreepingTailIsJudgedAgainstTheLongerOfTheTwoEnds() {
        // The boundary the seam line prints as end= is the greater of the two identities, not the
        // file's length. Measured on the EO2's 640x640 clip, where the player says 15000 for a 14114
        // ms file: here the player's number IS the end, and a rule that asked for the file's length
        // would be waiting for a position the media never reaches.
        VideoLoopSeamRule.Decision creep =
                VideoLoopSeamRule.evaluate(15049, 15000, POLL_MS, 15000, 14114L);
        assertTrue(creep.restarts());
        assertEquals(VideoLoopSeamRule.Tail.CREEPING, creep.tail);
        assertEquals("the player's own duration is the end when it is the longer one",
                15000L, creep.boundaryMs);

        // And the other direction, from the dump's clip: the file is longer, so the file is the end.
        assertEquals(EO2_FILE_MS,
                VideoLoopSeamRule.evaluate(56011, 55023, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS).boundaryMs);
    }

    @Test
    public void anImplausiblePreviousReadingIsNotEvidenceOfABackwardsStep() {
        // The caller stores whatever the position getter returned, whether or not this rule believed it,
        // so one epoch-scale reading stays in the history. Un-guarded, that makes the next legitimate
        // reading look like a 2.1-billion-millisecond rewind and cuts a still-playing tail short --
        // 572 ms early here, and REWOUNDED is the branch that introduced the exposure.
        assertFalse("MAX_VALUE then a still-playing tail must not restart",
                VideoLoopSeamRule.evaluate(23049, Integer.MAX_VALUE, POLL_MS, CRAWL_PLAYER_MS, CRAWL_FILE_MS)
                        .restarts());

        // The same reading after an ordinary previous IS the seam, so the guard costs nothing real.
        assertEquals(VideoLoopSeamRule.Tail.REWOUNDED,
                VideoLoopSeamRule.evaluate(23049, 23110, POLL_MS, CRAWL_PLAYER_MS, CRAWL_FILE_MS).tail);

        // And the guard is the same slack bound the current reading faces, boundary included: a
        // previous 30 s past the end is still believed, one ms further is not.
        int believable = EO2_PLAYER_MS + VideoLoopSeamRule.END_SLACK_MS;
        assertEquals(VideoLoopSeamRule.Tail.REWOUNDED,
                VideoLoopSeamRule.evaluate(55049, believable, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS).tail);
        assertFalse(VideoLoopSeamRule
                .evaluate(55049, believable + 1, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS).restarts());
    }

    @Test
    public void thePollAfterARestartIsQuiet() {
        // The caller writes -1 after a restart precisely so the next poll cannot re-fire off the
        // reading it is about to make. Neither tail test may accept a missing previous reading.
        assertFalse(VideoLoopSeamRule.evaluate(900, -1, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS).restarts());
        assertFalse(VideoLoopSeamRule.evaluate(55049, -1, POLL_MS, EO2_PLAYER_MS, EO2_FILE_MS).restarts());
    }

    @Test
    public void reasonWordsMatchTheDurableLogLine() {
        // Operators grep these words out of files/eo1-app.log. A rename is a silent break: the seam
        // line is the only record of a wrap that logcat keeps, and a new tail with no word would
        // print "none" beside a restart.
        assertEquals("parked", VideoLoopSeamRule.evaluate(23000, 23000, POLL_MS,
                CRAWL_PLAYER_MS, CRAWL_FILE_MS).reasonWord());
        assertEquals("creeping", VideoLoopSeamRule.evaluate(23879, 23374, POLL_MS,
                CRAWL_PLAYER_MS, CRAWL_FILE_MS).reasonWord());
        assertEquals("rewound", VideoLoopSeamRule.evaluate(55049, 55756, POLL_MS,
                EO2_PLAYER_MS, EO2_FILE_MS).reasonWord());
        assertEquals("none", VideoLoopSeamRule.evaluate(941, 0, POLL_MS,
                EO2_PLAYER_MS, EO2_FILE_MS).reasonWord());
    }

    @Test
    public void noEndKnownMeansNoSeam() {
        // Neither the player nor the probe could name an end. Without one there is nothing to be
        // behind, and guessing would cut every lap short at the first slow poll.
        assertFalse(VideoLoopSeamRule.evaluate(50000, 49000, POLL_MS, 0, -1L).restarts());
        assertFalse(VideoLoopSeamRule.evaluate(50000, 51000, POLL_MS, 0, -1L).restarts());
    }
}
