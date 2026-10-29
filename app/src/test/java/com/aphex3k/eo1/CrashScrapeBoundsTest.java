package com.aphex3k.eo1;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The two bounds the startup scrape had never had, asserted through API that exists in both the
 * pre-fix and post-fix {@link CrashSources} — so the same file fails against one and passes against
 * the other, which is the only honest way to say a fix was load-bearing rather than cosmetic.
 *
 * <p>{@link #aStuckChildCannotParkTheScrapeForever()} is the one that matters: until exactly one
 * thread owned {@code destroy()}/{@code waitFor()} for the life of the child, the read thread reaped
 * in its own {@code finally} while the deadline helper was still inside {@code destroy()} on the same
 * process, and the scrape thread never came back. Nothing was thrown, so nothing was logged: the
 * process just stopped publishing a verdict, which is how a frame with a working radio read as
 * {@code unavailable} until it was rebooted.
 */
public class CrashScrapeBoundsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final long T0 = 1_700_000_000_000L;

    private static final class Runner implements CrashSources.CommandRunner {
        private final Process p;

        Runner(Process p) {
            this.p = p;
        }

        @Override
        public Process start(String[] argv) {
            return p;
        }
    }

    /** A child whose reaping never completes, which is what a wedged {@code logcat} looks like. */
    private static final class StuckChild extends Process {
        private final CountDownLatch gate = new CountDownLatch(1);
        private final byte[] out;
        private final InputStream in;

        StuckChild(String output) {
            this.out = output.getBytes();
            this.in = new ByteArrayInputStream(this.out);
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            gate.await(120, TimeUnit.SECONDS);
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            try {
                gate.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void release() {
            gate.countDown();
        }
    }

    /**
     * A child with more output than the retention cap that exits 143 if it was destroyed before it
     * finished writing and 0 if it was drained to EOF — which is what {@code logcat -d} does. A reader
     * that stopped at the cap therefore reported the non-zero status <i>of its own kill</i> as a dead
     * source, and discarded whatever it had already filtered.
     */
    private static final class KilledUnlessDrained extends Process {
        // Typed as the array stream, not InputStream: the base class's available() declares
        // IOException, and this class only wants the byte count.
        private final ByteArrayInputStream in;
        private volatile boolean destroyed;

        KilledUnlessDrained(String output) {
            this.in = new ByteArrayInputStream(output.getBytes());
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return destroyed && in.available() > 0 ? 143 : 0;
        }

        @Override
        public int exitValue() {
            return waitFor();
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    private File emptyDir(String name) throws IOException {
        File d = new File(temp.getRoot(), name);
        assertTrue(d.mkdirs());
        return d;
    }

    @Test
    public void aStuckChildCannotParkTheScrapeForever() throws Exception {
        final File baseDir = temp.newFolder("crashes");
        final File dropbox = emptyDir("dropbox");
        final File anr = emptyDir("anr");
        final StuckChild child = new StuckChild(
                "F/libc    ( 1234): Fatal signal 11 (SIGSEGV) at 0x0 (code=1)\n");

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                CrashSources.scrape(baseDir, null, new Runner(child), T0, dropbox, anr);
            }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            // Well past every bound the class declares (a 3s read deadline plus slack).
            worker.join(15_000L);
            assertFalse("the scrape thread parked reaping the child and never returned",
                    worker.isAlive());
        } finally {
            child.release();
        }
    }

    @Test
    public void aBufferLargerThanTheCapIsNotADeadSource() throws Exception {
        File baseDir = temp.newFolder("crashes2");
        File dropbox = emptyDir("dropbox2");
        File anr = emptyDir("anr2");
        StringBuilder noise = new StringBuilder();
        while (noise.length() <= CrashSources.MAX_OUTPUT_CHARS) {
            noise.append("I/ISystemWriteService(  123): readSysfs could not contact remote\n");
        }

        CrashSources.Availability a = CrashSources.scrape(baseDir, null,
                new Runner(new KilledUnlessDrained(noise.toString())), T0, dropbox, anr);

        assertNotEquals("a big buffer was reported as a failed source: " + a.logcat,
                CrashSources.UNAVAILABLE, a.logcat);
    }

    @Test
    public void aChildThatFailedOnItsOwnIsStillNotStoredAsACrash() throws Exception {
        // The invariant the byte-cap fix must not quietly widen: a non-zero exit that was not our kill
        // means the text is logcat's own complaint, not the crash it never read.
        File baseDir = temp.newFolder("crashes3");
        File dropbox = emptyDir("dropbox3");
        File anr = emptyDir("anr3");
        CrashSources.Availability a = CrashSources.scrape(baseDir, null,
                new Runner(new RefusingChild()), T0, dropbox, anr);
        assertNotEquals("a refused logcat became a report: " + a.logcat,
                CrashSources.COPIED, a.logcat);
    }

    /** Exits 1 without having been killed by us, the way a denied {@code logcat} does. */
    private static final class RefusingChild extends Process {
        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(
                    "Unable to open log device 'system': Permission denied\n".getBytes());
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 1;
        }

        @Override
        public int exitValue() {
            return 1;
        }

        @Override
        public void destroy() {
        }
    }
}
