package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.aphex3k.update.UpdateManifest;
import com.aphex3k.update.UpdateState;

import org.junit.Test;

/**
 * JVM tests for {@link UpdateManager}'s pure statics ({@code checkDue}, {@code reconcileState},
 * {@code judgeStaged} and {@code chooseInstallPath}). No Android framework classes are exercised.
 */
public class UpdateManagerTest {

    private static UpdateState state(UpdateState.State s) {
        UpdateState st = UpdateState.idle();
        st.state = s;
        st.expectedVersionCode = 7;
        st.expectedVersionName = "1.7.0";
        st.lastCheckedMs = 0;
        st.attempts = 3;
        return st;
    }

    /** A stage the update host itself fetched: build 7, published as {@code sha}. */
    private static UpdateState hostStage(String sha) {
        UpdateState st = state(UpdateState.State.STAGED);
        st.manifestSha256 = sha;
        st.stagedSource = UpdateState.SOURCE_MANIFEST;
        return st;
    }

    /** A manifest the host could have served: {@code code}, built from {@code sha}. */
    private static UpdateManifest published(int code, String sha) {
        UpdateManifest m = new UpdateManifest();
        m.versionCode = code;
        m.versionName = "1." + code + ".0";
        m.apkUrl = "http://host/eo1.apk";
        m.sha256 = sha;
        m.sizeBytes = 1024L;
        return m;
    }

    private static String sha64(char c) {
        char[] hex = new char[64];
        java.util.Arrays.fill(hex, c);
        return new String(hex);
    }

    private static final String SHA_A = sha64('a');
    private static final String SHA_B = sha64('b');

    /** Build 7 staged, build 6 running: what {@link #hostStage} and {@link #published} describe. */
    private static final int INSTALLED = 6;

    private static final long MINUTE = 60_000L;

    // ---------------------------------------------------------------- checkDue

    @Test
    public void checkDue_nullStateFirstRunIsDue() {
        assertTrue(UpdateManager.checkDue(1L, null, 120));
    }

    @Test
    public void checkDue_intervalNotElapsed() {
        UpdateState st = state(UpdateState.State.IDLE);
        st.lastCheckedMs = 1_000_000L;
        assertFalse(UpdateManager.checkDue(st.lastCheckedMs + 119 * MINUTE - 1, st, 120));
    }

    @Test
    public void checkDue_intervalBoundaryIsDue() {
        UpdateState st = state(UpdateState.State.IDLE);
        st.lastCheckedMs = 1_000_000L;
        assertTrue(UpdateManager.checkDue(st.lastCheckedMs + 120 * MINUTE, st, 120));
    }

    @Test
    public void checkDue_inFlightStatesAreBlocked() {
        for (UpdateState.State s : new UpdateState.State[]{
                UpdateState.State.CHECKING,
                UpdateState.State.DOWNLOADING,
                UpdateState.State.VERIFYING,
                UpdateState.State.INSTALL_PENDING}) {
            UpdateState st = state(s);
            st.lastCheckedMs = 0;
            assertFalse("expected " + s + " to block", UpdateManager.checkDue(10 * 365 * 24 * 60 * MINUTE, st, 120));
        }
    }

    /**
     * A staged build the host produced no longer ends the story: the manifest gets re-read on the
     * ordinary interval, so a frame left waiting on a person (or on a transport that keeps
     * refusing) finds out when the operator ships something else.
     */
    @Test
    public void checkDue_hostStagedUpdateIsRecheckedOnTheInterval() {
        UpdateState st = hostStage(SHA_A);
        st.lastCheckedMs = 1_000_000L;
        assertFalse(UpdateManager.checkDue(st.lastCheckedMs + 1, st, 120));
        assertTrue(UpdateManager.checkDue(st.lastCheckedMs + 120 * MINUTE, st, 120));
    }

    /** A stage this app did not download is not this app's to replace, however stale it gets. */
    @Test
    public void checkDue_stageNotFromTheHostStaysBlocked() {
        UpdateState uploaded = state(UpdateState.State.STAGED);
        uploaded.stagedSource = UpdateState.SOURCE_UPLOAD;
        uploaded.lastCheckedMs = 0;
        assertFalse(UpdateManager.checkDue(10 * 365 * 24 * 60 * MINUTE, uploaded, 120));

        // A state file written before stagedSource existed: the app cannot tell where the stage
        // came from, so it keeps today's behaviour rather than guessing.
        UpdateState legacy = state(UpdateState.State.STAGED);
        legacy.lastCheckedMs = 0;
        assertEquals(UpdateState.SOURCE_UNKNOWN, legacy.stagedSource);
        assertFalse(UpdateManager.checkDue(10 * 365 * 24 * 60 * MINUTE, legacy, 120));
    }

    // -------------------------------------------------------------- judgeStaged

    @Test
    public void judgeStaged_hostThatCannotAnswerKeepsTheStage() {
        // An unparseable manifest is a hiccup, not a verdict: it must never cost a frame the
        // installable build it already holds.
        assertEquals(UpdateManager.StagedVerdict.KEEP,
                UpdateManager.judgeStaged(null, hostStage(SHA_A), INSTALLED));
        UpdateManifest junk = published(0, "");
        assertFalse(junk.isValid());
        assertEquals(UpdateManager.StagedVerdict.KEEP,
                UpdateManager.judgeStaged(junk, hostStage(SHA_A), INSTALLED));
    }

    @Test
    public void judgeStaged_sameBuildAndBytesKeepsTheStage() {
        assertEquals(UpdateManager.StagedVerdict.KEEP,
                UpdateManager.judgeStaged(published(7, SHA_A), hostStage(SHA_A), INSTALLED));
        // Case is not part of the comparison; the manifest stores lowercase, the state may not.
        assertEquals(UpdateManager.StagedVerdict.KEEP,
                UpdateManager.judgeStaged(published(7, SHA_A.toUpperCase(java.util.Locale.US)),
                        hostStage(SHA_A), INSTALLED));
    }

    @Test
    public void judgeStaged_newerBuildReplacesTheStage() {
        assertEquals(UpdateManager.StagedVerdict.REPLACE,
                UpdateManager.judgeStaged(published(8, SHA_B), hostStage(SHA_A), INSTALLED));
        // A higher code published from the same bytes is still a different release.
        assertEquals(UpdateManager.StagedVerdict.REPLACE,
                UpdateManager.judgeStaged(published(8, SHA_A), hostStage(SHA_A), INSTALLED));
    }

    /** The half of the request a versionCode comparison alone would miss. */
    @Test
    public void judgeStaged_sameCodeDifferentBytesReplacesTheStage() {
        assertEquals(UpdateManager.StagedVerdict.REPLACE,
                UpdateManager.judgeStaged(published(7, SHA_B), hostStage(SHA_A), INSTALLED));
    }

    @Test
    public void judgeStaged_stagedBuildAlreadyRunningClearsIt() {
        assertEquals(UpdateManager.StagedVerdict.CLEAR,
                UpdateManager.judgeStaged(published(6, SHA_A), hostStage(SHA_A), 6));
        assertEquals(UpdateManager.StagedVerdict.CLEAR,
                UpdateManager.judgeStaged(published(5, SHA_A), hostStage(SHA_A), 6));
    }

    @Test
    public void judgeStaged_onlyAHostStageIsEverTouched() {
        UpdateState uploaded = hostStage(SHA_A);
        uploaded.stagedSource = UpdateState.SOURCE_UPLOAD;
        assertEquals(UpdateManager.StagedVerdict.SKIP,
                UpdateManager.judgeStaged(published(8, SHA_B), uploaded, INSTALLED));

        UpdateState legacy = hostStage(SHA_A);
        legacy.stagedSource = UpdateState.SOURCE_UNKNOWN;
        assertEquals(UpdateManager.StagedVerdict.SKIP,
                UpdateManager.judgeStaged(published(8, SHA_B), legacy, INSTALLED));

        // Not staged at all — a plain check from IDLE is not a verdict on a stage.
        assertEquals(UpdateManager.StagedVerdict.SKIP,
                UpdateManager.judgeStaged(published(8, SHA_B), state(UpdateState.State.IDLE), INSTALLED));
        assertEquals(UpdateManager.StagedVerdict.SKIP,
                UpdateManager.judgeStaged(published(8, SHA_B), null, INSTALLED));
    }

    /**
     * The half of the start-up rule that must never delete: an {@code IDLE} state only takes
     * ownership of bytes it can vouch for, and vouching for an upload is not this code's to do.
     */
    @Test
    public void adoptsInterruptedStage_onlyTheHostsOwnVerifiedBytes() {
        UpdateState interrupted = hostStage(SHA_A);
        interrupted.state = UpdateState.State.IDLE;
        assertTrue(UpdateManager.adoptsInterruptedStage(interrupted, true));

        // A master that does not hash to the state is a different build than the state names.
        assertFalse(UpdateManager.adoptsInterruptedStage(interrupted, false));

        UpdateState upload = hostStage(SHA_A);
        upload.state = UpdateState.State.IDLE;
        upload.stagedSource = UpdateState.SOURCE_UPLOAD;
        assertFalse("a killed POST /update swap leaves operator bytes, not a host stage",
                UpdateManager.adoptsInterruptedStage(upload, true));

        UpdateState legacy = hostStage(SHA_A);
        legacy.state = UpdateState.State.IDLE;
        legacy.stagedSource = UpdateState.SOURCE_UNKNOWN;
        assertFalse(UpdateManager.adoptsInterruptedStage(legacy, true));

        // reconcileState news up a blank state once an install is confirmed; expectedVersionCode 0
        // is that case, and adopting there would re-stage the build already running.
        UpdateState confirmed = hostStage(SHA_A);
        confirmed.state = UpdateState.State.IDLE;
        confirmed.expectedVersionCode = 0;
        assertFalse(UpdateManager.adoptsInterruptedStage(confirmed, true));

        assertFalse("nothing to adopt when the state still owns the stage",
                UpdateManager.adoptsInterruptedStage(hostStage(SHA_A), true));
    }

    /** withState() lists its fields by hand, which is how a new one goes missing quietly. */
    @Test
    public void withState_carriesStagedSourceThroughATransition() {
        UpdateState staged = hostStage(SHA_A);
        UpdateState failed = UpdateManager.withState(staged, UpdateState.State.FAILED, "boom");
        assertEquals(UpdateState.SOURCE_MANIFEST, failed.stagedSource);
        assertEquals(SHA_A, failed.manifestSha256);
        assertEquals(7, failed.expectedVersionCode);

        assertEquals(UpdateState.SOURCE_MANIFEST, UpdateState.failed(staged, "boom").stagedSource);
    }

    @Test
    public void checkDue_failedStateStillHonorsInterval() {
        UpdateState st = state(UpdateState.State.FAILED);
        st.lastCheckedMs = 1_000_000L;
        assertTrue(UpdateManager.checkDue(st.lastCheckedMs + 120 * MINUTE, st, 120));
        assertFalse(UpdateManager.checkDue(st.lastCheckedMs + 1, st, 120));
    }

    @Test
    public void checkDue_nonPositiveIntervalClampsToOneMinute() {
        UpdateState st = state(UpdateState.State.IDLE);
        st.lastCheckedMs = 0;
        assertTrue(UpdateManager.checkDue(MINUTE, st, 0));
        assertFalse(UpdateManager.checkDue(MINUTE - 1, st, 0));
    }

    // ---------------------------------------------------------- reconcileState

    @Test
    public void reconcile_nullStateIsIdle() {
        UpdateState r = UpdateManager.reconcileState(null, 7);
        assertEquals(UpdateState.State.IDLE, r.state);
        assertEquals(0, r.lastCheckedMs);
    }

    @Test
    public void reconcile_staleMidCycleResetsToIdle() {
        for (UpdateState.State s : new UpdateState.State[]{
                UpdateState.State.CHECKING,
                UpdateState.State.DOWNLOADING,
                UpdateState.State.VERIFYING}) {
            UpdateState r = UpdateManager.reconcileState(state(s), 1);
            assertEquals(UpdateState.State.IDLE, r.state);
            assertEquals("interrupted-by-restart", r.lastError);
            // pending metadata survives so nothing is silently lost
            assertEquals(7, r.expectedVersionCode);
            assertEquals(3, r.attempts);
        }
    }

    @Test
    public void reconcile_installPendingMatchMeansSuccess() {
        UpdateState st = state(UpdateState.State.INSTALL_PENDING);
        st.lastCheckedMs = 123L;
        UpdateState r = UpdateManager.reconcileState(st, 7);
        assertEquals(UpdateState.State.IDLE, r.state);
        assertEquals(0, r.attempts);
        assertEquals(123L, r.lastCheckedMs);
        assertEquals(0, r.expectedVersionCode);
        assertEquals("", r.lastError);
    }

    @Test
    public void reconcile_stagedMatchMeansSuccess() {
        UpdateState r = UpdateManager.reconcileState(state(UpdateState.State.STAGED), 7);
        assertEquals(UpdateState.State.IDLE, r.state);
        assertEquals(0, r.attempts);
    }

    @Test
    public void reconcile_installPendingStaleDemotesToStaged() {
        UpdateState st = state(UpdateState.State.INSTALL_PENDING);
        st.lastError = "boom";
        UpdateState r = UpdateManager.reconcileState(st, 6);
        assertEquals(UpdateState.State.STAGED, r.state);
        assertEquals("install-did-not-complete", r.lastError);
        assertEquals(7, r.expectedVersionCode);
    }

    @Test
    public void reconcile_stagedUnchangedWhenNotInstalled() {
        UpdateState st = state(UpdateState.State.STAGED);
        UpdateState r = UpdateManager.reconcileState(st, 6);
        assertSame(st, r);
        assertEquals(UpdateState.State.STAGED, r.state);
    }

    @Test
    public void reconcile_idleAndFailedUnchanged() {
        UpdateState idle = state(UpdateState.State.IDLE);
        assertSame(idle, UpdateManager.reconcileState(idle, 1));

        UpdateState failed = state(UpdateState.State.FAILED);
        failed.lastError = "sha256-mismatch";
        UpdateState r = UpdateManager.reconcileState(failed, 1);
        assertSame(failed, r);
        assertEquals("sha256-mismatch", r.lastError);
    }

    @Test
    public void reconcile_expectedVersionZeroNeverCountsAsSuccess() {
        UpdateState st = state(UpdateState.State.INSTALL_PENDING);
        st.expectedVersionCode = 0;
        UpdateState r = UpdateManager.reconcileState(st, 0);
        assertEquals(UpdateState.State.STAGED, r.state);
        assertEquals("install-did-not-complete", r.lastError);

        UpdateState stStaged = state(UpdateState.State.STAGED);
        stStaged.expectedVersionCode = 0;
        UpdateState rStaged = UpdateManager.reconcileState(stStaged, 0);
        assertSame(stStaged, rStaged);
    }

    // ---------------------------------------------------------- chooseInstallPath

    @Test
    public void installPath_permissionBeatsEverything() {
        assertEquals(UpdateManager.InstallPath.HEADLESS,
                UpdateManager.chooseInstallPath(true, false, false));
        assertEquals(UpdateManager.InstallPath.HEADLESS,
                UpdateManager.chooseInstallPath(true, true, true));
    }

    @Test
    public void installPath_adbdIsSilent() {
        assertEquals(UpdateManager.InstallPath.ADBD,
                UpdateManager.chooseInstallPath(false, true, false));
        assertEquals(UpdateManager.InstallPath.ADBD,
                UpdateManager.chooseInstallPath(false, true, true));
    }

    /** The dialog is what a person pressing the button gets — never what a tick does. */
    @Test
    public void installPath_dialogOnlyOnHumanRequest() {
        assertEquals(UpdateManager.InstallPath.INTENT,
                UpdateManager.chooseInstallPath(false, false, true));
        assertEquals(UpdateManager.InstallPath.DEFER,
                UpdateManager.chooseInstallPath(false, false, false));
    }
}
