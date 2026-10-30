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

    /** A state file written before {@link #stagedSource} existed, or a state with no stage. */
    public static final String SOURCE_UNKNOWN = "";
    /** The staged APK was fetched from the update host, so the host may replace it. */
    public static final String SOURCE_MANIFEST = "manifest";
    /** An operator staged this APK through {@code POST /update}, so nothing may replace it. */
    public static final String SOURCE_UPLOAD = "upload";

    public State state = State.IDLE;
    /**
     * The build this cycle is <em>fetching</em>, kept apart from the {@code expected*} fields
     * because those describe the APK actually on disk. A cycle used to overwrite them the moment
     * it parsed a manifest, so a process killed during the download or between the copy and the
     * master rename left state naming bytes that were nowhere on disk while the real staged APK
     * sat there unowned — unrecoverable at startup, and for an uploaded stage it also lost the
     * {@link #stagedSource} that protects it. The {@code fetch*} values are promoted into
     * {@code expected*} only when the swap commits.
     */
    public int fetchVersionCode;
    public String fetchVersionName = "";
    public String fetchSha256 = "";
    public long fetchSizeBytes;
    /**
     * What produced the staged APK. Only {@link #SOURCE_MANIFEST} may be displaced automatically
     * when the host publishes something else: an uploaded APK was put here on purpose, and
     * {@link #SOURCE_UNKNOWN} is whatever a frame had staged before this field existed — neither
     * may be guessed at, so both are left alone. Never infer it from a file name.
     */
    public String stagedSource = SOURCE_UNKNOWN;
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
    /**
     * Wall-clock ms an install was last fired (0 = never). The process that fires an install can be
     * killed by it, by a relaunch, or by a watchdog restart, and {@code INSTALL_PENDING} must not
     * become a dead end in the next process — so the settle deadline is persisted, not remembered.
     */
    public long installFiredAtMs;
    /**
     * True when the staged update is retryable but every silent transport failed, so it needs a
     * person to confirm it on the frame's screen. Set only by that deferral and cleared by every
     * other transition, so a stale flag can never claim a human is still needed.
     */
    public boolean needsHumanConfirmation;
    /**
     * True while an install has the frame's adbd open on TCP 5555 because this app started it — an
     * exposure an operator agreed to for the length of one action, not one boot. Survives precisely
     * so the process that replaces a successful install can close the door the previous one opened.
     */
    public boolean armedTcpAdb;

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
            s.stagedSource = previous.stagedSource;
            s.fetchVersionCode = previous.fetchVersionCode;
            s.fetchVersionName = previous.fetchVersionName;
            s.fetchSha256 = previous.fetchSha256;
            s.fetchSizeBytes = previous.fetchSizeBytes;
            s.lastCheckedMs = previous.lastCheckedMs;
            s.attempts = previous.attempts;
        }
        s.state = State.FAILED;
        s.lastError = reason == null ? "" : reason;
        return s;
    }
}
