package com.aphex3k.eo1;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Liveness watchdog for an unattended frame.
 *
 * <p>Guards one invariant: <b>while the screen should be on, the frame must keep putting new
 * content on it.</b> Every display path in the app ends up in {@code MainActivity.displayPicture}
 * or {@code displayVideo}; if neither has run for {@link #staleLimitMs(long)}, something is wedged
 * — a lost timer tick, a download that never finishes, a main thread blocked in a dead framework's
 * binder call, or a network stack that never came back after a framework restart. None of those are
 * covered by the existing video watchdog, which only watches MediaPlayer position while the
 * rotation loop is already running.
 *
 * <p>Runs on its own {@link Timer} thread rather than the activity's {@code Handler} on purpose:
 * the 2026-10-13 incident blocked the main thread for 15 minutes in a binder call into a deadlocked
 * WindowManager, so a handler-based watchdog would have been blind at exactly the moment it was
 * needed. The timer thread keeps ticking while the UI thread is stuck, which is what makes
 * {@link #LEVEL_SELF_RESTART} possible at all.
 *
 * <p>Recovery is a ladder. Each rung is taken at most once per wedge, only after the previous rung
 * has had time to work; it escalates from cheap and local (re-drive the rotation) to global
 * (reboot the frame), and the reboot rung is opt-in. The clock that drives the ladder restarts
 * whenever the screen goes from legitimately off (quiet hours, power button, manual toggle) to on:
 * time spent dark is not a wedge, and must never count toward one once the frame wakes back up.
 *
 * <p>Every tick is guarded against {@code Throwable}: an exception escaping a {@code TimerTask}
 * reaches the process's {@code UncaughtExceptionHandler}, which calls {@code System.exit(2)} — the
 * watchdog must never itself be the thing that kills the app.
 */
public final class KioskWatchdog {

    /** How often the watchdog evaluates. */
    public static final long TICK_MS = 60_000L;
    /** How often a routine heartbeat line is logged while nothing is wrong. */
    public static final long HEARTBEAT_MS = 10 * 60_000L;

    /** A wedge must survive at least this many rotation intervals before the ladder starts. */
    public static final int STALE_INTERVAL_MULTIPLE = 3;
    /** Lower bound on the staleness limit, so a short interval does not make the ladder twitchy. */
    public static final long STALE_FLOOR_MS = 20 * 60_000L;
    /**
     * Consecutive ticks in which the main thread advanced nothing before it is declared wedged.
     * Deliberately not a wall-clock age window: the EO2 legitimately spends one to two minutes of
     * main-thread work on a cold start (multidex extraction plus the retained-MQTT burst), and
     * killing it mid-startup produced a loop in which no process ever lived long enough to draw a
     * frame. One tick of silence is still not enough either — after a long suspend the tick and its
     * probe both resume un-acked, which would trade a wedge for a nightly self-inflicted restart.
     */
    public static final int MAIN_LOOP_DEAD_TICKS = 2;
    /** A download in flight longer than this is treated as a stalled display. */
    public static final long DOWNLOAD_STALL_MS = 5 * 60_000L;
    /** Offline this long before the ladder may touch the network stack. */
    public static final long OFFLINE_REPAIR_MS = 10 * 60_000L;
    /** Minimum spacing between network-repair attempts. */
    public static final long REPAIR_SPACING_MS = 10 * 60_000L;
    /** Repair attempts before the ladder escalates past the network rung. */
    public static final int MAX_REPAIR_ATTEMPTS = 3;
    /** Minimum spacing between self-restarts of our own process. */
    public static final long SELF_RESTART_COOLDOWN_MS = 10 * 60_000L;

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_FORCE_ROTATION = 1;
    public static final int LEVEL_RECOVER_PLAYBACK = 2;
    public static final int LEVEL_REPAIR_NETWORK = 3;
    public static final int LEVEL_SELF_RESTART = 4;
    public static final int LEVEL_REBOOT = 5;

    /** Age past {@link #staleLimitMs(long)} at which each rung becomes eligible. */
    private static final long[] LEVEL_OFFSETS = {
            0L,
            0L,
            5 * 60_000L,
            10 * 60_000L,
            0L,             // self-restart is driven by main-loop liveness, not by age
            30 * 60_000L,
    };

    /** Everything the watchdog needs to know, supplied by the activity. */
    public interface Host {

        /** Wall-clock ms; consistent within a boot, which is all the ladder needs. */
        long nowMs();

        /** True while another activity (the settings dialog) is in front of us. */
        boolean isPaused();

        boolean screenShouldBeOn();

        /** Configured rotation interval in ms. */
        long intervalMs();

        /** When something was last put on screen; 0 if never. */
        long lastDisplayAtMs();

        /** How long a download has been in flight; 0 when nothing is downloading. */
        long downloadInFlightMs();

        boolean networkAvailable();

        /** How long we have wanted a network and not had one; 0 while connected. */
        long offlineMs();

        /**
         * A counter that increases whenever the main thread runs anything the activity tracks —
         * in practice each {@link #postProbe()} acknowledgement. Only a value that is unchanged
         * between two ticks means the loop did nothing at all in that whole minute.
         */
        long mainLoopProgressToken();

        /** Ask the main thread to ack; the acknowledgement advances the progress token. */
        void postProbe();

        /** Whether the user has allowed the last-resort device reboot. */
        boolean autoRebootEnabled();

        void forceRotation();

        void recoverPlayback();

        /** Nudge the Wi-Fi stack back to life; logs what it did. */
        void repairNetwork();

        /** Restart our own process: relaunch scheduled first, then kill. */
        void selfRestart();

        /** Reboot the whole device through the ROM's adbd. */
        void rebootDevice();

        /** Record a watchdog event in logcat and the file log. */
        void logWatchdogEvent(String message, boolean severe);
    }

    private final Host host;
    private Timer timer = new Timer(true);

    /** Highest rung already acted on for the current wedge; reset once the frame recovers. */
    private int actedLevel = LEVEL_NONE;
    private long lastActionAtMs = 0L;
    private long lastHeartbeatAtMs = 0L;
    private long lastSelfRestartAtMs = 0L;
    private long lastRepairAtMs = 0L;
    private int repairAttempts = 0;
    /** True when the ladder ended at a reboot rung the user has not permitted. */
    private boolean rebootBlocked = false;
    /** Consecutive ticks in which the main thread advanced nothing. */
    private int probeMisses = 0;
    /** Progress token seen at the last check; baselined by {@link #KioskWatchdog(Host)}. */
    private long lastProgressToken = -1L;
    /**
     * {@link Host#screenShouldBeOn()} as of the previous {@link #evaluate(long)}, used to catch the
     * false-to-true edge. Starts {@code true} so a process that boots into dark quiet hours does
     * not mistake "first tick, screen is off" for "the screen just woke up" — boot-time staleness
     * is anchored by {@code MainActivity} at {@code onCreate} instead.
     */
    private boolean lastTickScreenShouldBeOn = true;
    /** When the screen last went from should-be-off to should-be-on; clamps how old a wedge can look. */
    private long screenWokeAtMs = 0L;
    private int ticks = 0;
    private String lastAction = "none";

    public KioskWatchdog(Host host) {
        this.host = host;
        // Baseline the progress counter at construction so the first evaluation compares two real
        // samples instead of always seeing "change" against an impossible value.
        this.lastProgressToken = host.mainLoopProgressToken();
    }

    /** Start (or restart after a {@link #stop()}) the minute-by-minute evaluation. */
    public synchronized void start() {
        stop();
        probeMisses = 0;
        // Baseline the progress counter and put a probe in flight now, so the first tick has a
        // real interval of main-thread work to compare against instead of an empty queue.
        lastProgressToken = host.mainLoopProgressToken();
        host.postProbe();
        timer = new Timer(true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                tick();
            }
        }, TICK_MS, TICK_MS);
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
    }

    /** Staleness limit for a given rotation interval. */
    public static long staleLimitMs(long intervalMs) {
        return Math.max(STALE_FLOOR_MS, STALE_INTERVAL_MULTIPLE * Math.max(0L, intervalMs));
    }

    /**
     * Age of the last thing shown on screen. Zero (healthy) while the screen should be off, or
     * before the first display. This is the pure arithmetic only — callers must not feed a
     * {@code lastDisplayAtMs} older than the last time the screen legitimately turned back on
     * (see {@link #evaluate}'s {@code screenWokeAtMs} clamp), or a frame that was simply meant to
     * be dark for a while would count its whole quiet period as a wedge the moment it wakes.
     */
    public static long staleMs(boolean screenShouldBeOn, long nowMs, long lastDisplayAtMs,
                               long downloadInFlightMs) {
        if (!screenShouldBeOn || lastDisplayAtMs <= 0L) {
            return 0L;
        }
        long displayAge = Math.max(0L, nowMs - lastDisplayAtMs);
        // A download that never completes freezes the rotation no matter how recent the last
        // display was — and a stuck progress bar is exactly what a user notices.
        return Math.max(displayAge, downloadInFlightMs);
    }

    /**
     * The highest age-driven rung this state has earned. {@link #LEVEL_SELF_RESTART} is decided
     * separately, because a dead main thread is independent of how long the screen has been stale.
     */
    public static int requiredLevel(long staleMs, long intervalMs) {
        long limit = staleLimitMs(intervalMs);
        int level = LEVEL_NONE;
        for (int i = LEVEL_FORCE_ROTATION; i <= LEVEL_REBOOT; i++) {
            if (i == LEVEL_SELF_RESTART) {
                continue;
            }
            if (staleMs >= limit + LEVEL_OFFSETS[i]) {
                level = i;
            }
        }
        return level;
    }

    /**
     * One evaluation. Package-visible so unit tests can drive the ladder without a timer.
     */
    void tick() {
        try {
            ticks++;
            host.postProbe();
            evaluate(host.nowMs());
        } catch (Throwable t) {
            // Never let a watchdog bug reach the global handler (which calls System.exit(2)).
            try {
                host.logWatchdogEvent("tick failed: " + t, true);
            } catch (Throwable ignored) {
            }
        }
    }

    private void evaluate(long now) {
        // The age clock must restart the moment the screen legitimately wakes up (end of quiet
        // hours, the physical power button, a manual toggle, a brightness adjust) — otherwise the
        // first tick to see "screen should be on" measures staleness from the last thing shown
        // before the dark period, hours old, and requiredLevel() jumps straight past every lower
        // rung to the reboot rung in a single evaluation. Clamping here rather than in the wake
        // call sites is what makes it catch all of them, including ones that never touch
        // MainActivity's quiet-hours path at all.
        boolean screenOn = host.screenShouldBeOn();
        if (screenOn && !lastTickScreenShouldBeOn) {
            screenWokeAtMs = now;
        }
        lastTickScreenShouldBeOn = screenOn;
        long stale = staleMs(screenOn, now, Math.max(host.lastDisplayAtMs(), screenWokeAtMs),
                host.downloadInFlightMs());
        // Progress, not promptness: a main thread that acks two minutes late has still emptied its
        // queue and is working. Only an unchanged counter means it never got to anything.
        long token = host.mainLoopProgressToken();
        if (token != lastProgressToken) {
            probeMisses = 0;
        } else {
            probeMisses++;
        }
        lastProgressToken = token;
        boolean mainLoopDead = probeMisses >= MAIN_LOOP_DEAD_TICKS;
        int wanted = requiredLevel(stale, host.intervalMs());

        if (wanted == LEVEL_NONE && !mainLoopDead && actedLevel != LEVEL_NONE) {
            actedLevel = LEVEL_NONE;
            repairAttempts = 0;
            rebootBlocked = false;
        }

        if (now - lastHeartbeatAtMs >= HEARTBEAT_MS) {
            lastHeartbeatAtMs = now;
            host.logWatchdogEvent(heartbeatLine(stale, now, screenOn), false);
        }

        // Another activity is in front (settings dialog): the loop is legitimately idle, so no
        // rung may fire. The heartbeat above still reports it, so this state cannot be mistaken
        // for a healthy frame from the log alone.
        if (host.isPaused()) {
            return;
        }

        // A wedged main thread outranks the age ladder: every lower rung posts work to the UI
        // thread, and that work would just queue up behind the block.
        if (mainLoopDead && now - lastSelfRestartAtMs >= SELF_RESTART_COOLDOWN_MS) {
            lastSelfRestartAtMs = now;
            act(LEVEL_SELF_RESTART, stale, now);
            return;
        }

        if (wanted == LEVEL_REBOOT && !host.networkAvailable()
                && repairAttempts < MAX_REPAIR_ATTEMPTS) {
            // A reboot cannot fix an outage we have not finished trying to repair, and a frame that
            // comes back nightly during a long ISP outage is worse than one that waits it out.
            // Stay on the repair rung and let its spacing pace the attempts.
            wanted = LEVEL_REPAIR_NETWORK;
        }

        if (wanted <= actedLevel) {
            if (actedLevel == LEVEL_REPAIR_NETWORK && !host.networkAvailable()) {
                retryRepairIfDue(now, stale);
            }
            if (actedLevel == LEVEL_REBOOT && rebootBlocked && host.autoRebootEnabled()) {
                // The ladder ran out against a disabled permission; finish it once it is granted.
                act(LEVEL_REBOOT, stale, now);
            }
            return;
        }

        if (wanted == LEVEL_REPAIR_NETWORK && host.offlineMs() < OFFLINE_REPAIR_MS) {
            // Not enough time without a network to justify poking the Wi-Fi stack yet.
            return;
        }

        act(wanted, stale, now);
    }

    private void retryRepairIfDue(long now, long stale) {
        if (repairAttempts >= MAX_REPAIR_ATTEMPTS) {
            return;
        }
        if (now - lastRepairAtMs < REPAIR_SPACING_MS) {
            return;
        }
        act(LEVEL_REPAIR_NETWORK, stale, now);
    }

    private void act(int level, long stale, long now) {
        actedLevel = Math.max(actedLevel, level);
        lastActionAtMs = now;
        String reason = "stale " + (stale / 60_000L) + "m";
        switch (level) {
            case LEVEL_FORCE_ROTATION:
                lastAction = "force-rotation";
                host.logWatchdogEvent("(" + reason + "): forcing a rotation", true);
                host.forceRotation();
                break;
            case LEVEL_RECOVER_PLAYBACK:
                lastAction = "recover-playback";
                host.logWatchdogEvent("(" + reason + "): forcing a player teardown and rotating", true);
                host.recoverPlayback();
                break;
            case LEVEL_SELF_RESTART:
                lastAction = "self-restart";
                host.logWatchdogEvent("main thread ran nothing for " + probeMisses
                        + " ticks in a row (stale " + (stale / 60_000L) + "m)"
                        + " — restarting app process", true);
                host.selfRestart();
                break;
            case LEVEL_REPAIR_NETWORK:
                lastAction = "repair-network";
                repairAttempts++;
                lastRepairAtMs = now;
                host.logWatchdogEvent("(" + reason + ", offline " + (host.offlineMs() / 60_000L)
                        + "m): network repair attempt " + repairAttempts + "/" + MAX_REPAIR_ATTEMPTS, true);
                host.repairNetwork();
                break;
            case LEVEL_REBOOT:
                if (host.autoRebootEnabled()) {
                    lastAction = "reboot";
                    rebootBlocked = false;
                    host.logWatchdogEvent("(" + reason + "): rebooting device", true);
                    host.rebootDevice();
                } else {
                    lastAction = "reboot-disabled";
                    rebootBlocked = true;
                    host.logWatchdogEvent("(" + reason + "): ladder exhausted; automatic reboot is "
                            + "disabled — allow it (options dialog, or config.autoReboot over the LAN "
                            + "config editor) for the frame to self-heal from this", true);
                }
                break;
            default:
                break;
        }
    }

    private String heartbeatLine(long stale, long now, boolean screenOn) {
        StringBuilder sb = new StringBuilder();
        sb.append("heartbeat tick=").append(ticks)
                .append(" stale=").append(stale / 1000L).append("s")
                .append(" limit=").append(staleLimitMs(host.intervalMs()) / 1000L).append("s")
                .append(" level=").append(actedLevel)
                .append(" action=").append(lastAction)
                .append(" network=").append(host.networkAvailable() ? "up" : "down");
        long offline = host.offlineMs();
        if (offline > 0L) {
            sb.append(" offlineFor=").append(offline / 60_000L).append("m");
        }
        if (probeMisses > 0) {
            sb.append(" UNACKED_PROBES=").append(probeMisses);
        }
        sb.append(" screen=").append(screenOn ? "on" : "off");
        if (host.isPaused()) {
            // Rungs are suppressed while another activity is in front; say so, so a long pause
            // cannot be mistaken for a healthy frame.
            sb.append(" PAUSED");
        }
        if (lastActionAtMs > 0L) {
            sb.append(" lastActionAge=" + ((now - lastActionAtMs) / 60_000L) + "m");
        }
        return sb.toString();
    }

    /** How long the display loop has been silent right now (0 during quiet hours). */
    public long staleMsNow() {
        return staleMs(host.screenShouldBeOn(), host.nowMs(),
                Math.max(host.lastDisplayAtMs(), screenWokeAtMs), host.downloadInFlightMs());
    }

    /**
     * Run one rung immediately, as if its threshold had been reached. For {@code /control} on debug
     * builds only — the way to prove a rung works on real hardware without waiting for a wedge.
     */
    public void forceAction(int level) {
        act(level, staleMsNow(), host.nowMs());
    }

    /** Rung already reached for the current wedge (0 = healthy). */
    public int actedLevel() {
        return actedLevel;
    }

    public String lastAction() {
        return lastAction;
    }

    public long lastActionAtMs() {
        return lastActionAtMs;
    }

    public int repairAttempts() {
        return repairAttempts;
    }

    /** Consecutive ticks the main thread has failed to ack; the restart rung needs two. */
    public int probeMisses() {
        return probeMisses;
    }

    public int ticks() {
        return ticks;
    }
}
