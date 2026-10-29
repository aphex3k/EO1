package com.aphex3k.eo1;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class CrashSourcesTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final long T0 = 1_700_000_000_000L;

    private static final String CRASH_LINE =
            "--------- beginning of /dev/log/system\n"
            + "I/debuggerd( 1234): *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n"
            + "F/libc    ( 1234): Fatal signal 11 (SIGSEGV) at 0x00000000 (code=1), thread 1240\n";

    /** Records the argv it was asked to start, and replays a canned stdout. */
    private static final class FakeRunner implements CrashSources.CommandRunner {
        final List<String[]> argvs = new ArrayList<>();
        private final String output;
        private final int exit;
        private final IOException failure;
        private final Process canned;

        FakeRunner(String output) {
            this(output, 0, null);
        }

        FakeRunner(String output, int exit) {
            this(output, exit, null);
        }

        FakeRunner(String output, int exit, IOException failure) {
            this.output = output;
            this.exit = exit;
            this.failure = failure;
            this.canned = null;
        }

        /** For the children that do not behave: argv is still recorded, the output is theirs. */
        FakeRunner(Process canned) {
            this.output = null;
            this.exit = 0;
            this.failure = null;
            this.canned = canned;
        }

        @Override
        public Process start(String[] argv) throws IOException {
            argvs.add(argv);
            if (failure != null) {
                throw failure;
            }
            if (canned != null) {
                return canned;
            }
            return new FakeProcess(output, exit);
        }
    }

    /**
     * A child that cannot be reaped: {@code destroy()} and {@code waitFor()} both sit on one gate,
     * which is what a wedged process looks like from {@code UNIXProcess}'s side. The gate is released
     * by the test's {@code finally}, so no thread outlives the test.
     */
    private static final class StuckProcess extends Process {
        private final java.util.concurrent.CountDownLatch gate =
                new java.util.concurrent.CountDownLatch(1);
        private final InputStream in;

        StuckProcess(InputStream in) {
            this.in = in;
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            await();
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            await();
        }

        void release() {
            gate.countDown();
        }

        private void await() {
            try {
                gate.await(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A finished child with canned stdout, that records whether we ever left it unreaped. */
    private static final class FakeProcess extends Process {
        private final String output;
        private final int exit;
        volatile boolean reaped;

        FakeProcess(String output, int exit) {
            this.output = output;
            this.exit = exit;
        }

        @Override
        public InputStream getInputStream() {
            byte[] b = output == null ? new byte[0] : output.getBytes();
            return new ByteArrayInputStream(b);
        }

        @Override
        public OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return exit;
        }

        @Override
        public int exitValue() {
            return exit;
        }

        @Override
        public void destroy() {
            reaped = true;
        }
    }

    private File crashDir() throws IOException {
        return temp.newFolder("crashes");
    }

    private static File dirWith(File parent, String name, String... files) throws IOException {
        File d = new File(parent, name);
        assertTrue(d.mkdirs());
        for (String f : files) {
            File out = new File(d, f);
            FileOutputStream os = new FileOutputStream(out);
            try {
                os.write(("----- pid 12 at boot ----\nnative dump body for " + f + "\n").getBytes("UTF-8"));
            } finally {
                os.close();
            }
        }
        return d;
    }

    @Test
    public void logcatArgvIsAConstantAndNothingIsInterpolated() throws Exception {
        File baseDir = crashDir();
        FakeRunner runner = new FakeRunner(CRASH_LINE);
        CrashSources.scrape(baseDir, null, runner, T0);

        assertEquals(1, runner.argvs.size());
        // `-b crash` is not an option: LOG_ID_CRASH arrived in Android 7.0 and this ROM's logcat
        // knows only main/system/radio, so asking for `crash` just fails.
        assertArrayEquals(new String[]{"/system/bin/logcat", "-d", "-b", "system"},
                runner.argvs.get(0));
        assertArrayEquals(CrashSources.LOGCAT_NATIVE, runner.argvs.get(0));
    }

    @Test
    public void nativeCrashLinesBecomeOneReport() throws Exception {
        File baseDir = crashDir();
        CrashSources.Availability a = CrashSources.scrape(baseDir, null,
                new FakeRunner(CRASH_LINE), T0);

        assertEquals(CrashSources.COPIED, a.logcat);
        CrashStore.Info[] infos = CrashStore.list(baseDir);
        assertEquals(1, infos.length);
        assertEquals(CrashStore.KIND_LOGCAT, infos[0].kind);
        assertTrue(infos[0].name.contains("-logcat-"));
    }

    @Test
    public void vendorChatterIsNotStoredAsACrash() throws Exception {
        // This ROM writes ~10 lines/second of unrelated noise into the system buffer; storing it
        // whole would fill the retention budget and bury the two lines that matter.
        File baseDir = crashDir();
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            noise.append("I/ISystemWriteService(  123): readSysfs could not contact remote\n");
        }
        CrashSources.Availability a = CrashSources.scrape(baseDir, null,
                new FakeRunner(noise.toString()), T0);

        assertEquals(CrashSources.EMPTY, a.logcat);
        assertEquals(0, CrashStore.list(baseDir).length);
    }

    @Test
    public void aFailedLogcatIsUnavailableNotAJunkReport() throws Exception {
        // capture() merges stderr, so logcat's own complaint is otherwise indistinguishable from
        // output — and an operator would read "copied" while nothing was ever captured.
        File baseDir = crashDir();
        CrashSources.Availability a = CrashSources.scrape(baseDir, null,
                new FakeRunner("Unable to open log device 'crash': Permission denied\n", 1), T0);

        assertEquals(CrashSources.UNAVAILABLE, a.logcat);
        assertEquals(0, CrashStore.list(baseDir).length);
    }

    @Test
    public void anEmptyBufferIsNotAnUnavailableSource() throws Exception {
        File baseDir = crashDir();
        CrashSources.Availability a = CrashSources.scrape(baseDir, null, new FakeRunner("  \n"), T0);
        assertEquals(CrashSources.EMPTY, a.logcat);
        assertEquals(0, CrashStore.list(baseDir).length);
    }

    @Test
    public void aRefusedSubprocessOnlyDisablesItsOwnSource() throws Exception {
        File baseDir = crashDir();
        FakeRunner runner = new FakeRunner(null, 0, new IOException("permission denied"));
        CrashSources.Availability a = CrashSources.scrape(baseDir, null, runner, T0);
        assertEquals(CrashSources.UNAVAILABLE, a.logcat);
        // And the app's own java-source capture is entirely unaffected.
        assertNotNull(CrashStore.list(baseDir));
    }

    @Test
    public void anUnchangedBufferIsNotRecopiedEveryBoot() throws Exception {
        File baseDir = crashDir();
        CrashSources.scrape(baseDir, null, new FakeRunner(CRASH_LINE), T0);
        CrashSources.Availability second =
                CrashSources.scrape(baseDir, null, new FakeRunner(CRASH_LINE), T0 + 1000L);

        assertEquals(CrashSources.UNCHANGED, second.logcat);
        assertEquals("one report, not two", 1, CrashStore.list(baseDir).length);
    }

    @Test
    public void dropboxArtifactsAreCopiedInAndNamedByKind() throws Exception {
        File baseDir = crashDir();
        File host = temp.getRoot();
        File dropbox = dirWith(host, "dropbox", "system_app_crash@1.txt", "system_server_anr@2.txt");
        File anr = new File(host, "no-such-anr-dir");

        CrashSources.Availability a = CrashSources.scrape(baseDir, null, new FakeRunner(""), T0,
                dropbox, anr);

        assertEquals(CrashSources.COPIED, a.dropbox);
        assertEquals(CrashSources.UNAVAILABLE, a.anr);
        assertEquals(2, CrashStore.list(baseDir).length);
        for (CrashStore.Info info : CrashStore.list(baseDir)) {
            assertEquals(CrashStore.KIND_DROPBOX, info.kind);
            assertNotNull("every copy must carry a dedupe fingerprint", info.origin);
        }
    }

    @Test
    public void theSameArtifactIsNotRecopiedOnTheNextBoot() throws Exception {
        File baseDir = crashDir();
        File dropbox = dirWith(temp.getRoot(), "dropbox1", "system_app_crash@1.txt");
        File anr = dirWith(temp.getRoot(), "anr1", "system_server_anr@2.txt");

        CrashSources.scrape(baseDir, null, new FakeRunner(""), T0, dropbox, anr);
        int afterFirst = CrashStore.list(baseDir).length;
        CrashSources.Availability second =
                CrashSources.scrape(baseDir, null, new FakeRunner(""), T0 + 60_000L, dropbox, anr);

        assertEquals(afterFirst, CrashStore.list(baseDir).length);
        assertEquals(CrashSources.UNCHANGED, second.dropbox);
        assertEquals(CrashSources.UNCHANGED, second.anr);
    }

    @Test
    public void anUnreadableDirectoryReportsUnavailableNotEmpty() throws Exception {
        File baseDir = crashDir();
        File aRealFileNotADir = temp.newFile("not-a-directory");

        CrashSources.Availability a = CrashSources.scrape(baseDir, null, new FakeRunner(""), T0,
                aRealFileNotADir, aRealFileNotADir);

        assertEquals(CrashSources.UNAVAILABLE, a.dropbox);
        assertEquals(CrashSources.UNAVAILABLE, a.anr);
        assertEquals(0, a.copies);
    }

    @Test
    public void aNonTextFileIsNotCopied() throws Exception {
        File baseDir = crashDir();
        File dropbox = dirWith(temp.getRoot(), "dropbox2", "notes.txt");
        assertTrue(new File(dropbox, "system_app_crash@1.png").createNewFile());

        CrashSources.scrape(baseDir, null, new FakeRunner(""), T0, dropbox,
                temp.newFolder("anr-png"));

        for (CrashStore.Info info : CrashStore.list(baseDir)) {
            assertTrue(info.name, !info.name.contains(".png"));
        }
    }

    @Test
    public void oneBootsCopyBudgetIsBounded() throws Exception {
        File baseDir = crashDir();
        File dropbox = temp.newFolder("dropbox3");
        for (int i = 0; i < 40; i++) {
            File f = new File(dropbox, "system_app_crash@" + i + ".txt");
            FileOutputStream os = new FileOutputStream(f);
            try {
                os.write(new byte[64 * 1024]);
            } finally {
                os.close();
            }
        }
        CrashSources.Availability a = CrashSources.scrape(baseDir, null, new FakeRunner(""), T0,
                dropbox, temp.newFolder("anr-budget"));

        assertTrue("copies must stop at the per-file limit, not all 40",
                a.copies <= CrashSources.MAX_COPIES_PER_DIR);
        assertTrue("the store must stay inside one boot's budget",
                CrashStore.stats(baseDir).totalBytes <= CrashSources.COPY_BUDGET_BYTES
                        + (long) CrashSources.MAX_COPIES_PER_DIR * 1024L);
    }

    @Test
    public void gzippedTombstonesAreCopiedAndDecompressed() throws Exception {
        // On this ROM's real /data/system/dropbox, every native crash is
        // SYSTEM_TOMBSTONE@<n>.txt.gz — a filter on ".txt" alone would skip all of them.
        File baseDir = crashDir();
        File dropbox = temp.newFolder("dropbox4");
        File gz = new File(dropbox, "SYSTEM_TOMBSTONE@88442907.txt.gz");
        java.util.zip.GZIPOutputStream os =
                new java.util.zip.GZIPOutputStream(new FileOutputStream(gz));
        try {
            os.write("*** *** *** *** *** *** *** *** ***\nFatal signal 11 (SIGSEGV), pid 991\n"
                    .getBytes("UTF-8"));
        } finally {
            os.close();
        }

        CrashSources.Availability a = CrashSources.scrape(baseDir, null, new FakeRunner(""), T0,
                dropbox, dropbox);

        assertEquals(CrashSources.COPIED, a.dropbox);
        CrashStore.Info info = CrashStore.list(baseDir)[0];
        assertTrue(read(new File(baseDir, info.name)).contains("Fatal signal 11"));
    }

    @Test
    public void frameworkEventLogNoiseIsNotCopied() throws Exception {
        File baseDir = crashDir();
        File dropbox = temp.newFolder("dropbox5");
        for (int i = 0; i < 159; i++) {
            dirWrite(new File(dropbox, "event_data@" + i + ".txt"));
        }
        dirWrite(new File(dropbox, "SYSTEM_TOMBSTONE@7.txt.gz"));

        CrashSources.scrape(baseDir, null, new FakeRunner(""), T0, dropbox,
                temp.newFolder("anr-noise"));

        CrashStore.Info[] infos = CrashStore.list(baseDir);
        assertEquals("only the tombstone is crash evidence", 1, infos.length);
        assertTrue(infos[0].name, infos[0].name.contains("-dropbox-"));
        assertTrue(CrashSources.isCrashArtifact("SYSTEM_TOMBSTONE@7.txt.gz"));
        assertTrue(CrashSources.isCrashArtifact("system_app_crash@1.txt"));
        assertTrue(CrashSources.isCrashArtifact("system_server_anr@2.txt.gz"));
        assertFalse(CrashSources.isCrashArtifact("event_data@1.txt"));
        assertFalse(CrashSources.isCrashArtifact("SYSTEM_BOOT.txt"));
        assertFalse(CrashSources.isCrashArtifact("package_changed@2.txt"));
    }

    /** A copied artifact must be dated by its own time, or a bulk scrape evicts live crashes. */
    @Test
    public void copiesAreDatedByTheArtifactNotByTheScrape() throws Exception {
        File baseDir = crashDir();
        File dropbox = temp.newFolder("dropbox6");
        File old = dirWrite(new File(dropbox, "SYSTEM_TOMBSTONE@1.txt"));
        long sixDaysAgo = T0 - 6L * 24 * 60 * 60 * 1000;
        assertTrue(old.setLastModified(sixDaysAgo));

        CrashSources.scrape(baseDir, null, new FakeRunner(""), T0, dropbox,
                temp.newFolder("anr-dated"));

        assertEquals(sixDaysAgo, CrashStore.list(baseDir)[0].epochMillis);
    }

    private static File dirWrite(File f) throws IOException {
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write("----- pid 991 at boot -----\n".getBytes("UTF-8"));
        } finally {
            os.close();
        }
        return f;
    }

    @Test
    public void availabilityStartsUnavailableAndIsNeverNull() {
        CrashSources.Availability a = CrashSources.last();
        assertNotNull(a);
        assertEquals(CrashSources.UNAVAILABLE, a.logcat);
        assertEquals(CrashSources.UNAVAILABLE, a.dropbox);
        assertEquals(CrashSources.UNAVAILABLE, a.anr);
        assertNull("no scrape has run, so there is no reason to report", a.logcatReason);
        assertTrue(a.asMap().containsKey("Source-Dropbox"));
        assertFalse("the reason key is additive and must not be there before one exists",
                a.asMap().containsKey("Source-Logcat-Reason"));
    }

    @Test
    public void captureDrainsTheChildAndNeverLeaksIt() throws Exception {
        FakeProcess p = new FakeProcess("01-01 A/libc: Fatal signal 6 in pid 991\n", 0);
        String out = CrashSources.capture(p);
        assertTrue(out.contains("Fatal signal 6"));
        // waitFor(long, TimeUnit) does not exist before API 26, so the bound is destroy().
        assertTrue("child was left running", p.reaped);
    }

    @Test
    public void anUnreapableChildCannotParkTheScrapeThread() throws Exception {
        // The bug this test exists for: the read thread used to destroy() and waitFor() the child in
        // its own finally while the deadline helper was still inside destroy() on the same process.
        // Two waiters on one UNIXProcess means the second can wait for a notification already
        // delivered — and a startup scrape that never returns publishes no verdict at all, which is
        // how a frame with nothing wrong with it read as "unavailable" until the next reboot.
        final File baseDir = crashDir();
        final File dropbox = dirWith(temp.getRoot(), "stuck-dropbox");
        final File anr = dirWith(temp.getRoot(), "stuck-anr");
        final StuckProcess stuck = new StuckProcess(
                new ByteArrayInputStream(CRASH_LINE.getBytes("UTF-8")));
        final CrashSources.Availability[] box = new CrashSources.Availability[1];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                box[0] = CrashSources.scrape(baseDir, null, new FakeRunner(stuck), T0, dropbox, anr);
            }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(CrashSources.TIMEOUT_MS + CrashSources.REAP_GRACE_MS + 5000L);
            assertFalse("scrape thread parked in the child's destroy()/waitFor()",
                    worker.isAlive());
        } finally {
            stuck.release();
        }

        assertNotNull("the scrape must return an answer, any answer", box[0]);
        assertEquals(CrashSources.UNAVAILABLE, box[0].logcat);
        assertTrue("the reason must name the reap that never completed: " + box[0].logcatReason,
                box[0].logcatReason != null && box[0].logcatReason.contains("exit status"));
        assertEquals("a child we could not reap is not a report", 0,
                CrashStore.list(baseDir).length);
    }

    @Test
    public void theByteCapBoundsWhatIsRetainedNotWhatWasSeen() throws Exception {
        // A frame that has been up for hours has a system buffer larger than the cap. Until the cap
        // bounded retention rather than the scan, that was indistinguishable from a dead radio: the
        // read stopped at 512 KB, the child was killed for it, and the non-zero exit of the logcat we
        // killed was reported as an unavailable source — discarding everything already found.
        // ChildUnfinishedPastTheCap models that: it only exits clean if it was drained to EOF.
        final File baseDir = crashDir();
        final File dropbox = dirWith(temp.getRoot(), "big-dropbox");
        final File anr = dirWith(temp.getRoot(), "big-anr");
        StringBuilder noise = new StringBuilder();
        while (noise.length() <= CrashSources.MAX_OUTPUT_CHARS) {
            noise.append("I/ISystemWriteService(  123): readSysfs could not contact remote\n");
        }

        CrashSources.Availability a = CrashSources.scrape(baseDir, null,
                new FakeRunner(new ChildUnfinishedPastTheCap(noise.toString())), T0, dropbox, anr);

        assertTrue("a big buffer is a source that worked: " + a.logcat,
                CrashSources.EMPTY.equals(a.logcat) || CrashSources.COPIED.equals(a.logcat));
        assertTrue("and the cap must be admitted, not silently applied: " + a.logcatReason,
                a.logcatReason != null && a.logcatReason.contains("oldest"));
    }

    /**
     * A child with more output than the retention cap that exits 143 if it was destroyed before it
     * finished writing, and 0 if it was drained to EOF. That is what {@code logcat -d} does: it is
     * killed by a reader that gave up mid-buffer, not by a failure of its own. Stopping the read at
     * the cap therefore produced a non-zero status, which the source reported as {@code unavailable}
     * and everything already filtered was thrown away.
     */
    private static final class ChildUnfinishedPastTheCap extends Process {
        private final byte[] out;
        private final java.io.ByteArrayInputStream in;
        private volatile boolean destroyed;

        ChildUnfinishedPastTheCap(String output) {
            this.out = output.getBytes();
            this.in = new java.io.ByteArrayInputStream(this.out);
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
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

    @Test
    public void aFailedSourceNamesItsReasonAndAGoodOneDoesNot() throws Exception {
        File baseDir = crashDir();
        File dropbox = dirWith(temp.getRoot(), "why-dropbox");
        File anr = dirWith(temp.getRoot(), "why-anr");

        CrashSources.Availability failed = CrashSources.scrape(baseDir, null,
                new FakeRunner("Unable to open log device 'crash': Permission denied\n", 1), T0,
                dropbox, anr);
        assertEquals(CrashSources.UNAVAILABLE, failed.logcat);
        assertTrue(failed.asMap().containsKey("Source-Logcat-Reason"));
        assertTrue(failed.asMap().get("Source-Logcat-Reason").contains("exited 1"));

        CrashSources.Availability worked = CrashSources.scrape(baseDir, null,
                new FakeRunner(CRASH_LINE), T0 + 1000L, dropbox, anr);
        assertEquals(CrashSources.COPIED, worked.logcat);
        assertFalse("nothing failed, so nothing may claim to have",
                worked.asMap().containsKey("Source-Logcat-Reason"));
    }

    private static String read(File f) throws IOException {
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
