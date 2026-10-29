package com.aphex3k.eo1;

import android.content.Context;

import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Durable store of crash diagnostics: {@code filesDir/crashes}, one plaintext report per event.
 *
 * <p>Everything here operates on a plain {@link File baseDir} rather than a {@link Context} so it is
 * unit-testable on the JVM; {@link #dirFor(Context)} is the only Context-bound entry point. This
 * mirrors {@link UploadedMedia}, including its name-safety rules.
 *
 * <p>The listing metadata lives in the <b>file name</b> — {@code crash-<epochMillis>-<seq>-<kind>-
 * <versionCode>.txt} — so {@code /crashes} can be built without reading a single body. The version
 * code is in the name because it is the only link from a dump to the R8 mapping file that retraces
 * it ({@code mapping-<versionCode>.txt.gz}, archived by {@code publish-update.sh}).
 *
 * <p>Nothing in this class may assume it is running on a healthy process: {@code write} is called
 * from inside {@code uncaughtException}. It therefore throws {@link IOException} on failure and
 * leaves every recovery decision to {@link CrashReporter}, and {@link #prune} never throws at all.
 */
public final class CrashStore {

    /** Directory name under {@code getFilesDir()} holding crash reports. */
    public static final String DIR_NAME = "crashes";

    /** A report older than this is evicted regardless of how little space the store uses. */
    public static final long RETENTION_MS = 14L * 24L * 60L * 60L * 1000L;

    /** Total store cap; past this the oldest reports are evicted, newest always kept. */
    public static final long MAX_TOTAL_BYTES = 4L * 1024L * 1024L;

    /** Cap on the stack trace + lead-up written into one report. */
    public static final long MAX_DUMP_BYTES = 128L * 1024L;

    /** How much of a report {@link #readMeta} reads to pick up the header. */
    static final int HEADER_READ_BYTES = 1024;

    /** Report kinds, encoded in the file name. */
    public static final String KIND_JAVA = "java";
    public static final String KIND_LOGCAT = "logcat";
    public static final String KIND_DROPBOX = "dropbox";
    public static final String KIND_ANR = "anr";

    static final String FORMAT_TAG = "eo1-crash/1";

    /** Header key naming a copied artifact's source, as {@code <name>|<length>|<lastModified>}. */
    static final String HEADER_ORIGIN = "Origin";

    private static final String PREFIX = "crash-";
    private static final String SUFFIX = ".txt";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private CrashStore() {
    }

    /**
     * Returns the crash directory, creating it if necessary. A failure to create it is not fatal:
     * every listing returns empty and every write reports {@link IOException} to its caller.
     */
    public static File dirFor(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            // best effort
        }
        return dir;
    }

    /**
     * Builds a report name. {@code epochMillis} and {@code seq} are fixed-width so that a plain
     * numeric comparison of the prefix orders the store newest-first.
     */
    public static String newName(long epochMillis, int seq, String kind, int versionCode) {
        return PREFIX
                + pad(Long.toString(Math.max(0L, epochMillis)), 13)
                + "-" + pad(Integer.toString(Math.max(0, Math.min(999, seq))), 3)
                + "-" + (kind == null ? KIND_JAVA : kind)
                + "-" + Math.max(0, versionCode)
                + SUFFIX;
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(width);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    /**
     * Writes {@code content} into {@code baseDir} as one report: a temporary file, fsynced, then
     * renamed into place, so a process that dies mid-write can only leave a {@code .tmp} we ignore.
     *
     * @return the file written
     * @throws IOException if the directory or the report could not be written
     */
    public static File write(File baseDir, DumpContent content) throws IOException {
        if (baseDir == null) {
            throw new IOException("no crash directory");
        }
        if (content == null) {
            throw new IOException("no crash content");
        }
        if (!baseDir.isDirectory() && !baseDir.mkdirs()) {
            throw new IOException("cannot create " + baseDir.getAbsolutePath());
        }
        IOException first = null;
        // seq is per-process; collide only when two dumps land in the same millisecond with the
        // same counter value (a restored process, or a hand-copied file), so retry a few names.
        for (int attempt = 0; attempt < 64; attempt++) {
            File target = new File(baseDir,
                    newName(content.epochMillis, content.seq + attempt, content.kind,
                            content.versionCode));
            if (target.exists()) {
                continue;
            }
            File tmp = new File(baseDir, target.getName() + ".tmp");
            try {
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    Writer w = new OutputStreamWriter(out, UTF_8);
                    render(w, content);
                    w.flush();
                    out.getFD().sync();
                } finally {
                    closeQuietly(out);
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete();
                    throw new IOException("cannot rename crash report");
                }
                return target;
            } catch (IOException e) {
                tmp.delete();
                if (first == null) {
                    first = e;
                }
            }
        }
        throw (first != null ? first : new IOException("no free crash report name"));
    }

    /**
     * Renders one report: {@link #FORMAT_TAG} marker, a {@code Key: value} header, the stack trace
     * (or the copied text), the log lead-up, then a JSON block for tooling.
     *
     * <p>The trace is streamed straight into {@code w} rather than formatted into a {@link String}:
     * this runs inside a crash handler on a device with ~800 MB of storage and a small heap, and a
     * 10 000-deep cause chain materialized as text could itself be what fails.
     */
    static void render(Writer w, DumpContent c) throws IOException {
        w.write(FORMAT_TAG);
        w.write('\n');
        Map<String, String> header = identity(c);
        for (Map.Entry<String, String> entry : header.entrySet()) {
            w.write(entry.getKey());
            w.write(": ");
            w.write(oneLine(entry.getValue()));
            w.write('\n');
        }
        w.write('\n');

        BudgetedWriter budget = new BudgetedWriter(w);
        w.write("---- ");
        w.write(c.error != null ? "stack trace" : "captured output");
        w.write(" ----\n");
        if (c.error != null) {
            printTrace(budget, c.error);
        } else if (c.text != null) {
            budget.write(c.text);
            budget.write('\n');
        } else if (c.source != null) {
            // A copied ANR trace or dropbox entry can be megabytes; stream it past the budget
            // instead of holding it in memory on an 800 MB device.
            Reader r = null;
            try {
                java.io.InputStream raw = new FileInputStream(c.source);
                // The framework gzips its own crash artifacts (SYSTEM_TOMBSTONE@<n>.txt.gz is where
                // a native death actually lands on this ROM), so a copy has to decompress to be
                // worth reading. GZIPInputStream has existed since API 1.
                if (c.source.getName().endsWith(".gz")) {
                    raw = new java.util.zip.GZIPInputStream(raw, 8192);
                }
                r = new java.io.InputStreamReader(raw, UTF_8);
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    budget.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // The source vanished mid-copy; the marker below says the section is short.
            } finally {
                closeQuietly(r);
            }
        }
        boolean traceTruncated = budget.truncated();
        if (traceTruncated) {
            w.write("\n[");
            w.write(c.error != null ? "stack trace" : "captured output");
            w.write(" truncated at ");
            w.write(Long.toString(MAX_DUMP_BYTES));
            w.write(" bytes]\n");
        }

        w.write("\n---- last log lines ----\n");
        budget.nextSection();
        if (c.leadUp == null || c.leadUp.isEmpty()) {
            w.write("(none)\n");
        } else {
            budget.write(c.leadUp);
            if (budget.truncated()) {
                w.write("[lead-up truncated at ");
                w.write(Long.toString(MAX_DUMP_BYTES));
                w.write(" bytes]\n");
            }
        }

        w.write("\nDeveloper information (JSON):\n");
        w.write(c.json(traceTruncated).toString());
        w.write('\n');
    }

    /** Cause-chain depth beyond which a report says "truncated" instead of walking further. */
    private static final int MAX_CAUSES = 200;

    /**
     * Writes a throwable's trace in the standard {@code java.lang.Foo: msg / tab at / Caused by:}
     * form, walking the cause chain <b>iteratively</b>.
     *
     * <p>Not {@code Throwable.printStackTrace(PrintWriter)}: that recurses once per cause, so a deep
     * chain raises {@link StackOverflowError} — which, called from inside a crash handler, lands in
     * the one place a lost dump is both likely and fatal: the dying thread's stack is what is already
     * in doubt. Walking it here also lets the byte budget stop the walk rather than merely discarding
     * characters while the recursion runs on.
     */
    private static void printTrace(BudgetedWriter w, Throwable error) throws IOException {
        Throwable cur = error;
        int depth = 0;
        while (cur != null) {
            if (depth == MAX_CAUSES) {
                w.write("[cause chain truncated at " + MAX_CAUSES + " causes]\n");
                return;
            }
            if (depth > 0) {
                w.write("Caused by: ");
            }
            w.write(cur.getClass().getName());
            String msg = cur.getMessage();
            if (msg != null) {
                w.write(": ");
                w.write(msg);
            }
            w.write('\n');
            StackTraceElement[] frames = cur.getStackTrace();
            if (frames != null) {
                for (StackTraceElement frame : frames) {
                    w.write("\tat ");
                    w.write(String.valueOf(frame));
                    w.write('\n');
                }
            }
            Throwable[] suppressed = cur.getSuppressed();
            if (suppressed != null && suppressed.length > 0) {
                // Named but not expanded: suppressed exceptions nest, and expanding them here is
                // how this method would become recursive again.
                w.write("\t... " + suppressed.length + " suppressed: ");
                for (int i = 0; i < suppressed.length; i++) {
                    if (i > 0) {
                        w.write(", ");
                    }
                    w.write(String.valueOf(suppressed[i].getClass().getName()));
                }
                w.write('\n');
            }
            if (w.truncated()) {
                return;
            }
            Throwable next = cur.getCause();
            cur = next == cur ? null : next;
            depth++;
        }
    }

    /**
     * The same cause-chain walk {@link #printTrace} performs, rendered into one String for callers with
     * no {@code BudgetedWriter} to hand — {@link AppLogger} is the one that matters, because both the
     * {@code /logs} ring and a report's lead-up section hold a trace as a single string.
     *
     * <p>Iterative for the same reason {@code printTrace} is: {@code Throwable.printStackTrace}
     * recurses once per cause, and the caller that needs this is {@code CrashReporter.record()}, which
     * runs on the thread that is already dying — the one place a lost dump is both likely and fatal.
     *
     * <p>It is {@code printTrace} itself over a {@link BudgetedWriter} that drains into a string, not a
     * second walk: a bound that is checked between throwables would let one throwable with a long
     * message or a deep stack come back oversized, and {@link AppLogger} would then hold and write all
     * of it on the crash-handling thread.
     */
    static String traceToString(Throwable error) {
        java.io.StringWriter sw = new java.io.StringWriter();
        BudgetedWriter w = new BudgetedWriter(sw);
        try {
            printTrace(w, error);
            w.flush();
        } catch (IOException ignored) {
            // A StringWriter cannot fail; the only bound is the one BudgetedWriter applies.
        }
        if (w.truncated()) {
            // Same wording the report body uses, so the two renderings of one trace cannot diverge.
            sw.write("[stack trace truncated at " + MAX_DUMP_BYTES + " bytes]\n");
        }
        return sw.toString();
    }

    /**
     * The caller's header plus the identity fields, which come from the record itself and never from
     * what a caller remembered to add: {@code Version-Code} is the only thing that picks the mapping
     * file able to retrace the trace below it, so a report missing one is a report that can never be
     * read.
     */
    private static Map<String, String> identity(DumpContent c) {
        Map<String, String> h = new java.util.LinkedHashMap<>();
        // Clamped on the way in, because both the plaintext header and the JSON trailer are built
        // from this map: one bound covers both, and no caller has to remember to size its own value.
        for (Map.Entry<String, String> entry : c.header.entrySet()) {
            h.put(entry.getKey(), clamp(entry.getValue()));
        }
        // Written last so a caller cannot put the wrong mapping key on its own trace.
        h.put("Kind", c.kind);
        h.put("Epoch-Millis", Long.toString(c.epochMillis));
        h.put("Version-Code", Integer.toString(c.versionCode));
        if (c.error != null) {
            h.put("Exception", c.error.getClass().getName());
            // Bounded here, not at write time: the header and the JSON trailer both come from this
            // map, and a getMessage() that carries a response body or a long path would otherwise
            // be written (and then duplicated into the JSON) with nothing capping it — inside the
            // crash handler, where allocating megabytes is how a report fails to exist at all.
            h.put("Message", clamp(String.valueOf(c.error.getMessage())));
        }
        return h;
    }

    /** Cap on one header value; the trace has its own, larger budget. */
    static final int MAX_HEADER_VALUE = 2000;

    private static String clamp(String s) {
        if (s == null || s.length() <= MAX_HEADER_VALUE) {
            return s;
        }
        return s.substring(0, MAX_HEADER_VALUE) + "...[" + s.length() + " chars]";
    }

    /** Package-private: {@link CrashSources} names a failure with it in a report header. */
    static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
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
        return list(baseDir, true);
    }

    /**
     * @param readHeaders whether to open each file for its header keys. Every name-only caller
     *                    ({@code /state}'s summary, retention pruning) skips them: this is a device
     *                    slow enough that reading a kilobyte per report per request is noticeable.
     */
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

    /**
     * Reads one report's name metadata plus the first kilobyte of its header.
     *
     * @return the info, or {@code null} if the file is not readable
     */
    public static Info readMeta(File f) {
        return f != null && f.isFile() ? fromFile(f, true) : null;
    }

    private static Info fromFile(File f, boolean readHeaders) {
        long[] stamp = parseStamp(f.getName());
        Info info = new Info();
        info.name = f.getName();
        info.sizeBytes = f.length();
        info.lastModified = f.lastModified();
        if (stamp != null) {
            info.parsed = true;
            info.epochMillis = stamp[0];
            info.seq = (int) stamp[1];
            info.kind = kindOf(f.getName());
            info.versionCode = versionCodeOf(f.getName());
        } else {
            info.epochMillis = info.lastModified;
            info.kind = "unknown";
        }
        if (readHeaders) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new java.io.InputStreamReader(new FileInputStream(f), UTF_8),
                        HEADER_READ_BYTES);
                char[] buf = new char[HEADER_READ_BYTES];
                int n = r.read(buf);
                String head = new String(buf, 0, Math.max(0, n));
                info.exception = headerValue(head, "Exception");
                info.origin = headerValue(head, HEADER_ORIGIN);
            } catch (IOException ignored) {
                // An unreadable body still lists, from its name alone.
            } finally {
                closeQuietly(r);
            }
        }
        return info;
    }

    /**
     * Extracts {@code <epochMillis>, <seq>} from a report name, or {@code null} if it does not
     * match {@link #newName}.
     */
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

    private static String kindOf(String name) {
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

    /** Deletes one report by explicit operator action. */
    public static boolean delete(File baseDir, String name) {
        File f = resolve(baseDir, name);
        return f != null && f.delete();
    }

    /**
     * Enforces {@link #RETENTION_MS} and {@link #MAX_TOTAL_BYTES}, oldest first, always keeping the
     * newest report. Never throws: it runs both after a successful write (from the crash path) and
     * at startup.
     */
    public static Stats prune(File baseDir, long nowMs) {
        cleanTempFiles(baseDir);
        Info[] infos;
        try {
            infos = list(baseDir, false);
        } catch (Throwable t) {
            return new Stats(0, 0L, 0L, 0);
        }
        long total = 0L;
        for (Info info : infos) {
            total += info.sizeBytes;
        }
        for (int i = infos.length - 1; i >= 1; i--) {
            if (nowMs - infos[i].epochMillis > RETENTION_MS) {
                if (delete(baseDir, infos[i].name)) {
                    total -= infos[i].sizeBytes;
                }
            }
        }
        // list() is newest-first, so walking the tail evicts oldest. Index 0 is never removed:
        // an empty store is worse than one that is slightly over budget.
        for (int i = infos.length - 1; i >= 1 && total > MAX_TOTAL_BYTES; i--) {
            if (delete(baseDir, infos[i].name)) {
                total -= infos[i].sizeBytes;
            }
        }
        Info[] remaining = list(baseDir, false);
        return statsOf(remaining);
    }

    /**
     * Removes {@code <name>.tmp} left behind by a write whose process died mid-rename. Those are
     * invisible to {@link #list} (they do not end in {@code .txt}), so without this sweep every
     * crash-on-crash adds a stranded file that no route serves and no retention rule evicts.
     *
     * <p>Only stale ones go: a {@code .tmp} younger than this may belong to a write in flight on
     * another thread.
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

    /** Summarises the store without pruning it, and without opening a single report. */
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
                infos.length == 0 ? 0 : infos[0].versionCode);
    }

    /** The {@code /state} {@code crash} block, minus the source-availability fields. */
    public static JsonObject statsJson(File baseDir) {
        Stats s = stats(baseDir);
        JsonObject o = new JsonObject();
        o.addProperty("count", s.count);
        o.addProperty("lastAtMs", s.lastAtMs);
        o.addProperty("lastVersionCode", s.lastVersionCode);
        o.addProperty("totalBytes", s.totalBytes);
        return o;
    }

    /**
     * A report's listing metadata, from its name plus the first kilobyte of its header.
     */
    public static final class Info {
        public String name;
        public String kind = "unknown";
        public long epochMillis;
        public int seq;
        public int versionCode;
        public long sizeBytes;
        public long lastModified;
        public String exception;
        public String origin;
        boolean parsed;
    }

    /**
     * What to write. The body comes from the first non-null of {@link #error} (a Java crash),
     * {@link #text} (captured command output), or {@link #source} (a copied artifact file);
     * {@link #leadUp} is the recent log tail.
     */
    public static final class DumpContent {
        public long epochMillis = System.currentTimeMillis();
        public int seq;
        public String kind = KIND_JAVA;
        public int versionCode;
        public Throwable error;
        public String text;
        public File source;
        public String leadUp;
        public Map<String, String> header = new java.util.LinkedHashMap<>();

        JsonObject json(boolean traceTruncated) {
            JsonObject o = new JsonObject();
            o.addProperty("format", FORMAT_TAG);
            for (Map.Entry<String, String> entry : identity(this).entrySet()) {
                o.addProperty(entry.getKey(), entry.getValue());
            }
            o.addProperty("traceTruncated", traceTruncated);
            return o;
        }
    }

    /**
     * A store snapshot.
     */
    public static final class Stats {
        public final int count;
        public final long totalBytes;
        public final long lastAtMs;
        public final int lastVersionCode;

        Stats(int count, long totalBytes, long lastAtMs, int lastVersionCode) {
            this.count = count;
            this.totalBytes = totalBytes;
            this.lastAtMs = lastAtMs;
            this.lastVersionCode = lastVersionCode;
        }
    }

    /**
     * Passes through at most {@link #MAX_DUMP_BYTES} characters per section and then silently drops
     * the rest, recording that it did. Dropping rather than throwing is what lets a runaway cause
     * chain be bounded without an exception escaping a crash path.
     */
    private static final class BudgetedWriter extends Writer {
        private final Writer out;
        private long remaining;
        private boolean dropped;

        BudgetedWriter(Writer out) {
            this.out = out;
            this.remaining = MAX_DUMP_BYTES;
        }

        void nextSection() {
            remaining = MAX_DUMP_BYTES;
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
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() {
            // The caller owns the underlying writer.
        }
    }

    /** Formats an epoch millis as local wall-clock text, for report headers. */
    public static String localTime(long epochMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date(epochMillis));
    }

    /** Renders one report to a string. Used by tests and by scrapers that have no file to stream. */
    static String renderToString(DumpContent c) {
        java.io.StringWriter sw = new java.io.StringWriter();
        try {
            render(sw, c);
        } catch (IOException ignored) {
            // StringWriter does not fail
        }
        return sw.toString();
    }
}
