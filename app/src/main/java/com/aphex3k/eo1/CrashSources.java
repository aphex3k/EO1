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
 * <p>Runs on a background thread ({@code Eo1Application} must not wait on it), once per process and
 * once more if that attempt never publishes, and reports what it found per source, because "nothing
 * crashed" and "we could not look" must not look the same to an operator. {@link
 * Availability#logcatReason} is the other half of that sentence: it says <i>which</i> failure made a
 * source read {@link #UNAVAILABLE}, because one word was five diagnoses and an operator cannot act on
 * a word. Expect {@code dropbox} and {@code anr} to read
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

    /**
     * How long the child gets to reach end of output before we end it. A subprocess we cannot reap is
     * not worth a hung startup thread — and the bound has to be a deadline that destroys the child,
     * because {@code Process.waitFor(long, TimeUnit)} does not exist before API 26.
     */
    static final long TIMEOUT_MS = 3000L;

    /** On top of {@link #TIMEOUT_MS}: how long the reader waits for the reaper's exit status. */
    static final long REAP_GRACE_MS = 1000L;

    /**
     * On top of the two bounds above: when the watchdog gives up on a scrape thread that never
     * published and says so. Slack, not a fifth timeout — a scrape that crosses it is wedged in
     * something this class cannot bound, and that is a reportable fact rather than a silence.
     */
    static final long SCRAPE_BUDGET_MS = TIMEOUT_MS + REAP_GRACE_MS + 3000L;

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

        /**
         * Why {@link #logcat} reads what it does when the answer is not "it worked" — a refused exec,
         * a child that would not end, a non-zero exit, a reap we could not complete, or a buffer past
         * the retention cap. Null when there is nothing to explain.
         *
         * <p>{@link #UNAVAILABLE} alone is five different diagnoses wearing one word, and an operator
         * cannot act on a word. This is the half of the contract that makes a frame that could not
         * look distinguishable from one where nothing crashed.
         */
        public String logcatReason;

        Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("Source-Logcat", logcat);
            m.put("Source-Dropbox", dropbox);
            m.put("Source-Anr", anr);
            if (logcatReason != null) {
                m.put("Source-Logcat-Reason", logcatReason);
            }
            return m;
        }
    }

    private static final String TAG = "EO1-crash";

    private static volatile Availability last = new Availability();

    /**
     * Whether a scrape thread is expected to publish. Cleared by that thread in a {@code finally}, so
     * the watchdog's only read is "has anybody come back yet".
     */
    private static volatile boolean scrapeFinished = true;

    private static volatile java.util.Timer watchdog;

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
        startAttempt(0, context, logger);
    }

    /** How many times a scrape that never published is allowed to be tried again. */
    private static final int MAX_RETRIES = 1;

    private static void startAttempt(final int attempt, final Context context,
            final AppLogger logger) {
        scrapeFinished = false;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Availability a;
                try {
                    a = scrape(CrashStore.dirFor(context), logger, PROCESS_RUNNER,
                            System.currentTimeMillis());
                } catch (Throwable e) {
                    // Diagnostics that cannot be harvested are not worth a crash of their own. The
                    // catch stays; what used to be wrong about it is the silence — this thread is a
                    // daemon with no supervisor, so a swallowed throw here was indistinguishable from
                    // a frame that simply had nothing to find, and logcat holds ~13 minutes.
                    a = new Availability();
                    a.logcatReason = "scrape: " + oneLine(e);
                    try {
                        if (logger != null) {
                            logger.error(TAG, "crash scrape failed", e);
                        }
                    } catch (Throwable ignored) {
                        // Reporting must not become the failure it is reporting.
                    }
                }
                publish(a, logger);
                // After the publish: the flag means "the read is already the real verdict", not
                // merely "a thread stopped", or the watchdog could read a finished scrape as one
                // that never published.
                scrapeFinished = true;
            }
        }, "eo1-crash-scrape");
        t.setDaemon(true);
        t.start();

        // One deadline for the thread, not for the child: every wait inside the scrape is bounded in
        // drain(), so crossing this one means something this class cannot bound has wedged. Say so
        // durably, and try once more — a frame that never hears back should not read "unavailable"
        // until the next reboot just because one scrape got stuck.
        watchdog().schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                if (scrapeFinished) {
                    return;
                }
                Availability a = new Availability();
                a.logcatReason = "scrape did not publish within " + SCRAPE_BUDGET_MS + "ms"
                        + (attempt > 0 ? " (after retry " + attempt + ")" : "");
                publish(a, logger);
                if (attempt < MAX_RETRIES) {
                    startAttempt(attempt + 1, context, logger);
                }
            }
        }, SCRAPE_BUDGET_MS);
    }

    private static java.util.Timer watchdog() {
        synchronized (CrashSources.class) {
            if (watchdog == null) {
                watchdog = new java.util.Timer("eo1-crash-watchdog", true);
            }
            return watchdog;
        }
    }

    /** Publishes one scrape's verdict: the read for {@code /state}, the header for reports, the log. */
    private static void publish(Availability a, AppLogger logger) {
        last = a;
        try {
            CrashReporter reporter = CrashReporter.get();
            if (reporter != null) {
                reporter.setSourceAvailability(a.asMap());
            }
        } catch (Throwable ignored) {
            // best effort
        }
        try {
            if (logger != null) {
                logger.info(TAG, "crash sources: logcat=" + a.logcat
                        + " dropbox=" + a.dropbox + " anr=" + a.anr
                        + " copies=" + a.copies
                        + (a.logcatReason == null ? "" : " reason=" + a.logcatReason));
            }
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /**
     * A throwable named on one line. These strings become report header values and {@code /state}
     * fields, and an exception message is free to contain a newline — so the same rule
     * {@link CrashStore} applies to every header value applies here too.
     */
    private static String oneLine(Throwable t) {
        return CrashStore.oneLine(t == null ? "null" : t.toString());
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
        Drain d;
        try {
            d = drain(runner.start(LOGCAT_NATIVE));
        } catch (Throwable t) {
            out.logcat = UNAVAILABLE;
            out.logcatReason = "exec: " + oneLine(t);
            return;
        }
        if (d.terminated) {
            // We ended this child, so its non-zero exit (or the read error our own close provoked) is
            // our doing and not evidence that the buffer was unreadable. What it wrote before that
            // point is real output, and the whole reason the drain reads to end of output is to keep
            // it: discarding a partial capture is how a slow-but-working logcat lost its crash lines.
            out.logcatReason = "ended by us at the read deadline ("
                    + (TIMEOUT_MS + REAP_GRACE_MS) + "ms); partial capture of "
                    + d.text.length() + " chars retained"
                    + (d.exitKnown && d.exit != 0 ? ", exited " + d.exit : "")
                    + (d.error != null ? ", read: " + oneLine(d.error) : "");
        } else {
            if (d.error != null) {
                out.logcat = UNAVAILABLE;
                out.logcatReason = "read: " + oneLine(d.error);
                return;
            }
            if (!d.exitKnown) {
                out.logcat = UNAVAILABLE;
                out.logcatReason = "no exit status within " + REAP_GRACE_MS
                        + "ms of the last byte";
                return;
            }
            // Must be read, not ignored: the drain merges stderr, so a rejected logcat — an unknown
            // buffer name, or READ_LOGS denied on a sideload — yields a non-empty string of error text
            // that would otherwise be stored as if it were the crash it never read.
            if (d.exit != 0) {
                out.logcat = UNAVAILABLE;
                out.logcatReason = "logcat exited " + d.exit;
                return;
            }
        }
        if (d.truncated) {
            // Appended, not assigned: a read can be both past the retention cap and ended by our
            // deadline, and dropping either fact would misreport what was actually looked at.
            out.logcatReason = (out.logcatReason == null ? "" : out.logcatReason + "; ")
                    + "buffer exceeded " + MAX_OUTPUT_CHARS + " chars; scanned the oldest part only";
        }
        String crash = filterNativeCrashLines(d.text);
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
            out.logcatReason = "store: could not write the report into " + baseDir.getName();
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

    /** What reading one child to a bounded end produced, and how it ended. */
    static final class Drain {
        /** The output, at most {@link CrashSources#MAX_OUTPUT_CHARS} characters of it. */
        String text = "";
        /** Set by the reader under {@code lock}: it has stopped reading, however it stopped. */
        boolean readDone;
        /** True when the child reached end of output, as opposed to us giving up on it. */
        boolean complete;
        /**
         * True when we ended the child — at the deadline, or because the read stopped without EOF. Its
         * exit status is then <i>our</i> kill rather than its own failure, which is the difference
         * between discarding a working capture and keeping a refused one.
         */
        boolean terminated;
        /** True when there was more output than the retention cap could hold. */
        boolean truncated;
        int exit;
        boolean exitKnown;
        /** Why the read itself failed, if it did. */
        Throwable error;
    }

    /**
     * Reads a {@code -d}-style child to completion and returns only its text. Kept because the
     * discipline it encodes is what tests assert; {@link #drain} is the real thing.
     */
    static String capture(final Process process) throws IOException {
        Drain d = drain(process);
        if (d.error != null) {
            if (d.error instanceof IOException) {
                throw (IOException) d.error;
            }
            throw new IOException(String.valueOf(d.error));
        }
        return d.text;
    }

    /**
     * Reads {@code process} to end of output or to the deadline, whichever comes first, and always
     * leaves it reaped.
     *
     * <p><b>Exactly one thread ever destroys or waits on the child:</b> the reaper. The reader closes
     * the stream it was handed and touches nothing else. That is why this is two threads and a monitor
     * rather than a read loop with a {@code finally}. Until it was one thread doing both, the read
     * thread called {@code destroy()} and then {@code waitFor()} while the deadline helper was still
     * inside {@code destroy()} on the same process — and {@code java.lang.UNIXProcess} hands the exit
     * status to the first waiter and can leave a second waiting for a notification already delivered.
     * The scrape thread parked there, on a frame with nothing wrong with it, and a startup scrape that
     * never returns publishes no verdict at all: every wait in this class is now bounded, and none of
     * them is on the calling thread's unbounded goodwill.
     *
     * <p>Reading to end of output rather than stopping at the byte cap matters twice over: the child is
     * never left blocked writing into a pipe nobody empties, and the cap then bounds what is
     * <i>kept</i> instead of what is <i>seen</i>.
     */
    static Drain drain(final Process process) {
        final Drain d = new Drain();
        final Object lock = new Object();
        final long beganAt = System.currentTimeMillis();
        Thread reaper = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean abandoned;
                synchronized (lock) {
                    long left;
                    while (!d.readDone
                            && (left = beganAt + TIMEOUT_MS - System.currentTimeMillis()) > 0) {
                        try {
                            lock.wait(left);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    abandoned = !d.readDone;
                    if (abandoned) {
                        d.terminated = true;
                    }
                }
                if (abandoned) {
                    // BEFORE destroy(), not after it. destroy() can itself block — that is the failure
                    // mode this class exists to survive — and readChild runs on the calling thread, so
                    // a blocked destroy() would keep drain() from ever reaching the bounded wait below.
                    // Closing the pipe is what releases a reader parked inside read(); abandoning the
                    // read beats parking the scrape.
                    try {
                        process.getInputStream().close();
                    } catch (Throwable ignored) {
                        // best effort
                    }
                }
                // Always: destroying a child that already exited is a no-op, and leaving one running
                // because the read went cleanly is the leak this thread exists to prevent.
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // best effort
                }
                int code = 0;
                boolean known = false;
                try {
                    code = process.waitFor();
                    known = true;
                } catch (Throwable ignored) {
                    // A reap that cannot complete is reported as no status, never as status zero.
                }
                synchronized (lock) {
                    d.exit = code;
                    d.exitKnown = known;
                    lock.notifyAll();
                }
            }
        }, "eo1-logcat-reap");
        reaper.setDaemon(true);
        reaper.start();

        readChild(process, d, lock, beganAt);

        synchronized (lock) {
            long left;
            while (!d.exitKnown
                    && (left = beganAt + TIMEOUT_MS + REAP_GRACE_MS
                            - System.currentTimeMillis()) > 0) {
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return d;
    }

    /**
     * The reader half of {@link #drain}: drain the pipe to EOF, keep at most the cap, never stop
     * draining because the cap is full. Runs on the calling thread.
     */
    private static void readChild(Process process, Drain d, Object lock, long beganAt) {
        StringBuilder sb = new StringBuilder();
        InputStream is = null;
        Reader r = null;
        boolean eof = false;
        Throwable error = null;
        try {
            is = process.getInputStream();
            r = new InputStreamReader(is, Charset.forName("UTF-8"));
            char[] buf = new char[4096];
            int n;
            // The child dies at the deadline whether or not this loop notices, so the clock test here
            // is only against a child that produces output forever and survives destroy().
            while (System.currentTimeMillis() - beganAt <= TIMEOUT_MS) {
                n = r.read(buf);
                if (n < 0) {
                    eof = true;
                    break;
                }
                if (n == 0) {
                    continue;
                }
                int room = MAX_OUTPUT_CHARS - sb.length();
                if (room <= 0) {
                    d.truncated = true;
                } else {
                    sb.append(buf, 0, Math.min(n, room));
                }
            }
        } catch (Throwable t) {
            error = t;
        } finally {
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
            d.text = sb.toString();
            d.complete = eof;
            d.error = error;
            synchronized (lock) {
                // Not reaching EOF means the deadline ended this read, whatever the child's own status
                // then says — so mark it here as well as in the reaper, whose view can be earlier.
                if (!eof) {
                    d.terminated = true;
                }
                d.readDone = true;
                lock.notifyAll();
            }
        }
    }
}
