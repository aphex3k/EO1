package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The split and the sentence, with no files and no framework in sight.
 *
 * <p>{@code DiagnosticsReportTest} exercises these through real temp files; this class pins them as
 * truth tables, because both are decisions and a decision that only appears inside file IO gets
 * re-broken by the next person who tidies {@code tailLog}. The defect they exist to prevent is
 * hardware-proven: report {@code report-1794788472918-000-manual-19.txt} carried 400 lines covering
 * 26 minutes and never opened the rotated half beside it, so three watchdog restarts from 09:18–09:24
 * were unanswerable — and the report said nothing that would tell a reader it was 26 minutes rather
 * than everything.
 */
public class LogWindowTest {

    private static final LogWindow.Half ABSENT = new LogWindow.Half(0, 0, 0L, 0);

    @Test
    public void theBudgetIsSplitInHalfWhenBothHalvesExist() {
        assertEquals(200, LogWindow.liveShare(400, true));
        assertEquals(200, LogWindow.rotatedShare(400, true));
        assertEquals(DiagnosticsReport.APP_LOG_LINES / 2, LogWindow.liveShare(DiagnosticsReport.APP_LOG_LINES, true));
    }

    @Test
    public void theWholeBudgetGoesToTheLiveHalfWithoutARotatedFile() {
        assertEquals(400, LogWindow.liveShare(400, false));
        assertEquals(0, LogWindow.rotatedShare(400, false));
    }

    @Test
    public void aOneLineBudgetIsNotSplit() {
        // One line is the newest line. Splitting a budget of 1 would ask both files for a fraction
        // of a line and report a window of nothing.
        assertEquals(1, LogWindow.liveShare(1, true));
        assertEquals(0, LogWindow.rotatedShare(1, true));
    }

    @Test
    public void theSharesAlwaysAddUpToTheBudget() {
        for (int n = 0; n <= 1000; n++) {
            for (boolean rotated : new boolean[]{false, true}) {
                int live = LogWindow.liveShare(n, rotated);
                int old = LogWindow.rotatedShare(n, rotated);
                assertEquals("shares must sum to the budget for n=" + n + " rotated=" + rotated,
                        Math.max(0, n), live + old);
                assertTrue("the live half never gets less than nothing", live >= 0);
                assertTrue("and the older half never fills a budget that is not there", old >= 0);
                if (n < 2) {
                    assertEquals("a budget below 2 is not split", Math.max(0, n), live);
                } else if (rotated) {
                    assertTrue("with a rotated present the older half must get at least a third",
                            old >= n / 3);
                } else {
                    assertEquals(n, live);
                }
            }
        }
        assertEquals("a negative budget is nothing, not an exception", 0, LogWindow.liveShare(-5, true));
        assertEquals(0, LogWindow.rotatedShare(-5, true));
    }

    @Test
    public void aHalfThatShowsFewerLinesThanAskedIsTheWholeFile() {
        LogWindow.Half h = new LogWindow.Half(200, 12, 1224L, 1224);
        assertTrue("12 lines from a file asked for 200 means the file ran out", h.wholeFileFitted());
    }

    @Test
    public void aHalfThatFillsItsRequestMayHoldMore() {
        LogWindow.Half h = new LogWindow.Half(200, 200, 800_000L, 20_400);
        assertFalse("a half that filled its quota says nothing about what is behind it",
                h.wholeFileFitted());
    }

    @Test
    public void theApproximateLineCountIsLabelled() {
        LogWindow.Half h = new LogWindow.Half(200, 200, 83_148L, 20_400);
        String approx = h.approximateLinesOnDisk();
        assertTrue("an estimate must be printed as one: " + approx, approx.startsWith("~"));
        assertEquals("~815", approx);
    }

    @Test
    public void anEstimateWithNothingToShowRefusesToGuess() {
        // "0" would read as an empty file; "unknown" reads as the byte arithmetic that it is.
        assertEquals("unknown", ABSENT.approximateLinesOnDisk());
        assertEquals("unknown", new LogWindow.Half(200, 0, 4096L, 0).approximateLinesOnDisk());
    }

    @Test
    public void theCoverageLineFitsOnOnePhysicalLine() {
        // BudgetedWriter keeps the head of a section and drops its tail; a multi-line coverage clause
        // would therefore be the part that survives, and the part that gets dropped would be the
        // newest log lines the sentence exists to contextualise.
        String one = LogWindow.coverageLine(
                new LogWindow.Half(200, 200, 83_148L, 20_400),
                new LogWindow.Half(200, 200, 86_016L, 20_400),
                "2026-11-15 15:55:38", "2026-11-15 16:21:10");
        assertEquals("exactly one newline, and last: " + one, one.length() - 1, one.lastIndexOf('\n'));
        assertEquals(1, newLineCount(one));
        assertTrue(one, one.startsWith("[log window: "));
        assertTrue(one, one.endsWith("]\n"));
    }

    @Test
    public void theCoverageLineNamesBothHalvesAndTheRetentionCeiling() {
        String line = LogWindow.coverageLine(
                new LogWindow.Half(200, 200, 83_148L, 20_400),
                new LogWindow.Half(200, 200, 86_016L, 20_400),
                "2026-11-15 15:55:38", "2026-11-15 16:21:10");
        assertTrue(line, line.contains("2026-11-15 15:55:38 -> 2026-11-15 16:21:10"));
        assertTrue(line, line.contains("shown 400 lines (200 newest half + 200 oldest half)"));
        assertTrue(line, line.contains("of live ~815, rotated ~843"));
        assertTrue("the ceiling must be stated, or a reader assumes the file is the history",
                line.contains("retention is 2 halves x 262144 bytes"));
        assertTrue(line, line.contains("everything older was rotated away with no trace"));
    }

    @Test
    public void aWholeFileHalfSaysSoInsteadOfEstimating() {
        String line = LogWindow.coverageLine(
                new LogWindow.Half(200, 10, 1_020L, 1_020),
                new LogWindow.Half(200, 200, 86_016L, 20_400),
                "2026-11-15 09:00:00", "2026-11-15 16:21:10");
        assertTrue(line, line.contains("of live (whole file, 10 lines)"));
        assertFalse("an approximation of a file that was read whole is a rounding of a known number",
                line.contains("of live ~"));
        assertTrue("the other half is still an estimate, and still labelled as one",
                line.contains("rotated ~843"));
    }

    /**
     * A frame that has never rotated keeps its whole record in the live file — and on a flooded frame
     * that file is several times fuller than the section's 400-line budget. The first version of this
     * sentence printed the budget as the record ("so 400 lines is the whole record it keeps"), which is
     * the same class of error this class exists to remove: a true statement about the files used to
     * imply a false one about their contents. The fixture is a 160 KiB live half holding ~1,600 lines.
     */
    @Test
    public void aFrameThatNeverRotatedStatesTheFileNotTheBudget() {
        String line = LogWindow.coverageLine(
                new LogWindow.Half(400, 400, 163_840L, 40_776), ABSENT,
                "2026-11-15 15:55:38", "2026-11-15 16:21:10");
        assertTrue(line, line.contains("no rotated half on disk"));
        assertTrue("the live half's own size, not the number of lines this section happened to show",
                line.contains("it holds about 1622 lines and the newest 400 are shown"));
        assertFalse("the budget must never be printed as the record's size",
                line.contains("400 lines is the whole record"));
        assertTrue(line, line.contains("only the live half exists on this frame so far"));
        assertFalse(line, line.contains("everything older was rotated away"));
    }

    @Test
    public void aNeverRotatedFrameThatFitsSaysAllItsLinesAreShown() {
        String line = LogWindow.coverageLine(
                new LogWindow.Half(400, 12, 1_224L, 1_224), ABSENT,
                "2026-11-15 15:55:38", "2026-11-15 15:56:10");
        assertTrue(line, line.contains("no rotated half on disk"));
        assertTrue(line, line.contains("all 12 of its lines are shown"));
        assertFalse("a file that was read whole is a count, not an estimate",
                line.contains("about"));
    }

    @Test
    public void anUndatedTailAdmitsItIsUndated() {
        String line = LogWindow.coverageLine(
                new LogWindow.Half(200, 200, 83_148L, 20_400),
                new LogWindow.Half(200, 200, 86_016L, 20_400), "", "");
        assertTrue(line, line.contains("undated (no timestamped line in the shown text)"));
    }

    @Test
    public void aSingleLineNamesOneInstantNotARange() {
        String line = LogWindow.coverageLine(
                new LogWindow.Half(1, 1, 102L, 102), ABSENT, "2026-11-15 16:21:10",
                "2026-11-15 16:21:10");
        assertTrue(line, line.contains("2026-11-15 16:21:10 (single line)"));
        assertFalse(line, line.contains(" -> "));
    }

    /**
     * The stamps agree because {@code AppLogger} resolves to the second, not because one line was
     * shown: the brightness triple alone puts three rows inside one timestamp, and a crash burst can
     * put thirty. The first version decided this case from the two stamps and called any window like
     * it a single line — a true observation implying a false count, which is the defect class of this
     * whole file.
     */
    @Test
    public void aWindowWhoseEndsShareOneSecondCountsItsLinesInsteadOfGuessing() {
        String line = LogWindow.coverageLine(
                new LogWindow.Half(400, 12, 1_224L, 1_224), ABSENT,
                "2026-11-15 16:21:10", "2026-11-15 16:21:10");
        assertTrue(line, line.contains("(same second, 12 lines)"));
        assertFalse("twelve lines are not one line", line.contains("(single line)"));
    }

    /**
     * Fewer lines than asked does not alone prove the file was read whole: {@code tail} also stops at
     * the section's byte ceiling. A half that hit the ceiling must fall through to the labelled
     * estimate rather than claiming an exact whole-file count for a file it only partly read.
     */
    @Test
    public void aHalfCutOffByTheByteCeilingIsNotClaimedAsTheWholeFile() {
        LogWindow.Half ceiled = new LogWindow.Half(400, 300, 262_144L, 131_072);
        assertFalse("300 lines of a 256 KiB file is a byte ceiling, not an exhausted file",
                ceiled.wholeFileFitted());
        String line = LogWindow.coverageLine(
                ceiled, new LogWindow.Half(400, 400, 262_144L, 131_072),
                "2026-11-15 09:00:00", "2026-11-15 16:21:10");
        assertTrue(line, line.contains("of live ~"));
        assertFalse(line, line.contains("of live (whole file"));
    }

    @Test
    public void nothingOnDiskIsStillALine() {
        String line = LogWindow.coverageLine(ABSENT, ABSENT, "", "");
        assertTrue(line, line.contains("nothing on disk"));
        assertTrue(line, line.startsWith("[log window: "));
        assertEquals(1, newLineCount(line));
        // A file that exists and is empty is not a file that does not: the sentence cannot tell the
        // two apart from File.length() alone, so it claims neither.
        assertTrue(LogWindow.coverageLine(new LogWindow.Half(200, 0, 0L, 0), ABSENT, "", "")
                .contains("has any bytes"));
        assertTrue(LogWindow.coverageLine(new LogWindow.Half(200, 0, 4096L, 0), ABSENT, "", "")
                .contains("holds no complete line"));
    }

    @Test
    public void stampsAreRecognisedStructurally() {
        String text = "2026-11-15 15:55:38 I [EO1] first\n"
                + "2026-11-15 16:21:10 I [EO1] last\n";
        assertEquals("2026-11-15 15:55:38", LogWindow.firstStamp(text));
        assertEquals("2026-11-15 16:21:10", LogWindow.lastStamp(text));
    }

    @Test
    public void aNonTimestampedLineIsNotAStamp() {
        // The line-1 assumption is the one that fails on a real tail: the slice starts at a byte
        // boundary chosen by counting newlines backwards, so the first line is very often the middle
        // of a stack trace.
        String text = "\tat com.foo.Bar(Bar.java:41)\n"
                + "2026-11-15 15:55:38 I [EO1] after the trace\n"
                + "Caused by: java.io.IOException\n";
        assertEquals("2026-11-15 15:55:38", LogWindow.firstStamp(text));
        assertEquals("2026-11-15 15:55:38", LogWindow.lastStamp(text));
        assertEquals("", LogWindow.firstStamp("no stamps here at all\n"));
        assertEquals("", LogWindow.lastStamp("no stamps here at all\n"));
        assertEquals("", LogWindow.firstStamp("2026-11-15 15:55:3"));
        assertEquals("", LogWindow.lastStamp("x2026-11-15 15:55:38"));
        assertEquals("", LogWindow.firstStamp(null));
        assertEquals("", LogWindow.lastStamp(null));
    }

    @Test
    public void theLastStampIgnoresTrailingBlankLines() {
        String text = "2026-11-15 15:55:38 I [EO1] a\n"
                + "2026-11-15 16:21:10 I [EO1] b\n";
        assertEquals("2026-11-15 16:21:10", LogWindow.lastStamp(text));
        assertEquals("2026-11-15 16:21:10", LogWindow.lastStamp(text + "\n"));
        // No trailing newline at all — the read was cut at MAX_SECTION_BYTES mid-file.
        assertEquals("2026-11-15 16:21:10", LogWindow.lastStamp(text.trim()));
    }

    @Test
    public void theRetentionFactsMatchWhatAppLoggerActuallyDoes() {
        // HALF_BYTES is a mirror of AppLogger's private cap, quoted on every report, so a drift there
        // would be a report printing a promise the logger does not keep. The GENERATIONS half of the
        // sentence is what rotate() does: it deletes the previous .1 before renaming.
        assertEquals(2, LogWindow.GENERATIONS);
        assertEquals(256L * 1024L, LogWindow.HALF_BYTES);
    }

    private static int newLineCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
