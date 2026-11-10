package com.aphex3k.eo1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.HttpUrl;

/**
 * The close of the download window, which is what the progress indicator is a picture of.
 *
 * <p>The bar used to be cleared only by a {@code done} progress event, and {@code done} is emitted
 * solely when the response body returns -1 from a read. A transfer that ends any other way — a read
 * timeout, a reset, the write failing — produces no such event, so the bar kept its last partial
 * percent over whatever the frame went on to display, until some later download happened to finish
 * cleanly. That is the stalled bar an operator sees; the counter's descent to zero is the only edge
 * that also covers it.
 */
public class MediaManagerDownloadCycleTest {

    private static class RecordingListener implements ApiServiceGenerator.ProgressListener {
        int closes;
        int bytesEvents;

        @Override
        public void clientProgressUpdates(long bytesRead, long contentLength, boolean done,
                                          HttpUrl url) {
            bytesEvents++;
        }

        @Override
        public void downloadCycleClosed() {
            closes++;
        }
    }

    /** A listener that throws must not be able to speak over the download's own failure. */
    private static class ThrowingListener extends RecordingListener {
        @Override
        public void downloadCycleClosed() {
            super.downloadCycleClosed();
            throw new IllegalStateException("the UI went away");
        }
    }

    @Test
    public void theLastDownloadLeavingClosesTheCycleOnce() {
        RecordingListener listener = new RecordingListener();
        AtomicInteger counter = new AtomicInteger(1);

        MediaManager.releaseActiveDownload(counter, listener);

        assertEquals(1, listener.closes);
        assertEquals(0, counter.get());
    }

    @Test
    public void aCycleStaysOpenWhileAnotherDownloadIsUp() {
        RecordingListener listener = new RecordingListener();
        AtomicInteger counter = new AtomicInteger(2);

        MediaManager.releaseActiveDownload(counter, listener);
        assertEquals("one download still in flight", 0, listener.closes);
        assertEquals(1, counter.get());

        MediaManager.releaseActiveDownload(counter, listener);
        assertEquals(1, listener.closes);
        assertEquals(0, counter.get());
    }

    /**
     * The listener reaches {@link MediaManager} through a WeakReference, so it can legitimately be
     * gone by the time the last download finishes. Retiring the count must not be conditional on
     * having someone to tell: a counter left one high closes the forwarding gate for every download
     * after it, which is a worse version of the bug this class exists for.
     */
    @Test
    public void aGoneListenerStillRetiresTheCount() {
        AtomicInteger counter = new AtomicInteger(1);

        MediaManager.releaseActiveDownload(counter, null);

        assertEquals(0, counter.get());
    }

    /**
     * This runs inside a {@code finally} on the thread reading the socket. An exception thrown here
     * would displace the download's own exception on its way to the ladder, and reach a handler that
     * exits the process.
     */
    @Test
    public void aThrowingListenerCannotReachBackIntoTheDownload() {
        ThrowingListener listener = new ThrowingListener();
        AtomicInteger counter = new AtomicInteger(1);

        // Reaching the assertions at all is the assertion: nothing was rethrown.
        MediaManager.releaseActiveDownload(counter, listener);

        assertEquals(1, listener.closes);
        assertEquals(0, counter.get());
    }

    @Test
    public void twoCyclesBackToBackEachCloseOnce() {
        RecordingListener listener = new RecordingListener();
        AtomicInteger counter = new AtomicInteger(0);

        for (int round = 0; round < 2; round++) {
            counter.incrementAndGet();
            MediaManager.releaseActiveDownload(counter, listener);
        }

        assertEquals(2, listener.closes);
        assertEquals(0, counter.get());
    }
}
