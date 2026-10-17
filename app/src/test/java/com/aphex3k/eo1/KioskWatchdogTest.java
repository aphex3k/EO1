package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The ladder is the whole point of the watchdog, so it is tested through the package-visible
 * {@code tick()} rather than through a timer: each case states how long the frame has been silent
 * and asserts exactly which rung fires, at most once.
 */
public class KioskWatchdogTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    private static final class FakeHost implements KioskWatchdog.Host {
        long now = 1_000_000L;
        boolean paused;
        boolean screenShouldBeOn = true;
        long intervalMs = 15 * MINUTE;
        long lastDisplayAtMs = now;
        long downloadInFlightMs;
        boolean networkAvailable = true;
        long offlineMs;
        boolean mainLoopAlive = true;
        long progressToken = 0;
        boolean autoRebootEnabled;

        final List<String> actions = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        RuntimeException throwOnTick;

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public boolean screenShouldBeOn() {
            return screenShouldBeOn;
        }

        @Override
        public long intervalMs() {
            return intervalMs;
        }

        @Override
        public long lastDisplayAtMs() {
            return lastDisplayAtMs;
        }

        @Override
        public long downloadInFlightMs() {
            return downloadInFlightMs;
        }

        @Override
        public boolean networkAvailable() {
            return networkAvailable;
        }

        @Override
        public long offlineMs() {
            return offlineMs;
        }

        @Override
        public long mainLoopProgressToken() {
            return progressToken;
        }

        @Override
        public void postProbe() {
            actions.add("probe");
            // A live looper runs the acknowledgement, which advances the token; a wedged one never
            // gets to it, so the value stands still between two ticks.
            if (mainLoopAlive) {
                progressToken++;
            }
        }

        @Override
        public boolean autoRebootEnabled() {
            return autoRebootEnabled;
        }

        @Override
        public void forceRotation() {
            actions.add("forceRotation");
        }

        @Override
        public void recoverPlayback() {
            actions.add("recoverPlayback");
        }

        @Override
        public void repairNetwork() {
            actions.add("repairNetwork");
        }

        @Override
        public void selfRestart() {
            actions.add("selfRestart");
        }

        @Override
        public void rebootDevice() {
            actions.add("rebootDevice");
        }

        @Override
        public void logWatchdogEvent(String message, boolean severe) {
            if (throwOnTick != null) {
                throw throwOnTick;
            }
            events.add(message);
        }

        /** Advance the clock and let the watchdog look once. */
        void advance(long ms) {
            now += ms;
        }
    }

    private FakeHost host;
    private KioskWatchdog watchdog;

    @Before
    public void setUp() {
        host = new FakeHost();
        watchdog = new KioskWatchdog(host);
    }

    /** Move the clock forward so the display has been silent for exactly {@code staleMs}. */
    private void silentFor(long staleMs) {
        host.advance(staleMs);
        host.lastDisplayAtMs = host.now - staleMs;
    }

    private void tickTogether(long periodMs, int times) {
        for (int i = 0; i < times; i++) {
            host.advance(periodMs);
            watchdog.tick();
        }
    }

    private boolean acted(String action) {
        return host.actions.contains(action);
    }

    /**
     * Walk the ladder up to {@code level} one rung per tick, taking the rungs below honestly rather
     * than poking a field, so a case about one rung is not written in terms of nothing else having
     * happened. What the climb did stays in {@code host.actions}.
     */
    private void climbTo(int level) {
        int guard = 0;
        while (watchdog.actedLevel() < level && guard++ < 12) {
            host.advance(MINUTE);
            watchdog.tick();
        }
        assertTrue("the ladder stalled below rung " + level, watchdog.actedLevel() >= level);
    }

    // ------------------------------------------------------------------ thresholds

    @Test
    public void staleLimitNeverBelowTheFloor() {
        assertEquals(KioskWatchdog.STALE_FLOOR_MS, KioskWatchdog.staleLimitMs(60_000L));
        assertEquals("a nonsense interval must not collapse the limit to zero",
                KioskWatchdog.STALE_FLOOR_MS, KioskWatchdog.staleLimitMs(0L));
    }

    @Test
    public void staleLimitScalesWithLongIntervals() {
        assertEquals(3 * HOUR, KioskWatchdog.staleLimitMs(HOUR));
    }

    @Test
    public void quietHoursAreNeverStale() {
        assertEquals(0L, KioskWatchdog.staleMs(false, 10 * HOUR, 0L, HOUR));
    }

    @Test
    public void aStrandedDownloadCountsEvenWhenTheLastDisplayIsRecent() {
        long now = 10 * HOUR;
        assertEquals(30 * MINUTE, KioskWatchdog.staleMs(true, now, now - 60_000L, 30 * MINUTE));
        assertEquals(3 * HOUR, KioskWatchdog.staleMs(true, now, now - 3 * HOUR, 60_000L));
    }

    @Test
    public void eachRungBecomesEligibleLaterThanTheOneBefore() {
        long limit = KioskWatchdog.staleLimitMs(15 * MINUTE);
        assertEquals(KioskWatchdog.LEVEL_NONE, KioskWatchdog.requiredLevel(limit - 1, 15 * MINUTE));
        assertEquals(KioskWatchdog.LEVEL_FORCE_ROTATION, KioskWatchdog.requiredLevel(limit, 15 * MINUTE));
        assertEquals(KioskWatchdog.LEVEL_RECOVER_PLAYBACK,
                KioskWatchdog.requiredLevel(limit + 5 * MINUTE, 15 * MINUTE));
        assertEquals(KioskWatchdog.LEVEL_REPAIR_NETWORK,
                KioskWatchdog.requiredLevel(limit + 10 * MINUTE, 15 * MINUTE));
        assertEquals(KioskWatchdog.LEVEL_REBOOT,
                KioskWatchdog.requiredLevel(limit + 30 * MINUTE, 15 * MINUTE));
    }

    // ------------------------------------------------------------------ the ladder

    @Test
    public void healthyFrameTakesNoActionButStillHeartbeats() {
        tickTogether(MINUTE, 20);
        assertFalse(acted("forceRotation"));
        assertFalse(acted("recoverPlayback"));
        assertFalse(acted("selfRestart"));
        assertTrue("a heartbeat line must reach the log", host.events.size() >= 2);
        assertTrue(host.events.get(0).startsWith("heartbeat"));
    }

    @Test
    public void nothingFiresWhileAnotherActivityIsInTheForeground() {
        host.paused = true;
        silentFor(3 * HOUR);
        tickTogether(MINUTE, 10);
        assertEquals(10, host.actions.size()); // probes only
        assertFalse(acted("forceRotation"));
        assertTrue("a long pause must be visible in the heartbeat", containsPausedHeartbeat());
    }

    @Test
    public void theFrameStaysQuietDuringQuietHoursHoweverLongNothingShowed() {
        host.screenShouldBeOn = false;
        host.lastDisplayAtMs = host.now - HOUR;
        host.advance(2 * HOUR);
        tickTogether(MINUTE, 10);
        assertEquals(10, host.actions.size());
    }

    /**
     * The 2026-10-16 false-positive reboot: quiet hours end, {@code screenShouldBeOn} flips true,
     * but nothing has been displayed for the whole dark window since {@code lastRotationActivityMs}
     * only advances on an actual display or the rotation tick's screen-on branch. A watchdog tick
     * landing in the gap before that next rotation tick would otherwise age the frame by the full
     * length of the dark period — here, long enough to skip every lower rung straight to reboot.
     */
    @Test
    public void wakingUpAfterALongQuietPeriodDoesNotCountTheDarkTimeAsStale() {
        host.screenShouldBeOn = false;
        host.autoRebootEnabled = true;
        // Last real display was right before quiet hours began, eight hours ago.
        host.lastDisplayAtMs = host.now - 8 * HOUR;
        tickTogether(MINUTE, 10);
        assertEquals(0, count("forceRotation"));
        assertEquals(0, count("rebootDevice"));

        // Quiet hours end. The rotation timer's next tick is up to a full interval away, but the
        // watchdog's own tick does not wait for it.
        host.screenShouldBeOn = true;
        watchdog.tick();
        assertEquals("the dark period must not count toward the wedge", KioskWatchdog.LEVEL_NONE,
                watchdog.actedLevel());
        assertEquals(0, count("forceRotation"));
        assertEquals(0, count("rebootDevice"));

        // Only after a full limit's worth of silence since waking does the ladder start, and it
        // starts from the bottom rung.
        long limit = KioskWatchdog.staleLimitMs(host.intervalMs());
        tickTogether(MINUTE, (int) (limit / MINUTE) + 1);
        assertEquals(1, count("forceRotation"));
        assertEquals(0, count("rebootDevice"));
    }

    @Test
    public void aSilentLoopDrivesTheRotationOnce() {
        silentFor(KioskWatchdog.staleLimitMs(15 * MINUTE));
        watchdog.tick();
        assertEquals(1, count("forceRotation"));
        watchdog.tick();
        assertEquals("the same rung must not fire twice", 1, count("forceRotation"));
    }

    @Test
    public void aRecoveringFrameResetsTheLadder() {
        silentFor(KioskWatchdog.staleLimitMs(15 * MINUTE) + MINUTE);
        watchdog.tick();
        assertEquals(1, count("forceRotation"));

        host.lastDisplayAtMs = host.now;
        tickTogether(MINUTE, 3);
        assertEquals(KioskWatchdog.LEVEL_NONE, watchdog.actedLevel());

        silentFor(KioskWatchdog.staleLimitMs(15 * MINUTE) + MINUTE);
        watchdog.tick();
        assertEquals("a second wedge must escalate from the bottom again", 2, count("forceRotation"));
    }

    @Test
    public void playbackRecoveryFollowsAFailedRotation() {
        long limit = KioskWatchdog.staleLimitMs(15 * MINUTE);
        silentFor(limit + MINUTE);
        watchdog.tick();
        assertEquals(1, count("forceRotation"));

        tickTogether(MINUTE, 5);
        assertEquals(1, count("recoverPlayback"));
    }

    @Test
    public void networkRepairWaitsUntilWeHaveBeenOfflineLongEnough() {
        long limit = KioskWatchdog.staleLimitMs(15 * MINUTE);
        silentFor(limit + 10 * MINUTE);
        host.networkAvailable = false;
        host.offlineMs = MINUTE;
        climbTo(KioskWatchdog.LEVEL_REPAIR_NETWORK);
        assertEquals(0, count("repairNetwork"));

        // The outage is old enough now, and the ladder already sits on the repair rung.
        host.offlineMs = KioskWatchdog.OFFLINE_REPAIR_MS;
        host.advance(KioskWatchdog.REPAIR_SPACING_MS);
        watchdog.tick();
        assertEquals(1, count("repairNetwork"));
    }

    @Test
    public void networkRepairIsRetriedWithSpacingAndThenStops() {
        long limit = KioskWatchdog.staleLimitMs(15 * MINUTE);
        silentFor(limit + 10 * MINUTE);
        host.networkAvailable = false;
        host.offlineMs = KioskWatchdog.OFFLINE_REPAIR_MS;

        // The climb's last step is the first repair attempt.
        climbTo(KioskWatchdog.LEVEL_REPAIR_NETWORK);
        assertEquals(1, count("repairNetwork"));

        tickTogether(MINUTE, 4);
        assertEquals("no earlier than the spacing", 1, count("repairNetwork"));

        tickTogether(MINUTE, 6);
        assertEquals(2, count("repairNetwork"));

        for (int i = 0; i < 60; i++) {
            host.advance(MINUTE);
            watchdog.tick();
        }
        assertTrue("attempts are capped", watchdog.repairAttempts() <= KioskWatchdog.MAX_REPAIR_ATTEMPTS);
        assertEquals(count("repairNetwork"), watchdog.repairAttempts());
        assertEquals("the ladder must move on to the reboot rung, not repair forever",
                KioskWatchdog.LEVEL_REBOOT, watchdog.actedLevel());
        assertTrue(loggedContains("automatic reboot is disabled"));
    }

    @Test
    public void rebootIsTheLastResortAndRefusedUnlessEnabled() {
        long limit = KioskWatchdog.staleLimitMs(15 * MINUTE);
        silentFor(limit + 31 * MINUTE);
        climbTo(KioskWatchdog.LEVEL_REBOOT);
        assertEquals(0, count("rebootDevice"));
        assertTrue("the refusal must be logged", loggedContains("automatic reboot is disabled"));

        host.autoRebootEnabled = true;
        watchdog.tick();
        assertEquals(1, count("rebootDevice"));
    }

    /**
     * The frame this rung exists for: a wedge that already looks hours old the first time the
     * watchdog sees it must not be answered by jumping to the one rung it is not allowed to pull.
     * On a healthy-network frame the ladder now walks past the cheap rungs and reaches
     * self-restart, which is the rung that recovers an unattended frame with {@code autoReboot}
     * off — instead of logging the refusal once and then idling on it for days.
     */
    @Test
    public void aWedgeThatLooksOldOnSightStillClimbsFromTheBottom() {
        silentFor(KioskWatchdog.staleLimitMs(15 * MINUTE) + 12 * HOUR);

        watchdog.tick();
        assertEquals("the first evaluation takes the cheapest rung", 1, count("forceRotation"));
        assertEquals(0, count("rebootDevice"));

        tickTogether(MINUTE, 2);
        assertEquals(0, count("repairNetwork"));
        assertEquals("the repair rung is passed over, not waited on",
                KioskWatchdog.LEVEL_REPAIR_NETWORK, watchdog.actedLevel());

        watchdog.tick();
        assertTrue("self-restart must be reached without the reboot permission",
                acted("selfRestart"));
        assertEquals(0, count("rebootDevice"));
    }

    @Test
    public void aWedgedMainThreadRestartsWithoutWaitingForTheAgeLadder() {
        host.mainLoopAlive = false;
        watchdog.tick();
        watchdog.tick();
        assertEquals("two missed probes, not one", 1, count("selfRestart"));

        watchdog.tick();
        assertEquals("the cooldown must hold", 1, count("selfRestart"));

        tickTogether(MINUTE, 10);
        assertEquals(2, count("selfRestart"));
    }

    @Test
    public void oneUnackedProbeAfterAWakeIsNotTreatedAsAWedge() {
        host.mainLoopAlive = false;
        watchdog.tick();
        assertEquals(0, count("selfRestart"));

        host.mainLoopAlive = true;
        watchdog.tick();
        watchdog.tick();
        assertEquals("the looper answered, so nothing was wrong", 0, count("selfRestart"));
        assertEquals(0, watchdog.probeMisses());
    }

    /**
     * The EO2 cold start seen on 2026-10-14: minutes of main-thread work (multidex extraction plus
     * the retained-MQTT burst) between acknowledgements, so a wall-clock liveness window declared
     * the loop dead while it was merely busy. Killing it started the same slow startup again, in a
     * loop no process ever survived long enough to draw a frame. Liveness is progress now.
     */
    @Test
    public void aSlowButProgressingMainLoopIsNeverRestarted() {
        // Stale enough to earn the first age rung, and left to age for twelve ticks: the ladder may
        // escalate, but a main thread that keeps answering must never be killed for it.
        silentFor(KioskWatchdog.staleLimitMs(15 * MINUTE) + MINUTE);
        tickTogether(MINUTE, 12);
        assertEquals(0, count("selfRestart"));
        assertEquals(0, watchdog.probeMisses());
        assertTrue("staleness still has to be answered by the age ladder", acted("forceRotation"));
    }

    @Test
    public void progressResetsTheMissCountSoAMidRunWedgeIsStillCaught() {
        tickTogether(MINUTE, 5);
        assertEquals(0, watchdog.probeMisses());

        host.mainLoopAlive = false;
        watchdog.tick();
        assertEquals(1, watchdog.probeMisses());
        assertEquals("one tick of silence is not proof", 0, count("selfRestart"));

        watchdog.tick();
        assertEquals(1, count("selfRestart"));
    }

    @Test
    public void aDeadMainThreadTakesOverTheLadderOnceConfirmed() {
        silentFor(KioskWatchdog.staleLimitMs(15 * MINUTE));
        host.mainLoopAlive = false;

        watchdog.tick();
        assertEquals("one unacked probe is not proof — the ordinary first rung still runs",
                1, count("forceRotation"));

        watchdog.tick();
        assertEquals(1, count("selfRestart"));
        assertEquals("a wedged looper must not keep climbing the age ladder",
                0, count("recoverPlayback"));
    }

    @Test
    public void aFrameThatHasNeverShownAnythingStillEscalates() {
        // The 2026-10-13 state: relaunched into a dead network, so no rotation ever reached the
        // screen. "Nothing has happened yet" must not read as healthy.
        host.networkAvailable = false;
        host.offlineMs = KioskWatchdog.OFFLINE_REPAIR_MS;
        tickTogether(MINUTE, 60);
        assertEquals(1, count("forceRotation"));
        assertEquals(1, count("recoverPlayback"));
        assertTrue(count("repairNetwork") >= 1);
    }

    @Test
    public void anOutageIsRepairedBeforeTheFrameIsRebooted() {
        host.networkAvailable = false;
        host.offlineMs = HOUR;
        host.autoRebootEnabled = true;
        silentFor(KioskWatchdog.staleLimitMs(15 * MINUTE) + 40 * MINUTE);

        // One rung per tick, so the ladder arrives at the repair rung through the rungs below it.
        // What must not change is the order: repairs are spent before the reboot rung is reached.
        climbTo(KioskWatchdog.LEVEL_REPAIR_NETWORK);
        assertEquals("the reboot rung must not skip the repairs", 1, count("repairNetwork"));
        assertEquals(0, count("rebootDevice"));

        tickTogether(MINUTE, 25);
        assertEquals(KioskWatchdog.MAX_REPAIR_ATTEMPTS, watchdog.repairAttempts());
        assertEquals("only after the repairs are exhausted", 1, count("rebootDevice"));
    }

    @Test
    public void aWatchdogBugCannotCrashTheApp() {
        host.throwOnTick = new RuntimeException("boom");
        watchdog.tick(); // must not propagate: a TimerTask exception reaches System.exit(2)
        assertEquals(KioskWatchdog.LEVEL_NONE, watchdog.actedLevel());
    }

    @Test
    public void forceActionRunsARungForDiagnostics() {
        watchdog.forceAction(KioskWatchdog.LEVEL_RECOVER_PLAYBACK);
        assertEquals(1, count("recoverPlayback"));
        assertEquals(KioskWatchdog.LEVEL_RECOVER_PLAYBACK, watchdog.actedLevel());
    }

    private int count(String action) {
        int n = 0;
        for (String candidate : host.actions) {
            if (candidate.equals(action)) {
                n++;
            }
        }
        return n;
    }

    private boolean loggedContains(String needle) {
        for (String event : host.events) {
            if (event.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPausedHeartbeat() {
        for (String event : host.events) {
            if (event.startsWith("heartbeat") && event.contains("PAUSED")) {
                return true;
            }
        }
        return false;
    }
}
