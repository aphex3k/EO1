package com.aphex3k.eo1;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The process's only {@link Thread.UncaughtExceptionHandler}: it writes one durable
 * {@link CrashStore} report, then preserves exactly the restart contract the app has always had —
 * arm the +120 s HOME relaunch alarm and {@code System.exit(2)}. It never chains to the previous
 * handler: {@link KioskWatchdog} and {@link UpdateManager} both rely on that pair, and chaining
 * would put the framework's own crash dialog in front of a headless frame.
 *
 * <p>Installed by {@link Eo1Application#attachBaseContext} rather than by an Activity, because the
 * crashes worth reading are the ones that happen before an Activity ever exists — during layout
 * inflation, the manager constructors in {@code MainActivity.onCreate}, or config load.
 *
 * <p>Two rules hold this class together. <b>Nothing in the crash path may throw</b> — the body is
 * wrapped, and the alarm and exit live in a {@code finally} so a reporter that fails still restarts
 * the frame. And <b>it runs the contract once</b> — {@link #writing} is set before any work, so a
 * crash raised while a report is being written returns without touching the half-written file or
 * exiting early.
 *
 * <p>It deliberately never reads {@code configuration.json} or SharedPreferences: a crash report is
 * served over the LAN unauthenticated, and Immich credentials must not be able to ride out in one.
 */
public final class CrashReporter implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "EO1-crash";

    /** How long before the dead process's relaunch alarm fires. */
    public static final long RELAUNCH_DELAY_MS = 120_000L;

    /** Today's exit code, kept so anything observing the process sees no change. */
    static final int EXIT_CODE = 2;

    /** Log-ring lines copied into each report as the lead-up. */
    private static final int LEAD_UP_LINES = 50;

    /**
     * Arms the restart. Kept separate from {@code uncaughtException} so it can be faked in tests:
     * the observable contract of this class is "we always come back".
     */
    public interface Relaunch {
        void arm(long delayMs);
    }

    /** Terminates the process. */
    public interface Exiter {
        void exit(int code);
    }

    /**
     * Reads the device/environment facts that go into a report header.
     *
     * <p>A seam because the JVM test classpath stubs {@code android.util.Log} and nothing else:
     * every framework <i>method</i> below throws "Stub!" on a unit test. Keeping them behind one
     * interface is what lets {@link CrashReporter} be tested with a fake instead of requiring the
     * whole of {@code android.os} to be mocked.
     */
    interface Env {
        Map<String, String> snapshot(File baseDir);
    }

    /** The production {@link Env}. Never called from a test. */
    private static final Env DEVICE_ENV = new Env() {
        @Override
        public Map<String, String> snapshot(File baseDir) {
            return deviceFacts(baseDir);
        }
    };

    /**
     * The identity block every report header starts with. Package-private and {@code File}-based
     * because {@link DiagnosticsReport} headers a report of its own with the same facts, and the
     * two must not be able to disagree about what {@code Android} or {@code Disk-Free-Bytes} means.
     *
     * <p>Nothing here may reach a binder: a header key that can block is how a capture thread gets
     * stranded past its deadline, which is why the signing verdict lives in a budgeted section
     * instead. {@code StatFs} and {@code SystemClock} are the only two calls that leave the process,
     * and neither can wait on another party.
     */
    static Map<String, String> deviceFacts(File baseDir) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Version-Name", String.valueOf(BuildConfig.VERSION_NAME));
        h.put("Version-Code", Integer.toString(BuildConfig.VERSION_CODE));
        h.put("Build-Type", BuildConfig.DEBUG ? "debug" : "release");
        h.put("Device-Manufacturer", String.valueOf(Build.MANUFACTURER));
        h.put("Device-Model", String.valueOf(Build.MODEL));
        h.put("Device-Name", String.valueOf(Build.DEVICE));
        h.put("Android", String.valueOf(Build.VERSION.RELEASE)
                + " (API " + Build.VERSION.SDK_INT + ")");
        Runtime rt = Runtime.getRuntime();
        h.put("Memory-Bytes", (rt.totalMemory() - rt.freeMemory()) + "/" + rt.maxMemory());
        h.put("Disk-Free-Bytes", Long.toString(freeBytes(baseDir)));
        h.put("Uptime-Millis", Long.toString(SystemClock.uptimeMillis()));
        h.put("Elapsed-Realtime-Millis", Long.toString(SystemClock.elapsedRealtime()));
        return h;
    }

    /** A header with nothing but what a test supplies. */
    private static final Env NULL_ENV = new Env() {
        @Override
        public Map<String, String> snapshot(File baseDir) {
            return new LinkedHashMap<>();
        }
    };

    /** The production pair: an AlarmManager one-shot into HOME, then {@code System.exit(2)}. */
    private static final class AndroidTransport implements Relaunch, Exiter {
        private final Context context;

        AndroidTransport(Context context) {
            this.context = context;
        }

        @Override
        public void arm(long delayMs) {
            relaunchAfter(context, delayMs);
        }

        @Override
        public void exit(int code) {
            System.exit(code);
        }
    }

    private static volatile CrashReporter instance;

    private final File baseDir;
    private final Relaunch relaunch;
    private final Exiter exiter;
    private final Env env;
    private final AtomicInteger seq = new AtomicInteger();

    /** Null until {@link Eo1Application} has built the shared logger. */
    private volatile AppLogger logger;

    /** Which optional capture sources worked, recorded into every report header. */
    private volatile Map<String, String> sourceAvailability = new LinkedHashMap<>();

    private final java.util.concurrent.atomic.AtomicBoolean writing =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Test constructor: no framework reads. */
    CrashReporter(File baseDir, Relaunch relaunch, Exiter exiter) {
        this(baseDir, relaunch, exiter, NULL_ENV);
    }

    CrashReporter(File baseDir, Relaunch relaunch, Exiter exiter, Env env) {
        this.baseDir = baseDir;
        this.relaunch = relaunch;
        this.exiter = exiter;
        this.env = env;
    }

    /**
     * Installs the process-wide reporter. Safe to call before any Activity exists.
     */
    public static void install(Context app) {
        Context owner = app.getApplicationContext() != null ? app.getApplicationContext() : app;
        AndroidTransport transport = new AndroidTransport(owner);
        CrashReporter reporter = new CrashReporter(CrashStore.dirFor(owner), transport, transport,
                DEVICE_ENV);
        instance = reporter;
        Thread.setDefaultUncaughtExceptionHandler(reporter);
    }

    /** The installed reporter, or {@code null} before {@link #install}. */
    public static CrashReporter get() {
        return instance;
    }

    /** Attaches the process's single {@link AppLogger}, enabling the lead-up section. */
    public void setLogger(AppLogger logger) {
        this.logger = logger;
    }

    /** Records what the startup scrapers found, for the report header. */
    public void setSourceAvailability(Map<String, String> sources) {
        this.sourceAvailability = sources != null ? sources : new LinkedHashMap<String, String>();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        // Claimed atomically, before anything else. Two threads dying at once is the normal shape of
        // a wedged device, and a check-then-set would let both write — the loser's exit() would kill
        // the winner mid-render, so the surviving report would be whichever trace mattered least.
        // One dump per process is the design; everyone else still gets a line in the log.
        if (!writing.compareAndSet(false, true)) {
            try {
                AppLogger log = this.logger;
                if (log != null) {
                    log.error(TAG, "also uncaught on "
                            + (thread != null ? thread.getName() : "?") + ":", throwable);
                }
            } catch (Throwable ignored) {
                // best effort
            }
            return;
        }
        try {
            record(thread, throwable);
        } catch (Throwable ignored) {
            // A reporter that cannot report must still restart the frame.
        } finally {
            try {
                relaunch.arm(RELAUNCH_DELAY_MS);
            } catch (Throwable ignored) {
            }
            try {
                exiter.exit(EXIT_CODE);
            } catch (Throwable ignored) {
                // If even exit is unavailable, returning lets the dying thread die on its own.
            }
        }
    }

    private void record(Thread thread, Throwable throwable) {
        AppLogger log = this.logger;
        try {
            if (log != null) {
                // Include the stack trace: a bare toString() only says the exception type, and
                // the device is often unreachable via adb — /logs is the only other source.
                log.error(TAG, "FATAL uncaught on " + (thread != null ? thread.getName() : "?") + ":",
                        throwable);
            } else {
                Log.e(TAG, "FATAL uncaught", throwable);
            }
        } catch (Throwable ignored) {
            // best effort
        }

        CrashStore.DumpContent content = new CrashStore.DumpContent();
        content.epochMillis = System.currentTimeMillis();
        content.seq = seq.getAndIncrement() % 1000;
        content.kind = CrashStore.KIND_JAVA;
        content.versionCode = BuildConfig.VERSION_CODE;
        content.error = throwable;
        content.header = header(thread, throwable);
        try {
            content.leadUp = log != null ? log.tail(LEAD_UP_LINES) : null;
        } catch (Throwable ignored) {
            // An unavailable log must not cost us the trace.
        }

        try {
            File written = CrashStore.write(baseDir, content);
            CrashStore.prune(baseDir, System.currentTimeMillis());
            Log.e(TAG, "crash report written: " + (written != null ? written.getName() : "?"));
        } catch (Throwable t) {
            // Disk full or unwritable: the rolling log line above is all that survives.
            Log.e(TAG, "could not write crash report: " + t);
        }
    }

    /**
     * Everything that identifies this death without the trace: when, which build (the
     * {@code Version-Code} from {@link #env} is what picks the {@code mapping-<vc>.txt.gz} that
     * retraces it), where, and what each optional capture source found.
     */
    private Map<String, String> header(Thread thread, Throwable throwable) {
        long now = System.currentTimeMillis();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Local-Time", CrashStore.localTime(now));
        h.put("Epoch-Millis", Long.toString(now));
        try {
            h.putAll(env.snapshot(baseDir));
        } catch (Throwable t) {
            h.put("Env-Error", String.valueOf(t));
        }
        h.put("Thread", thread != null ? str(thread.getName()) : "unknown");
        h.putAll(sourceAvailability);
        return h;
    }

    private static long freeBytes(File baseDir) {
        try {
            File path = baseDir != null ? baseDir.getParentFile() : Environment.getDataDirectory();
            StatFs fs = new StatFs(path != null ? path.getAbsolutePath()
                    : Environment.getDataDirectory().getAbsolutePath());
            return fs.getAvailableBlocks() * (long) fs.getBlockSize();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    /**
     * Arms the HOME relaunch the dead process used to schedule itself. Shared with
     * {@link UpdateManager}, which restarts after an install the same way, so the two crash-restart
     * paths cannot drift.
     *
     * <p>Builds its own intent rather than reusing an Activity's: a crash may happen when no
     * Activity has ever run, and the launcher intent is not available from an Application.
     */
    static void relaunchAfter(Context context, long delayMs) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(new ComponentName(context.getPackageName(),
                            MainActivity.class.getName()));
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(context, 0, home,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.AlarmManager am = (android.app.AlarmManager)
                    context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + delayMs, pi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "relaunch alarm failed: " + t);
        }
    }
}
