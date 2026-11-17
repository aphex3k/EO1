package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The store and the shape of one report. Everything here runs on the JVM against a temp directory,
 * which is the point of {@link DiagnosticsReport} being {@code File}-based: the triggers that matter
 * — a reboot rung, a hung main thread — cannot be reached from a test at all, and are proven by hand
 * on a frame instead (see docs/WEBSERVER.md).
 */
public class DiagnosticsReportTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /**
     * A base epoch for anything that is actually stored. Recent by construction: {@code capture}
     * prunes against the real clock at the end of every write, so reports pinned to a fixed year are
     * past retention the moment they land and each test deletes the file it is about to read back.
     */
    private static final long T0 = System.currentTimeMillis() - 3_600_000L;
    /** A fixed epoch, for the two assertions that compare a generated name against a literal one. */
    private static final long NAME_EPOCH = 1_700_000_000_000L;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private File dir() throws IOException {
        return temp.newFolder("reports");
    }

    private DiagnosticsReport.Content content(String trigger, long epochMillis) {
        DiagnosticsReport.Content c = new DiagnosticsReport.Content();
        c.trigger = trigger;
        c.epochMillis = epochMillis;
        c.versionCode = 4242;
        c.reason = "stale 52m";
        c.triggerThread = "eo1-test";
        c.header = new LinkedHashMap<>();
        c.liveness = text("liveness-line\n");
        c.threadDump = text("thread-line\n");
        c.appLog = text("log-line\n");
        c.state = text("{\"a\":1}\n");
        return c;
    }

    private static DiagnosticsReport.Collector text(final String s) {
        return new DiagnosticsReport.Collector() {
            @Override
            public String collect() {
                return s;
            }
        };
    }

    private DiagnosticsReport.Result capture(File baseDir, DiagnosticsReport.Content c) {
        return DiagnosticsReport.capture(baseDir, c);
    }

    private String only(File baseDir) throws IOException {
        DiagnosticsReport.Info[] infos = DiagnosticsReport.list(baseDir);
        assertEquals(1, infos.length);
        return infos[0].name;
    }

    private String body(File baseDir, String name) throws IOException {
        return DiagnosticsReport.renderToString(readDump(baseDir, name));
    }

    private DiagnosticsReport.Dump readDump(File baseDir, String name) throws IOException {
        // Reading back through the store rather than the in-memory Dump, so the listing metadata the
        // page shows is the same metadata the file was written under.
        DiagnosticsReport.Info info = DiagnosticsReport.readMeta(new File(baseDir, name));
        assertNotNull(info);
        DiagnosticsReport.Dump d = new DiagnosticsReport.Dump();
        d.trigger = info.trigger;
        d.versionCode = info.versionCode;
        d.epochMillis = info.epochMillis;
        d.seq = info.seq;
        d.sections = new DiagnosticsReport.Section[0];
        return d;
    }

    @Test
    public void newNameIsFixedWidthAndRoundTripsForEveryTrigger() {
        for (String trigger : new String[]{DiagnosticsReport.TRIGGER_MANUAL,
                DiagnosticsReport.TRIGGER_REBOOT, DiagnosticsReport.TRIGGER_EXHAUSTED}) {
            String n = DiagnosticsReport.newName(NAME_EPOCH, 7, trigger, 20103);
            assertTrue(trigger, n.equals("report-1700000000000-007-" + trigger + "-20103.txt"));
            assertTrue("generated name must pass the shared name guard",
                    UploadedMedia.isSafeName(n));

            DiagnosticsReport.Info parsed = DiagnosticsReport.readMetaNamed(n);
            assertEquals("trigger survives the name for " + trigger, trigger, parsed.trigger);
            assertEquals("version code survives the name for " + trigger, 20103, parsed.versionCode);
        }
    }

    @Test
    public void aHyphenatedTriggerCannotCorruptTheOtherSlots() {
        // The parser finds the trigger by counting hyphens. A token carrying one would push the
        // version code into the trigger's place and every listing would report build 0 — the field
        // that selects the mapping file — so the name builder refuses it instead.
        String n = DiagnosticsReport.newName(NAME_EPOCH, 1, "ladder-exhausted", 20103);
        assertEquals("report-1700000000000-001-manual-20103.txt", n);
        assertFalse(DiagnosticsReport.isTrigger("ladder-exhausted"));
    }

    @Test
    public void resolveRefusesTraversalAndForeignNames() throws IOException {
        File baseDir = dir();
        assertNull(DiagnosticsReport.resolve(baseDir, "../configuration.json"));
        assertNull(DiagnosticsReport.resolve(baseDir, "notes.md"));
        assertNull(DiagnosticsReport.resolve(baseDir, null));
    }

    @Test
    public void captureWritesOneReadableReport() throws IOException {
        File baseDir = dir();
        DiagnosticsReport.Result r = capture(baseDir, content(DiagnosticsReport.TRIGGER_REBOOT, T0));
        assertTrue(r.message, r.ok);
        assertTrue(r.name.endsWith("-reboot-4242.txt"));
        assertTrue(r.bytes > 0L);

        File f = new File(baseDir, r.name);
        assertTrue(f.isFile());
        String text = read(f);
        assertTrue(text.startsWith(DiagnosticsReport.FORMAT_TAG + "\n"));
        for (String key : new String[]{"Trigger: reboot", "Reason: stale 52m",
                "Trigger-Thread: eo1-test", "Mapping: mapping-4242.txt.gz",
                "Section-liveness: ok", "Section-state: ok", "Secrets: none"}) {
            assertTrue("missing " + key, text.contains(key));
        }
        for (String section : new String[]{"1. liveness", "2. thread dump", "3. application log",
                "4. device state"}) {
            assertTrue("missing section " + section, text.contains("---- " + section));
        }
        assertTrue(text.contains("liveness-line"));
        assertTrue(text.contains("log-line"));
        assertTrue("the JSON trailer is what a script parses",
                text.contains("Developer information (JSON):"));
    }

    @Test
    public void bothTriggersProduceTheSameSectionsInTheSameOrder() throws IOException {
        File baseDir = dir();
        String manual = capture(baseDir, content(DiagnosticsReport.TRIGGER_MANUAL, T0)).name;
        String reboot = capture(baseDir, content(DiagnosticsReport.TRIGGER_REBOOT, T0 + 1L)).name;

        String a = sectionTitles(read(new File(baseDir, manual)));
        String b = sectionTitles(read(new File(baseDir, reboot)));
        // Structural, not byte, parity: the two are taken at different instants and say different
        // things. What must never differ is that a reader learns the same four questions either way.
        assertEquals(a, b);
        assertEquals(4, a.split("\n").length);
    }

    @Test
    public void aHungSectionIsAbandonedAndTheFileStillExists() throws IOException {
        File baseDir = dir();
        DiagnosticsReport.Content c = content(DiagnosticsReport.TRIGGER_MANUAL, T0);
        c.stateBudgetMs = 60L;
        c.deadlineMs = 5_000L;
        c.state = new DiagnosticsReport.Collector() {
            @Override
            public String collect() throws InterruptedException {
                Thread.sleep(4_000L);
                return "too late to matter\n";
            }
        };
        DiagnosticsReport.Result r = capture(baseDir, c);
        assertTrue(r.message, r.ok);

        String text = read(new File(baseDir, r.name));
        assertTrue("the abandoned section must say so", text.contains("Section-state: timeout"));
        assertTrue("the hung section must not cost the rest of the report",
                text.contains("liveness-line"));
        assertFalse(text.contains("too late to matter"));
    }

    @Test
    public void anExpiredDeadlineMarksWhatNeverRan() throws IOException {
        File baseDir = dir();
        DiagnosticsReport.Content c = content(DiagnosticsReport.TRIGGER_MANUAL, T0);
        c.deadlineMs = 1L;
        c.threadDumpBudgetMs = 3_000L;
        c.threadDump = new DiagnosticsReport.Collector() {
            @Override
            public String collect() throws InterruptedException {
                Thread.sleep(2_000L);
                return "nope\n";
            }
        };
        DiagnosticsReport.Result r = capture(baseDir, c);
        assertTrue(r.ok);
        String text = read(new File(baseDir, r.name));
        // Section 2 timed out; sections 3 and 4 must then be named as never reached rather than
        // silently missing, or the reader cannot tell "no data" from "no attempt".
        assertTrue(text.contains("not-reached"));
    }

    @Test
    public void aThrowingSectionIsNamedAndDoesNotFailTheCapture() throws IOException {
        File baseDir = dir();
        DiagnosticsReport.Content c = content(DiagnosticsReport.TRIGGER_MANUAL, T0);
        c.liveness = new DiagnosticsReport.Collector() {
            @Override
            public String collect() {
                throw new IllegalStateException("collector blew up");
            }
        };
        DiagnosticsReport.Result r = capture(baseDir, c);
        assertTrue(r.message, r.ok);
        String text = read(new File(baseDir, r.name));
        assertTrue(text.contains("Section-liveness: error"));
        assertTrue(text.contains("collector blew up"));
    }

    @Test
    public void aMissingCollectorSaysUnavailable() throws IOException {
        File baseDir = dir();
        DiagnosticsReport.Content c = content(DiagnosticsReport.TRIGGER_MANUAL, T0);
        c.state = null;
        DiagnosticsReport.Result r = capture(baseDir, c);
        assertTrue(r.ok);
        assertTrue(read(new File(baseDir, r.name)).contains("Section-state: unavailable"));
    }

    @Test
    public void sectionTextPastTheCapIsMarkedNotSilentlyCut() throws IOException {
        File baseDir = dir();
        final StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            big.append("0123456789012345678901234567890123456789012345678901234567890123456789\n");
        }
        DiagnosticsReport.Content c = content(DiagnosticsReport.TRIGGER_MANUAL, T0);
        c.state = text(big.toString());
        DiagnosticsReport.Result r = capture(baseDir, c);
        assertTrue(r.ok);
        String text = read(new File(baseDir, r.name));
        assertTrue("a truncated section must say it was", text.contains("truncated at"));
        assertTrue("and must not blow the file past the cap by much",
                new File(baseDir, r.name).length() < 4L * DiagnosticsReport.MAX_SECTION_BYTES);
    }

    @Test
    public void captureIsUnbotheredByAnUnwritableDirectory() throws IOException {
        // A parent that is a regular file, so the store genuinely cannot be created. A merely absent
        // directory would be no test at all: capture makes one on the way to writing.
        File blocker = new File(temp.getRoot(), "not-a-directory");
        write(blocker, "x\n");
        File nope = new File(blocker, "reports");
        DiagnosticsReport.Result r = capture(nope,
                content(DiagnosticsReport.TRIGGER_MANUAL, T0));
        // The reboot path cannot do anything about a full disk; it can only not be stopped by one.
        assertFalse(r.ok);
        assertNotNull(r.message);
    }

    @Test
    public void theNewestNonManualReportSurvivesAnyNumberOfManualOnes() throws IOException {
        File baseDir = dir();
        capture(baseDir, content(DiagnosticsReport.TRIGGER_REBOOT, T0));
        for (int i = 0; i < DiagnosticsReport.MAX_REPORTS + 5; i++) {
            capture(baseDir, content(DiagnosticsReport.TRIGGER_MANUAL, T0 + 1_000L * (i + 1)));
        }
        boolean rebootSurvived = false;
        for (DiagnosticsReport.Info info : DiagnosticsReport.list(baseDir)) {
            if (DiagnosticsReport.TRIGGER_REBOOT.equals(info.trigger)) {
                rebootSurvived = true;
            }
        }
        assertTrue("pressing the button must never delete the evidence of a self-reboot",
                rebootSurvived);
        assertTrue("but the count cap still applies",
                DiagnosticsReport.list(baseDir).length <= DiagnosticsReport.MAX_REPORTS);
    }

    @Test
    public void agedReportsGoAndPruneStillReports() throws IOException {
        File baseDir = dir();
        capture(baseDir, content(DiagnosticsReport.TRIGGER_MANUAL, T0 - DiagnosticsReport.RETENTION_MS - 1L));
        DiagnosticsReport.Stats s = DiagnosticsReport.prune(baseDir, System.currentTimeMillis());
        assertEquals("a report past retention is evicted even when it is the newest", 0, s.count);
    }

    @Test
    public void pruneOnNothingSucceedsQuietly() throws IOException {
        File empty = temp.newFolder("empty");
        assertNotNull(DiagnosticsReport.prune(empty, System.currentTimeMillis()));
        DiagnosticsReport.prune(null, System.currentTimeMillis());
    }

    @Test
    public void tempFilesFromADeadWriteAreSweptButInFlightOnesAreNot() throws IOException {
        File baseDir = dir();
        File stale = new File(baseDir, "report-1600000000000-000-manual-1.txt.tmp");
        File fresh = new File(baseDir, "report-1600000000001-000-manual-1.txt.tmp");
        touch(stale, System.currentTimeMillis() - 3_600_000L);
        touch(fresh, System.currentTimeMillis());
        DiagnosticsReport.cleanTempFiles(baseDir);
        assertFalse(stale.exists());
        assertTrue("a .tmp younger than the sweep window may be a write in flight", fresh.exists());
    }

    /**
     * Both halves present, oldest first, and the budget respected at both edges.
     *
     * <p>The shape of this test changed with {@code tailLog} and its purpose did not. It used to ask
     * for 4 lines of a 3-line live file and a 2-line rotated one and assert {@code live-1} appeared;
     * a request that the live half could satisfy in full is precisely the case the old code short
     * circuited on, so under a split budget {@code live-1} is now outside the quota and cannot be in
     * it. The line is longer by one on each file for the same reason: with 2 lines per half there is a
     * boundary to bite on both sides rather than one.
     */
    @Test
    public void theLogTailReadsBothHalvesOldestFirst() throws IOException {
        File rotated = temp.newFile("eo1-app.log.1");
        File live = temp.newFile("eo1-app.log");
        write(rotated, "rotated-1\nrotated-2\nrotated-3\n");
        write(live, "live-1\nlive-2\nlive-3\nlive-4\n");
        String tail = DiagnosticsReport.tailLog(live, rotated, 4);
        int r1 = tail.indexOf("rotated-3");
        int l1 = tail.indexOf("live-3");
        assertTrue("both halves must be present", r1 >= 0 && l1 >= 0);
        assertTrue("and in order", r1 < l1);
        assertTrue(tail.contains("oldest half"));
        assertFalse("one line past the rotated share must not be dragged in",
                tail.contains("rotated-1"));
        assertFalse("nor one line past the live share", tail.contains("live-2"));
    }

    @Test
    public void theLogTailCountsLinesRatherThanBytes() throws IOException {
        File live = temp.newFile("eo1-app.log.2");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("line ").append(i).append(" x".repeat(200)).append('\n');
        }
        write(live, sb.toString());
        String tail = DiagnosticsReport.tailLog(live, null, 10);
        // Eleven physical lines: the ten asked for plus the one coverage line the section now opens
        // with. The budget is still lines, not bytes — 500 lines of 210 bytes each, of which ten are
        // returned.
        assertEquals("exactly the last ten lines, under a single header line",
                11, tail.split("\n").length);
        assertTrue(tail.contains("line 499"));
        assertFalse(tail.contains("line 489"));
    }

    @Test
    public void theReportCarriesNoSecretShapedValues() throws IOException {
        File baseDir = dir();
        DiagnosticsReport.Content c = content(DiagnosticsReport.TRIGGER_MANUAL, T0);
        // The collectors a real capture installs are the ones that could leak; this asserts the
        // invariant on the rendered text so a future section cannot quietly break it.
        c.state = text("{\"network\":{\"ip\":\"192.168.1.80\"},\"watchdog\":{\"level\":5}}\n");
        String text = read(new File(baseDir, capture(baseDir, c).name));
        assertFalse(text.contains("password"));
        assertFalse(text.contains("passwd"));
        assertFalse("an Immich session JWT must never appear", text.contains("eyJ"));
        assertFalse(text.contains("trusted_network_token"));
    }

    @Test
    public void lastRebootDistinguishesAnAnswerFromSilence() throws IOException {
        File baseDir = dir();
        long boot = T0 + 600_000L;
        assertTrue(DiagnosticsReport.lastReboot(baseDir, boot)
                .contains("no app-requested reboot precedes this boot"));

        capture(baseDir, content(DiagnosticsReport.TRIGGER_REBOOT, T0));
        String answer = DiagnosticsReport.lastReboot(baseDir, boot);
        assertTrue(answer, answer.contains("app-requested at"));
        assertTrue(answer, answer.contains("600s later"));

        // A reboot request from a *later* boot is not evidence about this one.
        capture(baseDir, content(DiagnosticsReport.TRIGGER_REBOOT, boot + 60_000L));
        assertTrue(DiagnosticsReport.lastReboot(baseDir, boot).contains("app-requested at"));
    }

    @Test
    public void aRefusedRebootCommandIsNotCreditedWithTheBootAfterIt() throws IOException {
        File baseDir = dir();
        DiagnosticsReport.Result r =
                capture(baseDir, content(DiagnosticsReport.TRIGGER_REBOOT, T0));
        long laterBoot = T0 + 600_000L;
        assertTrue("with nothing recorded, adjacency is the only evidence there is",
                DiagnosticsReport.lastReboot(baseDir, laterBoot).contains("600s later"));

        DiagnosticsReport.recordDispatchOutcome(baseDir, r.name, "UNREACHABLE (SocketTimeoutException)");

        String answer = DiagnosticsReport.lastReboot(baseDir, laterBoot);
        assertTrue(answer, answer.contains("but the command was refused"));
        assertTrue(answer, answer.contains("SocketTimeoutException"));
        assertFalse("a refused command cannot have caused the boot that followed it",
                answer.contains("600s later"));
        assertEquals("UNREACHABLE (SocketTimeoutException)",
                DiagnosticsReport.refusedDispatch(baseDir));
        assertTrue(DiagnosticsReport.statsJson(baseDir).toString()
                .contains("\"lastDispatch\":\"UNREACHABLE (SocketTimeoutException)\""));

        // The note is a record, not a report: it must not reach a listing, a route or the prune pass.
        assertEquals(1, DiagnosticsReport.list(baseDir).length);
        assertNull(DiagnosticsReport.resolve(baseDir, r.name + ".outcome"));
        assertTrue(DiagnosticsReport.delete(baseDir, r.name));
        assertFalse("deleting a report takes its note with it",
                new File(baseDir, r.name + ".outcome").exists());
    }

    @Test
    public void theStoreSummarisesWithoutOpeningABody() throws IOException {
        File baseDir = dir();
        capture(baseDir, content(DiagnosticsReport.TRIGGER_EXHAUSTED, T0));
        DiagnosticsReport.Stats s = DiagnosticsReport.stats(baseDir);
        assertEquals(1, s.count);
        assertEquals(DiagnosticsReport.TRIGGER_EXHAUSTED, s.lastTrigger);
        assertEquals(T0, s.lastAtMs);
        assertTrue(s.totalBytes > 0L);
        assertTrue(DiagnosticsReport.statsJson(baseDir).toString().contains("latestIsReboot"));
    }

    @Test
    public void namesThatAreNotReportsAreStillListed() throws IOException {
        File baseDir = dir();
        touch(new File(baseDir, "hand-planted.txt"), T0);
        DiagnosticsReport.Info[] infos = DiagnosticsReport.list(baseDir);
        assertEquals(1, infos.length);
        assertEquals("unknown", infos[0].trigger);
        assertFalse("a foreign name must sort last, which parsed==false encodes", infos[0].parsed);
    }

    // ------------------------------------------------------------------ the log window (part B)

    /**
     * The hardware-proven defect. Report {@code report-1794788472918-000-manual-19.txt} on frame
     * 192.168.1.114 carried exactly {@code APP_LOG_LINES} lines, spanned 26&nbsp;minutes
     * ({@code 15:55:38 -> 16:21:10}) and contained no {@code oldest half} marker: the live file filled
     * the quota, so the {@code given >= want} test closed the rotated half and the three
     * {@code watchdog: main thread ran nothing for 2 ticks in a row} lines from 09:18–09:24 were
     * unreachable through every route the app has. Fails on the pre-split code.
     */
    @Test
    public void aFullLiveHalfStillOpensTheRotatedHalf() throws IOException {
        File rotated = temp.newFile("eo1-app.log.1");
        File live = temp.newFile("eo1-app.log");
        write(rotated, lines("rotated", 900));
        write(live, lines("live", 900));

        String tail = DiagnosticsReport.tailLog(live, rotated, DiagnosticsReport.APP_LOG_LINES);

        assertTrue("the newest line the live half holds is the newest there is",
                tail.contains("live-0899"));
        assertTrue("and a line from inside the rotated half must be present even though the live "
                        + "half alone could have filled the whole budget",
                tail.contains("rotated-0800"));
        assertFalse("nothing older than the rotated share may be dragged in",
                tail.contains("rotated-0001"));
        assertFalse("and the live share is a share, not the whole file",
                tail.contains("live-0699"));
        assertTrue("the marker is how a reader learns which half a line came from",
                tail.contains("oldest half"));
        assertTrue(tail.contains("newest half"));
    }

    @Test
    public void theBudgetIsSplitAcrossTheTwoHalves() throws IOException {
        File rotated = temp.newFile("eo1-app.log.1");
        File live = temp.newFile("eo1-app.log");
        write(rotated, lines("rotated", 900));
        write(live, lines("live", 900));

        int n = DiagnosticsReport.APP_LOG_LINES;
        String tail = DiagnosticsReport.tailLog(live, rotated, n);
        String expected = LogWindow.liveShare(n, true) + " newest half + "
                + LogWindow.rotatedShare(n, true) + " oldest half";
        assertTrue("the coverage line must state the split it used, computed from the constant: "
                + tail, tail.contains(expected));
    }

    /**
     * The byte-budget guarantee. {@code BudgetedWriter} keeps a section's head and drops its tail, so
     * a widened line count here would silently delete the <b>newest</b> log lines from a report — the
     * opposite of what the change is for. Only the section's age may grow, never its size.
     */
    @Test
    public void theTotalLinesShownNeverExceedsTheBudget() throws IOException {
        int[][] shapes = {{900, 900}, {10, 900}, {900, 10}, {3, 3}, {0, 900}, {900, 0}};
        int n = DiagnosticsReport.APP_LOG_LINES;
        for (int k = 0; k < shapes.length; k++) {
            // Distinct names per shape: TemporaryFolder refuses a second newFile with the same name,
            // and a stale file from the previous round would quietly make this a test of one shape.
            File rotated = new File(temp.getRoot(), "shape" + k + "-old.log.1");
            File live = new File(temp.getRoot(), "shape" + k + "-live.log");
            write(rotated, lines("rotated", shapes[k][0]));
            write(live, lines("live", shapes[k][1]));
            int body = bodyLines(DiagnosticsReport.tailLog(live, rotated, n));
            assertTrue("live " + shapes[k][1] + " / rotated " + shapes[k][0] + " showed " + body
                    + " log lines against a budget of " + n, body <= n);
        }
    }

    @Test
    public void aShortLiveHalfGivesItsRemainderToTheOlderHalf() throws IOException {
        File rotated = temp.newFile("eo1-app.log.1");
        File live = temp.newFile("eo1-app.log");
        write(rotated, lines("rotated", 900));
        write(live, lines("live", 10));

        int n = DiagnosticsReport.APP_LOG_LINES;
        String tail = DiagnosticsReport.tailLog(live, rotated, n);

        assertTrue("every line a short live half has must be shown", tail.contains("live-0000"));
        assertTrue(tail.contains("live-0009"));
        assertEquals("and the budget must be filled from the older half, not left short",
                n, bodyLines(tail));
        // Counted by the marker's own bracket form, not by the phrase: the coverage line above the body
        // legitimately says "200 newest half + 200 oldest half" in prose, and a substring search for
        // "oldest half" counts that as a second marker. The thing being pinned is one discontinuity in
        // the body, which only the marker is.
        assertTrue("one oldest-half marker, however many lines it carried — and one is not zero",
                tail.indexOf("[--- oldest half:") >= 0
                        && tail.indexOf("[--- oldest half:") == tail.lastIndexOf("[--- oldest half:"));
        assertTrue("a half that ran out is stated as a fact, not estimated",
                tail.contains("whole file, 10 lines"));
    }

    @Test
    public void aFrameThatHasNeverRotatedSaysItsRecordIsOneHalf() throws IOException {
        File live = temp.newFile("eo1-app.log");
        write(live, lines("live", 30));
        String tail = DiagnosticsReport.tailLog(live, null, DiagnosticsReport.APP_LOG_LINES);
        assertTrue(tail, tail.contains("no rotated half on disk"));
        assertFalse("no marker for a half that was never opened", tail.contains("oldest half"));
        assertTrue("the whole budget went to the live file, so it must say what it holds — and it holds "
                + "30 lines, which is fewer than were asked for, not a budget-sized record",
                tail.contains("all 30 of its lines are shown"));
    }

    @Test
    public void theTailNamesTheWindowItCovered() throws IOException {
        File live = temp.newFile("eo1-app.log");
        // 60 lines at 26-second spacing is a 26-minute window, which is the number the hardware
        // produced and no report ever named.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append(stampAt(i * 26L)).append(" I [EO1] msg ").append(i).append('\n');
        }
        write(live, sb.toString());

        String tail = DiagnosticsReport.tailLog(live, null, 400);
        String first = tail.substring(0, tail.indexOf('\n'));
        assertTrue("the section must open with its own window: " + first,
                first.startsWith("[log window: "));
        assertTrue(first, first.contains("2026-11-15 00:00:00"));
        assertTrue(first, first.contains("2026-11-15 00:25:34"));
        assertTrue(first, first.contains(" -> "));
    }

    @Test
    public void aWindowSpanningBothHalvesNamesBothEndpoints() throws IOException {
        File rotated = temp.newFile("eo1-app.log.1");
        File live = temp.newFile("eo1-app.log");
        int n = DiagnosticsReport.APP_LOG_LINES;
        // Rotated first (older), live second: stamps continue across the boundary, 400 lines each at
        // one per second, so the shown window begins at the rotated share's first line.
        write(rotated, stampedLines(0L, n));
        write(live, stampedLines((long) n, n));

        String tail = DiagnosticsReport.tailLog(live, rotated, n);
        String first = tail.substring(0, tail.indexOf('\n'));
        int liveShare = LogWindow.liveShare(n, true);
        String earliest = stampAt((long) n - liveShare);
        String latest = stampAt(2L * n - 1L);
        assertTrue("the window statement cannot be merely the live half's span: " + first,
                first.contains(earliest + " -> " + latest));
        assertFalse("and it must not claim a line it did not read",
                first.contains(stampAt(0L)));
    }

    @Test
    public void anUndatedTailSaysItIsUndated() throws IOException {
        File live = temp.newFile("eo1-app.log");
        write(live, lines("plain", 20));
        String tail = DiagnosticsReport.tailLog(live, null, 400);
        assertTrue(tail, tail.contains("undated (no timestamped line in the shown text)"));
    }

    @Test
    public void aStackContinuationDoesNotBecomeATimestamp() throws IOException {
        // tail() cuts at a byte boundary chosen by counting newlines backwards, so a section almost
        // always starts mid-trace. A stamp search that assumed line 1 was a stamp would either print
        // garbage or call the whole window undated.
        File live = temp.newFile("eo1-app.log");
        StringBuilder sb = new StringBuilder();
        sb.append(stampAt(0L)).append(" E [EO1] java.lang.RuntimeException: boom\n");
        sb.append("\tat com.foo.Bar(Bar.java:41)\n");
        sb.append("Caused by: java.io.IOException: disk\n");
        sb.append("\tat com.foo.Baz(Baz.java:7)\n");
        sb.append(stampAt(60L)).append(" I [EO1] recovered\n");
        write(live, sb.toString());

        String tail = DiagnosticsReport.tailLog(live, null, 400);
        assertTrue(tail.contains("2026-11-15 00:00:00 -> 2026-11-15 00:01:00"));
        assertFalse("a trace line is not a stamp", tail.contains("window: \tat"));

        String trace = "\tat com.foo.Bar(Bar.java:41)\n";
        assertEquals("", LogWindow.firstStamp(trace));
        assertEquals("", LogWindow.lastStamp(trace));
    }

    @Test
    public void theCoverageLineSurvivesAByteTruncatedSection() {
        // The full path (collect() -> BudgetedWriter) needs the capture thread and a 128 KiB body to
        // prove, which is a test that waits. What that path cannot lose is what this asserts instead:
        // the sentence is a single physical line, and it is the first thing in the section, so
        // keep-the-head/drop-the-tail can only ever cost it nothing.
        String line = LogWindow.coverageLine(
                new LogWindow.Half(200, 200, 83_148L, 20_400),
                new LogWindow.Half(200, 200, 86_016L, 20_400),
                "2026-11-15 15:55:38", "2026-11-15 16:21:10");
        assertEquals("one line, ending in it", 1, occurrences(line, '\n'));
        assertTrue(line.endsWith("]\n"));

        File live = new File(temp.getRoot(), "never-written.log");
        String tail = DiagnosticsReport.tailLog(live, null, 10);
        assertEquals("index 0 of the section, before any marker or log line", 0,
                tail.indexOf("[log window: "));
        assertEquals("and the whole sentence is that first line", 1,
                occurrences(tail.substring(0, tail.indexOf('\n') + 1), '\n'));
    }

    @Test
    public void noLogFilesOnDiskStillNamesItsWindow() {
        File missing = new File(temp.getRoot(), "absent.log");
        File alsoMissing = new File(temp.getRoot(), "absent.log.1");
        String tail = DiagnosticsReport.tailLog(missing, alsoMissing, 400);
        assertTrue(tail, tail.contains("nothing on disk"));
        assertTrue(tail, tail.startsWith("[log window: "));
        assertTrue("the old sentinel text is kept for anyone parsing it",
                tail.contains("(no log lines on disk)\n"));
        assertEquals(2, occurrences(tail, '\n'));
    }

    // ------------------------------------------------------------------ helpers

    private static String sectionTitles(String report) {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (true) {
            int at = report.indexOf("---- ", pos);
            if (at < 0) {
                return sb.toString();
            }
            int end = report.indexOf(" ----", at);
            if (end < 0) {
                return sb.toString();
            }
            String title = report.substring(at + 5, end);
            // Each line carries its own outcome and elapsed time past the title. This comparison is
            // about which questions a report asks, so the clock has to come off — otherwise two
            // identical reports differ because one section took a millisecond more.
            int bracket = title.indexOf(" [");
            if (bracket >= 0) {
                title = title.substring(0, bracket);
            }
            if (title.charAt(0) >= '1' && title.charAt(0) <= '9') {
                sb.append(title).append('\n');
            }
            pos = end + 5;
        }
    }

    private static String read(File f) throws IOException {
        byte[] all = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < all.length) {
                int n = in.read(all, off, all.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        } finally {
            in.close();
        }
        return new String(all, 0, all.length, UTF_8);
    }

    /** {@code prefix-0000 … prefix-(count-1)}, one per line, zero-padded so no line is another's prefix. */
    private static String lines(String prefix, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(prefix).append('-')
              .append(String.format(Locale.US, "%04d", i)).append('\n');
        }
        return sb.toString();
    }

    /** {@code count} lines in the exact shape {@code AppLogger} writes, one second apart. */
    private static String stampedLines(long firstSecond, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(stampAt(firstSecond + i)).append(" I [EO1] msg ").append(i).append('\n');
        }
        return sb.toString();
    }

    /**
     * A stamp {@code seconds} after midnight on 2026-11-15, built by arithmetic rather than by
     * {@code SimpleDateFormat}: the format is what {@code firstStamp} recognises, but the instant
     * behind it must not depend on the test host's zone or on a DST boundary in the middle of the
     * window a test is asserting on.
     */
    private static String stampAt(long seconds) {
        return String.format(Locale.US, "2026-11-15 %02d:%02d:%02d",
                seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L);
    }

    /**
     * Log lines in a {@code tailLog} result — the physical lines that came from a log file, with the
     * coverage line, the two half markers and the two "nothing here" sentinels taken back out. This
     * is the number the section's budget is about.
     */
    private static int bodyLines(String tail) {
        int total = 0;
        for (int i = 0; i < tail.length(); i++) {
            if (tail.charAt(i) == '\n') {
                total++;
            }
        }
        total--;                                   // the coverage line, always present, always first
        if (tail.contains("[--- oldest half:")) total--;
        if (tail.contains("[--- newest half:")) total--;
        if (tail.contains("(no log lines on disk)")) total--;
        if (tail.contains("(no log lines requested)")) total--;
        return Math.max(0, total);
    }

    private static int occurrences(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static void write(File f, String s) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), UTF_8);
        try {
            w.write(s);
        } finally {
            w.close();
        }
    }

    private static void touch(File f, long modified) throws IOException {
        if (!f.getParentFile().isDirectory()) {
            f.getParentFile().mkdirs();
        }
        write(f, "x\n");
        if (!f.setLastModified(modified)) {
            // Filesystem granularity, not a failure of the code under test.
        }
    }
}
