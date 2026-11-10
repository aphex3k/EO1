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
        /** True by default: a held panel is the ordinary case, and most cases here are about age. */
        boolean screenHoldClaimed = true;
        /** Kept out of {@link #actions} for the same reason as {@link #captures}. */
        int holdReasserts;
        long intervalMs = 15 * MINUTE;
        /**
         * Pixels proven on the panel; 0 until a proof point runs, exactly as in the activity. This is
         * the clock the ladder reads, after {@link KioskWatchdog#escalationClockMs} resolves the
         * never-proven case to {@link #anchorMs}.
         */
        long provenAtMs = now;
        /**
         * What {@code MainActivity.lastRotationActivityMs} does: {@code runOnTimer} stamps it on every
         * tick, before it asks for the next asset. Modelled here and deliberately <em>not</em>
         * connected to the ladder — see {@link #tickTogether}, which refreshes it before every
         * evaluation, so any case that walks ticks through it fails outright if the ladder ever goes
         * back to measuring a clock the frame's own timer feeds.
         */
        long rotationTickAtMs = now;
        /** This process's start: what a frame that has never drawn anything ages from. */
        long anchorMs = now;
        long downloadInFlightMs;
        boolean networkAvailable = true;
        long offlineMs;
        /** Assets in the pool. Non-zero by default: most cases here are a frame that has assets. */
        int poolKeyCount = 1;
        boolean mainLoopAlive = true;
        long progressToken = 0;
        boolean autoRebootEnabled;
        final List<String> captures = new ArrayList<>();

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
        public boolean screenHoldClaimed() {
            return screenHoldClaimed;
        }

        @Override
        public void reassertScreenHold() {
            holdReasserts++;
            // Like the real activity, the repair lands on the UI thread and succeeds: a test that
            // wants to see the same wedge fail can leave this false and count the repeats.
            screenHoldClaimed = true;
        }

        @Override
        public long intervalMs() {
            return intervalMs;
        }

        @Override
        public long lastDisplayProvenAtMs() {
            // The same expression MainActivity runs, so the fake cannot quietly model a healthier
            // frame than the product has.
            return KioskWatchdog.escalationClockMs(provenAtMs, anchorMs);
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
        public boolean nothingToDisplay() {
            // The product's rule rather than a free boolean: an offline frame whose pool is empty is
            // still a wedge, and a fake that could claim both at once would let the cap look safe
            // when it is not. MainActivity.nothingToDisplay() is the same two terms.
            return poolKeyCount == 0 && offlineMs == 0L;
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
        public void rebootDevice(String reason) {
            actions.add("rebootDevice");
        }

        /**
         * Deliberately kept out of {@link #actions}: a diagnostics capture is documentation, not a
         * rung, and the existing assertions count actions per wedge.
         */
        @Override
        public void captureDiagnostics(String reason) {
            captures.add(reason);
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

    /** Move the clock forward so nothing has been proven on the panel for exactly {@code staleMs}. */
    private void silentFor(long staleMs) {
        host.advance(staleMs);
        host.provenAtMs = host.now - staleMs;
    }

    /**
     * Walk {@code times} evaluation ticks. Each one also refreshes the rotation clock, the way
     * {@code MainActivity.runOnTimer} does, so a case here that depends on the frame being stale is a
     * case that would fail if the ladder ever went back to measuring a clock the timer feeds.
     */
    private void tickTogether(long periodMs, int times) {
        for (int i = 0; i < times; i++) {
            host.advance(periodMs);
            host.rotationTickAtMs = host.now;
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
            host.rotationTickAtMs = host.now;
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

    /**
     * Which of the activity's two clocks the ladder measures, in the one place that choice is now
     * written. The 2026-11-08 frame proved the second branch is not decoration: with nothing ever
     * proven, a clock reporting 0 makes {@link KioskWatchdog#staleMs} answer 0, which reads healthy —
     * so a process that never reached first draw would never age at all.
     */
    @Test
    public void theWedgeClockIsTheProvenDisplayOrThisProcessStart() {
        assertEquals(5_000L, KioskWatchdog.escalationClockMs(5_000L, 1_000L));
        assertEquals("never proven: age from the process, not from nothing",
                1_000L, KioskWatchdog.escalationClockMs(0L, 1_000L));
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
        host.provenAtMs = host.now - HOUR;
        host.advance(2 * HOUR);
        tickTogether(MINUTE, 10);
        assertEquals(10, host.actions.size());
    }

    /**
     * A frame that keeps showing pictures on a panel nobody is holding up. Every age-driven signal in
     * the ladder is healthy for it — that is the whole problem — so the repair must happen without a
     * rung being earned and without the log going quiet.
     */
    @Test
    public void aPanelNobodyHeldUpIsReassertedWithoutTouchingTheLadder() {
        host.screenHoldClaimed = false;
        host.advance(MINUTE);
        watchdog.tick();
        assertEquals("one repair, on the UI-thread path, not as a rung", 1, host.holdReasserts);
        assertEquals("the repair must not be counted as ladder action", 1, host.actions.size());
        assertEquals(KioskWatchdog.LEVEL_NONE, watchdog.actedLevel());
        assertTrue("the repair must reach the log", containsEvent("display hold"));
    }

    @Test
    public void aHeldPanelIsNeverRepaired() {
        tickTogether(MINUTE, 20);
        assertEquals(0, host.holdReasserts);
    }

    @Test
    public void quietHoursDoNotAskForAPanelTheScheduleWantsDark() {
        host.screenShouldBeOn = false;
        host.screenHoldClaimed = false;
        tickTogether(MINUTE, 10);
        assertEquals("an off screen is not an unheld screen", 0, host.holdReasserts);
    }

    @Test
    public void aPanelThatKeepsLosingItsHoldIsPacedRatherThanSpammed() {
        for (int i = 0; i < 30; i++) {
            host.screenHoldClaimed = false;
            host.advance(MINUTE);
            watchdog.tick();
        }
        // Thirty minutes unheld, one repair per CLAIM_REPAIR_COOLDOWN_MS.
        assertTrue("repairs were not paced: " + host.holdReasserts,
                host.holdReasserts >= 4 && host.holdReasserts <= 8);
    }

    @Test
    public void anUnheldPanelDoesNotExcuseAFrameThatAlsoStoppedRotating() {
        host.screenHoldClaimed = false;
        silentFor(KioskWatchdog.staleLimitMs(15 * MINUTE) + MINUTE);
        tickTogether(MINUTE, 6);
        assertTrue("the ladder still has to climb for a frame that is both stale and unheld",
                watchdog.actedLevel() >= KioskWatchdog.LEVEL_FORCE_ROTATION);
    }

    /**
     * The 2026-10-16 false-positive reboot: quiet hours end, {@code screenShouldBeOn} flips true, and
     * the proven clock is eight hours old, because nothing was supposed to be displayed all night. A
     * watchdog tick landing in the gap before the first rotation after waking would otherwise age the
     * frame by the full length of the dark period — here, long enough to skip every lower rung
     * straight to reboot. The {@code screenWokeAtMs} clamp is what stops that, and it matters more now
     * that the clock it clamps genuinely does not move while the panel is dark.
     */
    @Test
    public void wakingUpAfterALongQuietPeriodDoesNotCountTheDarkTimeAsStale() {
        host.screenShouldBeOn = false;
        host.autoRebootEnabled = true;
        // Last real display was right before quiet hours began, eight hours ago.
        host.provenAtMs = host.now - 8 * HOUR;
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

        // A display is proven on the panel: the frame has recovered, and a timer tick alone
        // (rotationTickAtMs, refreshed by tickTogether) must not be able to say that.
        host.provenAtMs = host.now;
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
        host.provenAtMs = 0L;
        host.networkAvailable = false;
        host.offlineMs = KioskWatchdog.OFFLINE_REPAIR_MS;
        tickTogether(MINUTE, 60);
        assertEquals(1, count("forceRotation"));
        assertEquals(1, count("recoverPlayback"));
        assertTrue(count("repairNetwork") >= 1);
    }

    /**
     * The EO2 that sat white for 19.8 hours on 2026-11-08 with {@code autoReboot} granted, in the one
     * shape that made it unhealable: its rotation timer fired on schedule all night — 125 heartbeat
     * lines, {@code level=0 action=none}, {@code stale} never past 119 s against a 1200 s limit — and
     * the ladder measured its age against that same timer. Home was relaunched about 80 times and
     * never reached first draw, so nothing was ever proven, which is the only condition the old wiring
     * could not see.
     *
     * <p>Here the tick is modelled as it actually ran: {@link FakeHost#rotationTickAtMs} is refreshed
     * before every evaluation, exactly as {@code runOnTimer} does, and the proven clock never moves.
     * The age has to grow anyway and the climb has to reach the top rung.
     */
    @Test
    public void aFrameThatKeepsTickingButNeverDrawsClimbsAllTheWayToReboot() {
        host.provenAtMs = 0L;
        host.autoRebootEnabled = true;
        long limit = KioskWatchdog.staleLimitMs(host.intervalMs());

        // A night's worth of minutes, each one a rotation tick that stamped its clock and proved
        // nothing. The reboot threshold is limit + 30m, so this walks past it rather than teleporting
        // the frame there — the teleport is what made the old bug look fine in every other case here.
        tickTogether(MINUTE, (int) ((limit + 31 * MINUTE) / MINUTE));

        // The discriminating number: with the ladder reading the timer's clock, this age could not
        // exceed one rotation interval, so every threshold past it was unreachable by construction.
        assertTrue("the tick clock kept pace and the age still escaped one interval: "
                        + watchdog.staleMsNow(),
                watchdog.staleMsNow() > host.intervalMs());
        assertTrue("the ladder must walk the cheap rungs on the way up, not jump",
                1 == count("forceRotation") && 1 == count("recoverPlayback"));
        assertEquals("one reboot, once the permission was granted and every rung below spent",
                1, count("rebootDevice"));
        assertEquals(KioskWatchdog.LEVEL_REBOOT, watchdog.actedLevel());
        assertTrue("the reboot rung must not report a capture it did not need",
                host.captures.isEmpty());
    }

    /**
     * The false positive this commit would otherwise have shipped with. A frame whose pool is empty —
     * every backend switched off, a rotated Immich password, a Flickr stream with nothing public, a
     * local entry before anyone uploaded a file — makes no display call at all, so the wedge clock
     * cannot advance and the age grows for a reason no rung can fix. Before the commit the tick clock
     * pinned the age below one interval and nothing fired; with an honest clock the ladder would reach
     * self-restart, and since a restart re-seeds the very clock the ladder reads, it would climb again
     * every limit + 30 min forever — wiping a photo that was still on the panel to do it.
     */
    @Test
    public void aFrameWithNothingInItsPoolHoldsOnTheCheapRungs() {
        host.provenAtMs = 0L;
        host.poolKeyCount = 0;
        host.autoRebootEnabled = true;
        long limit = KioskWatchdog.staleLimitMs(host.intervalMs());

        tickTogether(MINUTE, (int) ((limit + 40 * MINUTE) / MINUTE));

        assertEquals("the two retries are still worth one attempt each",
                1, count("forceRotation"));
        assertEquals(1, count("recoverPlayback"));
        assertEquals("a restart cannot fill a pool", 0, count("selfRestart"));
        assertEquals("and a reboot certainly cannot", 0, count("rebootDevice"));
        assertEquals(KioskWatchdog.LEVEL_RECOVER_PLAYBACK, watchdog.actedLevel());
        assertTrue("the hold must be legible in the log, not look like a dead watchdog",
                containsEvent("NO-MEDIA"));
    }

    /**
     * The half of the previous case that must not be suppressed: a frame relaunched into a dead network
     * has an empty pool too, and the 2026-10-13 incident is the documented reason the repair and reboot
     * rungs exist. "Nothing to display" is therefore only a hold while the network is not the thing
     * being waited for.
     */
    @Test
    public void anEmptyPoolIsStillAWedgeWhenTheFrameHasNoNetwork() {
        host.provenAtMs = 0L;
        host.poolKeyCount = 0;
        host.networkAvailable = false;
        host.offlineMs = KioskWatchdog.OFFLINE_REPAIR_MS;
        long limit = KioskWatchdog.staleLimitMs(host.intervalMs());

        tickTogether(MINUTE, (int) ((limit + 15 * MINUTE) / MINUTE));

        assertTrue("the outage still has to be repaired, pool empty or not",
                count("repairNetwork") >= 1);
        assertTrue(watchdog.actedLevel() >= KioskWatchdog.LEVEL_REPAIR_NETWORK);
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

    private boolean containsEvent(String needle) {
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
