package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
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

    /**
     * A child that writes a native crash and then blocks without reaching EOF, whose {@code destroy()}
     * blocks as well — the combination a wedged {@code logcat} presents. Closing the pipe is the only
     * thing that ends the read, so the reaper must close <i>before</i> it destroys: in the other order
     * the reader never wakes, {@code readChild} never returns, and {@code drain()} never reaches the
     * bounded wait that is supposed to be its guarantee.
     */
    private static final class StallingChild extends Process {
        private final byte[] bytes =
                ("I/debuggerd( 1234): *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n"
                        + "F/libc    ( 1234): Fatal signal 11 (SIGSEGV) at 0x0 (code=1), thread 12\n")
                        .getBytes();
        private final Object pump = new Object();
        private volatile boolean released;
        private volatile boolean closed;
        private int pos;

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : (one[0] & 0xFF);
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    synchronized (pump) {
                        // A pipe hands over what it already has before it ever blocks.
                        if (pos < bytes.length) {
                            int n = Math.min(len, bytes.length - pos);
                            System.arraycopy(bytes, pos, b, off, n);
                            pos += n;
                            return n;
                        }
                        long until = System.currentTimeMillis() + 60_000L;
                        try {
                            while (!closed && !released && System.currentTimeMillis() < until) {
                                pump.wait(20L);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return -1;
                        }
                        if (closed) {
                            // What a pipe hands to a reader when another thread closes its fd.
                            throw new IOException("Stream Closed");
                        }
                        return -1;
                    }
                }

                @Override
                public void close() throws IOException {
                    closed = true;
                    synchronized (pump) {
                        pump.notifyAll();
                    }
                }
            };
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
            blockUntilReleased();
            return 143;
        }

        @Override
        public int exitValue() {
            return 143;
        }

        @Override
        public void destroy() {
            try {
                blockUntilReleased();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void blockUntilReleased() throws InterruptedException {
            synchronized (pump) {
                while (!released) {
                    pump.wait(50L);
                }
            }
        }

        void release() {
            released = true;
            synchronized (pump) {
                pump.notifyAll();
            }
        }
    }

    @Test
    public void aStallingChildStillKeepsWhatItAlreadyWrote() throws Exception {
        final File baseDir = temp.newFolder("crashes4");
        final File dropbox = emptyDir("dropbox4");
        final File anr = emptyDir("anr4");
        final StallingChild child = new StallingChild();

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                CrashSources.scrape(baseDir, null, new Runner(child), T0, dropbox, anr);
            }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(20_000L);
            assertFalse("the scrape parked: destroy() blocked before anything closed the pipe",
                    worker.isAlive());
        } finally {
            child.release();
        }

        CrashStore.Info[] infos = CrashStore.list(baseDir);
        org.junit.Assert.assertEquals(
                "crash lines already read must survive our own deadline", 1, infos.length);
        org.junit.Assert.assertEquals(CrashStore.KIND_LOGCAT, infos[0].kind);

        // And the caveat travels with the artifact, not only with the live verdict: a partial log read
        // opened months later has to say it was partial, because /state by then describes another boot.
        String head = firstLines(new File(baseDir, infos[0].name), 25);
        assertTrue("the report must carry its own truncation evidence:\n" + head,
                head.contains("Source-Logcat-Reason"));
    }

    @Test
    public void aStreamThatFailsIsNotAnEmptyBuffer() throws Exception {
        // The failure must stay a failure. Marking "we terminated this" for any read that did not reach
        // EOF routes a throwing stream past the read-error check, and an unreadable source comes up
        // reading "empty" — which is the one verdict that tells an operator to stop looking.
        File baseDir = temp.newFolder("crashes5");
        File dropbox = emptyDir("dropbox5");
        File anr = emptyDir("anr5");

        CrashSources.Availability a = CrashSources.scrape(baseDir, null,
                new Runner(new ThrowingChild()), T0, dropbox, anr);

        org.junit.Assert.assertEquals(CrashSources.UNAVAILABLE, a.logcat);
        assertTrue("and it must name the read, not a deadline: " + a.logcatReason,
                a.logcatReason != null && a.logcatReason.startsWith("read:"));
        assertEquals(0, CrashStore.list(baseDir).length);
    }

    /** Reads output that dies the moment anything asks for it, without ever reaching EOF. */
    private static final class ThrowingChild extends Process {
        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("unexpected EOF while reading header");
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    throw new IOException("unexpected EOF while reading header");
                }
            };
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
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }

    private static String firstLines(File f, int n) throws IOException {
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        try {
            String line;
            while (n-- > 0 && (line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        return sb.toString();
    }

    @Test
    public void aCaptureExactlyAtTheCapIsNotCalledPartial() throws Exception {
        // Nothing was discarded when the buffer is exactly full at EOF, and claiming otherwise puts a
        // "scanned the oldest part only" warning in /state and in the report header for a capture that
        // was complete. The cap is an exact multiple of the read buffer, so this is a shape a real
        // drained logcat reaches often.
        File baseDir = temp.newFolder("crashes8");
        File dropbox = emptyDir("dropbox8");
        File anr = emptyDir("anr8");
        byte[] exact = new byte[CrashSources.MAX_OUTPUT_CHARS];
        for (int i = 0; i < exact.length; i++) {
            exact[i] = (byte) ('a' + (i % 26));
        }

        CrashSources.Availability a = CrashSources.scrape(baseDir, null,
                new Runner(new FlatChild(exact)), T0, dropbox, anr);

        assertEquals(CrashSources.EMPTY, a.logcat);
        assertTrue("a complete capture must not carry a truncation warning: " + a.logcatReason,
                a.logcatReason == null);
    }

    /** A child that writes exactly what it is given and exits clean; nothing about it is wedged. */
    private static final class FlatChild extends Process {
        private final InputStream in;

        FlatChild(byte[] output) {
            this.in = new ByteArrayInputStream(output);
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
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }

    /**
     * The worst case the platform could hand us: a pipe read that another thread's {@code close()} does
     * not release. Nothing the reader can do ends it, so this is the case in which the scrape's bound
     * would be a promise rather than a guarantee if reaching the bound depended on getting <i>out</i> of
     * the read — the assumption a JVM fake cannot exercise and no hardware here has confirmed.
     */
    private static final class UnreleasableReadChild extends Process {
        /** Written before the read wedges, so there is something that must survive. */
        private final byte[] head =
                ("I/debuggerd( 1234): *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n"
                        + "F/libc    ( 1234): Fatal signal 11 (SIGSEGV) at 0x0 (code=1), thread 12\n")
                        .getBytes();
        private int pos;
        private final CountDownLatch gate = new CountDownLatch(1);

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    return read(new byte[1], 0, 1);
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (pos < head.length) {
                        int n = Math.min(len, head.length - pos);
                        System.arraycopy(head, pos, b, off, n);
                        pos += n;
                        return n;
                    }
                    // Past the prefix, park in a read that nothing releases.
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                    return -1;
                }
                // Deliberately no close() override: the parked reader stays parked, which is the point.
            };
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
            return 143;
        }

        @Override
        public int exitValue() {
            return 143;
        }

        @Override
        public void destroy() {
        }

        void release() {
            gate.countDown();
        }
    }

    @Test
    public void aBoundNeverDependsOnGettingOutOfTheRead() throws Exception {
        final File baseDir = temp.newFolder("crashes7");
        final File dropbox = emptyDir("dropbox7");
        final File anr = emptyDir("anr7");
        final UnreleasableReadChild child = new UnreleasableReadChild();

        final CrashSources.Availability[] box = new CrashSources.Availability[1];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                box[0] = CrashSources.scrape(baseDir, null, new Runner(child), T0, dropbox, anr);
            }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(CrashSources.TIMEOUT_MS + CrashSources.REAP_GRACE_MS + 5_000L);
            assertFalse("the scrape was held hostage by a read it could not leave",
                    worker.isAlive());
        } finally {
            child.release();
        }

        // Returning on time is only half of it: the lines read before the read wedged are the evidence,
        // and losing them would make the source report `empty` — found nothing — while it actually
        // found a native crash and simply could not finish looking.
        assertEquals(CrashSources.COPIED, box[0].logcat);
        CrashStore.Info[] infos = CrashStore.list(baseDir);
        assertEquals(1, infos.length);
        assertTrue("the crash prefix did not survive the abandoned read",
                firstLines(new File(baseDir, infos[0].name), 40).contains("Fatal signal 11"));
    }

    @Test
    public void oneOversizedThrowableIsStillBounded() {
        // The budget has to cut inside a single throwable, not only between them: AppLogger holds and
        // writes whatever this returns, on the thread that is already dying.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            big.append("0123456789");
        }
        String rendered = CrashStore.traceToString(new Throwable(big.toString()));
        assertTrue("one throwable came back at " + rendered.length() + " chars",
                rendered.length() <= CrashStore.MAX_DUMP_BYTES + 1024L);
    }
}
