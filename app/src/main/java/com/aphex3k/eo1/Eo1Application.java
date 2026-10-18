package com.aphex3k.eo1;

import android.content.Context;

import androidx.multidex.MultiDexApplication;

/**
 * Process bootstrap. Exists so that the crash handler and the log outlive any Activity: the app is
 * the device's {@code HOME}, so a throw during {@code MainActivity.onCreate} used to mean the
 * process died with no handler installed at all and no record of why.
 *
 * <p>{@link CrashReporter#install} runs in {@link #attachBaseContext} — the earliest point at which
 * a {@code Context} exists — because everything after it (layout inflation, the manager constructors,
 * config load, update reconciliation) can throw before an Activity could ever hook anything.
 */
public final class Eo1Application extends MultiDexApplication {

    /** The process's single {@link AppLogger}; null only before {@link #onCreate()}. */
    private volatile AppLogger logger;

    @Override
    protected void attachBaseContext(Context base) {
        // MultiDex.install is what this override is for on API 19; nothing may precede it.
        super.attachBaseContext(base);
        try {
            CrashReporter.install(this);
        } catch (Throwable ignored) {
            // A handler that cannot install must not prevent the app from starting.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            AppLogger log = new AppLogger(this);
            this.logger = log;
            CrashReporter reporter = CrashReporter.get();
            if (reporter != null) {
                reporter.setLogger(log);
            }
            CrashSources.start(this, log);
        } catch (Throwable ignored) {
            // Startup diagnostics are optional; MainActivity will build what it needs.
        }
    }

    /** The process's single {@link AppLogger}, or {@code null} if this is not our process owner. */
    public AppLogger logger() {
        return logger;
    }

    /**
     * The shared logger for a Context, so the app keeps exactly one {@link AppLogger} — and one
     * in-memory ring — per process.
     */
    public static AppLogger loggerFor(Context context) {
        if (context instanceof Eo1Application) {
            return ((Eo1Application) context).logger;
        }
        Context app = context != null ? context.getApplicationContext() : null;
        return app instanceof Eo1Application ? ((Eo1Application) app).logger : null;
    }
}
