package com.aphex3k.eo1;

/**
 * The arithmetic behind one diagnostics report's application-log section: how a line budget is split
 * across the two files {@link AppLogger} keeps, and what the report is then obliged to say about the
 * window it actually holds.
 *
 * <p>It exists because of one report. A manual capture on frame 192.168.1.114 at 16:21 local on
 * 2026-11-15 produced {@code report-1794788472918-000-manual-19.txt}: 40,776 bytes, exactly
 * {@link DiagnosticsReport#APP_LOG_LINES} dated lines, and a log window of
 * {@code 15:55:38 -> 16:21:10} — <b>26 minutes</b> — with no {@code [--- oldest half: … ---]} marker
 * anywhere in it. The section had never opened {@code eo1-app.log.1}. Three
 * {@code watchdog: main thread ran nothing for 2 ticks in a row — restarting app process} lines from
 * 09:18–09:24 that morning were therefore unreachable through every HTTP route the app has, and the
 * report gave no sign of the gap: 26 minutes of history and "this is everything there is" print
 * identically, and this file printed neither.
 *
 * <p>The cause was one comparison in the old {@code tailLog}: the rotated half was read only
 * {@code if (given < want)}, so a frame whose live half was already full — which is the normal state
 * of any frame logging at more than a couple of lines a minute — closed the older half by satisfying
 * the quota. The quota was doing the job of a stop condition, and a full newest file says nothing
 * whatever about how far back the frame's memory goes. {@link #liveShare} replaces that with a
 * budget split, which is a decision rather than a coincidence.
 *
 * <p>The second half of this class is the line the old report lacked. No fix here can recover history
 * the frame no longer has: {@link AppLogger}'s {@code rotate()} deletes the previous
 * {@code eo1-app.log.1} before renaming onto it, so <b>two generations is the entire record</b>
 * ({@link #GENERATIONS}). What a report can do — and now must — is state its own span, how many lines
 * it got from each file, whether each file was read whole, and what the retention ceiling is, so a
 * 26-minute window is read as 26 minutes rather than as a frame that had nothing else to say.
 *
 * <p>Deliberately free of {@code android.*}, of {@code java.text.SimpleDateFormat} and of
 * {@code java.util.Calendar}: the split and the sentence are the two decisions worth pinning, and the
 * repo's rule for a decision is a pure class with a JVM test (see {@code VideoRoutingRule},
 * {@code VideoLoopSeamRule}, {@code DebugBoard.visible}). The reading of bytes stays in
 * {@code DiagnosticsReport}, which owns the file access.
 */
public final class LogWindow {

    /**
     * Generations {@link AppLogger} keeps: the live file plus one rotated half. Not a tunable — it is
     * what {@code AppLogger.rotate()} does, quoted here so the coverage line can say it in words.
     */
    public static final int GENERATIONS = 2;

    /**
     * Bytes per half — {@link AppLogger#MAX_FILE_BYTES} itself, not a copy of it. This number is printed
     * in the retention sentence of every report as a fact about how far back a frame can remember, and a
     * second literal here would be a claim that keeps its old value quietly if the rotation cap moves:
     * the frame would go on rotating at one size while every report quoted another, and no test could
     * see the drift because the test pins the copy rather than the cause.
     */
    public static final long HALF_BYTES = AppLogger.MAX_FILE_BYTES;

    /** Length of the leading {@code yyyy-MM-dd HH:mm:ss} every {@code AppLogger} line begins with. */
    private static final int STAMP_LEN = 19;

    private LogWindow() {
    }

    /**
     * How many of the {@code n} lines to read from the live (newest) half. With a rotated half present
     * the budget is split, so a report always carries a second, older window; without one the whole
     * budget goes to the live half, which is today's behaviour and still the right one — there is
     * nothing older to open. An odd budget gives its extra line to the newest half, because the newest
     * line is the one a reader looks at first. {@code n < 2} is all-live: one line is the newest line
     * and there is nothing to split.
     */
    public static int liveShare(int n, boolean rotatedPresent) {
        int want = Math.max(0, n);
        if (!rotatedPresent || want < 2) {
            return want;
        }
        return (want + 1) / 2;
    }

    /** {@code n - liveShare(n, rotatedPresent)}. Never negative, never more than {@code n}. */
    public static int rotatedShare(int n, boolean rotatedPresent) {
        return Math.max(0, Math.max(0, n) - liveShare(n, rotatedPresent));
    }

    /**
     * One half's facts, gathered by the caller from what it <b>already read</b> plus
     * {@link java.io.File#length()} — never a re-scan, because this runs inside a section with a
     * 1500 ms deadline on a thread that may be abandoned mid-call.
     *
     * <p>{@code requested} is how many lines were asked of <i>that</i> file, which after
     * {@code tailLog}'s top-up is not always {@code rotatedShare}: a live half shorter than its share
     * hands its remainder to the older one, and the older one's honesty about having been read whole
     * depends on being compared against what it was actually asked for.
     */
    public static final class Half {
        public final int requested;
        public final int shown;
        /** The file's length in bytes, or 0 when there is no file. */
        public final long bytes;
        /** Bytes in the text that was read, used only to turn {@link #bytes} into an estimate. */
        public final int shownBytes;

        public Half(int requested, int shown, long bytes, int shownBytes) {
            this.requested = Math.max(0, requested);
            this.shown = Math.max(0, shown);
            this.bytes = Math.max(0L, bytes);
            this.shownBytes = Math.max(0, shownBytes);
        }

        /**
         * Fewer lines than asked means the whole file fitted: nothing older was left unread. The
         * distinction matters to a reader because it is the difference between "both files are full and
         * I showed you the top of one" and "this frame has 12 lines, total".
         */
        public boolean wholeFileFitted() {
            return requested > 0 && shown < requested;
        }

        /**
         * What this half holds in total, in the coverage line's slot for it: a file that fitted is
         * stated exactly, because reading all of it means the count is known and an estimate of a
         * known number is a rounding of a fact. Only a half that did <i>not</i> fit — the case where
         * lines are still sitting unread behind the quota — gets the {@code ~} treatment.
         */
        public String diskPhrase() {
            return wholeFileFitted() ? "(whole file, " + shown + " lines)" : approximateLinesOnDisk();
        }

        /**
         * A byte-derived <b>estimate</b> of how many lines the file holds, labelled as one with a
         * leading {@code ~}. It is not a count: nothing here opens the file line by line, so the
         * figure is the file's length divided by the mean byte-length of the lines that <i>were</i>
         * read, which is wrong by however much the unread part of the file differs from the read part.
         * It is printed because "somewhere around eight hundred lines exist behind these two hundred"
         * is the fact a reader needs, and the alternative — scanning a 256 KiB file on a 1500 ms
         * budget to be exact — is the thing {@code AppLogger.tailFile} is avoided for.
         */
        public String approximateLinesOnDisk() {
            long estimate = estimatedLinesOnDisk();
            return estimate < 0L ? "unknown" : "~" + estimate;
        }

        /**
         * The same figure as a number, {@code -1} when it cannot be formed — for sentences that
         * already carry the word "about" in prose and must not print a second tilde. Kept separate
         * from {@link #approximateLinesOnDisk()} rather than unpicked out of its string, because a
         * substring past a label character is the kind of coupling that breaks silently when the
         * label changes to "unknown".
         */
        public long estimatedLinesOnDisk() {
            if (shown <= 0 || shownBytes <= 0) {
                return -1L;
            }
            long meanBytes = Math.max(1L, shownBytes / shown);
            return Math.min((long) Integer.MAX_VALUE, bytes / meanBytes);
        }
    }

    /**
     * The coverage line, printed <b>first</b> in the section so {@code BudgetedWriter}'s
     * keep-the-head-and-drop-the-tail cannot lose it. Always exactly one physical line ending in
     * {@code '\n'}, and a line even when everything is empty — that line <i>is</i> the point of this
     * class. Every field is computed from what was read; nothing is invented, and no clause is printed
     * about a half that was not opened.
     */
    public static String coverageLine(Half live, Half rotated, String firstStamp, String lastStamp) {
        Half l = live == null ? new Half(0, 0, 0L, 0) : live;
        Half r = rotated == null ? new Half(0, 0, 0L, 0) : rotated;

        StringBuilder sb = new StringBuilder(256);
        sb.append("[log window: ");

        if (l.shown + r.shown == 0) {
            // Say which files were looked at, because "nothing" from a reader who passed the wrong
            // path is a different problem from a frame that has never written a log line — and a
            // zero-byte file and an absent one are the same answer to {@code File.length()}, so the
            // first wording claims nothing about existence it cannot check.
            return sb.append(l.bytes + r.bytes == 0L
                    ? "nothing on disk — neither eo1-app.log nor eo1-app.log.1 has any bytes]\n"
                    : "nothing on disk — the log file is there but holds no complete line]\n")
                    .toString();
        }

        String first = firstStamp == null ? "" : firstStamp;
        String last = lastStamp == null ? "" : lastStamp;
        if (first.isEmpty()) {
            sb.append("undated (no timestamped line in the shown text)");
        } else if (first.equals(last)) {
            sb.append(first).append(" (single line)");
        } else {
            sb.append(first).append(" -> ").append(last);
        }

        boolean rotatedAbsent = r.requested == 0 && r.shown == 0 && r.bytes == 0L;

        sb.append(" | shown ").append(l.shown + r.shown).append(" lines (")
          .append(l.shown).append(" newest half");
        if (rotatedAbsent) {
            // "There is no rotated half" is a fact about the files on disk. It is NOT a fact about how
            // much the live half holds, and the two must not be collapsed into one sentence: a frame
            // that has been up since boot without reaching 256 KiB keeps its entire record in this one
            // file — and that file can be several times fuller than this section's budget, in which
            // case naming the budget ("so 400 lines is the whole record it keeps") states a number of
            // lines the frame never had and hides thousands it did. Same trap as a telemetry field that
            // is perfectly attributed and still wrong: the ceiling was right, the arithmetic was not.
            long estimate = l.estimatedLinesOnDisk();
            sb.append("; no rotated half on disk — the live half is this frame's whole record");
            if (!l.wholeFileFitted() && estimate >= 0L) {
                sb.append(", it holds about ").append(estimate).append(" lines and the newest ")
                  .append(l.shown).append(" are shown)");
            } else {
                sb.append(", all ").append(l.shown).append(" of its lines are shown)");
            }
            sb.append(" | retention is ").append(GENERATIONS).append(" halves x ").append(HALF_BYTES)
              .append(" bytes; only the live half exists on this frame so far");
        } else {
            sb.append(" + ").append(r.shown).append(" oldest half")
              .append(") of live ").append(l.diskPhrase())
              .append(", rotated ").append(r.diskPhrase());
            sb.append(" | retention is ").append(GENERATIONS).append(" halves x ").append(HALF_BYTES)
              .append(" bytes; everything older was rotated away with no trace");
        }
        return sb.append("]\n").toString();
    }

    /** The leading {@code yyyy-MM-dd HH:mm:ss} of the first timestamped line in {@code text}, or "". */
    public static String firstStamp(String text) {
        if (text == null) {
            return "";
        }
        int pos = 0;
        while (pos <= text.length()) {
            int end = text.indexOf('\n', pos);
            if (isStampAt(text, pos)) {
                return text.substring(pos, pos + STAMP_LEN);
            }
            if (end < 0) {
                break;
            }
            pos = end + 1;
        }
        return "";
    }

    /** The leading {@code yyyy-MM-dd HH:mm:ss} of the last timestamped line in {@code text}, or "". */
    public static String lastStamp(String text) {
        if (text == null || text.length() < STAMP_LEN) {
            return "";
        }
        // Walk backwards over line starts. The last physical line may have no trailing newline, so
        // the end of the text is a line end whether or not it is a '\n'.
        int lineEnd = text.length();
        while (lineEnd > 0) {
            int nl = text.lastIndexOf('\n', lineEnd - 1);
            // Lines produced by AppLogger always end in '\n', so a text ending in one has an empty
            // final "line" after the last newline: skip it by starting one character earlier.
            int start = nl + 1;
            if (start < lineEnd && isStampAt(text, start)) {
                return text.substring(start, start + STAMP_LEN);
            }
            if (nl < 0) {
                break;
            }
            lineEnd = nl;
        }
        return "";
    }

    /**
     * Whether a 19-character stamp ({@link #STAMP_LEN}) starts at {@code off}. Checked structurally — four
     * digits, {@code -}, two, {@code -}, two, space, two, {@code :}, two, {@code :}, two — rather than
     * by parsing, because a stack-trace continuation line ({@code \tat com.foo.Bar(Bar.java:1)},
     * {@code Caused by: …}) is a perfectly ordinary line of a logged throwable and must not be
     * mistaken for a stamp, and because {@code SimpleDateFormat} would both allocate and throw.
     * The search is per line for the same reason: line 1 of a tail is a trace continuation as often as
     * it is a stamp, since {@code tail()} cuts at a byte boundary.
     */
    private static boolean isStampAt(String s, int off) {
        if (off + STAMP_LEN > s.length()) {
            return false;
        }
        return digit(s, off) && digit(s, off + 1) && digit(s, off + 2) && digit(s, off + 3)
                && s.charAt(off + 4) == '-'
                && digit(s, off + 5) && digit(s, off + 6)
                && s.charAt(off + 7) == '-'
                && digit(s, off + 8) && digit(s, off + 9)
                && s.charAt(off + 10) == ' '
                && digit(s, off + 11) && digit(s, off + 12)
                && s.charAt(off + 13) == ':'
                && digit(s, off + 14) && digit(s, off + 15)
                && s.charAt(off + 16) == ':'
                && digit(s, off + 17) && digit(s, off + 18);
    }

    private static boolean digit(String s, int i) {
        char c = s.charAt(i);
        return c >= '0' && c <= '9';
    }
}
