package com.aphex3k.eo1;

import android.content.Context;

import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Durable store of frame-state diagnostics: {@code filesDir/reports}, one plaintext report per
 * capture. A report is written by the frame itself when the kiosk ladder reaches the reboot rung —
 * including the two ways that rung ends without a reboot — and on demand from the LAN page.
 *
 * <p>The reason this is a sibling of {@link CrashStore} rather than another kind inside it: a
 * self-reboot with no exception is the event being documented, and a file that landed in
 * {@code filesDir/crashes} would be announced by the landing page as a crash and would share the
 * crash store's budget with real ones. What is being kept here is evidence about a decision, not
 * the record of a death.
 *
 * <p>Like {@code CrashStore}, everything operates on a plain {@link File} so it is unit-testable on
 * the JVM, and the listing metadata lives in the <b>file name</b>
 * ({@code report-<epochMillis>-<seq>-<trigger>-<versionCode>.txt}) so {@code /reports} is built
 * without opening a single body.
 *
 * <p>The one rule that makes the class worth having: <b>a report must exist even when the thing it
 * describes is the reason it cannot</b>. Hence the section order (in-process counters first, the
 * framework's own answers last), and hence no collector being trusted to return — every budgeted
 * section is abandoned at its deadline and named {@code timeout} in the header. A frame hung in a
 * binder call still produces a report; it just says which parts of itself it could not see.
 */
public final class DiagnosticsReport {

    /** Directory name under {@code getFilesDir()} holding diagnostics reports. */
    public static final String DIR_NAME = "reports";

    /** A report older than this is evicted regardless of how little space the store uses. */
    public static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    /** Total store cap; past this the oldest reports are evicted. */
    public static final long MAX_TOTAL_BYTES = 8L * 1024L * 1024L;

    /** Report count cap, so a frame that reboots hourly cannot fill the disk with its own evidence. */
    public static final int MAX_REPORTS = 40;

    /** Cap on the text of one section. */
    public static final long MAX_SECTION_BYTES = 128L * 1024L;

    /**
     * Wall-clock limit on one whole capture — collection, render and write together. The thread that
     * triggered a capture waits no longer than this and then proceeds with whatever it was about to
     * do, which for the reboot rung is the reboot.
     */
    public static final long CAPTURE_DEADLINE_MS = 8000L;

    /**
     * Per-section limits, inside {@link #CAPTURE_DEADLINE_MS}. Deliberately sum to less than it, so
     * the last section still gets its own budget rather than whatever the earlier ones left.
     * {@code liveness} is unbudgeted because it reads no I/O and calls no framework method: the
     * values it holds are the ones a wedge cannot take away.
     */
    public static final long BUDGET_THREAD_DUMP_MS = 1000L;
    public static final long BUDGET_APP_LOG_MS = 1500L;
    public static final long BUDGET_STATE_MS = 5000L;

    /** Lines of the rolling application log kept, read from both halves of the file. */
    public static final int APP_LOG_LINES = 400;

    /** Stack frames per thread in the thread dump. Enough to see where a loop is parked. */
    public static final int THREAD_DUMP_MAX_FRAMES = 15;

    /** Minimum spacing between two captures, whatever asked for them. */
    public static final long CAPTURE_SPACING_MS = 30_000L;

    /** Trigger tokens, encoded in the file name. Hyphen-free by necessity — see {@link #newName}. */
    public static final String TRIGGER_MANUAL = "manual";
    public static final String TRIGGER_REBOOT = "reboot";
    public static final String TRIGGER_EXHAUSTED = "exhausted";

    static final String FORMAT_TAG = "eo1-diag/1";

    /** Section outcomes, reported once per section in the header. */
    static final String OK = "ok";
    static final String TIMEOUT = "timeout";
    static final String ERROR = "error";
    static final String NOT_REACHED = "not-reached";
    static final String UNAVAILABLE = "unavailable";

    private static final String PREFIX = "report-";
    private static final String SUFFIX = ".txt";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** Per-process, as in {@link CrashStore}: two reports in the same millisecond still differ. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private DiagnosticsReport() {
    }

    /** Returns the reports directory, creating it if necessary. Failure to create is not fatal. */
    public static File dirFor(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            // best effort
        }
        return dir;
    }

    /** Produces one section's text. Runs on the capture thread, and may be abandoned mid-call. */
    public interface Collector {
        String collect() throws Exception;
    }

    /**
     * What to write. The header carries only what the caller could not derive from the report itself,
     * and the caller is responsible for keeping framework calls out of it: a header key that blocks
     * is how a reboot worker gets stranded. The {@code state} section is where anything that may
     * hang belongs, because that is the one section with a deadline of its own.
     */
    public static final class Content {
        public String trigger = TRIGGER_MANUAL;
        public String reason = "";
        public String triggerThread = "";
        public long epochMillis = System.currentTimeMillis();
        public int versionCode;
        /** Epoch of this boot, so the store can answer "did the last reboot request land". */
        public long bootEpochMillis;
        public Map<String, String> header = new LinkedHashMap<>();
        public Collector liveness;
        public Collector threadDump;
        public Collector appLog;
        public Collector state;
        /** Both halves of the rolling log, for the built-in tailer. */
        public File logFile;
        public File rotatedLogFile;
        /**
         * The deadlines this capture enforces, defaulting to the constants. Visible rather than fixed
         * because "a section that cannot finish is abandoned" is the central guarantee of this class,
         * and a guarantee that can only be exercised by a test that waits eight seconds is a guarantee
         * nobody keeps.
         */
        public long deadlineMs = CAPTURE_DEADLINE_MS;
        public long threadDumpBudgetMs = BUDGET_THREAD_DUMP_MS;
        public long appLogBudgetMs = BUDGET_APP_LOG_MS;
        public long stateBudgetMs = BUDGET_STATE_MS;
        /** Extra machine-readable fields for the JSON trailer. May be null. */
        public JsonObject json;
    }

    /**
     * Builds a report name. {@code epochMillis} and {@code seq} are fixed-width so a plain numeric
     * comparison of the prefix orders the store newest-first.
     *
     * <p>{@code trigger} sits in a slot the parser finds by counting hyphens, exactly as
     * {@code CrashStore} does for {@code kind}: a token containing one would shift the version code
     * into the trigger's place and silently make every listing report {@code versionCode 0}, which
     * is the field that picks the mapping file. The three tokens are therefore compile-time
     * constants, and {@link #isTrigger} is what a caller with a string from elsewhere must pass.
     */
    public static String newName(long epochMillis, int seq, String trigger, int versionCode) {
        return PREFIX
                + pad(Long.toString(Math.max(0L, epochMillis)), 13)
                + "-" + pad(Integer.toString(Math.max(0, Math.min(999, seq))), 3)
                + "-" + (isTrigger(trigger) ? trigger : TRIGGER_MANUAL)
                + "-" + Math.max(0, versionCode)
                + SUFFIX;
    }

    /** Whether {@code s} is one of the three name-safe trigger tokens. */
    public static boolean isTrigger(String s) {
        return TRIGGER_MANUAL.equals(s) || TRIGGER_REBOOT.equals(s) || TRIGGER_EXHAUSTED.equals(s);
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(width);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    /**
     * Runs one capture: collects the four sections under their budgets, renders, fsyncs, renames,
     * prunes. Never throws — a capture that fails reports why in its {@link Result}, because the
     * caller is usually a thread that has something else urgent to do.
     */
    public static Result capture(File baseDir, Content c) {
        if (baseDir == null || c == null) {
            return new Result(false, null, 0L, 0L, "no report directory or content");
        }
        long startMs = System.currentTimeMillis();
        long deadline = startMs + Math.max(1L, c.deadlineMs);
        Section[] sections = {
                new Section("liveness", "1. liveness (in-process, no framework calls)", 0L, c.liveness),
                new Section("threads", "2. thread dump (this process)", c.threadDumpBudgetMs, c.threadDump),
                new Section("applog", "3. application log", c.appLogBudgetMs, c.appLog),
                new Section("state", "4. device state (the /state document)", c.stateBudgetMs, c.state),
        };
        for (int i = 0; i < sections.length; i++) {
            collect(sections[i], deadline);
        }

        Dump d = new Dump();
        d.epochMillis = c.epochMillis;
        d.seq = SEQ.getAndIncrement() % 1000;
        d.trigger = isTrigger(c.trigger) ? c.trigger : TRIGGER_MANUAL;
        d.versionCode = c.versionCode;
        d.reason = c.reason;
        d.triggerThread = c.triggerThread;
        d.bootEpochMillis = c.bootEpochMillis;
        d.lastReboot = lastReboot(baseDir, c.bootEpochMillis);
        d.header.putAll(c.header);
        d.json = c.json;
        d.sections = sections;

        try {
            File f = write(baseDir, d);
            prune(baseDir, System.currentTimeMillis());
            return new Result(true, f.getName(), f.length(),
                    System.currentTimeMillis() - startMs, null);
        } catch (Throwable t) {
            return new Result(false, null, 0L, System.currentTimeMillis() - startMs,
                    String.valueOf(t));
        }
    }

    /**
     * Runs one section. An unbudgeted section runs on this thread; a budgeted one runs on a
     * short-lived daemon so that "abandoned" is a thing that can actually happen — a collector stuck
     * in a binder call is the case the whole ordering exists for, and a deadline nobody can enforce
     * from outside is not a deadline.
     *
     * <p>The abandoned thread is left running. It writes only into its own {@link Section}, which by
     * then nobody reads again, and it is a daemon, so it cannot keep the process alive.
     */
    private static void collect(Section s, long deadlineMs) {
        if (s.collector == null) {
            s.outcome = UNAVAILABLE;
            s.text = "(this build supplies no collector for this section)\n";
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= deadlineMs) {
            s.outcome = NOT_REACHED;
            s.text = "(not collected: the capture deadline had passed)\n";
            return;
        }
        long allowed = s.budgetMs <= 0L
                ? Long.MAX_VALUE
                : Math.min(s.budgetMs, deadlineMs - now);
        s.startedAtMs = now;
        if (allowed == Long.MAX_VALUE) {
            runInline(s);
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runInline(s);
            }
        }, "eo1-diag-section");
        worker.setDaemon(true);
        try {
            worker.start();
            worker.join(allowed);
        } catch (Throwable ignored) {
            // A section that cannot even be started is simply a section that did not run.
        }
        s.elapsedMs = System.currentTimeMillis() - s.startedAtMs;
        if (!s.finished) {
            s.outcome = TIMEOUT;
            s.text = "(abandoned after " + s.elapsedMs + "ms still running)\n";
        }
    }

    private static void runInline(Section s) {
        long t0 = System.currentTimeMillis();
        try {
            String text = s.collector.collect();
            s.text = text == null ? "(collector returned nothing)\n" : text;
            s.outcome = OK;
        } catch (Throwable t) {
            s.text = "(failed: " + t + ")\n";
            s.outcome = ERROR;
        }
        s.elapsedMs = System.currentTimeMillis() - t0;
        s.finished = true;
    }

    /**
     * Writes {@code d} as one report: a temporary file, fsynced, then renamed into place, so a
     * process that dies mid-write can only leave a {@code .tmp} that no listing shows.
     *
     * <p>The {@code fsync} reaches the file's own data and metadata, and that is all that is
     * reachable on API 19: {@code android.system.Os} is 21 and {@code java.io} exposes no directory
     * descriptor, so the <i>directory entry</i> is never synced. The rename is therefore not proven
     * durable against a kernel restart that happens in the same instant, which is why the reboot
     * capture is issued before the reboot command rather than after it.
     */
    public static File write(File baseDir, Dump d) throws IOException {
        if (baseDir == null) {
            throw new IOException("no report directory");
        }
        if (d == null) {
            throw new IOException("no report content");
        }
        if (!baseDir.isDirectory() && !baseDir.mkdirs()) {
            throw new IOException("cannot create " + baseDir.getAbsolutePath());
        }
        IOException first = null;
        for (int attempt = 0; attempt < 64; attempt++) {
            File target = new File(baseDir,
                    newName(d.epochMillis, d.seq + attempt, d.trigger, d.versionCode));
            if (target.exists()) {
                continue;
            }
            File tmp = new File(baseDir, target.getName() + ".tmp");
            try {
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    Writer w = new OutputStreamWriter(out, UTF_8);
                    render(w, d);
                    w.flush();
                    out.getFD().sync();
                } finally {
                    closeQuietly(out);
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete();
                    throw new IOException("cannot rename diagnostics report");
                }
                return target;
            } catch (IOException e) {
                tmp.delete();
                if (first == null) {
                    first = e;
                }
            }
        }
        throw (first != null ? first : new IOException("no free report name"));
    }

    /** Renders one report: tag, header, reader banner, the four sections, then a JSON trailer. */
    static void render(Writer w, Dump d) throws IOException {
        w.write(FORMAT_TAG);
        w.write('\n');
        for (Map.Entry<String, String> entry : identity(d).entrySet()) {
            w.write(entry.getKey());
            w.write(": ");
            w.write(oneLine(entry.getValue()));
            w.write('\n');
        }
        w.write('\n');
        w.write(BANNER);
        w.write('\n');
        BudgetedWriter budget = new BudgetedWriter(w);
        for (int i = 0; i < d.sections.length; i++) {
            Section s = d.sections[i];
            w.write("\n---- ");
            w.write(s.title);
            w.write(" [");
            w.write(s.outcome);
            w.write(", ");
            w.write(Long.toString(s.elapsedMs));
            w.write("ms] ----\n");
            budget.nextSection();
            budget.write(s.text == null ? "(empty)\n" : s.text);
            if (budget.truncated()) {
                w.write("\n[section truncated at ");
                w.write(Long.toString(MAX_SECTION_BYTES));
                w.write(" bytes]\n");
            }
        }
        w.write("\nDeveloper information (JSON):\n");
        w.write(d.json().toString());
        w.write('\n');
    }

    /**
     * The six lines a stranger reads before any data. Each one exists because the absence of it
     * produced a wrong diagnosis in review: the staleness caveat, because the watchdog's own headline
     * number is refreshed by the very loop it measures; and the clock caveat, because half of what a
     * report holds was written by the thread that stopped writing.
     */
    static final String BANNER =
            "---- what this is ----\n"
            + "One frame's own account of its state at one instant, written before it rebooted itself\n"
            + "or when someone asked. It is NOT a crash report - nothing here died. Sections 1-3 are\n"
            + "read from this process's memory and files; section 4 is the /state document.\n"
            + "Read the 'Section-*' lines above first: a section that says timeout or not-reached is\n"
            + "an answer this frame could not get, which is itself a finding about what was stuck.\n"
            + "Values from an Activity or MediaManager field were written by the thread being\n"
            + "diagnosed: where mainLoopStallMs is large, they are as of that long ago, not as of\n"
            + "Requested-At. Anything named *Age or *StallMs is recomputed at capture time.\n"
            + "A live rotation loop refreshes the watchdog's stale= clock on every tick, so stale=0\n"
            + "alongside displayFailedCount rising is a wedge the ladder cannot see - trust\n"
            + "lastDisplaySucceededAtMs, not stale.\n"
            + "Before publishing this on a public issue, you may want to blank: network.wifi.ssid,\n"
            + "network.wifi.ip, device.name, media.current.originalFileName. No credential of any\n"
            + "kind is in here: the configuration document and SharedPreferences are read by none\n"
            + "of the collectors that built this file.\n";

    /** The caller's header plus the fields that must never depend on a caller remembering them. */
    private static Map<String, String> identity(Dump d) {
        Map<String, String> h = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : d.header.entrySet()) {
            h.put(entry.getKey(), clamp(entry.getValue()));
        }
        // Written last so a caller cannot put the wrong key on its own report.
        h.put("Trigger", d.trigger);
        h.put("Trigger-Thread", d.triggerThread);
        h.put("Reason", clamp(d.reason));
        h.put("Epoch-Millis", Long.toString(d.epochMillis));
        h.put("Requested-At", localTime(d.epochMillis));
        h.put("Version-Code", Integer.toString(d.versionCode));
        h.put("Mapping", "mapping-" + Math.max(0, d.versionCode) + ".txt.gz");
        h.put("Last-Reboot", clamp(d.lastReboot));
        h.put("Secrets", "none (no configuration document, no credentials, no SharedPreferences)");
        for (int i = 0; i < d.sections.length; i++) {
            h.put("Section-" + d.sections[i].key,
                    d.sections[i].outcome + " (" + d.sections[i].elapsedMs + "ms)");
        }
        return h;
    }

    /** Cap on one header value; a section body has its own, larger budget. */
    static final int MAX_HEADER_VALUE = 2000;

    private static String clamp(String s) {
        if (s == null || s.length() <= MAX_HEADER_VALUE) {
            return s;
        }
        return s.substring(0, MAX_HEADER_VALUE) + "...[" + s.length() + " chars]";
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /**
     * Lists the reports, newest first. Names that do not parse sort last, by mtime, so a
     * hand-planted file still shows up rather than vanishing.
     */
    public static Info[] list(File baseDir) {
        return list(baseDir, false);
    }

    static Info[] list(File baseDir, boolean readHeaders) {
        File[] files = baseDir == null ? null : baseDir.listFiles();
        List<Info> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(SUFFIX)) {
                    continue;
                }
                Info info = fromFile(f, readHeaders);
                if (info != null) {
                    out.add(info);
                }
            }
        }
        // Collections.sort, not List.sort — the latter is a Java 8 default method absent on API 19.
        Collections.sort(out, new Comparator<Info>() {
            @Override
            public int compare(Info a, Info b) {
                if (a.parsed != b.parsed) {
                    return a.parsed ? -1 : 1;
                }
                if (a.epochMillis != b.epochMillis) {
                    return a.epochMillis > b.epochMillis ? -1 : 1;
                }
                if (a.seq != b.seq) {
                    return a.seq > b.seq ? -1 : 1;
                }
                if (a.lastModified != b.lastModified) {
                    return a.lastModified > b.lastModified ? -1 : 1;
                }
                return b.name.compareTo(a.name);
            }
        });
        return out.toArray(new Info[0]);
    }

    /** Reads one report's name metadata plus the header keys a listing needs. */
    public static Info readMeta(File f) {
        return f != null && f.isFile() ? fromFile(f, true) : null;
    }

    /** How much of a report {@link #readMeta} reads to pick up the header. */
    static final int HEADER_READ_BYTES = 1024;

    /**
     * The metadata a file name carries, with no file and no body. The web listing shows a build number
     * it read back out of the name, so {@link #newName} and this parser have to agree — which is what
     * a caller holding only a name gets to check.
     */
    public static Info readMetaNamed(String name) {
        Info info = new Info();
        info.name = name;
        long[] stamp = parseStamp(name);
        if (stamp != null) {
            info.parsed = true;
            info.epochMillis = stamp[0];
            info.seq = (int) stamp[1];
            info.trigger = triggerOf(name);
            info.versionCode = versionCodeOf(name);
        } else {
            info.trigger = "unknown";
        }
        return info;
    }

    private static Info fromFile(File f, boolean readHeaders) {
        Info info = readMetaNamed(f.getName());
        info.sizeBytes = f.length();
        info.lastModified = f.lastModified();
        if (!info.parsed) {
            info.epochMillis = info.lastModified;
        }
        if (readHeaders) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new java.io.InputStreamReader(new FileInputStream(f), UTF_8),
                        HEADER_READ_BYTES);
                char[] buf = new char[HEADER_READ_BYTES];
                int n = r.read(buf);
                String head = new String(buf, 0, Math.max(0, n));
                info.reason = headerValue(head, "Reason");
            } catch (IOException ignored) {
                // An unreadable body still lists, from its name alone.
            } finally {
                closeQuietly(r);
            }
        }
        return info;
    }

    /** Extracts {@code <epochMillis>, <seq>} from a report name, or {@code null} if it is not one. */
    static long[] parseStamp(String name) {
        if (name == null || !name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
            return null;
        }
        String body = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        int first = body.indexOf('-');
        int second = first < 0 ? -1 : body.indexOf('-', first + 1);
        if (first < 0 || second < 0) {
            return null;
        }
        try {
            long ms = Long.parseLong(body.substring(0, first));
            int seq = Integer.parseInt(body.substring(first + 1, second));
            return new long[]{ms, seq};
        } catch (NumberFormatException e) {
            return null;
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static String triggerOf(String name) {
        String body = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        int first = body.indexOf('-');
        int second = first < 0 ? -1 : body.indexOf('-', first + 1);
        int third = second < 0 ? -1 : body.indexOf('-', second + 1);
        if (second < 0 || third < 0) {
            return "unknown";
        }
        return body.substring(second + 1, third);
    }

    private static int versionCodeOf(String name) {
        String body = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        int second = body.indexOf('-');
        second = second < 0 ? -1 : body.indexOf('-', second + 1);
        int third = second < 0 ? -1 : body.indexOf('-', second + 1);
        if (third < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(body.substring(third + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Reads a {@code Key: value} line out of a report header, stopping at the first blank line. */
    static String headerValue(String head, String key) {
        int pos = 0;
        while (pos < head.length()) {
            int nl = head.indexOf('\n', pos);
            String line = nl < 0 ? head.substring(pos) : head.substring(pos, nl);
            if (line.trim().isEmpty()) {
                return null;
            }
            if (line.startsWith(key) && line.length() > key.length() && line.charAt(key.length()) == ':') {
                return line.substring(key.length() + 1).trim();
            }
            if (nl < 0) {
                return null;
            }
            pos = nl + 1;
        }
        return null;
    }

    /**
     * Resolves a report name to a safe, existing file inside {@code baseDir}.
     *
     * @return the file, or {@code null} if missing or unsafe
     */
    public static File resolve(File baseDir, String name) {
        if (name == null || !name.endsWith(SUFFIX)) {
            return null;
        }
        return UploadedMedia.resolve(baseDir, name);
    }

    /** Deletes one report by explicit operator action. Nothing else ever removes a report early. */
    public static boolean delete(File baseDir, String name) {
        File f = resolve(baseDir, name);
        return f != null && f.delete();
    }

    /**
     * Enforces {@link #RETENTION_MS}, {@link #MAX_TOTAL_BYTES} and {@link #MAX_REPORTS}, oldest
     * first. Never throws: it runs after every successful write, on a thread that has somewhere else
     * to be.
     *
     * <p>Two rules hold files back. The newest non-{@code manual} report survives everything, because
     * otherwise the act of collecting a routine snapshot would be the thing that deletes the evidence
     * an issue was filed about. And no capacity gate may remove the newest report of all: 8 MB of
     * reports is not worth fewer than one. Retention is the one gate allowed to take that newest
     * file, or a frame that has been quietly wedged for a month would hoard a single stale report and
     * the store would read as healthy.
     */
    public static Stats prune(File baseDir, long nowMs) {
        cleanTempFiles(baseDir);
        Info[] infos;
        try {
            infos = list(baseDir, false);
        } catch (Throwable t) {
            return new Stats(0, 0L, 0L, null);
        }
        int keepNonManual = indexOfNewestNonManual(infos);
        long total = 0L;
        for (Info info : infos) {
            total += info.sizeBytes;
        }
        // Each gate is re-tested against what is still standing, per file: a store one report over
        // the count cap must shed one report, not everything older than the newest.
        int remaining = infos.length;
        for (int i = infos.length - 1; i >= 0; i--) {
            if (i == keepNonManual) {
                continue;
            }
            boolean aged = nowMs - infos[i].epochMillis > RETENTION_MS;
            boolean overCapacity = i > 0
                    && (total > MAX_TOTAL_BYTES || remaining > MAX_REPORTS);
            if ((aged || overCapacity) && delete(baseDir, infos[i].name)) {
                total -= infos[i].sizeBytes;
                remaining--;
            }
        }
        return statsOf(list(baseDir, false));
    }

    private static int indexOfNewestNonManual(Info[] infos) {
        for (int i = 0; i < infos.length; i++) {
            if (!TRIGGER_MANUAL.equals(infos[i].trigger)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Removes {@code <name>.tmp} left behind by a write whose process died mid-rename. Only stale
     * ones go: a younger {@code .tmp} may belong to the capture thread of a report being written.
     */
    static void cleanTempFiles(File baseDir) {
        File[] entries;
        try {
            entries = baseDir == null ? null : baseDir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (entries == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 600_000L;
        for (File f : entries) {
            if (f.isFile() && f.getName().endsWith(SUFFIX + ".tmp") && f.lastModified() < cutoff) {
                try {
                    f.delete();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    /** Summarises the store without pruning it and without opening a single report. */
    public static Stats stats(File baseDir) {
        return statsOf(list(baseDir, false));
    }

    private static Stats statsOf(Info[] infos) {
        long total = 0L;
        for (Info info : infos) {
            total += info.sizeBytes;
        }
        return new Stats(infos.length, total,
                infos.length == 0 ? 0L : infos[0].epochMillis,
                infos.length == 0 ? null : infos[0].trigger);
    }

    /** The {@code /state} {@code diagnostics} block. */
    public static JsonObject statsJson(File baseDir) {
        Stats s = stats(baseDir);
        JsonObject o = new JsonObject();
        o.addProperty("count", s.count);
        o.addProperty("lastAtMs", s.lastAtMs);
        o.addProperty("lastTrigger", s.lastTrigger == null ? "" : s.lastTrigger);
        o.addProperty("totalBytes", s.totalBytes);
        o.addProperty("latestIsReboot", TRIGGER_REBOOT.equals(s.lastTrigger));
        return o;
    }

    /**
     * Whether the frame's own last reboot request landed, answered from the store alone: the newest
     * {@code -reboot-} report older than this boot proves the command was issued, and the gap says how
     * long the device took to come back.
     *
     * <p>There is no boot counter and no {@code BOOT_COMPLETED} receiver in this app, so adjacency of
     * wall-clock stamps is the whole of the evidence — which is why "a power loss or a vendor reboot
     * looks identical to nothing at all" is a named answer rather than a guess.
     */
    public static String lastReboot(File baseDir, long bootEpochMillis) {
        Info[] infos;
        try {
            infos = list(baseDir, false);
        } catch (Throwable t) {
            return "unknown (the report store could not be read)";
        }
        for (Info info : infos) {
            if (TRIGGER_REBOOT.equals(info.trigger) && info.epochMillis <= bootEpochMillis) {
                long deltaMs = bootEpochMillis - info.epochMillis;
                return "app-requested at " + localTime(info.epochMillis) + " (" + info.name
                        + "), this boot began " + localTime(bootEpochMillis) + ", "
                        + deltaMs / 1000L + "s later";
            }
        }
        // A store that is empty says nothing either way; one with only manual captures since the
        // boot says the frame was alive and nobody asked. Both are "no request precedes this boot".
        return "no app-requested reboot precedes this boot (power loss, or a reboot this app did not ask for)";
    }

    /**
     * The newest {@code n} lines of {@code live}, falling back into {@code rotated} when the live
     * file is shorter than asked. Both halves are needed because the earliest a self-reboot can
     * follow a wedge is roughly fifty minutes, and the live file rolls at 256 KiB — well inside that
     * on a frame that is logging failures.
     *
     * <p>Deliberately not {@link AppLogger#tailFile(int)}: that is {@code synchronized} on the same
     * monitor every {@code info()} call holds across an open, a write and a close, so a storage stall
     * would consume this section's budget on a lock rather than on the read, and it slurps the whole
     * file line by line. This reads backwards from the end instead.
     */
    public static String tailLog(File live, File rotated, int n) {
        int want = Math.max(0, n);
        String now = want == 0 ? "" : tail(live, want);
        int given = countLinesInText(now);
        String old = given >= want ? "" : tail(rotated, want - given);
        if (old.isEmpty()) {
            return now.isEmpty() ? "(no log lines on disk)\n" : now;
        }
        return "[--- oldest half: " + (rotated == null ? "?" : rotated.getName()) + " ---]\n"
                + old + now;
    }

    /**
     * Reads the last {@code lines} lines of a file by seeking backwards for the line boundary and
     * then reading forward, so cost is O(requested) rather than O(file) and the bytes reach the
     * reader in the order they were written.
     */
    private static String tail(File f, int lines) {
        if (f == null || !f.isFile() || lines <= 0) {
            return "";
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            if (len == 0L) {
                return "";
            }
            long pos = len;
            long start = 0L;
            int found = 0;
            boolean bounded = false;
            byte[] block = new byte[8192];
            // The slice begins just after the newline that closes the line before the last `lines`
            // ones — a byte boundary that can never cut a UTF-8 character. A file shorter than that
            // never finds a boundary and is returned whole, from offset 0.
            while (pos > 0L && !bounded) {
                int chunk = (int) Math.min((long) block.length, pos);
                pos -= chunk;
                raf.seek(pos);
                raf.readFully(block, 0, chunk);
                for (int i = chunk - 1; i >= 0; i--) {
                    if (block[i] == '\n') {
                        found++;
                        if (found > lines) {
                            start = pos + i + 1;
                            bounded = true;
                            break;
                        }
                    }
                }
                if (len - pos > MAX_SECTION_BYTES) {
                    break;
                }
            }
            int size = (int) Math.min(len - start, MAX_SECTION_BYTES);
            byte[] all = new byte[size];
            raf.seek(start);
            raf.readFully(all, 0, size);
            return new String(all, UTF_8);
        } catch (Throwable t) {
            return "(log tail failed: " + t + ")\n";
        } finally {
            closeQuietly(raf);
        }
    }

    private static int countLinesInText(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** Formats an epoch millis as local wall-clock text, for report headers and listings. */
    public static String localTime(long epochMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date(epochMillis));
    }

    /** Renders one report to a string. Used by tests. */
    static String renderToString(Dump d) {
        java.io.StringWriter sw = new java.io.StringWriter();
        try {
            render(sw, d);
        } catch (IOException ignored) {
            // StringWriter does not fail
        }
        return sw.toString();
    }

    /**
     * One section's header key, its title, its own time budget, what it produced, and how that ended.
     *
     * <p>The key is carried, not read back out of the title. A {@code Section-*} header line is the
     * only place a reader learns whether a section ran at all, so it has to survive a reworded title;
     * deriving it from the title's first word turned "1. liveness (in-process, no framework calls)"
     * into a key holding the whole parenthetical, so the header said nothing readable.
     */
    static final class Section {
        final String key;
        final String title;
        final long budgetMs;
        final Collector collector;
        volatile String text = "";
        volatile String outcome = NOT_REACHED;
        volatile long elapsedMs;
        volatile boolean finished;
        long startedAtMs;

        Section(String key, String title, long budgetMs, Collector collector) {
            this.key = key;
            this.title = title;
            this.budgetMs = budgetMs;
            this.collector = collector;
        }
    }

    /** A rendered report, as {@link #write} and {@link #renderToString} take it. */
    public static final class Dump {
        public long epochMillis = System.currentTimeMillis();
        public int seq;
        public String trigger = TRIGGER_MANUAL;
        public String reason = "";
        public String triggerThread = "";
        public int versionCode;
        public long bootEpochMillis;
        public String lastReboot = "";
        public Map<String, String> header = new LinkedHashMap<>();
        Section[] sections = new Section[0];
        JsonObject json;

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("format", FORMAT_TAG);
            for (Map.Entry<String, String> entry : identity(this).entrySet()) {
                o.addProperty(entry.getKey(), entry.getValue());
            }
            if (json != null) {
                o.add("extra", json);
            }
            return o;
        }
    }

    /** The outcome of one capture, shaped for the web handler that asked for it. */
    public static final class Result {
        public final boolean ok;
        public final String name;
        public final long bytes;
        public final long elapsedMs;
        public final String message;

        Result(boolean ok, String name, long bytes, long elapsedMs, String message) {
            this.ok = ok;
            this.name = name;
            this.bytes = bytes;
            this.elapsedMs = elapsedMs;
            this.message = message;
        }
    }

    /** A store snapshot. */
    public static final class Stats {
        public final int count;
        public final long totalBytes;
        public final long lastAtMs;
        public final String lastTrigger;

        Stats(int count, long totalBytes, long lastAtMs, String lastTrigger) {
            this.count = count;
            this.totalBytes = totalBytes;
            this.lastAtMs = lastAtMs;
            this.lastTrigger = lastTrigger;
        }
    }

    /** A report's listing metadata, from its name plus (optionally) its header. */
    public static final class Info {
        public String name;
        public String trigger = "unknown";
        public long epochMillis;
        public int seq;
        public int versionCode;
        public long sizeBytes;
        public long lastModified;
        public String reason;
        boolean parsed;
    }

    /**
     * Passes through at most {@link #MAX_SECTION_BYTES} characters per section and records that it
     * did. Dropping rather than throwing is what lets a runaway section be bounded without an
     * exception escaping the capture thread — which would reach the global handler, and the global
     * handler exits the process.
     */
    private static final class BudgetedWriter extends Writer {
        private final Writer out;
        private long remaining;
        private boolean dropped;

        BudgetedWriter(Writer out) {
            this.out = out;
            this.remaining = MAX_SECTION_BYTES;
        }

        void nextSection() {
            remaining = MAX_SECTION_BYTES;
            dropped = false;
        }

        boolean truncated() {
            return dropped;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            int allowed = (int) Math.min((long) len, remaining);
            if (allowed < len) {
                dropped = true;
            }
            if (allowed > 0) {
                out.write(cbuf, off, allowed);
                remaining -= allowed;
            }
        }

        @Override
        public void write(String str) throws IOException {
            write(str.toCharArray(), 0, str.length());
        }

        @Override
        public void write(int single) throws IOException {
            write(new char[]{(char) single}, 0, 1);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() {
            // The caller owns the underlying writer.
        }
    }

}
