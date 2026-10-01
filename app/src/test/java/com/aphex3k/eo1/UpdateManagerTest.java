package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.aphex3k.update.UpdateState;

import org.junit.Test;

/**
 * JVM tests for {@link UpdateManager}'s pure statics ({@code checkDue} and
 * {@code reconcileState}). No Android framework classes are exercised.
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
                UpdateState.State.STAGED,
                UpdateState.State.INSTALL_PENDING}) {
            UpdateState st = state(s);
            st.lastCheckedMs = 0;
            assertFalse("expected " + s + " to block", UpdateManager.checkDue(10 * 365 * 24 * 60 * MINUTE, st, 120));
        }
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
}
