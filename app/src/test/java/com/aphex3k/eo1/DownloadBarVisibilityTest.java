package com.aphex3k.eo1;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When a download-progress event is allowed to leave the bar on screen.
 *
 * <p>The race this pins: the progress callback is bound to the HTTP client, not to the acquisition,
 * so a background prefetch's bytes reach {@link MediaManager#clientProgressUpdates} and pass its
 * in-flight early-out whenever a *counted* download happens to be running. That prefetch post can
 * then land behind the close raised when the counted download fails — and since a prefetch never
 * raises the count, it never lowers it either, so nothing else would ever hide the bar again. The
 * answer is not to order the two posts but to ask the question at the moment of drawing: is a
 * counted download up right now. Either order then ends with the bar hidden.
 */
public class DownloadBarVisibilityTest {

    /** An event carrying a real fraction from a transfer that is still counted. */
    @Test
    public void aProgressEventFromACountedDownloadShowsTheBar() {
        assertTrue(MainActivity.downloadBarVisible(false, 500, true));
    }

    /**
     * The interleaving itself. Same event, but the cycle closed while it was in the queue: showing it
     * would be a bar with no owner, which is the stalled indicator this PR exists to remove.
     */
    @Test
    public void anEventThatLandedAfterTheCycleClosedCannotReopenTheBar() {
        assertFalse(MainActivity.downloadBarVisible(false, 500, false));
    }

    @Test
    public void theLastEventOfATransferNeverLeavesTheBarUp() {
        assertFalse(MainActivity.downloadBarVisible(true, 1000, true));
        assertFalse(MainActivity.downloadBarVisible(true, 1000, false));
    }

    /** An undeclared length has no fraction to draw, whatever else is true. */
    @Test
    public void anEventWithNoFractionHasNothingToShow() {
        assertFalse(MainActivity.downloadBarVisible(false, -1, true));
    }

    /**
     * The mirror of the race above, in the other direction. Download A's close is raised, and B is
     * started and counted before that close reaches the UI thread. The close must stand aside: hiding
     * there blanks a transfer that is genuinely running, and zeroing the clocks would re-anchor B's
     * age to now and make {@code downloadInFlightMs()} understate it.
     */
    @Test
    public void aCloseThatFindsANewerDownloadCountedStandsAside() {
        assertFalse(MainActivity.downloadCycleEndsTheBar(true));
    }

    /**
     * Standing aside cannot strand the bar, because a close only declines when a download is counted,
     * and that download raises a close of its own when it ends. Walked as a sequence: A's close
     * stands aside, B's bytes show the bar, B's close — with nothing else up — is the one that ends it.
     */
    @Test
    public void aStoodAsideCloseIsFollowedByTheLastDownloadsOwn() {
        assertFalse(MainActivity.downloadCycleEndsTheBar(true));
        assertTrue(MainActivity.downloadBarVisible(false, 400, true));
        assertTrue(MainActivity.downloadCycleEndsTheBar(false));
    }
}
