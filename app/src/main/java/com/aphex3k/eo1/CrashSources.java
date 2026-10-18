package com.aphex3k.eo1;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two startup scrapers that widen capture beyond the app's own Java exceptions: the
 * {@code system} log buffer, where debuggerd writes a native death on this API level, and the
 * framework's {@code /data/system/dropbox} and {@code /data/anr} entries.
 *
 * <p>Runs once per process on a background thread ({@code Eo1Application} must not wait on it) and
 * reports what it found per source, because "nothing crashed" and "we could not look" must not look
 * the same to an operator. Expect {@code dropbox} and {@code anr} to read
 * {@link #UNAVAILABLE} on a frame: neither directory is traversable by a non-system uid on
 * API 19, and this class deliberately does not escalate — harvesting them through
 * {@code DeviceRepair}/{@code AdbdInstallClient} would mean opening TCP adb <i>as root</i> to satisfy
 * a diagnostics read, which the project rules forbid. {@link CrashStore#KIND_JAVA} is the only
 * source that is guaranteed.
 *
 * <p>Anything copied is <b>copied in</b>, never served in place: the report lands in
 * {@code filesDir/crashes} like every other one, so no crash route reaches outside it.
 */
public final class CrashSources {

    /**
     * Starts {@code logcat -d} (dump and exit) on the buffer where a native death actually lands on
     * this platform.
     *
     * <p><b>Not</b> {@code -b crash}: {@code LOG_ID_CRASH} arrived in Android 7.0, and the ROM's own
     * {@code /system/bin/logcat} only knows {@code main}, {@code system} and {@code radio} — asking
     * for {@code crash} fails. On API 19 debuggerd writes the {@code Fatal signal} lines into the
     * {@code system} buffer, so that is what we read and then filter.
     *
     * <p>An absolute-path, compile-time-constant argv — the discipline the project applies to every
     * command line the app runs: never the single-string form, never anything interpolated.
     */
    static final String[] LOGCAT_NATIVE = {"/system/bin/logcat", "-d", "-b", "system"};

    /** Lines in the {@code system} buffer that mean "a process died natively". */
    private static final String[] NATIVE_CRASH_MARKERS = {
            "Fatal signal", "signal 11 (SIGSEGV)", "signal 6 (SIGABRT)", "SIGSEGV", "SIGABRT",
            "*** *** ***", "debuggerd", ">>> ", "Tombstone",
    };

    /** Bound on how much of the buffer we scan, independent of the byte ceiling. */
    static final int MAX_OUTPUT_LINES = 20000;

    /** Fixed source directories. Nothing here is built from a listing or from input. */
    static final String DIR_DROPBOX = "/data/system/dropbox";
    static final String DIR_ANR = "/data/anr";

    /** A subprocess we cannot reap is not worth a hung startup thread. */
    static final long TIMEOUT_MS = 3000L;
    static final int MAX_OUTPUT_CHARS = 512 * 1024;
    static final int MAX_COPIES_PER_DIR = 20;
    static final long COPY_BUDGET_BYTES = 1024L * 1024L;

    /** Per-source outcomes, surfaced verbatim in {@code /state}'s {@code crash.sources} block. */
    public static final String COPIED = "copied";
    public static final String EMPTY = "empty";
    public static final String UNAVAILABLE = "unavailable";
    /** Readable, but every artifact in it was already in the store. */
    public static final String UNCHANGED = "unchanged";
    /** Readable, but this boot's copy budget was already spent — nothing was looked at. */
    public static final String LIMITED = "budget-exhausted";

    /** Seam for tests: starts a constant-argv subprocess and hands back its process. */
    interface CommandRunner {
        Process start(String[] argv) throws IOException;
    }

    private static final CommandRunner PROCESS_RUNNER = new CommandRunner() {
        @Override
        public Process start(String[] argv) throws IOException {
            // Merging stderr means a chatty logcat cannot deadlock on a pipe nobody is reading.
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            return pb.start();
        }
    };

    /**
     * What the last scrape found. Written once, read by the web threads.
     */
    public static final class Availability {
        public String logcat = UNAVAILABLE;
        public String dropbox = UNAVAILABLE;
        public String anr = UNAVAILABLE;
        public int copies;

        Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("Source-Logcat", logcat);
            m.put("Source-Dropbox", dropbox);
            m.put("Source-Anr", anr);
            return m;
        }
    }

    private static volatile Availability last = new Availability();

    private CrashSources() {
    }

    /** The most recent scrape result; never null, and "unavailable" until the first one finishes. */
    public static Availability last() {
        return last;
    }

    /**
     * Starts the scrape thread. Called from {@code Eo1Application#onCreate}, after
     * {@code super.onCreate()} has returned, so the {@code logcat} child does not compete with
     * {@code MainActivity.onCreate} on a device this slow.
     */
    public static void start(final Context context, final AppLogger logger) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Availability a;
                try {
                    a = scrape(CrashStore.dirFor(context), logger, PROCESS_RUNNER,
                            System.currentTimeMillis());
                } catch (Throwable ignored) {
                    // Diagnostics that cannot be harvested are not worth a crash of their own.
                    a = new Availability();
                }
                last = a;
                CrashReporter reporter = CrashReporter.get();
                if (reporter != null) {
                    reporter.setSourceAvailability(a.asMap());
                }
                if (logger != null) {
                    logger.info("EO1-crash", "crash sources: logcat=" + a.logcat
                            + " dropbox=" + a.dropbox + " anr=" + a.anr
                            + " copies=" + a.copies);
                }
            }
        }, "eo1-crash-scrape");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Runs all three harvests into {@code baseDir}. Package-private and dependency-injected so tests
     * can supply a fake {@link CommandRunner} and a temporary directory.
     *
     * @return the same {@code out} instance the caller passed
     */
    /** Production scrape: the two fixed framework directories. */
    static Availability scrape(File baseDir, AppLogger logger, CommandRunner runner, long nowMs) {
        return scrape(baseDir, logger, runner, nowMs, new File(DIR_DROPBOX), new File(DIR_ANR));
    }

    /**
     * The scrape itself, with the source directories as parameters so a test can point them at a
     * fixture instead of a host path that does not exist on a build machine.
     */
    static Availability scrape(File baseDir, AppLogger logger, CommandRunner runner, long nowMs,
            File dropboxDir, File anrDir) {
        Availability out = new Availability();
        Set<String> known = knownOrigins(baseDir);
        harvestLogcat(baseDir, logger, runner, nowMs, known, out);
        long budget = COPY_BUDGET_BYTES;
        budget -= harvestDir(baseDir, dropboxDir, CrashStore.KIND_DROPBOX, logger,
                nowMs, known, out, budget);
        if (budget > 0) {
            harvestDir(baseDir, anrDir, CrashStore.KIND_ANR, logger, nowMs, known, out, budget);
        } else {
            out.anr = LIMITED;
        }
        try {
            CrashStore.prune(baseDir, nowMs);
        } catch (Throwable ignored) {
            // best effort
        }
        return out;
    }

    /** Fingerprints of everything already stored, so a boot loop cannot refill the store with one ANR. */
    private static Set<String> knownOrigins(File baseDir) {
        Set<String> known = new HashSet<>();
        try {
            CrashStore.Info[] infos = CrashStore.list(baseDir);
            int limit = Math.min(infos.length, 50);
            for (int i = 0; i < limit; i++) {
                if (infos[i].origin != null) {
                    known.add(infos[i].origin);
                }
            }
        } catch (Throwable t) {
            // An unreadable store just means we may copy something twice.
        }
        return known;
    }

    private static void harvestLogcat(File baseDir, AppLogger logger, CommandRunner runner,
            long nowMs, Set<String> known, Availability out) {
        String text;
        int exit;
        try {
            Process p = runner.start(LOGCAT_NATIVE);
            text = capture(p);
            // Must be read, not ignored: capture() merges stderr, so a rejected logcat — an unknown
            // buffer name, or READ_LOGS denied on a sideload — yields a non-empty string of error
            // text that would otherwise be stored as if it were the crash it never read.
            exit = p.waitFor();
        } catch (Throwable t) {
            out.logcat = UNAVAILABLE;
            return;
        }
        if (exit != 0) {
            out.logcat = UNAVAILABLE;
            return;
        }
        String crash = filterNativeCrashLines(text);
        if (crash.isEmpty()) {
            // The scrape worked and found nothing native. Distinct from UNAVAILABLE above.
            out.logcat = EMPTY;
            return;
        }
        String origin = "logcat|" + crash.length();
        if (known.contains(origin)) {
            out.logcat = UNCHANGED;
            return;
        }
        if (write(baseDir, logger, CrashStore.KIND_LOGCAT, nowMs, origin, null, crash, null)
                != null) {
            out.logcat = COPIED;
            out.copies++;
        } else {
            out.logcat = UNAVAILABLE;
        }
    }

    /**
     * Keeps only the lines that speak about a native death, plus their {@code F/libc} headers.
     *
     * <p>The whole {@code system} buffer is several hundred kilobytes of vendor chatter (this ROM
     * writes ~10 lines/second from {@code ISystemWriteService} alone); storing it verbatim would
     * fill the retention budget with noise and bury the two lines that matter.
     */
    static String filterNativeCrashLines(String buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int scanned = 0;
        java.util.StringTokenizer lines = new java.util.StringTokenizer(buffer, "\n");
        while (lines.hasMoreTokens() && scanned++ < MAX_OUTPUT_LINES) {
            String line = lines.nextToken();
            for (String marker : NATIVE_CRASH_MARKERS) {
                if (line.contains(marker)) {
                    out.append(line).append('\n');
                    break;
                }
            }
        }
        return out.toString();
    }

    /**
     * Copies the newest text artifacts of one fixed directory.
     *
     * @return bytes charged against the caller's copy budget
     */
    private static long harvestDir(File baseDir, File dir, String kind, AppLogger logger,
            long nowMs, Set<String> known, Availability out, long budget) {
        String outcome = UNAVAILABLE;
        long used = 0L;
        File[] files = null;
        try {
            // listFiles() returns null both when the directory is unreadable and when it is absent;
            // isDirectory() tells the two apart without a second syscall.
            files = dir.isDirectory() ? dir.listFiles() : null;
        } catch (Throwable t) {
            files = null;
        }
        if (files == null) {
            outcome = UNAVAILABLE;
        } else if (files.length == 0) {
            outcome = EMPTY;
        } else {
            List<File> candidates = new ArrayList<>();
            for (File f : files) {
                // Only crash-relevant artifacts, and only ones we can actually read. On this ROM's
                // dump, /data/system/dropbox holds ~159 event_data@*.txt for every one
                // SYSTEM_TOMBSTONE@*.txt.gz — filtering on the suffix alone would spend the whole
                // budget on noise and skip every native crash.
                if (f.isFile() && f.canRead() && isCrashArtifact(f.getName())) {
                    candidates.add(f);
                }
            }
            Collections.sort(candidates, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            int limit = Math.min(candidates.size(), MAX_COPIES_PER_DIR);
            int written = 0;
            boolean skippedDuplicate = false;
            for (int i = 0; i < limit && used < budget; i++) {
                File f = candidates.get(i);
                String origin = f.getName() + '|' + f.length() + '|' + f.lastModified();
                if (known.contains(origin)) {
                    skippedDuplicate = true;
                    continue;
                }
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("Source-Name", f.getName());
                extra.put("Source-Path", dir.getAbsolutePath() + '/' + f.getName());
                extra.put("Source-Mtime", Long.toString(f.lastModified()));
                // The artifact's own time, not this boot's: a tombstone from three weeks ago must
                // not sort as the newest thing in the store, or the byte cap evicts the live
                // java-crash reports an operator is actually chasing while the bulk copy sits
                // there for its full retention window.
                File stored = write(baseDir, logger, kind, Math.min(f.lastModified(), nowMs),
                        origin, f, null, extra);
                if (stored != null) {
                    written++;
                    used += stored.length();
                }
            }
            if (written > 0) {
                outcome = COPIED;
                out.copies += written;
            } else {
                outcome = skippedDuplicate ? UNCHANGED : EMPTY;
            }
        }
        if (CrashStore.KIND_DROPBOX.equals(kind)) {
            out.dropbox = outcome;
        } else {
            out.anr = outcome;
        }
        return used;
    }

    /** @return the report written, or {@code null} if it could not be. */
    private static File write(File baseDir, AppLogger logger, String kind, long nowMs,
            String origin, File source, String text, Map<String, String> extra) {
        CrashStore.DumpContent c = new CrashStore.DumpContent();
        c.epochMillis = nowMs;
        c.seq = SEQ.getAndIncrement() % 1000;
        c.kind = kind;
        c.versionCode = BuildConfig.VERSION_CODE;
        c.source = source;
        c.text = text;
        c.header.put("Local-Time", CrashStore.localTime(nowMs));
        c.header.put("Version-Name", String.valueOf(BuildConfig.VERSION_NAME));
        c.header.put("Build-Type", BuildConfig.DEBUG ? "debug" : "release");
        c.header.put("Device-Model", String.valueOf(Build.MODEL));
        c.header.put(CrashStore.HEADER_ORIGIN, origin);
        if (extra != null) {
            c.header.putAll(extra);
        }
        c.header.put("Note", "captured at startup by a scraper, not a Java uncaught exception");
        try {
            c.leadUp = logger != null ? logger.tail(20) : null;
        } catch (Throwable ignored) {
            // best effort
        }
        try {
            return CrashStore.write(baseDir, c);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether a {@code dropbox}/{@code anr} file name is crash evidence rather than routine
     * framework bookkeeping. Case-insensitive substring match on a fixed list, applied to names
     * listed from a {@code File} that is already known to be a regular file.
     */
    static boolean isCrashArtifact(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.US);
        if (!lower.endsWith(".txt") && !lower.endsWith(".txt.gz")) {
            return false;
        }
        return lower.contains("crash") || lower.contains("tombstone") || lower.contains("anr");
    }

    /** Distinct from {@link CrashReporter}'s counter so a java and a copied dump never collide. */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger(500);

    /**
     * Reads a {@code -d}-style child to completion, bounded by both elapsed time and size.
     *
     * <p>The bound is a helper thread that destroys the process at the deadline, because
     * {@code Process.waitFor(long, TimeUnit)} does not exist before API 26 and a plain
     * {@code waitFor()} before draining can block forever.
     */
    static String capture(final Process process) throws IOException {
        final Thread killer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(TIMEOUT_MS);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }, "eo1-logcat-deadline");
        killer.setDaemon(true);
        killer.start();
        StringBuilder sb = new StringBuilder();
        InputStream is = null;
        Reader r = null;
        try {
            is = process.getInputStream();
            r = new InputStreamReader(is, Charset.forName("UTF-8"));
            char[] buf = new char[4096];
            int n;
            while (sb.length() < MAX_OUTPUT_CHARS && (n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        } finally {
            killer.interrupt();
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                    // best effort
                }
            } else if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
            try {
                process.destroy();
            } catch (Throwable ignored) {
                // best effort
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
                // best effort
            }
        }
        return sb.toString();
    }
}
