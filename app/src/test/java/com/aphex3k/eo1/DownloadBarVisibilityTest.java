package com.aphex3k.eo1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aphex3k.eo1.MainActivity.DownloadBarAction;
import com.aphex3k.eo1.MainActivity.DownloadBarSignal;

/**
 * Every cell of the download bar's rule, plus the three interleavings that produced it.
 *
 * <p>The bar is one view shared by whichever transfer happens to be streaming, so each of these
 * verdicts exists because of a concrete ordering someone found:
 * <ul>
 *   <li>a prefetch's bytes reaching the UI thread after the counted download that let them through
 *       the gate failed — which re-showed a bar nothing would ever hide again;</li>
 *   <li>a close arriving after another thread started a new download — which blanked a transfer that
 *       was genuinely running;</li>
 *   <li>and the guard added for the second case hiding behind itself, so a chunked download left the
 *       previous file's fill frozen on screen, which is the stalled bar all over again.</li>
 * </ul>
 *
 * <p>What a JVM test cannot reach is that the callers consult this table at all: all three call sites
 * run on the UI thread through {@code runOnUiThread}, so that half is held by structure rather than by
 * a case here — the same caveat the TsPlayer gate documents.
 */
public class DownloadBarVisibilityTest {

    // --- a progress event, which is the only one that can legitimately fill the bar --------------

    @Test
    public void bytesFromACountedDownloadWithAKnownLengthShowTheirFraction() {
        assertEquals(DownloadBarAction.SHOW, MainActivity.downloadBarAction(
                DownloadBarSignal.TRANSFER_PROGRESS, 620, true));
    }

    /** The first race: the event passed the socket-thread gate, but the cycle closed behind it. */
    @Test
    public void bytesFromADownloadThatIsNoLongerCountedCannotReopenTheBar() {
        assertEquals(DownloadBarAction.HIDE, MainActivity.downloadBarAction(
                DownloadBarSignal.TRANSFER_PROGRESS, 620, false));
    }

    /**
     * The second race's counterpart. A chunked transfer has no denominator, so there is nothing to
     * draw — and the fill already on the bar belongs to a transfer that has ended. Showing nothing is
     * less false than showing someone else's progress.
     */
    @Test
    public void bytesWithNoFractionClearThePreviousTransfersFillRatherThanKeepIt() {
        assertEquals(DownloadBarAction.HIDE, MainActivity.downloadBarAction(
                DownloadBarSignal.TRANSFER_PROGRESS, -1, true));
    }

    @Test
    public void bytesWithNoFractionAndNothingCountedHideTheBar() {
        assertEquals(DownloadBarAction.HIDE, MainActivity.downloadBarAction(
                DownloadBarSignal.TRANSFER_PROGRESS, -1, false));
    }

    // --- terminal events, which speak for a transfer and not for the one on screen ---------------

    /** A's end-of-body must not blank B, which is counted and streaming. */
    @Test
    public void aFinishedTransferCannotBlankOneStillRunning() {
        assertEquals(DownloadBarAction.UNCHANGED, MainActivity.downloadBarAction(
                DownloadBarSignal.TRANSFER_FINISHED, 1000, true));
    }

    /** With nothing else up, the finish is the end of the window. */
    @Test
    public void aFinishedTransferWithNothingElseCountedHidesTheBar() {
        assertEquals(DownloadBarAction.HIDE, MainActivity.downloadBarAction(
                DownloadBarSignal.TRANSFER_FINISHED, 1000, false));
    }

    /** A close is an edge, not a verdict: if another transfer is counted, this is not the last. */
    @Test
    public void aCloseThatFindsANewerDownloadCountedStandsAside() {
        assertEquals(DownloadBarAction.UNCHANGED, MainActivity.downloadBarAction(
                DownloadBarSignal.CYCLE_CLOSED, 0, true));
    }

    @Test
    public void theLastCloseEndsTheWindow() {
        assertEquals(DownloadBarAction.HIDE, MainActivity.downloadBarAction(
                DownloadBarSignal.CYCLE_CLOSED, 0, false));
    }

    // --- the orderings, walked ------------------------------------------------------------------

    /**
     * Standing aside cannot strand the bar. A close only declines while a download is counted, and
     * every counted download retires the count in its {@code finally}, so the close that finds the
     * count at zero is whichever download ends last.
     */
    @Test
    public void aStoodAsideCloseIsFollowedByTheLastDownloadsOwn() {
        assertEquals(DownloadBarAction.UNCHANGED, MainActivity.downloadBarAction(
                DownloadBarSignal.CYCLE_CLOSED, 0, true));
        assertEquals(DownloadBarAction.SHOW, MainActivity.downloadBarAction(
                DownloadBarSignal.TRANSFER_PROGRESS, 400, true));
        assertEquals(DownloadBarAction.HIDE, MainActivity.downloadBarAction(
                DownloadBarSignal.CYCLE_CLOSED, 0, false));
    }

    /**
     * The case the guard for the previous test would otherwise allow: A shows 62%, its close stands
     * aside because B started in the gap, and B has no {@code Content-Length}. A's fill must not
     * survive B's first event.
     */
    @Test
    public void aChunkedDownloadFollowingAFinishedKnownLengthOneDoesNotKeepItsFill() {
        assertEquals(DownloadBarAction.SHOW, MainActivity.downloadBarAction(
                DownloadBarSignal.TRANSFER_PROGRESS, 620, true));
        assertEquals(DownloadBarAction.UNCHANGED, MainActivity.downloadBarAction(
                DownloadBarSignal.CYCLE_CLOSED, 0, true));
        assertEquals(DownloadBarAction.HIDE, MainActivity.downloadBarAction(
                DownloadBarSignal.TRANSFER_PROGRESS, -1, true));
    }

    // --- the in-flight clocks, which answer the same two questions differently -------------------

    @Test
    public void bytesFromACountedTransferAreEvidenceTheDownloadIsInFlight() {
        assertTrue(MainActivity.downloadBytesCounted(DownloadBarSignal.TRANSFER_PROGRESS, true));
    }

    /**
     * The fourth round's race. A prefetch passes the forwarding gate while a counted download is up,
     * stops, and resumes after that download's close has cleared the clocks. Stamping there reports a
     * transfer that has ended, and the next download inherits a start time that is not its own.
     */
    @Test
    public void bytesArrivingAfterTheCloseAreNotEvidenceOfAnything() {
        assertFalse(MainActivity.downloadBytesCounted(DownloadBarSignal.TRANSFER_PROGRESS, false));
    }

    /** A finish and a close both say the window is ending, not that bytes are moving now. */
    @Test
    public void aTerminalEventDoesNotStampTheClocks() {
        assertFalse(MainActivity.downloadBytesCounted(DownloadBarSignal.TRANSFER_FINISHED, true));
        assertFalse(MainActivity.downloadBytesCounted(DownloadBarSignal.CYCLE_CLOSED, true));
    }

    /**
     * Bytes with no declared length: they keep the transfer alive in the clocks, and still draw no
     * bar. The two rules differ here on purpose — an undeclared {@code Content-Length} is a missing
     * denominator, not a missing download.
     */
    @Test
    public void undeclaredLengthBytesStillStampEvenThoughTheyDrawNothing() {
        assertTrue(MainActivity.downloadBytesCounted(DownloadBarSignal.TRANSFER_PROGRESS, true));
        assertEquals(DownloadBarAction.HIDE, MainActivity.downloadBarAction(
                DownloadBarSignal.TRANSFER_PROGRESS, -1, true));
    }
}
