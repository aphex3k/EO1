package com.aphex3k.eo1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The fraction the download bar shows for one progress event.
 *
 * <p>Two failures this guard exists to make impossible. A response with no {@code Content-Length}
 * reports -1, which was passed straight to the view as its maximum — a bar with no denominator can
 * never move, and a stalled bar is the very complaint. And a body larger than 2 GiB does not fit the
 * int the view's argument takes, which on the UI thread is an exit, not a rendering glitch.
 */
public class DownloadBarGeometryTest {

    @Test
    public void aKnownLengthScalesToPerMille() {
        assertEquals(0, MainActivity.downloadBarPermille(0L, 1000L));
        assertEquals(250, MainActivity.downloadBarPermille(250L, 1000L));
        assertEquals(1000, MainActivity.downloadBarPermille(1000L, 1000L));
    }

    /** Servers over-run a declared length; the bar must not fill past its own end or go negative. */
    @Test
    public void theFillStaysInsideTheBar() {
        assertEquals(1000, MainActivity.downloadBarPermille(4000L, 1000L));
        assertEquals(0, MainActivity.downloadBarPermille(-1L, 1000L));
    }

    /**
     * The case that produced the stalled bar: no declared length means there is no fraction, so the
     * caller hides the bar rather than parking it where it looks frozen.
     */
    @Test
    public void anUndeclaredOrEmptyLengthHasNoFraction() {
        assertEquals(-1, MainActivity.downloadBarPermille(512L, -1L));
        assertEquals(-1, MainActivity.downloadBarPermille(0L, 0L));
        assertEquals(-1, MainActivity.downloadBarPermille(0L, -1L));
    }

    /** Two gigabytes of video is the size this hardware is allowed to be handed at all. */
    @Test
    public void aBodyOverTwoGigabytesStillFitsTheViewsIntArgument() {
        long overIntMax = (long) Integer.MAX_VALUE + 1L;
        int fill = MainActivity.downloadBarPermille(overIntMax / 2L, overIntMax);

        assertEquals(500, fill);
    }
}
