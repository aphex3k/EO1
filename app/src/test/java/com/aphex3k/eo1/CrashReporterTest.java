package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The contract of a crash handler: write what happened, arm the restart, die. In that order, and
 * only once — including when any step fails.
 */
public class CrashReporterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final class FakeTransport implements CrashReporter.Relaunch, CrashReporter.Exiter {
        int arms;
        long lastDelayMs = -1L;
        int exits;
        int lastCode;
        boolean throwOnArm;

        @Override
        public void arm(long delayMs) {
            arms++;
            lastDelayMs = delayMs;
            if (throwOnArm) {
                throw new IllegalStateException("alarm service gone");
            }
        }

        @Override
        public void exit(int code) {
            exits++;
            lastCode = code;
        }
    }

    private File dir() throws Exception {
        return temp.newFolder("crashes");
    }

    @Test
    public void writesTheReportThenArmsAndExitsOnce() throws Exception {
        File baseDir = dir();
        FakeTransport transport = new FakeTransport();
        CrashReporter reporter = new CrashReporter(baseDir, transport, transport);

        reporter.uncaughtException(Thread.currentThread(),
                new NullPointerException("getFoo() on a null object"));

        assertEquals(1, transport.exits);
        assertEquals(2, transport.lastCode);
        assertEquals(1, transport.arms);
        assertEquals(CrashReporter.RELAUNCH_DELAY_MS, transport.lastDelayMs);

        CrashStore.Info[] infos = CrashStore.list(baseDir);
        assertEquals(1, infos.length);
        assertEquals(CrashStore.KIND_JAVA, infos[0].kind);
        assertEquals("java.lang.NullPointerException", infos[0].exception);
    }

    @Test
    public void theReportNamesTheVersionCodeThatSelectsItsMapping() throws Exception {
        File baseDir = dir();
        FakeTransport transport = new FakeTransport();
        new CrashReporter(baseDir, transport, transport)
                .uncaughtException(Thread.currentThread(), new RuntimeException("x"));

        String name = CrashStore.list(baseDir)[0].name;
        assertEquals(Integer.toString(BuildConfig.VERSION_CODE),
                name.substring(name.lastIndexOf('-') + 1, name.length() - ".txt".length()));
    }

    @Test
    public void aBrokenStoreStillArmsTheAlarmAndExits() throws Exception {
        // A crash store that cannot be written (disk full, unwritable dir) must never cost the frame
        // its restart — that was the real hazard in the old handler.
        File notADir = temp.newFile("blocked");
        FakeTransport transport = new FakeTransport();
        new CrashReporter(notADir, transport, transport)
                .uncaughtException(Thread.currentThread(), new RuntimeException("x"));

        assertTrue("nothing should have been written", CrashStore.list(notADir).length == 0);
        assertEquals(1, transport.arms);
        assertEquals(1, transport.exits);
    }

    @Test
    public void anAlarmThatThrowsStillExits() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.throwOnArm = true;
        new CrashReporter(dir(), transport, transport)
                .uncaughtException(Thread.currentThread(), new RuntimeException("x"));

        assertEquals(1, transport.exits);
    }

    @Test
    public void aCrashWhileWritingADumpDoesNotRunTheContractTwice() throws Exception {
        File baseDir = dir();
        FakeTransport transport = new FakeTransport();
        final CrashReporter[] holder = new CrashReporter[1];
        // An Env that itself dies: this is the "throw inside the crash path" case.
        CrashReporter.Env reentrant = new CrashReporter.Env() {
            @Override
            public Map<String, String> snapshot(File dir) {
                holder[0].uncaughtException(Thread.currentThread(),
                        new IllegalStateException("reentrant"));
                return new LinkedHashMap<>();
            }
        };
        holder[0] = new CrashReporter(baseDir, transport, transport, reentrant);
        holder[0].uncaughtException(Thread.currentThread(), new RuntimeException("original"));

        assertEquals("nested call must not exit mid-write", 1, transport.exits);
        assertEquals(1, transport.arms);
        assertEquals("exactly one dump", 1, CrashStore.list(baseDir).length);
    }

    @Test
    public void theLeadUpIsIncludedWhenTheLogExists() throws Exception {
        File baseDir = dir();
        File logFile = new File(temp.getRoot(), "eo1-app.log");
        AppLogger logger = new AppLogger(logFile);
        logger.info("EO1", "rotation started");
        logger.error("EO1", "thumbnail fetch failed");

        FakeTransport transport = new FakeTransport();
        CrashReporter reporter = new CrashReporter(baseDir, transport, transport);
        reporter.setLogger(logger);
        reporter.uncaughtException(Thread.currentThread(), new RuntimeException("boom"));

        String body = readFile(new File(baseDir, CrashStore.list(baseDir)[0].name));
        assertTrue(body.contains("---- last log lines ----"));
        assertTrue("the lead-up must reach the report", body.contains("rotation started"));
        assertTrue("the FATAL line itself belongs to the same event",
                body.contains("FATAL uncaught on"));
    }

    @Test
    public void aNullLoggerStillProducesATrace() throws Exception {
        File baseDir = dir();
        FakeTransport transport = new FakeTransport();
        CrashReporter reporter = new CrashReporter(baseDir, transport, transport);
        reporter.setLogger(null);
        reporter.uncaughtException(Thread.currentThread(), new IllegalStateException("no log"));

        String body = readFile(new File(baseDir, CrashStore.list(baseDir)[0].name));
        assertTrue(body.contains("java.lang.IllegalStateException: no log"));
        assertTrue(body.contains("(none)"));
    }

    @Test
    public void aDeepCauseChainIsCapturedAndBounded() throws Exception {
        File baseDir = dir();
        Throwable t = new RuntimeException("root");
        for (int i = 0; i < 10_000; i++) {
            t = new RuntimeException("frame " + i, t);
        }
        FakeTransport transport = new FakeTransport();
        // Must reach the finally rather than blowing up in OutOfMemoryError territory.
        new CrashReporter(baseDir, transport, transport).uncaughtException(Thread.currentThread(), t);

        assertEquals(1, transport.exits);
        File dump = new File(baseDir, CrashStore.list(baseDir)[0].name);
        assertTrue("report must stay near the cap, was " + dump.length(),
                dump.length() <= CrashStore.MAX_DUMP_BYTES + 8L * 1024L);
        // What must survive is the frame that actually killed the thread; how far the walk gets
        // after that is whichever of the two bounds (byte budget, cause-depth cap) arrives first.
        String body = readFile(dump);
        assertTrue(body.contains("java.lang.RuntimeException: frame 9999"));
        assertTrue("the trace must be bounded", body.contains("truncated at "));
    }

    @Test
    public void sourceAvailabilityIsRecordedInEveryReport() throws Exception {
        File baseDir = dir();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("Source-Dropbox", CrashSources.UNAVAILABLE);
        FakeTransport transport = new FakeTransport();
        CrashReporter reporter = new CrashReporter(baseDir, transport, transport);
        reporter.setSourceAvailability(sources);
        reporter.uncaughtException(Thread.currentThread(), new RuntimeException("x"));

        assertTrue(readFile(new File(baseDir, CrashStore.list(baseDir)[0].name))
                .contains("Source-Dropbox: unavailable"));
        assertFalse(readFile(new File(baseDir, CrashStore.list(baseDir)[0].name))
                .contains("password"));
    }

    @Test
    public void twoThreadsDyingAtOnceProduceOneReport() throws Exception {
        // A wedged device usually takes more than one thread with it. The claim must be atomic:
        // with a check-then-set both would write, and the loser's exit() would kill the winner
        // mid-render.
        File baseDir = dir();
        FakeTransport transport = new FakeTransport();
        final CrashReporter reporter = new CrashReporter(baseDir, transport, transport);
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        Thread[] threads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int id = i;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    reporter.uncaughtException(Thread.currentThread(),
                            new RuntimeException("thread " + id));
                }
            });
            threads[i].start();
        }
        ready.await();
        go.countDown();
        for (Thread t : threads) {
            t.join(5000L);
        }

        assertEquals("exactly one dump", 1, CrashStore.list(baseDir).length);
        assertEquals("exactly one exit", 1, transport.exits);
        assertEquals("exactly one relaunch alarm", 1, transport.arms);
    }

    private static String readFile(File f) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return new String(bos.toByteArray(), "UTF-8");
    }
}
