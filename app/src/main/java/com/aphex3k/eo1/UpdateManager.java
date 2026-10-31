package com.aphex3k.eo1;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.util.Log;

import com.aphex3k.eo1.trust.TrustCensus;
import com.aphex3k.update.ApkManifestInfo;
import com.aphex3k.update.ApkSignatureVerifier;
import com.aphex3k.update.ApkUploadResult;
import com.aphex3k.update.UpdateManifest;
import com.aphex3k.update.UpdateState;
import com.aphex3k.update.UpdateStateStore;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Manifest-driven self-update.
 *
 * <p>Every cycle: fetch the plain-HTTP manifest ({@code updateManifestUrl}), validate it,
 * download the APK to a private {@code .part} file, then gate it through
 * SHA-256 → {@link ApkSignatureVerifier} (including signer-certificate match against the
 * installed app) → strictly-greater versionCode. Only then is the APK staged (private master
 * plus a world-readable public copy under {@code /sdcard/Download/}, because the installer
 * runs under a different uid) and installed.
 *
 * <p>Install dispatch is a ladder — see {@link #chooseInstallPath}: {@code INSTALL_PACKAGES}
 * (signature|system, so no sideloaded frame holds it) → the device's own adbd, which
 * {@link AdbdInstallClient#ensureTcpAdbd()} starts when the ROM never configured it → and if
 * neither answers, the update simply stays {@code STAGED} with {@code needsHumanConfirmation} set.
 * <b>An automatic cycle never puts the confirmation dialog on screen.</b> A frame is mounted on a
 * wall, usually dark during quiet hours: an unattended dialog is not an install, and it used to be
 * retried every tick against nobody, which is what made self-update look silently broken. The
 * dialog is what an explicit {@code Install on the frame} in the web UI gets, for a frame that has
 * no other way (an OTG keyboard or mouse plugged into the frame).
 *
 * <p>Retries ride the rotation tick — {@code interval} minutes, and the tick stops with the
 * activity — so the settle window below is a floor, not a schedule. And a stage is not the end of
 * the story: while the host's own build sits staged, the ordinary check interval still re-reads the
 * manifest and {@link #judgeStaged} decides whether those bytes are still what is published. If the
 * operator has since shipped something else, the published build is fetched and verified first and
 * only then replaces the stage, so a superseded frame converges on the release that was meant for
 * it instead of retrying a stale one forever. A stage an operator uploaded by hand is never
 * displaced by that path — see {@link UpdateState#stagedSource}.
 *
 * <p>Recovery across process death: the installer kills this process on success, so success
 * is detected only by the new process's {@link #reconcileOnStartup()}. A +120 s relaunch alarm
 * (the same one {@code uncaughtException} uses) is armed just before a <em>silent</em> dispatch,
 * so a fatal crash mid-install still gets the frame back to a launchable state. It is never armed
 * for the dialog: it used to fire MainActivity back over a confirmation a person was still reading.
 * {@code INSTALL_PENDING} carries {@link UpdateState#installFiredAtMs} for the same reason — the
 * pending state must not outlive the process that set it.
 */
public class UpdateManager {

    private static final String TAG = "EO1-update";
    private static final int BUFFER = 32 * 1024;
    private static final long MANIFEST_BODY_CAP = 64 * 1024;
    /** Free space required besides the APK itself, for the installer's headroom. */
    private static final long STAGING_HEADROOM_BYTES = 32L * 1024 * 1024;
    private static final long RELAUNCH_ALARM_MS = CrashReporter.RELAUNCH_DELAY_MS;
    /** How long a fired install may hold INSTALL_PENDING before the next tick treats it as failed. */
    private static final long INSTALL_SETTLE_MS = 90_000L;
    /** {@code lastError} of a staged update no silent transport on this frame can install. */
    static final String REASON_NEEDS_HUMAN = "needs-human-confirmation";

    private final WeakReference<UpdateManagerListener> listener;
    private final Context context;
    private final SettingsManager settingsManager;
    private final UpdateStateStore stateStore;
    private final File updatesDir;
    private final File partFile;
    private final File stagedMaster;
    private final File stagedPublic;

    private UpdateState state;
    /**
     * The thread currently entitled to change update state or touch the staged files — the cycle
     * thread while a check is running, the install thread once one is dispatched, and nobody after
     * an operator resets or uploads. A cycle blocks for seconds in {@code fetchManifest} and minutes
     * in {@code download}, and it carries a {@code prior} snapshot from the moment it started;
     * without this, a reset plus a fresh upload that completes in that window would have the old
     * cycle write the discarded stage's identity over the new one, and the next install would find
     * a pair that matches nothing and delete it. Checked under the monitor at every mutation, so a
     * superseded worker simply discards itself rather than corrupting what replaced it.
     */
    private volatile Thread updateWorker;
    /**
     * Held across the operations that destroy a staged file — {@code deleteStagedFiles()} and the
     * rename that replaces the private master — together with the ownership check that authorises
     * them. Deliberately NOT held across the bulk copy to the public path or over a download: those
     * take seconds on this hardware and would block the UI thread that dispatches
     * {@code /control?action=update-reset}, which is itself a liveness signal to the kiosk watchdog.
     * A stale worker's copy can only land on the public file, which {@code runInstall()} re-makes
     * from the master; the master is the one file whose loss is unrecoverable, and this makes
     * "check that I still own it" and "replace it" one critical section.
     *
     * <p>Order is always {@code stageGate} then {@code this}. Never the reverse.
     */
    private final Object stageGate = new Object();
    private boolean installInFlight;
    private boolean installFiredInThisProcess;
    private volatile String lastInstallAttempt = "none";
    private OkHttpClient httpClient;

    public UpdateManager(UpdateManagerListener listener, Context context, SettingsManager settingsManager) {
        this.listener = new WeakReference<>(listener);
        this.context = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        this.settingsManager = settingsManager;
        this.updatesDir = new File(this.context.getFilesDir(), "updates");
        if (!updatesDir.isDirectory() && !updatesDir.mkdirs()) {
            Log.w(TAG, "could not create " + updatesDir.getAbsolutePath());
        }
        this.partFile = new File(updatesDir, "eo1-update.apk.part");
        this.stagedMaster = new File(updatesDir, "eo1-update.apk");
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        this.stagedPublic = new File(downloads != null ? downloads : new File(Environment.getExternalStorageDirectory(), "Download"),
                "eo1-update.apk");
        this.stateStore = new UpdateStateStore(UpdateStateStore.defaultStateFile(this.context));
        this.state = this.stateStore.read();
    }

    // ------------------------------------------------------------------ triggering

    /** Forced update cycle (TOP+BACK key combo, {@code /control?action=check-updates}). */
    public void checkForUpdates(Activity parent) {
        startCycle();
    }

    /**
     * Interval-gated cycle called from {@code runOnTimer()}. No-ops in debug builds, when no
     * manifest URL is configured, when a cycle is already in flight, and when the configured
     * interval has not elapsed.
     */
    public void checkIfDue() {
        if (BuildConfig.DEBUG) {
            return;
        }
        Configuration config = settingsManager.getConfiguration();
        if (config == null || !config.updatesEnabled()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        synchronized (this) {
            if (!checkDue(nowMs, state, config.updateCheckIntervalMinutes)) {
                return;
            }
        }
        startCycle();
    }

    /**
     * Package-visible for unit tests. True when a new check cycle may start: no cycle in flight,
     * no install awaiting its settle window, and the configured interval has elapsed.
     *
     * <p>A {@code STAGED} update the host itself produced does <em>not</em> block a check. It used
     * to, permanently, which is how a frame that staged build N and was then left waiting on a
     * person — or on a transport that keeps refusing — kept re-installing a build the operator had
     * since replaced, without ever learning. While such a stage exists the manifest is re-read on
     * the ordinary interval and {@link #judgeStaged} decides whether the bytes on disk are still
     * the bytes published.
     *
     * <p>A stage this code did not fetch stays blocked: an APK the operator uploaded by hand, and
     * anything staged by a build older than {@link UpdateState#stagedSource}, are not this
     * predicate's to displace.
     */
    static boolean checkDue(long nowMs, UpdateState state, int intervalMinutes) {
        if (state == null) {
            // No state file at all: never checked, so the first cycle runs now.
            return true;
        }
        if (state.state != null) {
            switch (state.state) {
                case STAGED:
                    if (!UpdateState.SOURCE_MANIFEST.equals(state.stagedSource)) {
                        return false;
                    }
                    break;
                case CHECKING:
                case DOWNLOADING:
                case VERIFYING:
                case INSTALL_PENDING:
                    return false;
                default:
                    break;
            }
        }
        long intervalMs = Math.max(1, intervalMinutes) * 60_000L;
        long last = state != null ? state.lastCheckedMs : 0L;
        return nowMs - last >= intervalMs;
    }

    // ------------------------------------------------------------ startup reconcile

    /**
     * Called once from {@code onCreate}, before anything else touches the web server: clears
     * stale mid-cycle states, confirms a completed install (new versionCode == expected),
     * and demotes a stale {@code INSTALL_PENDING} back to the retryable {@code STAGED}.
     */
    public void reconcileOnStartup() {
        boolean inheritedAdbWindow = false;
        synchronized (this) {
            UpdateState current = state;
            UpdateState reconciled = reconcileState(current, BuildConfig.VERSION_CODE);
            if (current.armedTcpAdb) {
                // A silent install opened TCP adb and was killed by its own success before it could
                // close it. The window was agreed to for one action, so close it here.
                inheritedAdbWindow = true;
                reconciled.armedTcpAdb = false;
            }

            if (current.state == UpdateState.State.CHECKING
                    || current.state == UpdateState.State.DOWNLOADING
                    || current.state == UpdateState.State.VERIFYING) {
                // The process died mid-cycle: drop the partial download, start clean.
                partFile.delete();
            }
            if ((current.state == UpdateState.State.STAGED
                    || current.state == UpdateState.State.INSTALL_PENDING)
                    && reconciled.state == UpdateState.State.IDLE) {
                // We are now running the version we staged: the update completed.
                deleteStagedFiles();
                notifyDebug("self-update " + current.expectedVersionCode + " confirmed installed; state reset");
            }
            // A check killed anywhere between the manifest parse and the final rename leaves an
            // IDLE state (reconcileState demotes a mid-cycle one) sitting on top of a complete APK
            // that no state claims. Both identities survive — expected* names the stage that was
            // installable when the cycle started, fetch* the build it was replacing — so whichever
            // the bytes match decides what the frame owns again. Recovering the first one also
            // hands an uploaded stage back its stagedSource, which is the only thing protecting it
            // from the next check. Nothing here ever deletes: VERIFYING is also what POST /update
            // parks in while it swaps an uploaded APK, and those bytes are the operator's.
            UpdateState recovered = recoverInterruptedStage(reconciled,
                    stagedIntactAs(reconciled.manifestSha256, reconciled.expectedSizeBytes),
                    stagedIntactAs(reconciled.fetchSha256, reconciled.fetchSizeBytes));
            if (recovered != reconciled) {
                reconciled = recovered;
            }
            if (reconciled.state == UpdateState.State.STAGED
                    && !stagedPublic.exists()
                    && !stagedMaster.exists()) {
                // A staged state without its files can never install.
                reconciled = withState(reconciled, UpdateState.State.IDLE, "staged-files-missing");
                reconciled.stagedSource = UpdateState.SOURCE_UNKNOWN;
            }
            // Orphaned temp dirs from a process death mid POST /update (the web server's
            // finally-block cleanup never got to run).
            File[] entries = updatesDir.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    if (entry.isDirectory() && entry.getName().startsWith("apk_tmp_")) {
                        deleteDirQuiet(entry);
                    }
                }
            }
            state = reconciled;
            stateStore.write(state);
        }
        notifyDebug("update state: " + state.state + (state.lastError.isEmpty() ? "" : " (" + state.lastError + ")"));
        if (inheritedAdbWindow) {
            // Outside the lock and after the state write: this restarts adbd, which takes a moment.
            notifyDebug("startup: an earlier install left adbd open — "
                    + AdbdInstallClient.teardownTcpAdbd().detail);
        }
    }

    /**
     * Package-visible for unit tests. Pure startup reconciliation — no file access. A stale
     * mid-cycle state restarts from IDLE; a staged/pending install whose expected version is
     * now the running version succeeded; a pending install that did not take effect is
     * retryable STAGED.
     */
    static UpdateState reconcileState(UpdateState state, int installedVersionCode) {
        if (state == null || state.state == null) {
            return UpdateState.idle();
        }
        switch (state.state) {
            case CHECKING:
            case DOWNLOADING:
            case VERIFYING:
                return withState(state, UpdateState.State.IDLE, "interrupted-by-restart");
            case INSTALL_PENDING:
            case STAGED:
                if (state.expectedVersionCode > 0 && state.expectedVersionCode == installedVersionCode) {
                    UpdateState fresh = new UpdateState();
                    fresh.lastCheckedMs = state.lastCheckedMs;
                    return fresh; // success: IDLE, attempts reset
                }
                if (state.state == UpdateState.State.INSTALL_PENDING) {
                    return withState(state, UpdateState.State.STAGED, "install-did-not-complete");
                }
                return state; // STAGED stays retryable
            default:
                return state;
        }
    }

    /**
     * Package-visible for unit tests. Pure half of the start-up recovery described at the call
     * site: given an {@code IDLE} state a killed cycle left behind and what the private master
     * turned out to be, return the state this launch should run with.
     *
     * <p>Either answer restores a {@code STAGED} state rather than inventing one — the first from
     * the identity that was on disk when the cycle started (carrying its {@code stagedSource}, so
     * an uploaded stage is protected again), the second by promoting the fetched identity, which
     * is what the swap had just committed to the master. A confirmed install is excluded by
     * construction: {@link #reconcileState} news up a blank state for that, and a blank state has
     * no sha for either comparison to match.
     */
    static UpdateState recoverInterruptedStage(UpdateState reconciled, boolean masterIsStaged,
            boolean masterIsFetching) {
        if (reconciled == null || reconciled.state != UpdateState.State.IDLE) {
            return reconciled;
        }
        // An operator who uploads the very APK already staged satisfies BOTH matches, and the
        // pending upload's provenance has to win: manifest says replaceable, upload says hands off,
        // and only one of those cannot end with the next check deleting someone's file.
        boolean canPromote = masterIsFetching && reconciled.fetchVersionCode > 0
                && reconciled.fetchSha256 != null && !reconciled.fetchSha256.isEmpty();
        if (canPromote && (UpdateState.SOURCE_UPLOAD.equals(reconciled.fetchOrigin)
                || !masterIsStaged)) {
            UpdateState promoted = withState(reconciled, UpdateState.State.STAGED,
                    "staged-recovered-after-restart");
            promoted.expectedVersionCode = reconciled.fetchVersionCode;
            promoted.expectedVersionName = reconciled.fetchVersionName;
            promoted.manifestSha256 = reconciled.fetchSha256;
            promoted.expectedSizeBytes = reconciled.fetchSizeBytes;
            promoted.stagedSource = reconciled.fetchOrigin == null
                    ? UpdateState.SOURCE_UNKNOWN : reconciled.fetchOrigin;
            promoted.fetchVersionCode = 0;
            promoted.fetchVersionName = "";
            promoted.fetchSha256 = "";
            promoted.fetchSizeBytes = 0L;
            promoted.fetchOrigin = UpdateState.SOURCE_UNKNOWN;
            return promoted;
        }
        if (masterIsStaged) {
            // The bytes on disk are the stage that was there before the check, so the build the
            // check was fetching is simply not here and nothing is pending. Leaving fetch* set
            // would have /state advertise a download that is not running, and let a later crash
            // match these bytes against a hash that never described them.
            UpdateState restored = withState(reconciled, UpdateState.State.STAGED,
                    "staged-recovered-after-restart");
            restored.fetchVersionCode = 0;
            restored.fetchVersionName = "";
            restored.fetchSha256 = "";
            restored.fetchSizeBytes = 0L;
            restored.fetchOrigin = UpdateState.SOURCE_UNKNOWN;
            return restored;
        }
        return reconciled;
    }

    /**
     * Called from {@code onResume}: if this process fired an install and is still alive on the old
     * version, it did not take effect — roll back to the retryable {@code STAGED}. Naming the reason
     * matters here: {@code canceled-or-refused} means a person dismissed the dialog we were shown,
     * and anything else means a silent transport that promised more than it delivered.
     */
    public void reconcileInstallOutcome() {
        boolean rolledBack = false;
        synchronized (this) {
            if (!installFiredInThisProcess) {
                return;
            }
            if (state.state != UpdateState.State.INSTALL_PENDING) {
                // The worker already recorded the outcome (observer failure / exception).
                installFiredInThisProcess = false;
                return;
            }
            installFiredInThisProcess = false;
            boolean dialog = "intent".equals(lastInstallAttempt);
            state = withState(state, UpdateState.State.STAGED,
                    dialog ? "install-canceled-or-refused" : "install-did-not-complete");
            state.installFiredAtMs = 0L;
            stateStore.write(state);
            rolledBack = true;
        }
        if (rolledBack) {
            notifyDebug("install did not complete in-process; back to STAGED (retried on the next tick)");
        }
    }

    // ------------------------------------------------------------------- installing

    /**
     * Installs a staged update, called from the web UI's install button and from every rotation
     * tick — so a stuck install is retried until it lands or {@link #resetUpdate()}.
     *
     * @param humanRequested {@code true} only for the button. It authorises the one thing an
     *                       unattended frame cannot use — the confirmation dialog — and makes the
     *                       call report why it did nothing. A tick passes {@code false}, and a tick
     *                       with no silent transport then leaves the update staged and silent
     *                       instead of showing a dialog to nobody.
     */
    public void installStaged(final boolean humanRequested) {
        String event = null; // a state change worth logging on an unattended tick too
        String note = null;  // a reply to a press; on a tick it would be noise every interval
        boolean dispatch = false;
        synchronized (this) {
            if (installInFlight) {
                return;
            }
            if (state.state == UpdateState.State.INSTALL_PENDING) {
                long firedAt = state.installFiredAtMs;
                long elapsedMs = System.currentTimeMillis() - firedAt;
                if (firedAt > 0L && elapsedMs <= INSTALL_SETTLE_MS) {
                    // An install really is in flight — pm install can run for minutes. The old
                    // "no staged update" read as a broken state machine when it only meant
                    // "you pressed this a few seconds after it already fired".
                    note = "install already dispatched " + (elapsedMs / 1000L)
                            + "s ago; waiting for it to settle";
                } else {
                    // INSTALL_PENDING with its settle window spent (or with no timestamp at all,
                    // i.e. a state file older than this field): the install did not land. Roll back
                    // so this attempt can retry — including in a process that never fired it.
                    installFiredInThisProcess = false;
                    state = withState(state, UpdateState.State.STAGED, "install-did-not-complete");
                    state.installFiredAtMs = 0L;
                    stateStore.write(state);
                    event = "install fired earlier did not settle; back to STAGED";
                }
            }
            if (state.state == UpdateState.State.STAGED) {
                installInFlight = true;
                // The install owns the update work from here: a check thread still blocked on a
                // fetch must not come back and rewrite the stage this install is reading.
                updateWorker = null;
                dispatch = true;
            } else if (note == null) {
                note = "nothing staged (state=" + state.state + ")";
            }
        }
        if (event != null) {
            notifyDebug("install-staged: " + event);
        }
        if (note != null && humanRequested) {
            notifyDebug("install-staged: " + note);
        }
        if (!dispatch) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runInstall(humanRequested);
                } catch (Throwable t) {
                    Log.e(TAG, "install crashed", t);
                    failState("install-crashed: " + t);
                } finally {
                    synchronized (UpdateManager.this) {
                        installInFlight = false;
                    }
                }
            }
        }, "EO1-update-install");
        thread.start();
    }

    /** Which transport an install dispatch takes; see {@link #chooseInstallPath}. */
    enum InstallPath {
        /** This frame holds INSTALL_PACKAGES: silent installPackage. */
        HEADLESS,
        /** Silent install through the device's own adbd (possibly after re-arming it). */
        ADBD,
        /** The confirmation dialog — only ever launched because a person asked for it. */
        INTENT,
        /** Nothing on this frame can install by itself: stay STAGED and say so. */
        DEFER
    }

    /**
     * The install ladder as a pure decision, so "will this frame install by itself?" is answerable
     * in a unit test instead of only on a shelf. A frame with no silent transport must never put a
     * dialog on an unattended screen: it would sit on a dark panel until a relaunch yanked it away,
     * which is what made self-update look broken.
     *
     * @param adbdReachable true when the device's own adbd answers on loopback — possibly only
     *                      because {@link AdbdInstallClient#ensureTcpAdbd()} just started it
     */
    static InstallPath chooseInstallPath(boolean holdsPermission, boolean adbdReachable, boolean humanRequested) {
        if (holdsPermission) {
            return InstallPath.HEADLESS;
        }
        if (adbdReachable) {
            return InstallPath.ADBD;
        }
        return humanRequested ? InstallPath.INTENT : InstallPath.DEFER;
    }

    private void runInstall(boolean humanRequested) {
        UpdateState snapshot;
        synchronized (this) {
            if (state.state != UpdateState.State.STAGED) {
                return;
            }
            snapshot = state;
        }

        // Re-hash gate: stagedPublic lives in a world-writable directory. Self-heal from the
        // private master when the public copy changed on disk.
        boolean publicOk = snapshot.manifestSha256.equals(sha256Safe(stagedPublic))
                && stagedPublic.length() == snapshot.expectedSizeBytes;
        if (!publicOk) {
            Log.w(TAG, "staged public copy changed on disk; re-copying from private master");
            boolean masterOk = snapshot.manifestSha256.equals(sha256Safe(stagedMaster))
                    && stagedMaster.length() == snapshot.expectedSizeBytes;
            if (!masterOk) {
                // The private master is the corrupt one, so nothing on disk is trustworthy and the
                // pair goes. This is the only case that may delete. The install thread is not an
                // owner (an install dispatch revokes the cycle that started it), so it needs the
                // gate for serialisation only — installInFlight is what makes it unique.
                discardStagedUnderGate();
                failState("staged-file-changed");
                return;
            }
            if (!copyFile(stagedMaster, stagedPublic)
                    || stagedPublic.length() != snapshot.expectedSizeBytes) {
                // The master is intact and the destination is the problem — full, unmounted, or
                // otherwise unwritable. Deleting the good master here would throw away the only
                // installable APK the frame has because of a defect in its copy, and FAILED would
                // stop the retry that is the one thing likely to succeed later. Stay staged.
                setStaged("public-copy-failed");
                notifyDebug("install: staged APK is sound but the installer-readable copy failed;"
                        + " retrying on the next tick");
                return;
            }
        }

        boolean holdsPermission = holdsInstallPermission();
        // No INSTALL_PACKAGES (true of every sideloaded frame): the device's own adbd is the only
        // silent transport, and on EO1 it is not even listening. This is the one and only place the
        // app starts it — for this install, of this staged APK, closed again in the finally below.
        // Probing and re-arming change no state, so the ladder can decide on the answer.
        AdbdInstallClient.Result armed = holdsPermission
                ? null : AdbdInstallClient.ensureTcpAdbd(loopbackAdbAllowed());
        boolean adbdReachable = armed != null && (armed.outcome == AdbdInstallClient.Outcome.SUCCESS
                || armed.outcome == AdbdInstallClient.Outcome.ARMED);
        // Only an adbd this call started is this call's to close.
        final boolean armedByUs = armed != null && armed.outcome == AdbdInstallClient.Outcome.ARMED;

        switch (chooseInstallPath(holdsPermission, adbdReachable, humanRequested)) {
            case HEADLESS:
                armAndPersist("headless installPackage", snapshot.expectedVersionCode);
                lastInstallAttempt = "headless";
                try {
                    fireHeadlessInstall(Uri.fromFile(stagedPublic));
                } catch (Throwable t) {
                    Log.e(TAG, "installPackage threw", t);
                    setStaged("installPackage-threw: " + t);
                }
                break;

            case ADBD: {
                armAndPersist("adbd pm install", snapshot.expectedVersionCode);
                // Record it before the attempt: a successful pm install kills this process, and the
                // next one has to know it inherited a door the last one opened.
                persistAdbArmed(armedByUs);
                boolean settled;
                try {
                    settled = installViaAdbd(snapshot);
                } finally {
                    closeAdbIfWeOpenedIt(armedByUs);
                }
                if (!settled) {
                    // Looked reachable and then did not answer.
                    if (humanRequested) {
                        fireInstallIntent(snapshot);
                    } else {
                        setNeedsHuman();
                    }
                }
                break;
            }

            case INTENT:
                fireInstallIntent(snapshot);
                break;

            case DEFER:
                // Nothing on this frame can install it, and an unattended confirmation dialog is
                // not a plan — it would sit on a dark panel until something else took the screen
                // back. Stay staged and retryable; the web UI says who is owed a click.
                setNeedsHuman();
                break;
        }
    }

    /**
     * Whether this frame may open loopback adb <em>to install an update</em>: <i>Trusted Network</i>
     * and <i>Allow loopback ADB for update installs</i>, both on, decided on the options dialog. Read
     * per attempt so flipping either one takes effect without a restart. The permission covers one
     * action at a time: this install, or a {@link DeviceRepair} rung the watchdog has actually
     * reached — never launch, and never left open between actions.
     */
    private boolean loopbackAdbAllowed() {
        try {
            return settingsManager != null && settingsManager.isLoopbackAdbEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /** Notes in the state file that this install opened the port, so a successor can close it. */
    private void persistAdbArmed(boolean armed) {
        synchronized (this) {
            state.armedTcpAdb = armed;
            stateStore.write(state);
        }
        if (armed) {
            notifyDebug("install: started adbd for this install; it will be closed again after");
        }
    }

    /** End of the exposure window: closes adbd if — and only if — this install started it. */
    private void closeAdbIfWeOpenedIt(boolean armedByUs) {
        if (!armedByUs) {
            return; // the ROM's own daemon, or there was none to close
        }
        AdbdInstallClient.Result closed = AdbdInstallClient.teardownTcpAdbd();
        persistAdbArmed(false);
        notifyDebug("install: " + closed.detail);
    }

    /** Records that a silent install is being fired, and keeps the frame launchable if we die. */
    private void armAndPersist(String how, int expectedVersionCode) {
        armRelaunchAlarm();
        persistInstallPending();
        installFiredInThisProcess = true;
        notifyDebug("install: " + how + " (version " + expectedVersionCode + ")");
    }

    private void fireInstallIntent(UpdateState snapshot) {
        // A person asked for this install, so the dialog is wanted: it is how a bootstrap frame
        // finishes an update, via an OTG keyboard/mouse at the frame. No relaunch alarm here — it
        // used to fire MainActivity back over the pending confirmation 120 s into it.
        persistInstallPending();
        installFiredInThisProcess = true;
        lastInstallAttempt = "intent";
        wakeScreenForInstaller();
        notifyDebug("install: confirmation dialog on the frame's screen (version "
                + snapshot.expectedVersionCode + ")");
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(stagedPublic), "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "install intent threw", t);
            setStaged("install-intent-threw: " + t);
        }
    }

    /**
     * Installs the staged public copy through the device's own adbd
     * ({@code 127.0.0.1:5555}, enabled by the ROM's {@code service.adb.tcp.port}) —
     * no confirmation UI, which a headless frame cannot interact with.
     *
     * @return {@code true} when the attempt is settled (success, or a pm failure
     *         already recorded as retryable {@code STAGED}); {@code false} when adbd
     *         is unreachable and the caller should fall back to the install intent.
     */
    private boolean installViaAdbd(UpdateState snapshot) {
        lastInstallAttempt = "adbd";
        AdbdInstallClient.Result result = new AdbdInstallClient().install(stagedPublic);
        if (result.outcome == AdbdInstallClient.Outcome.PM_FAILED) {
            notifyDebug("adbd pm install failed: " + result.detail);
            installFiredInThisProcess = false;
            setStaged("install-failed: " + result.detail);
            return true;
        }
        if (result.outcome != AdbdInstallClient.Outcome.SUCCESS) {
            notifyDebug("adbd did not answer: " + result.detail);
            return false;
        }
        // pm replaces the package and kills this process; the new process's
        // reconcileOnStartup() confirms (INSTALL_PENDING -> IDLE, staged files deleted).
        notifyDebug("adbd pm install succeeded (version " + snapshot.expectedVersionCode
                + "); awaiting process replacement");
        return true;
    }

    /**
     * Fires the headless install reflectively: the compile-time android.jar lacks
     * {@code installPackage} and the {@code PackageManager$PackageInstallObserver} interface,
     * but the device's framework has both at API 19. The proxy implements the real
     * framework interface, so the installer can call back into it.
     */
    private void fireHeadlessInstall(Uri uri) throws Exception {
        Class<?> observerClass =
                Class.forName("android.content.pm.PackageManager$PackageInstallObserver");
        Object observer = Proxy.newProxyInstance(
                context.getClassLoader(),
                new Class<?>[]{observerClass},
                installCallbackHandler);
        Method installPackage = PackageManager.class.getMethod(
                "installPackage", Uri.class, observerClass, String.class);
        installPackage.invoke(context.getPackageManager(), uri, observer, null);
    }

    /** Deletes the staged APKs and returns to {@code IDLE} (keeps the last-checked time). */
    public void resetUpdate() {
        synchronized (stageGate) {
            synchronized (this) {
                // Revoked before the files go, so a cycle blocked on a fetch cannot come back and
                // rename a stage the operator has just asked to discard. Gate then monitor, always.
                updateWorker = null;
                deleteStagedFiles();
                partFile.delete();
                installFiredInThisProcess = false;
                state = withState(state, UpdateState.State.IDLE, "");
                state.attempts = 0;
                // withState carries stagedSource, and an IDLE state with no stage on disk must not
                // keep naming the source of one — nor a build some earlier cycle was still fetching,
                // which recovery would otherwise try to match against files that are about to be gone.
                state.stagedSource = UpdateState.SOURCE_UNKNOWN;
                state.fetchVersionCode = 0;
                state.fetchVersionName = "";
                state.fetchSha256 = "";
                state.fetchSizeBytes = 0L;
                state.fetchOrigin = UpdateState.SOURCE_UNKNOWN;
                stateStore.write(state);
            }
        }
        notifyDebug("update reset");
    }

    // ------------------------------------------------------------- web apk upload

    /** The staging directory ({@code filesDir/updates}); the web server uses it as the temp dir for APK uploads. */
    public File updatesDir() {
        return updatesDir;
    }

    /**
     * Verifies an APK uploaded through {@code POST /update} with the same gates as the
     * download path — size cap, SHA-256, {@link ApkSignatureVerifier} (signer-certificate
     * match against the installed app), strictly-greater versionCode (read from the APK's
     * own binary manifest) — and, when it passes, stages it for install (private master +
     * world-readable public copy, state {@code STAGED}). No auto-install: the web UI shows
     * the computed SHA-256 and the user decides whether to fire
     * {@link #installStaged(boolean)}.
     *
     * <p>Called from a web worker thread. While verifying and staging, the state is held in
     * {@code VERIFYING} (the same in-flight marker the download cycle uses) so the timer's
     * install retry and a manual check-updates cannot run concurrently. A rejected upload
     * restores the previous state — when it replaced a staged APK that is left untouched,
     * otherwise the state goes to {@code FAILED}.
     *
     * @param apk the uploaded file; moved into the updates dir on success, deleted on rejection.
     * @return the result (never null) with the computed SHA-256 for the user to verify
     *         against the published release.
     */
    public ApkUploadResult stageUploadedApk(File apk) {
        if (apk == null || !apk.isFile() || apk.length() <= 0) {
            return ApkUploadResult.reject("", 0, false, "file-unavailable");
        }
        final long size = apk.length();
        if (size > UpdateManifest.MAX_APK_BYTES) {
            apk.delete();
            return ApkUploadResult.reject("", size, false, "exceeds-size-cap");
        }

        boolean replacingStaged;
        String previousLastError;
        synchronized (this) {
            if (installInFlight) {
                apk.delete();
                return ApkUploadResult.reject("", size, false, "install-in-flight");
            }
            switch (state.state) {
                case CHECKING:
                case DOWNLOADING:
                case VERIFYING:
                case INSTALL_PENDING:
                    apk.delete();
                    return ApkUploadResult.reject("", size, false, "update-cycle-in-flight");
                default:
                    break;
            }
            replacingStaged = state.state == UpdateState.State.STAGED;
            previousLastError = state.lastError;
            // Reserve the state as in-flight before the slow verification work, the same
            // way startCycle() reserves CHECKING — and take the mutation right away from any check
            // thread that is still blocked on a fetch, because its snapshot of this stage is now
            // the thing being replaced.
            updateWorker = null;
            state = withState(state, UpdateState.State.VERIFYING, "");
            stateStore.write(state);
        }

        boolean oldStagedGone = false;
        ApkManifestInfo manifest = null;
        String sha = "";
        try {
            sha = sha256Safe(apk);
            if (sha.isEmpty()) {
                return failUpload(apk, replacingStaged, previousLastError, sha, size, false, "sha256-failed");
            }
            X509Certificate installed = installedCert();
            if (installed == null) {
                return failUpload(apk, replacingStaged, previousLastError, sha, size, false, "no-installed-cert");
            }
            ApkSignatureVerifier.Result signature = ApkSignatureVerifier.verify(apk, installed);
            if (!signature.valid) {
                return failUpload(apk, replacingStaged, previousLastError, sha, size, false,
                        "signature-rejected: " + signature.reason);
            }
            manifest = ApkManifestInfo.read(apk);
            if (manifest == null || manifest.versionCode == null) {
                return failUpload(apk, replacingStaged, previousLastError, sha, size, true, "version-code-unreadable");
            }
            if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                return failUpload(apk, replacingStaged, previousLastError, sha, size, true,
                        "not-newer: " + manifest.versionCode + " is not newer than installed " + BuildConfig.VERSION_CODE);
            }
            long required = size + STAGING_HEADROOM_BYTES;
            if (freeBytes(updatesDir) < required || freeBytes(stagedPublic.getParentFile()) < required) {
                return failUpload(apk, replacingStaged, previousLastError, sha, size, true, "insufficient-space");
            }
            // Claim the bytes' identity BEFORE they replace anything on disk. A process killed
            // between the rename below and the metadata write at the end would otherwise come back
            // describing the stage it overwrote — and if that one was host-sourced, the next check
            // would be free to delete the operator's APK. fetchOrigin is what makes the recovered
            // stage an upload rather than a licence to discard it.
            synchronized (this) {
                state.fetchVersionCode = manifest.versionCode;
                state.fetchVersionName = manifest.versionName == null ? "" : manifest.versionName;
                state.fetchSha256 = sha;
                state.fetchSizeBytes = size;
                state.fetchOrigin = UpdateState.SOURCE_UPLOAD;
                stateStore.write(state);
            }
            // Swap: replace any previously staged update. Under the same gate the check cycle takes
            // for its master rename, so the two can neither interleave nor leave one master half
            // replaced by the other. Remember whether the old staged files are actually gone: a
            // later failure may only restore the STAGED state when they are, or the state would
            // claim an APK that no longer exists on disk.
            synchronized (stageGate) {
                oldStagedGone = deleteStagedFiles();
                if (!apk.renameTo(stagedMaster)) {
                    stagedMaster.delete();
                    if (!apk.renameTo(stagedMaster)) {
                        return failUpload(null, oldStagedGone, previousLastError, sha, size, true,
                                "stage-rename-failed");
                    }
                }
            }
            if (!externalStorageWritable()) {
                discardStagedUnderGate();
                return failUpload(null, oldStagedGone, previousLastError, sha, size, true,
                        "external-storage-unavailable");
            }
            if (!copyFile(stagedMaster, stagedPublic) || stagedPublic.length() != stagedMaster.length()) {
                discardStagedUnderGate();
                return failUpload(null, oldStagedGone, previousLastError, sha, size, true,
                        "stage-copy-failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "upload staging crashed", e);
            // Best effort: the file may or may not still exist at its temp location. The
            // previous STAGED state is restorable only while its files are still on disk.
            return failUpload(apk, replacingStaged && !oldStagedGone, previousLastError, "", size,
                    false, "stage-crashed: " + e.getClass().getSimpleName());
        }

        synchronized (this) {
            state.expectedVersionCode = manifest.versionCode;
            state.expectedVersionName = manifest.versionName;
            state.manifestSha256 = sha;
            state.expectedSizeBytes = size;
            state.lastCheckedMs = System.currentTimeMillis();
            // This stage is the operator's, not the update host's: judgeStaged must never displace
            // it, whatever the manifest starts saying. And whatever a cycle was mid-fetch of is
            // moot now — these bytes won.
            state.stagedSource = UpdateState.SOURCE_UPLOAD;
            state.fetchVersionCode = 0;
            state.fetchVersionName = "";
            state.fetchSha256 = "";
            state.fetchSizeBytes = 0L;
            state.fetchOrigin = UpdateState.SOURCE_UNKNOWN;
            state = withState(state, UpdateState.State.STAGED, "");
            stateStore.write(state);
        }
        String stagedMsg = "staged uploaded APK " + manifest.versionName + " ("
                + manifest.versionCode + ", " + size + " B)";
        Log.i(TAG, stagedMsg);
        notifyDebug(stagedMsg);
        return ApkUploadResult.staged(sha, size);
    }

    private ApkUploadResult failUpload(File apk, boolean restoringStaged, String previousLastError,
            String sha, long size, boolean signatureValid, String reason) {
        if (apk != null) {
            apk.delete();
        }
        synchronized (this) {
            if (restoringStaged) {
                // The previously staged APK is still on disk with its original metadata.
                state = withState(state, UpdateState.State.STAGED, previousLastError);
            } else {
                state = withState(state, UpdateState.State.FAILED, reason);
            }
            // The rejected upload is not pending anywhere; the identity claimed for it is void.
            state.fetchVersionCode = 0;
            state.fetchVersionName = "";
            state.fetchSha256 = "";
            state.fetchSizeBytes = 0L;
            state.fetchOrigin = UpdateState.SOURCE_UNKNOWN;
            stateStore.write(state);
        }
        Log.w(TAG, "uploaded APK rejected: " + reason);
        notifyDebug("uploaded APK rejected: " + reason);
        return ApkUploadResult.reject(sha, size, signatureValid, reason);
    }

    // ---------------------------------------------------------------------- cycle

    private void startCycle() {
        synchronized (this) {
            UpdateState s = state;
            if (installInFlight) {
                // The platform installer has stagedPublic open right now. A cycle that replaces
                // that pair mid-install would have the installer read a file that is being
                // rewritten under it, and runInstall's own re-hash gate would then throw away a
                // stage that was about to land.
                notifyDebug("update cycle refused (install in flight)");
                return;
            }
            switch (s.state) {
                case CHECKING:
                case DOWNLOADING:
                case VERIFYING:
                case INSTALL_PENDING:
                    // A cycle is already in flight, or an install is inside its settle window.
                    notifyDebug("update cycle refused (state=" + s.state + ")");
                    return;
                default:
                    break;
            }
            // A STAGED update no longer stops a check: checkDue lets the timer re-read the host for
            // a stage the host itself produced, and a forced check gets here for any stage. Either
            // way runCycle consults judgeStaged before it overwrites anything, so a stage this app
            // did not download is still untouched when the cycle decides what it may do.
            final UpdateState prior = s;
            state = withState(s, UpdateState.State.CHECKING, "");
            state.attempts = s.attempts + 1;
            state.lastCheckedMs = System.currentTimeMillis();
            stateStore.write(state);
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        runCycle(prior);
                    } catch (Throwable t) {
                        Log.e(TAG, "update cycle crashed", t);
                        failOrRestore(prior, "cycle-crashed: " + t);
                    }
                }
            }, "EO1-update-cycle");
            // Claim the mutation right for this worker in the same monitor transaction that put the
            // state into CHECKING, so no other thread can be handed the update work while it runs.
            // Starting it under the lock is deliberate: the thread's first act is to contend for
            // this same monitor, and holding it until start() returns is what keeps the claim
            // unambiguous.
            updateWorker = worker;
            worker.start();
        }
    }

    /** What the published manifest says about a build already staged on disk. */
    enum StagedVerdict {
        /** Not this code's stage to touch: an upload, a pre-{@code stagedSource} stage, no stage. */
        SKIP,
        /** What is staged is what is published, or the host cannot be believed about it. */
        KEEP,
        /** The host publishes different bytes — fetch them and replace the stage on verification. */
        REPLACE,
        /** The staged build is already running (or older than it): the stage is pointless. */
        CLEAR
    }

    /**
     * Package-visible for unit tests. Pure: does the manifest still describe what is staged?
     *
     * <p>The asymmetry is the whole point. An unreadable or invalid manifest answers {@code KEEP},
     * never {@code REPLACE}: a host that hiccups must not cost a frame the installable build it
     * already holds. And a stage this app did not download is not this app's to replace, whatever
     * the host says.
     */
    static StagedVerdict judgeStaged(UpdateManifest manifest, UpdateState staged, int installedVersionCode) {
        if (staged == null || staged.state != UpdateState.State.STAGED
                || !UpdateState.SOURCE_MANIFEST.equals(staged.stagedSource)) {
            return StagedVerdict.SKIP;
        }
        if (manifest == null || !manifest.isValid()) {
            return StagedVerdict.KEEP;
        }
        if (manifest.versionCode <= installedVersionCode) {
            return StagedVerdict.CLEAR;
        }
        if (manifest.versionCode == staged.expectedVersionCode
                && staged.manifestSha256 != null
                && manifest.sha256.trim().equalsIgnoreCase(staged.manifestSha256.trim())) {
            return StagedVerdict.KEEP;
        }
        return StagedVerdict.REPLACE;
    }

    /**
     * One check cycle, on the {@code EO1-update-cycle} thread.
     *
     * @param prior the state as it was before this cycle claimed {@code CHECKING} — the stage a
     *              failure must go back to, if there was one.
     */
    private void runCycle(UpdateState prior) {
        // CHECKING: fetch + validate the manifest.
        Configuration config = settingsManager.getConfiguration();
        String manifestUrl = config != null ? config.updateManifestUrl : null;
        if (manifestUrl == null || manifestUrl.trim().isEmpty()) {
            // Nothing to compare against. A stage already on disk stays a stage — the host being
            // unconfigured is not a verdict on it, and deleting it here would be an edit to the
            // config silently becoming an uninstall.
            if (prior.state == UpdateState.State.STAGED) {
                restoreStaged(prior, "manifest-url-not-configured");
            } else {
                setIdle("manifest-url-not-configured");
            }
            return;
        }

        String body;
        try {
            body = fetchManifest(manifestUrl.trim());
        } catch (ManifestHttpException e) {
            failOrRestore(prior, "manifest-http-" + e.statusCode);
            return;
        } catch (Exception e) {
            failOrRestore(prior, "manifest-fetch: " + e + TrustCensus.gateNote(e));
            return;
        }

        UpdateManifest manifest = UpdateManifest.parse(body);
        if (manifest == null) {
            failOrRestore(prior, "manifest-unparseable");
            return;
        }
        String invalid = manifest.validationError();
        if (invalid != null) {
            failOrRestore(prior, "manifest-invalid: " + invalid);
            return;
        }

        // What the host says about a build already staged. A cycle only starts from STAGED when a
        // person forced it — checkDue() gates the automatic one — so either way the answer decides
        // whether anything below this line is allowed to touch the staged pair.
        if (prior.state == UpdateState.State.STAGED) {
            StagedVerdict verdict = judgeStaged(manifest, prior, BuildConfig.VERSION_CODE);
            if (verdict == StagedVerdict.SKIP) {
                restoreStaged(prior, UpdateState.SOURCE_UPLOAD.equals(prior.stagedSource)
                        ? "staged-source-upload" : "staged-source-unknown");
                return;
            }
            if (verdict == StagedVerdict.KEEP) {
                restoreStaged(prior, "staged-still-current");
                return;
            }
            if (verdict == StagedVerdict.REPLACE) {
                notifyDebug("staged " + prior.expectedVersionName + " (" + prior.expectedVersionCode
                        + ") is superseded by " + manifest.versionName + " (" + manifest.versionCode
                        + (manifest.versionCode == prior.expectedVersionCode
                                ? ", same code different bytes" : "") + "); fetching the published one");
            }
            // CLEAR falls through to the gate below, which now also discards the pointless stage.
        }

        if (!manifest.isNewerThan(BuildConfig.VERSION_CODE)) {
            // The host has nothing newer than what is running. Staged bytes under an IDLE state
            // would have /state advertise a build no state owns, so the host's own go with it —
            // but an APK the operator uploaded is never deleted on a manifest's say-so, however
            // pointless the host thinks it has become. Local files only; nothing is ever installed
            // that is not strictly newer than what is running.
            discardStageIfStillOurs();
            notifyUpdateChecked(false);
            setIdle("up-to-date");
            return;
        }
        notifyUpdateChecked(true);

        // What the cycle is going to fetch — deliberately NOT written into the expected* fields,
        // which keep describing the APK that is actually on disk until the swap commits below.
        // Overwriting them here is what made an interrupted check unrecoverable.
        synchronized (this) {
            if (!ownsUpdateWork()) {
                abandonedCycle();
                return;
            }
            state.fetchVersionCode = manifest.versionCode;
            state.fetchVersionName = manifest.versionName == null ? "" : manifest.versionName;
            state.fetchSha256 = manifest.sha256.trim().toLowerCase(Locale.US);
            state.fetchSizeBytes = manifest.sizeBytes;
            // Whichever way these bytes land, a stage made of them is the host's to replace next.
            state.fetchOrigin = UpdateState.SOURCE_MANIFEST;
            stateStore.write(state);
        }

        // Space pre-check (advisory; the installer is the final authority).
        long required = manifest.sizeBytes + STAGING_HEADROOM_BYTES;
        if (freeBytes(updatesDir) < required || freeBytes(stagedPublic.getParentFile()) < required) {
            failOrRestore(prior, "insufficient-space");
            return;
        }

        if (!cycleTransition(UpdateState.State.DOWNLOADING)) {
            abandonedCycle();
            return;
        }

        // DOWNLOADING: stream to the private .part (32 KB chunks, size-capped).
        partFile.delete();
        try {
            long total = download(manifest.apkUrl.trim(), manifest.sizeBytes);
            if (total != manifest.sizeBytes) {
                partFile.delete();
                failOrRestore(prior, "size-mismatch");
                return;
            }
        } catch (Exception e) {
            partFile.delete();
            failOrRestore(prior, "download-failed: " + e + TrustCensus.gateNote(e));
            return;
        }

        if (!cycleTransition(UpdateState.State.VERIFYING)) {
            abandonedCycle();
            return;
        }

        // VERIFYING: SHA-256 → JAR signature (incl. signer-certificate match) → version gate.
        try {
            String sha = sha256Safe(partFile);
            if (!sha.equalsIgnoreCase(state.fetchSha256)) {
                partFile.delete();
                failOrRestore(prior, "sha256-mismatch");
                return;
            }
            X509Certificate installed = installedCert();
            if (installed == null) {
                partFile.delete();
                failOrRestore(prior, "no-installed-cert");
                return;
            }
            ApkSignatureVerifier.Result result = ApkSignatureVerifier.verify(partFile, installed);
            if (!result.valid) {
                partFile.delete();
                failOrRestore(prior, "signature-rejected: " + result.reason);
                return;
            }
            if (!manifest.isNewerThan(BuildConfig.VERSION_CODE)) {
                partFile.delete();
                failOrRestore(prior, "not-newer");
                return;
            }
        } catch (Exception e) {
            partFile.delete();
            failOrRestore(prior, "verify-crashed: " + e);
            return;
        }

        // STAGED. The two files are written in the opposite order to what looks natural — the
        // installer-readable copy first, the private master last — because every rung that can
        // still fail must leave the previous stage intact for failOrRestore's re-hash gate.
        // Renaming over the master first made that rename the point of no return: rename(2)
        // unlinks bytes_N there, and the three rungs after it all deleted the pair, so a build
        // that had already passed all four gates could still leave a REPLACE frame with nothing
        // staged and its install retry dead.
        // Last chance to back out cleanly: everything below this line changes files. The master
        // rename re-checks inside the gate that reset and upload also take, because a check between
        // here and there is exactly the gap that lets a revoked worker damage a new stage.
        if (!ownsUpdateWork()) {
            abandonedCycle();
            return;
        }
        try {
            if (!externalStorageWritable()) {
                // Nothing has been touched yet, so the stage this frame already holds survives.
                failOrRestore(prior, "external-storage-unavailable");
                return;
            }
            if (!copyFile(partFile, stagedPublic) || stagedPublic.length() != manifest.sizeBytes) {
                // The public file may now be a truncated new build, but the master still carries
                // the old one, and runInstall re-makes the public copy from the master before it
                // dispatches — so restoring the old stage here is self-healing, not a lie.
                failOrRestore(prior, "stage-copy-failed");
                return;
            }
            // The last irreversible step, and the metadata that names it, in one critical section:
            // a reset that landed between the two would leave the new bytes on disk under a state
            // that says nothing is staged. The rename consumes the part file, so from here both
            // halves are the verified new build.
            int swapped;
            synchronized (stageGate) {
                swapped = swapMasterLocked();
                if (swapped == 1) {
                    commitFetchedStage();
                } else if (swapped == 0) {
                    // The retry consumed the old master, so nothing installable is left to preserve
                    // either way; the next cycle starts from a clean directory.
                    deleteStagedFiles();
                }
            }
            if (swapped < 0) {
                // Superseded between the copy above and the gate: whoever took over owns the pair
                // now, and the bytes we fetched are still sitting in the part file, harmlessly.
                abandonedCycle();
                return;
            }
            if (swapped == 0) {
                failState("stage-rename-failed", true);
                return;
            }
        } catch (Exception e) {
            failOrRestore(prior, "stage-failed: " + e);
            return;
        }

        // commitFetchedStage() already ran, inside the gate and immediately after the rename that
        // it describes. Calling it again here would promote from the fields it cleared, zeroing the
        // stage's identity out from under the files that are now on disk.
        notifyDebug("staged update " + manifest.versionName + " (" + manifest.versionCode + ")");

        // Proceed straight to the install; if this process dies before firing, the next
        // tick's installStaged(false) retries from STAGED.
        installStaged(false);
    }

    // -------------------------------------------------------------------- plumbing

    private String fetchManifest(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("Cache-Control", "no-store")
                .build();
        Response response = client().newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new ManifestHttpException(response.code());
            }
            InputStream in = response.body().byteStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MANIFEST_BODY_CAP) {
                    throw new java.io.IOException("manifest body exceeds 64 KB");
                }
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            response.close();
        }
    }

    /** Streams {@code url} to {@link #partFile} in 32 KB chunks. @return total bytes written. */
    private long download(String url, long sizeBytes) throws Exception {
        Request request = new Request.Builder().url(url).build();
        Response response = client().newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new java.io.IOException("http " + response.code());
            }
            InputStream in = response.body().byteStream();
            FileOutputStream out = new FileOutputStream(partFile);
            try {
                byte[] buf = new byte[BUFFER];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > sizeBytes || total > UpdateManifest.MAX_APK_BYTES) {
                        throw new java.io.IOException("download exceeds expected size");
                    }
                    // A reset or an upload can arrive minutes into this loop, and until it does the
                    // part file is this worker's alone. Stop writing the moment ownership is gone:
                    // a second cycle started after the reset would otherwise be appending to the
                    // same path, and whichever of the two finishes would hash nonsense.
                    if (!ownsUpdateWork()) {
                        throw new java.io.IOException("update cycle superseded");
                    }
                    out.write(buf, 0, n);
                }
                return total;
            } finally {
                out.close();
            }
        } finally {
            response.close();
        }
    }

    private synchronized OkHttpClient client() {
        if (httpClient == null) {
            // Dedicated client: no shared "Cache-Control: immutable" interceptor (a stale
            // cached manifest would hide new releases) and no cookie jar.
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .followRedirects(true)
                    .retryOnConnectionFailure(true)
                    .connectTimeout(25, TimeUnit.SECONDS)
                    .writeTimeout(25, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.MINUTES);
            httpClient = ApiServiceGenerator.enableTls12OnPreLollipop(builder, context).build();
        }
        return httpClient;
    }

    private X509Certificate installedCert() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_SIGNATURES);
            if (info.signatures != null && info.signatures.length > 0) {
                // v1 signatures store the certificate's DER bytes; parse them rather than
                // casting (the compile-time SDK types don't permit the direct cast).
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                return (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(info.signatures[0].toByteArray()));
            }
        } catch (Exception e) {
            Log.w(TAG, "installedCert: " + e);
        }
        return null;
    }

    private boolean holdsInstallPermission() {
        try {
            return context.checkPermission("android.permission.INSTALL_PACKAGES",
                    Process.myPid(), Process.myUid())
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The same +120 s relaunch alarm {@link CrashReporter} arms when a process dies. Shared so the
     * two restart paths cannot drift apart.
     */
    private void armRelaunchAlarm() {
        CrashReporter.relaunchAfter(context, RELAUNCH_ALARM_MS);
    }

    /**
     * Intent fallback only: the install dialog needs a visible screen, and the only way a
     * human can confirm it is an OTG mouse/keyboard (one-time bootstrap frames).
     */
    private void wakeScreenForInstaller() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock lock = pm.newWakeLock(
                        PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.SCREEN_DIM_WAKE_LOCK,
                        "com.aphex3k.eo1:update-installer");
                lock.acquire(5 * 60 * 1000L); // auto-release; covers the confirmation
            }
        } catch (Exception e) {
            Log.w(TAG, "wakeScreenForInstaller: " + e);
        }
    }

    private boolean externalStorageWritable() {
        try {
            return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        } catch (Exception e) {
            return false;
        }
    }

    private long freeBytes(File where) {
        try {
            File base = where.isDirectory() ? where : where.getParentFile();
            StatFs stat = new StatFs(base.getAbsolutePath());
            return (long) stat.getAvailableBlocks() * stat.getBlockSize();
        } catch (Exception e) {
            return Long.MAX_VALUE; // advisory pre-check only
        }
    }

    private boolean copyFile(File from, File to) {
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeQuietly(FileOutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** @return lowercase hex SHA-256 of the file, or {@code ""} when unreadable. */
    private String sha256Safe(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Deletes the staged files; reports whether every staged file that existed was removed. */
    private boolean deleteStagedFiles() {
        boolean ok = true;
        if (stagedPublic.exists() && !stagedPublic.delete()) {
            ok = false;
        }
        if (stagedMaster.exists() && !stagedMaster.delete()) {
            ok = false;
        }
        return ok;
    }

    /** Recursively deletes a temp directory (best effort). */
    private static void deleteDirQuiet(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                child.delete();
            }
        }
        dir.delete();
    }

    // --------------------------------------------------------------- state helpers

    private void setState(UpdateState.State newState) {
        synchronized (this) {
            state = withState(state, newState, "");
            stateStore.write(state);
        }
    }

    private void setIdle(String lastError) {
        synchronized (this) {
            state = withState(state, UpdateState.State.IDLE, lastError);
            stateStore.write(state);
        }
        notifyDebug("update: " + lastError);
    }

    private void failState(String reason) {
        failState(reason, false);
    }

    /**
     * @param cycleOwned true when the caller is a check cycle, which must still own the update work
     *                   to record a failure: a {@code FAILED} written by an abandoned cycle over an
     *                   upload that landed meanwhile would stop the install retry and hand the next
     *                   check a state that no longer matches the stage on disk.
     */
    private void failState(String reason, boolean cycleOwned) {
        partFile.delete();
        synchronized (this) {
            if (cycleOwned && !ownsUpdateWork()) {
                notifyDebug("update: a reset or upload took over mid-check; this check changed nothing");
                return;
            }
            state = UpdateState.failed(state, reason);
            stateStore.write(state);
        }
        Log.e(TAG, "update FAILED: " + reason);
        notifyDebug("update FAILED: " + reason);
    }

    /** Whether the calling thread is still the one entitled to change update state or files. */
    private boolean ownsUpdateWork() {
        return Thread.currentThread() == updateWorker;
    }

    /** The cycle's own state transition: refuses to advance a state it no longer owns. */
    private boolean cycleTransition(UpdateState.State next) {
        synchronized (this) {
            if (!ownsUpdateWork()) {
                return false;
            }
            state = withState(state, next, "");
            stateStore.write(state);
        }
        return true;
    }

    private void abandonedCycle() {
        notifyDebug("update: a reset or upload took over mid-check; this check changed nothing");
    }

    /**
     * Replaces the private master with the verified part file, or reports that it could not. Takes
     * the same gate as {@code resetUpdate()} and an upload's swap, and re-checks ownership inside
     * it, so a worker that was revoked while its download ran cannot come back and overwrite the
     * stage that replaced it — the master is the one file whose loss nothing else can repair.
     *
     * @return {@code 1} replaced, {@code 0} the rename failed twice, {@code -1} no longer ours
     */
    private int swapMasterLocked() {
        synchronized (this) {
            if (!ownsUpdateWork()) {
                return -1;
            }
        }
        if (partFile.renameTo(stagedMaster)) {
            return 1;
        }
        // Same directory, so an existing target is the only plausible reason; the retry costs
        // the old master, which is why the caller stops trying to preserve it.
        stagedMaster.delete();
        return partFile.renameTo(stagedMaster) ? 1 : 0;
    }

    /** Deletes the staged pair under the same gate that protects the master swap. */
    private boolean discardStagedUnderGate() {
        synchronized (stageGate) {
            return deleteStagedFiles();
        }
    }

    /**
     * Discards the stage only if this worker still owns the update <em>and</em> the stage is the
     * host's to discard — decision and deletion in one critical section, so a takeover landing
     * between them cannot leave this worker deleting bytes it has no claim to. Both matches are
     * read under the monitor, which is the only reason they can be read at all.
     */
    private void discardStageIfStillOurs() {
        synchronized (stageGate) {
            synchronized (this) {
                if (ownsUpdateWork() && mayDiscardStage(state.stagedSource)) {
                    deleteStagedFiles();
                }
            }
        }
    }

    /**
     * Puts back a stage that a cycle started and did not change: the same build, the same
     * metadata, and the same debt to a human — {@link #withState} would otherwise drop
     * {@code needsHumanConfirmation}, and a re-check that found nothing to do must not retire the
     * claim that someone still owes this frame a click.
     *
     * <p>This cycle's check timestamp and attempt count are kept rather than restored, so
     * re-checking a stage that is still current costs one manifest fetch per interval and not one
     * per rotation tick.
     */
    private void restoreStaged(UpdateState prior, String reason) {
        synchronized (this) {
            // Ownership and mutation in one transaction: checked in a lock of its own, another
            // thread could take the stage over in the gap between it and the write below.
            if (!ownsUpdateWork()) {
                // A reset or an upload took this stage over while the check was fetching. Its
                // snapshot of the old identity is now fiction, and writing it would leave the new
                // APK described as the discarded one.
                notifyDebug("update: a reset or upload took over mid-check; this check changed nothing");
                return;
            }
            long checkedMs = state.lastCheckedMs;
            int att = state.attempts;
            state = withState(prior, UpdateState.State.STAGED, reason);
            // Nothing is pending any more — the cycle decided not to replace this stage.
            state.fetchVersionCode = 0;
            state.fetchVersionName = "";
            state.fetchSha256 = "";
            state.fetchSizeBytes = 0L;
            state.fetchOrigin = UpdateState.SOURCE_UNKNOWN;
            state.needsHumanConfirmation = prior.needsHumanConfirmation;
            state.lastCheckedMs = checkedMs;
            state.attempts = att;
            stateStore.write(state);
        }
        notifyDebug("update: " + reason);
        // A tick that starts a re-check spends itself on the check: MainActivity's runOnTimer calls
        // checkIfDue() and then installStaged(false), and while the cycle is in flight that second
        // call sees CHECKING and dispatches nothing. With updateCheckIntervalMinutes at or below the
        // rotation interval, every tick started a check and no tick ever installed — the stage sat
        // there even after the transport recovered. The cycle that leaves a stage in place has to
        // attempt the install the tick was going to.
        installStaged(false);
    }

    /**
     * Records a cycle failure that found a still-installable stage on disk by going back to
     * {@code STAGED} instead of {@code FAILED}. A build the frame could not fetch must not leave
     * it worse off than before it noticed: {@code FAILED} also stops the install retry, which is
     * the only thing keeping an unattended frame current. The failure is still the
     * {@code lastError}, so {@code /state} and {@code eo1-app.log} say why nothing landed.
     */
    private void failOrRestore(UpdateState prior, String reason) {
        boolean ours;
        synchronized (this) {
            ours = ownsUpdateWork();
        }
        if (!ours) {
            // Not even the partial download is ours to delete: another worker may own it now.
            notifyDebug("update: a reset or upload took over mid-check; this check changed nothing");
            return;
        }
        partFile.delete();
        if (prior != null && prior.state == UpdateState.State.STAGED
                && stagedIntactAs(prior.manifestSha256, prior.expectedSizeBytes)) {
            restoreStaged(prior, reason);
            notifyDebug("update cycle failed; keeping staged "
                    + prior.expectedVersionName + " (" + prior.expectedVersionCode + ")");
            return;
        }
        failState(reason, true);
    }

    /** True when the private master is byte-for-byte the build named by {@code sha}/{@code size}. */
    private boolean stagedIntactAs(String sha, long size) {
        return sha != null && !sha.isEmpty()
                && sha.equals(sha256Safe(stagedMaster))
                && stagedMaster.length() == size;
    }

    /**
     * Whether a stage's bytes may be discarded because the update host has stopped wanting them.
     * Only a stage the host produced. An uploaded APK is the operator's to keep or reset, and an
     * unattributable one is left alone for the same reason — the same rule {@link #judgeStaged}
     * applies to replacement, applied to deletion, so no path reaches deletion by accident.
     */
    static boolean mayDiscardStage(String stagedSource) {
        return UpdateState.SOURCE_MANIFEST.equals(stagedSource);
    }

    private void persistInstallPending() {
        synchronized (this) {
            state = withState(state, UpdateState.State.INSTALL_PENDING, "");
            state.installFiredAtMs = System.currentTimeMillis();
            stateStore.write(state);
        }
    }

    /**
     * Promotes the build a cycle fetched into the identity of the stage on disk. Called only after
     * the swap committed, so up to this instant {@code expected*} and {@code stagedSource} still
     * described the APK that was installable — which is what lets a killed check be recovered
     * instead of lost, and keeps an uploaded stage marked as one while a replacement is in flight.
     */
    private void commitFetchedStage() {
        synchronized (this) {
            state.expectedVersionCode = state.fetchVersionCode;
            state.expectedVersionName = state.fetchVersionName;
            state.manifestSha256 = state.fetchSha256;
            state.expectedSizeBytes = state.fetchSizeBytes;
            // Whichever worker's bytes these are, that worker's source is what protects them next.
            state.stagedSource = state.fetchOrigin;
            state.fetchVersionCode = 0;
            state.fetchVersionName = "";
            state.fetchSha256 = "";
            state.fetchSizeBytes = 0L;
            state.fetchOrigin = UpdateState.SOURCE_UNKNOWN;
            state = withState(state, UpdateState.State.STAGED, "");
            stateStore.write(state);
        }
    }

    private void setStaged(String lastError) {
        synchronized (this) {
            state = withState(state, UpdateState.State.STAGED, lastError);
            stateStore.write(state);
        }
    }

    /** Package-visible for unit tests: every field a transition must survive is listed here. */
    static UpdateState withState(UpdateState s, UpdateState.State newState, String lastError) {
        UpdateState c = new UpdateState();
        c.expectedVersionCode = s.expectedVersionCode;
        c.expectedVersionName = s.expectedVersionName;
        c.manifestSha256 = s.manifestSha256;
        c.expectedSizeBytes = s.expectedSizeBytes;
        c.stagedSource = s.stagedSource;
        c.fetchVersionCode = s.fetchVersionCode;
        c.fetchVersionName = s.fetchVersionName;
        c.fetchSha256 = s.fetchSha256;
        c.fetchSizeBytes = s.fetchSizeBytes;
        c.fetchOrigin = s.fetchOrigin;
        c.lastCheckedMs = s.lastCheckedMs;
        c.attempts = s.attempts;
        c.installFiredAtMs = s.installFiredAtMs;
        c.armedTcpAdb = s.armedTcpAdb;
        c.state = newState;
        c.lastError = lastError == null ? "" : lastError;
        // needsHumanConfirmation is deliberately not carried: only the deferral below sets it,
        // so any other transition retires the claim that a person still owes the frame a click.
        return c;
    }

    /** STAGED, retryable, but nothing on this frame can install it without a click. */
    private void setNeedsHuman() {
        synchronized (this) {
            state = withState(state, UpdateState.State.STAGED, REASON_NEEDS_HUMAN);
            state.needsHumanConfirmation = true;
            // Nothing was fired, so nothing is owed a settle window.
            state.installFiredAtMs = 0L;
            stateStore.write(state);
        }
        notifyDebug("install: deferred — no silent transport on this frame;"
                + " use \"Install on the frame\" in the web UI");
    }

    /**
     * The {@code /state} "update" block. Called from the web-server thread; all state
     * access happens under the monitor.
     */
    public JsonObject stateJson() {
        UpdateState s;
        boolean headless;
        String url;
        synchronized (this) {
            s = state;
            headless = holdsInstallPermission();
            Configuration config = settingsManager.getConfiguration();
            url = config != null ? config.updateManifestUrl : null;
        }
        // Probe outside the monitor: the attempt can block up to a second.
        boolean adbdReachable = AdbdInstallClient.probe();
        JsonObject o = new JsonObject();
        o.addProperty("state", s.state.name());
        o.addProperty("installedVersionCode", BuildConfig.VERSION_CODE);
        o.addProperty("expectedVersionCode", s.expectedVersionCode);
        o.addProperty("expectedVersionName", s.expectedVersionName);
        o.addProperty("manifestUrl", url == null ? "" : url);
        o.addProperty("lastCheckedMs", s.lastCheckedMs);
        o.addProperty("lastError", s.lastError);
        o.addProperty("attempts", s.attempts);
        o.addProperty("stagedSha256", s.manifestSha256);
        // What the running cycle is fetching, when one is. During a supersede this names the new
        // build while expectedVersionCode/stagedSha256 still describe the APK on disk, which is the
        // point: those two are the stage, not a promise about it. Reported only while a cycle is
        // actually running — the values stay on disk after a failure so a later launch can match
        // them against the master, but a FAILED or STAGED frame that advertises a download has
        // stopped describing this frame and started describing the last one it failed at.
        boolean fetching = s.state == UpdateState.State.CHECKING
                || s.state == UpdateState.State.DOWNLOADING
                || s.state == UpdateState.State.VERIFYING;
        o.addProperty("fetchingVersionCode", fetching ? s.fetchVersionCode : 0);
        o.addProperty("fetchingSha256", fetching ? s.fetchSha256 : "");
        // "manifest" — the host owns this stage and may replace it; "upload" — an operator put it
        // here and nothing automatic will; "" — staged by a build older than this field, so the
        // app cannot tell and leaves it alone.
        o.addProperty("stagedSource", s.stagedSource);
        JsonObject staged = new JsonObject();
        boolean present = stagedPublic.exists() || stagedMaster.exists();
        staged.addProperty("present", present);
        staged.addProperty("bytes", stagedPublic.length());
        // Bytes in the staging directory that no state owns. A cycle re-checking a stage runs
        // through CHECKING/DOWNLOADING/VERIFYING while the parked APK is still on disk and still
        // installable, so those states own it too — only IDLE (an interrupted swap that
        // reconciled away) and FAILED (a cycle that gave up on these bytes) mean the file
        // belongs to nothing and will not be installed. Deliberately not a re-hash: /state is
        // polled, and hashing a 30 MB APK on the web thread to answer a question the state
        // already answers is the wrong trade.
        staged.addProperty("owned", present && s.state != UpdateState.State.IDLE
                && s.state != UpdateState.State.FAILED);
        o.add("stagedApk", staged);
        o.addProperty("installPermissionHeld", headless);
        // "defer-to-human": the next automatic dispatch puts nothing on screen and waits. Pressing
        // Install in the web UI is what turns that deferral into the confirmation dialog.
        InstallPath next = chooseInstallPath(headless, adbdReachable, false);
        o.addProperty("installMode", next == InstallPath.DEFER ? "defer-to-human" : next.name().toLowerCase(Locale.US));
        o.addProperty("lastInstallAttempt", lastInstallAttempt);
        o.addProperty("adbdReachable", adbdReachable);
        // Whether the frame is permitted to start adbd itself (both options-dialog flags), and
        // whether one is open right now because it did.
        o.addProperty("loopbackAdbAllowed", loopbackAdbAllowed());
        o.addProperty("tcpAdbArmedByApp", s.armedTcpAdb);
        o.addProperty("needsHumanConfirmation", s.needsHumanConfirmation);
        o.addProperty("installFiredAtMs", s.installFiredAtMs);
        return o;
    }

    // ------------------------------------------------------------------- listeners

    /**
     * InvocationHandler for the reflective {@code PackageManager$PackageInstallObserver}
     * proxy. API 19 signatures: {@code packageInstalled(int userId)} and
     * {@code packageInstallFailed(String errorMsg, int userId)} — dispatched by method name
     * so the interface itself is never referenced at compile time.
     */
    private final InvocationHandler installCallbackHandler = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            // A stray exception must never escape to the global UncaughtExceptionHandler
            // (which calls System.exit(2)).
            try {
                String name = method.getName();
                if ("packageInstalled".equals(name)) {
                    onInstalled(observerIntArg(args, 0));
                    return null;
                }
                if ("packageInstallFailed".equals(name)) {
                    String errorMsg = observerStringArg(args, 0);
                    onInstallFailed(errorMsg, observerIntArg(args, 1));
                    return null;
                }
                if ("toString".equals(name)) {
                    return "UpdateInstallObserver";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == (args == null ? null : args[0]);
                }
                return null;
            } catch (Throwable t) {
                Log.e(TAG, "install observer callback failed", t);
                return null;
            }
        }
    };

    private static int observerIntArg(Object[] args, int index) {
        return args != null && args.length > index && args[index] instanceof Number
                ? ((Number) args[index]).intValue()
                : -1;
    }

    private static String observerStringArg(Object[] args, int index) {
        return args != null && args.length > index && args[index] instanceof String
                ? (String) args[index]
                : "";
    }

    private void onInstalled(int userId) {
        // Log only: a real install-over kills this process; success is confirmed by the
        // new process's reconcileOnStartup(). Rolling back to STAGED is harmless either
        // way (the success path on startup handles both STAGED and INSTALL_PENDING).
        Log.i(TAG, "install observer: packageInstalled (user " + userId + ")");
        synchronized (UpdateManager.this) {
            installFiredInThisProcess = false;
            if (state.state == UpdateState.State.INSTALL_PENDING) {
                state = withState(state, UpdateState.State.STAGED, "");
                stateStore.write(state);
            }
        }
    }

    private void onInstallFailed(String errorMsg, int userId) {
        Log.w(TAG, "install observer: packageInstallFailed (user " + userId + "): " + errorMsg);
        synchronized (UpdateManager.this) {
            installFiredInThisProcess = false;
            if (state.state == UpdateState.State.INSTALL_PENDING) {
                state = withState(state, UpdateState.State.STAGED,
                        "install-failed: " + errorMsg);
                stateStore.write(state);
            }
        }
    }

    private void notifyDebug(String value) {
        UpdateManagerListener listener = this.listener.get();
        if (listener != null) {
            try {
                listener.debugInformationProvided(new DebugInformation("Update", value));
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyUpdateChecked(Boolean available) {
        UpdateManagerListener listener = this.listener.get();
        if (listener != null) {
            try {
                listener.updateChecked(available);
            } catch (Exception ignored) {
            }
        }
    }

    private static final class ManifestHttpException extends Exception {
        final int statusCode;

        ManifestHttpException(int statusCode) {
            this.statusCode = statusCode;
        }
    }
}
