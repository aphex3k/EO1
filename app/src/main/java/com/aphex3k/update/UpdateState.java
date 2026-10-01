package com.aphex3k.update;

import androidx.annotation.Keep;

/**
 * Persisted self-update state. The state file is the only channel between the process that
 * fires an install and the (possibly new) process that detects its outcome, so it must
 * survive process death — see {@link UpdateStateStore}.
 */
@Keep
public class UpdateState {

    public enum State {
        IDLE,
        CHECKING,
        DOWNLOADING,
        VERIFYING,
        STAGED,
        INSTALL_PENDING,
        FAILED
    }

    public State state = State.IDLE;
    /** versionCode the staged update (if any) is expected to install. */
    public int expectedVersionCode;
    public String expectedVersionName = "";
    /** SHA-256 of the APK this state was produced from; re-hash gate before each install. */
    public String manifestSha256 = "";
    public long expectedSizeBytes;
    /** Wall-clock ms of the last finished check cycle (0 = never). */
    public long lastCheckedMs;
    /** Short machine-readable reason when state is FAILED (or last non-empty error). */
    public String lastError = "";
    /** Check cycles started since the last success/reset (display-only). */
    public int attempts;

    public static UpdateState idle() {
        return new UpdateState();
    }

    /** A fresh FAILED state carrying over the pending-update metadata and attempt count. */
    public static UpdateState failed(UpdateState previous, String reason) {
        UpdateState s = new UpdateState();
        if (previous != null) {
            s.expectedVersionCode = previous.expectedVersionCode;
            s.expectedVersionName = previous.expectedVersionName;
            s.manifestSha256 = previous.manifestSha256;
            s.expectedSizeBytes = previous.expectedSizeBytes;
            s.lastCheckedMs = previous.lastCheckedMs;
            s.attempts = previous.attempts;
        }
        s.state = State.FAILED;
        s.lastError = reason == null ? "" : reason;
        return s;
    }
}
