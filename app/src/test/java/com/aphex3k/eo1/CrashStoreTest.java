package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class CrashStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final long T0 = 1_700_000_000_000L;

    private File dir() throws IOException {
        File d = temp.newFolder("crashes");
        return d;
    }

    private CrashStore.DumpContent content(long epochMillis, int seq, String kind) {
        CrashStore.DumpContent c = new CrashStore.DumpContent();
        c.epochMillis = epochMillis;
        c.seq = seq;
        c.kind = kind;
        c.versionCode = 4242;
        c.header = new LinkedHashMap<>();
        return c;
    }

    private File write(File baseDir, CrashStore.DumpContent c) throws IOException {
        RuntimeException boom = new RuntimeException("it broke");
        c.error = boom;
        c.header.put("Exception", boom.getClass().getName());
        return CrashStore.write(baseDir, c);
    }

    @Test
    public void newNameIsFixedWidthAndSafe() {
        String n = CrashStore.newName(T0, 7, CrashStore.KIND_JAVA, 4242);
        assertEquals("crash-1700000000000-007-java-4242.txt", n);
        assertTrue("generated name must pass the shared name guard",
                UploadedMedia.isSafeName(n));
    }

    @Test
    public void stampRoundTrips() {
        String n = CrashStore.newName(T0, 12, CrashStore.KIND_ANR, 99);
        long[] stamp = CrashStore.parseStamp(n);
        assertNotNull(stamp);
        assertEquals(T0, stamp[0]);
        assertEquals(12, stamp[1]);
    }

    @Test
    public void stampRejectsForeignNames() {
        assertNull(CrashStore.parseStamp("photo.jpg"));
        assertNull(CrashStore.parseStamp("crash-abc-001-java-1.txt"));
        assertNull(CrashStore.parseStamp("crash-1700000000000.txt"));
        assertNull(CrashStore.parseStamp(null));
    }

    @Test
    public void writeLandsOneFileWithVersionCodeInItsName() throws Exception {
        File baseDir = dir();
        File f = write(baseDir, content(T0, 0, CrashStore.KIND_JAVA));
        assertEquals("crash-" + T0 + "-000-java-4242.txt", f.getName());
        assertEquals(1, CrashStore.list(baseDir).length);
    }

    @Test
    public void listOrdersNewestFirstEvenWhenMtimeDisagrees() throws Exception {
        File baseDir = dir();
        write(baseDir, content(T0, 0, CrashStore.KIND_JAVA));
        File newer = write(baseDir, content(T0 + 5_000L, 0, CrashStore.KIND_JAVA));
        // An mtime in the past must not reorder a listing: the name wins.
        assertTrue(newer.setLastModified(T0 - 90_000L));

        CrashStore.Info[] infos = CrashStore.list(baseDir);
        assertEquals(2, infos.length);
        assertEquals(T0 + 5_000L, infos[0].epochMillis);
        assertEquals(T0, infos[1].epochMillis);
    }

    @Test
    public void listKeepsUnparseableFilesLast() throws Exception {
        File baseDir = dir();
        write(baseDir, content(T0, 0, CrashStore.KIND_JAVA));
        planted(new File(baseDir, "zzz-handmade.txt"), 2048, T0 + 10_000L);

        CrashStore.Info[] infos = CrashStore.list(baseDir);
        assertEquals(2, infos.length);
        assertEquals("crash-" + T0 + "-000-java-4242.txt", infos[0].name);
        assertEquals("zzz-handmade.txt", infos[1].name);
        assertEquals("unknown", infos[1].kind);
    }

    @Test
    public void headerIsReadFromTheFirstKilobyte() throws Exception {
        File baseDir = dir();
        CrashStore.DumpContent c = content(T0, 0, CrashStore.KIND_DROPBOX);
        c.header.put(CrashStore.HEADER_ORIGIN, "system_server_anr.txt|9000|1234");
        c.header.put("Source-Name", "system_server_anr.txt");
        // A body far longer than the header window must not cost us the header keys.
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            pad.append("com.example.Frame.dump(Frame.java:1234)\n");
        }
        c.text = pad.toString();
        c.error = null;
        File f = CrashStore.write(baseDir, c);

        CrashStore.Info info = CrashStore.list(baseDir)[0];
        assertEquals("system_server_anr.txt|9000|1234", info.origin);
        assertTrue(f.length() > CrashStore.HEADER_READ_BYTES);
    }

    @Test
    public void resolveRefusesTraversalAndForeignExtensions() throws Exception {
        File baseDir = dir();
        write(baseDir, content(T0, 0, CrashStore.KIND_JAVA));
        assertNotNull(CrashStore.resolve(baseDir, "crash-" + T0 + "-000-java-4242.txt"));
        assertNull(CrashStore.resolve(baseDir, "../configuration.json"));
        assertNull(CrashStore.resolve(baseDir, "..%2Fconfiguration.json"));
        assertNull(CrashStore.resolve(baseDir, "/etc/passwd"));
        assertNull(CrashStore.resolve(baseDir, "crash.txt.evil"));
        assertNull(CrashStore.resolve(baseDir, null));
    }

    @Test
    public void deleteOnlyRemovesAStoredReport() throws Exception {
        File baseDir = dir();
        File f = write(baseDir, content(T0, 0, CrashStore.KIND_JAVA));
        assertTrue(CrashStore.delete(baseDir, f.getName()));
        assertFalse(f.exists());
        assertFalse(CrashStore.delete(baseDir, f.getName()));
    }

    @Test
    public void pruneEvictsPastTheRetentionWindow() throws Exception {
        File baseDir = dir();
        long old = T0 - CrashStore.RETENTION_MS - 1_000L;
        long recent = T0 - CrashStore.RETENTION_MS + 60_000L;
        write(baseDir, content(old, 0, CrashStore.KIND_JAVA));
        write(baseDir, content(recent, 0, CrashStore.KIND_JAVA));
        write(baseDir, content(T0, 0, CrashStore.KIND_JAVA));

        CrashStore.prune(baseDir, T0);

        CrashStore.Info[] infos = CrashStore.list(baseDir);
        assertEquals(2, infos.length);
        assertEquals(T0, infos[0].epochMillis);
        assertEquals(recent, infos[1].epochMillis);
    }

    @Test
    public void pruneHonoursTheByteCapButNeverEmptiesTheStore() throws Exception {
        File baseDir = dir();
        // Just over the cap in one-file-per-step increments, oldest first.
        int steps = (int) (CrashStore.MAX_TOTAL_BYTES / (256L * 1024L)) + 2;
        for (int i = 0; i < steps; i++) {
            planted(new File(baseDir, CrashStore.newName(T0 + i * 1000L, 0,
                    CrashStore.KIND_JAVA, 1)), 256 * 1024, 0L);
        }
        assertTrue(CrashStore.stats(baseDir).totalBytes > CrashStore.MAX_TOTAL_BYTES);

        CrashStore.prune(baseDir, T0 + steps * 1000L);

        CrashStore.Stats after = CrashStore.stats(baseDir);
        assertTrue("store must come back under the cap", after.totalBytes <= CrashStore.MAX_TOTAL_BYTES);
        assertEquals("newest report must survive", T0 + (steps - 1) * 1000L, after.lastAtMs);
        assertTrue(after.count >= 1);
    }

    @Test
    public void pruneNeverThrowsOnABrokenStore() {
        CrashStore.prune(null, T0);
        CrashStore.prune(temp.getRoot(), T0);
        File notADir;
        try {
            notADir = temp.newFile("not-a-directory");
        } catch (IOException e) {
            fail(e.toString());
            return;
        }
        CrashStore.prune(notADir, T0);
        assertNotNull(CrashStore.stats(notADir));
    }

    @Test
    public void writeReportsFailureInsteadOfThrowingSomethingElse() throws Exception {
        File notADir = temp.newFile("blocked");
        try {
            CrashStore.write(notADir, content(T0, 0, CrashStore.KIND_JAVA));
            fail("expected IOException writing into a regular file");
        } catch (IOException expected) {
            // the crash path only needs this to be an exception it can swallow
        }
    }

    @Test
    public void reportCarriesTheTraceCausesAndVersionCode() throws Exception {
        File baseDir = dir();
        CrashStore.DumpContent c = content(T0, 0, CrashStore.KIND_JAVA);
        c.error = new IllegalStateException("outer",
                new NullPointerException("inner cause"));
        c.leadUp = "line one\nline two\n";
        CrashStore.write(baseDir, c);
        String s = read(baseDir, CrashStore.list(baseDir)[0].name);

        assertTrue(s.startsWith(CrashStore.FORMAT_TAG + "\n"));
        // The one field that selects the mapping file that retraces everything below it.
        assertTrue(s.contains("\nVersion-Code: 4242\n"));
        assertTrue(s.contains("Kind: java"));
        assertTrue(s.contains("Exception: java.lang.IllegalStateException"));
        assertTrue(s.contains("java.lang.IllegalStateException: outer"));
        assertTrue(s.contains("Caused by: java.lang.NullPointerException: inner cause"));
        assertTrue(s.contains("---- last log lines ----\nline one\nline two"));
        assertTrue(s.contains("Developer information (JSON):"));
    }

    @Test
    public void headerValuesStayOnOneLine() throws Exception {
        File baseDir = dir();
        CrashStore.DumpContent c = content(T0, 0, CrashStore.KIND_JAVA);
        c.error = new RuntimeException("a\nnewline\nin the message");
        CrashStore.write(baseDir, c);
        String s = read(baseDir, CrashStore.list(baseDir)[0].name);
        assertFalse(s.contains("Message: a\n"));
        assertTrue(s.contains("Exception: java.lang.RuntimeException"));
    }

    @Test
    public void aDeepCauseChainStopsAtTheDepthCapNotByRecursing() throws Exception {
        File baseDir = dir();
        CrashStore.DumpContent c = content(T0, 0, CrashStore.KIND_JAVA);
        Throwable t = new RuntimeException("root of a chain nobody should have to read");
        for (int i = 0; i < 400; i++) {
            t = new RuntimeException("frame " + i, t);
            // No frames, so the byte budget cannot win the race against the depth cap and this
            // test asserts the cap itself rather than whichever limit happened to trip first.
            t.setStackTrace(new StackTraceElement[0]);
        }
        c.error = t;
        CrashStore.write(baseDir, c);
        String s = read(baseDir, CrashStore.list(baseDir)[0].name);

        // Throwable.printStackTrace walks causes recursively; a StackOverflowError raised there
        // would cost the whole report, so the walk is iterative and capped.
        assertTrue(s.contains("java.lang.RuntimeException: frame 399"));
        assertTrue(s.contains("cause chain truncated at "));
        assertTrue(s.contains("Caused by: java.lang.RuntimeException: frame 398"));
    }

    @Test
    public void anEnormousTraceIsByteBounded() throws Exception {
        File baseDir = dir();
        CrashStore.DumpContent c = content(T0, 0, CrashStore.KIND_JAVA);
        RuntimeException t = new RuntimeException("one throwable, far too many frames");
        StackTraceElement[] frames = new StackTraceElement[20_000];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("com.aphex3k.eo1.VeryDeepClass", "frame" + i,
                    "VeryDeepClass.java", i);
        }
        t.setStackTrace(frames);
        c.error = t;
        CrashStore.write(baseDir, c);
        String s = read(baseDir, CrashStore.list(baseDir)[0].name);

        assertTrue(s.contains("stack trace truncated at "));
        assertTrue(s.contains("\"traceTruncated\":true"));
        assertTrue("report must stay near the cap, was " + s.length(),
                s.length() < CrashStore.MAX_DUMP_BYTES + 64 * 1024);
    }

    @Test
    public void sourceFileIsStreamedNotHeldInMemory() throws Exception {
        File baseDir = dir();
        File big = temp.newFile("anr-trace.txt");
        planted(big, 64 * 1024, 0L);

        CrashStore.DumpContent c = content(T0, 0, CrashStore.KIND_ANR);
        c.error = null;
        c.source = big;
        c.header.put(CrashStore.HEADER_ORIGIN, big.getName() + "|65536|0");
        CrashStore.write(baseDir, c);

        CrashStore.Info info = CrashStore.list(baseDir)[0];
        assertEquals(big.getName() + "|65536|0", info.origin);
        assertTrue(read(baseDir, info.name).contains("---- captured output ----"));
    }

    @Test
    public void aSecondDumpInTheSameMillisecondGetsItsOwnName() throws Exception {
        File baseDir = dir();
        CrashStore.DumpContent a = content(T0, 0, CrashStore.KIND_JAVA);
        CrashStore.DumpContent b = content(T0, 0, CrashStore.KIND_JAVA);
        b.error = new RuntimeException("second");
        a.error = new RuntimeException("first");
        File fa = CrashStore.write(baseDir, a);
        File fb = CrashStore.write(baseDir, b);
        assertFalse(fa.getName().equals(fb.getName()));
        assertEquals(2, CrashStore.list(baseDir).length);
    }

    @Test
    public void aHeaderValueCannotBypassTheReportBudget() throws Exception {
        // The header and the JSON trailer are written outside the trace's byte budget, so a value
        // carrying an error body or a long path would land there unbounded — and identity() is
        // where one clamp covers both renderings.
        File baseDir = dir();
        CrashStore.DumpContent c = content(T0, 0, CrashStore.KIND_LOGCAT);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            huge.append("HttpResponse: <html><body>");
        }
        c.header.put("Source-Body", huge.toString());
        c.text = "Fatal signal 11\n";
        CrashStore.write(baseDir, c);

        String s = read(baseDir, CrashStore.list(baseDir)[0].name);
        assertTrue("header must be clamped, report was " + s.length() + " chars",
                s.length() < CrashStore.MAX_HEADER_VALUE * 8L);
        assertTrue(s.contains("chars]"));
    }

    @Test
    public void anExceptionMessageIsClampedInTheHeaderButWholeInTheTrace() throws Exception {
        File baseDir = dir();
        CrashStore.DumpContent c = content(T0, 0, CrashStore.KIND_JAVA);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            huge.append("HttpResponse: <html><body>");
        }
        c.error = new RuntimeException(huge.toString());
        CrashStore.write(baseDir, c);

        String s = read(baseDir, CrashStore.list(baseDir)[0].name);
        String header = s.substring(0, s.indexOf("---- stack trace ----"));
        assertTrue("the one-line header view must stay short",
                header.length() < CrashStore.MAX_HEADER_VALUE * 4L);
        assertTrue(header.contains("chars]"));
        // The trace itself still carries the full message; nothing is lost, only summarised.
        assertTrue(s.indexOf("HttpResponse: <html><body>HttpResponse",
                s.indexOf("---- stack trace ----")) > 0);
    }

    @Test
    public void abandonedTempFilesAreSwept() throws Exception {
        File baseDir = dir();
        File stray = new File(baseDir, CrashStore.newName(T0 - 3_600_000L, 0,
                CrashStore.KIND_JAVA, 1) + ".tmp");
        planted(stray, 4096, T0 - 3_600_000L);
        File inFlight = new File(baseDir, CrashStore.newName(T0, 1, CrashStore.KIND_JAVA, 1) + ".tmp");
        planted(inFlight, 4096, System.currentTimeMillis());

        CrashStore.prune(baseDir, T0);

        assertFalse("a stale .tmp must not strand forever", stray.exists());
        assertTrue("a .tmp that may still be being written must be left alone", inFlight.exists());
    }

    // --- helpers ---

    private static void planted(File f, int bytes, long mtime) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            byte[] buf = new byte[bytes];
            for (int i = 0; i < bytes; i++) {
                buf[i] = (byte) ('a' + (i % 26));
            }
            out.write(buf);
        } finally {
            out.close();
        }
        if (mtime != 0L) {
            f.setLastModified(mtime);
        }
    }

    private static String read(File baseDir, String name) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.InputStream in = new java.io.FileInputStream(new File(baseDir, name));
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
