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
import com.aphex3k.update.SerialWorker;
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
 * <p><b>How to add work to this class.</b> Everything below runs on one thread, in the order it was
 * asked — with one exception, and it is the constructor: {@code sweepStagingTemps()} and
 * {@code reconcileOnStartup()} execute on whichever thread first needed an update manager, before the
 * worker exists. That is sound only because nothing can be queued yet, which is also why the sweep must
 * never be called from anywhere else in the mode that reaps upload slots. An entry point posts an item,
 * and the item's body is where the work happens. That is the whole concurrency design, and it is load-bearing for the rules above — a stage is never displaced by
 * a second writer because there is never a second writer. So a new entry point must not touch
 * {@code state}, the staged pair, or an {@link java.io.File} under either staging directory on the
 * caller's thread; {@code MainActivity} calls these methods from the UI thread, from a rotation tick,
 * from a web worker and from a framework binder thread, and none of those is allowed to decide
 * anything. If the work must answer its caller (only {@link #stageUploadedApk} does, because HTTP is
 * waiting), move whatever it needs to a name the queue owns <em>before</em> blocking, and bound the
 * wait. What serialisation does not give you, and what therefore still needs a real guard: another
 * process holding {@code stagedPublic} open while it installs (the {@code INSTALL_PENDING} tests), and
 * a process killed at an instruction no thread model constrains (the dual identity, and the rename
 * order in {@link #publishStagedPair}).
 *
 * <p>Recovery across process death: the installer kills this process on success, so success
 * is detected only by the new process's {@link #reconcileOnStartup()} — which runs once, while this
 * manager is being built, before any item can be queued. A +120 s relaunch alarm
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

    /**
     * The Activity this manager reports through. Weak, and replaced by {@link #attach}: the manager
     * outlives it, which is the point of this class being process-wide. Who is attached is a question
     * about a log sink and an on-screen toast, never about who may change state — there is exactly one
     * thread that does that, and it is nobody's to take away.
     */
    private volatile WeakReference<UpdateManagerListener> listener;
    /** What {@link #detach(UpdateManagerListener)} installs: the field is never itself null. */
    private static final WeakReference<UpdateManagerListener> NO_LISTENER =
            new WeakReference<>(null);
    private final Context context;
    /**
     * Read at the top of each task rather than captured for the life of the manager, because
     * {@code MainActivity.onCreate()} rebuilds it and a recreation is exactly the event a queued check
     * can survive: a cycle must not decide "are updates enabled?" from a configuration document the
     * outgoing Activity no longer owns.
     */
    private volatile SettingsManager settingsManager;
    private final UpdateStateStore stateStore;
    private final File updatesDir;
    private final File publicDir;
    private final File stagedMaster;
    private final File stagedPublic;
    /**
     * The two intermediate files a cycle or an upload streams through. Fixed names, because one thread
     * opens them. The two <em>shared</em> names above are only ever replaced by {@code rename(2)} out
     * of these — which is the property per-worker temps used to buy at the price of a sequence counter
     * plus an ownership check to decide whose counter it was.
     */
    private final File part;
    private final File publicPart;
    /**
     * One thread runs every mutation in this class, in the order it was asked. That is the entire
     * concurrency design, and it replaces an {@code updateWorker} thread token, a static
     * {@code processOwner}, a {@code retired} flag, a second {@code stageGate} monitor carrying a
     * documented lock order, and roughly eighty sites that had to ask one of them first.
     */
    private final SerialWorker worker;
    /** The one instance. An Activity recreation replaces its Activity, not its update worker. */
    private static volatile UpdateManager instance;

    /**
     * The state, as of its last write. Read by {@code /state} on a web thread, so every access takes
     * this object's monitor — and nothing slow happens inside it, which is why no task holds it across
     * a fetch, a hash or a copy.
     */
    private UpdateState state;
    private boolean installFiredInThisProcess;
    private volatile String lastInstallAttempt = "none";
    private OkHttpClient httpClient;

    /** Coalescing keys: two waiting items with the same key are one item, not a queue of repeats. */
    private static final long KEY_CHECK = 1L;
    private static final long KEY_INSTALL = 2L;
    private static final long KEY_RESET = 3L;
    /** A human's request may upgrade a queued automatic one; it must never be downgraded by one. */
    private static final int PRIORITY_HUMAN = 1;
    /**
     * How long {@code POST /update} waits for its own staging task. A latency bound on the HTTP answer
     * and nothing else: the uploaded file is renamed into a queue-owned name before the wait starts,
     * so timing out costs the browser a response, never the operator's APK.
     *
     * <p>Set by what staging costs here, not by patience. An 18 MB APK measured 22 s end to end on an
     * EO2 — SHA-256, the pure-Java signature pass, the copy and two renames — and that was with a
     * whole check cycle queued ahead of it. Beyond about twice that, a timeout answer is better than
     * the alternative, because {@code WebServer} runs on three threads and this one blocks: three
     * uploads parked on a wedged queue would take {@code /state} and {@code update-reset} down with
     * them, which are the two things you need when the queue is wedged. A slow answer is a bad
     * experience; an unreachable frame is an outage.
     */
    private static final long UPLOAD_HANDBACK_MS = 45_000L;

    /**
     * Returns the process's one update manager, building it on first call. Every later call is an
     * Activity recreation, which only re-points the listener and the configuration.
     */
    public static UpdateManager acquire(UpdateManagerListener listener, Context context,
            SettingsManager settingsManager) {
        UpdateManager m = instance;
        if (m != null) {
            m.attach(listener, settingsManager);
            return m;
        }
        synchronized (UpdateManager.class) {
            if (instance == null) {
                instance = new UpdateManager(listener, context, settingsManager);
            }
            return instance;
        }
    }

    /**
     * Points the singleton at a live Activity. Unconditional, from {@code onCreate} — including under
     * the relaunch ordering where a replacement is created before the outgoing Activity is destroyed,
     * which is why this overwrites rather than filling a gap.
     */
    public void attach(UpdateManagerListener listener, SettingsManager settingsManager) {
        this.listener = new WeakReference<>(listener);
        this.settingsManager = settingsManager;
    }

    /**
     * Called from {@code onDestroy}. Reference-compared so an outgoing Activity cannot un-attach the
     * one that replaced it; the worker keeps running either way. This is the only identity test left in
     * this file, and the worst it can do is misdirect one log line.
     */
    public static void detach(UpdateManagerListener gone) {
        UpdateManager m = instance;
        if (m == null) {
            return;
        }
        WeakReference<UpdateManagerListener> ref = m.listener;
        if (ref != null && ref.get() == gone) {
            // A reference holding nothing, never the absence of one. Both readers dereference the
            // field before testing its referent, which was sound while the field was final and
            // would otherwise stop being sound right here — and the place it would surface is the
            // line after a successful stage, where a throw unwinds into the cycle's own catch and
            // records FAILED over bytes that just passed all four gates.
            m.listener = NO_LISTENER;
        }
    }

    private UpdateManager(UpdateManagerListener listener, Context context, SettingsManager settingsManager) {
        this.listener = new WeakReference<>(listener);
        this.context = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        this.settingsManager = settingsManager;
        this.updatesDir = new File(this.context.getFilesDir(), "updates");
        if (!updatesDir.isDirectory() && !updatesDir.mkdirs()) {
            Log.w(TAG, "could not create " + updatesDir.getAbsolutePath());
        }
        this.stagedMaster = new File(updatesDir, "eo1-update.apk");
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        this.publicDir = downloads != null ? downloads
                : new File(Environment.getExternalStorageDirectory(), "Download");
        this.stagedPublic = new File(publicDir, "eo1-update.apk");
        this.part = new File(updatesDir, "eo1-update.apk.part");
        this.publicPart = new File(publicDir, "eo1-update.apk.part");
        this.stateStore = new UpdateStateStore(UpdateStateStore.defaultStateFile(this.context));
        this.state = this.stateStore.read();
        this.worker = new SerialWorker(new SerialWorker.Runner() {
            @Override
            public void start(Runnable loop) {
                Thread thread = new Thread(loop, "EO1-update-worker");
                // A daemon: a frame whose update thread wedged must still let the process go away.
                thread.setDaemon(true);
                thread.start();
            }
        }, new SerialWorker.FailureSink() {
            @Override
            public void onTaskThrew(String description, Throwable error) {
                settleStuckTask(description, error);
            }
        });
        // Both before anything can be queued, so the worker's first task sees a settled world. True
        // here is safe only because nothing has been queued yet — see sweepStagingTemps().
        sweepStagingTemps(true);
        reconcileOnStartup();
    }

    /**
     * Removes every intermediate file, named or legacy. Used by a reset and by construction, where
     * sweeping is right and deleting one known path would be arbitrary: abandoned temps from a killed
     * process are otherwise permanent on a device with about a gigabyte free.
     *
     * <p>Unconditional, and run before the worker starts — which is all the ordering rule the
     * once-per-process flag used to need ever was: that flag existed only because a second manager
     * could sweep while the first manager's cycle was still writing. There is one manager now, and one
     * thread that writes, so "at construction" is enough. It still has to cover the case the
     * interrupted-state branch in {@link #reconcileOnStartup()} cannot see: a process killed just after
     * {@code publishStagedPair()} committed its state, or inside {@code repairPublicCopy()}, leaves a
     * temp behind under a state reading {@code STAGED}, and that branch never runs — so a temp worth
     * up to 100 MiB could survive every subsequent launch.
     *
     * <p>{@code eo1-upload-*} is the name {@code POST /update} renames an operator's APK into, and it
     * is reaped only by the constructor. That distinction is the whole difference between a leftover
     * and a queued request: a web thread that has renamed and is now blocked waiting for the worker
     * owns a file at that name, and a reset queued ahead of it must not sweep the upload out from
     * under the item that is about to verify it. An upload's own task deletes its slot when it runs;
     * only a slot no task will ever reach — one left by a killed process — needs the start-up sweep,
     * and at that moment nothing is queued, which is exactly why "once, at construction" can be the
     * rule for it.
     */
    private void sweepStagingTemps(boolean reapsQueuedUploadSlots) {
        for (File dir : new File[]{updatesDir, publicDir}) {
            File[] entries = dir.listFiles();
            if (entries == null) {
                continue;
            }
            for (File entry : entries) {
                String name = entry.getName();
                if ((name.startsWith("eo1-update")
                        || (reapsQueuedUploadSlots && name.startsWith("eo1-upload")))
                        && !entry.equals(stagedMaster) && !entry.equals(stagedPublic)) {
                    if (entry.isDirectory()) {
                        deleteDirQuiet(entry);
                    } else {
                        entry.delete();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ triggering

    /**
     * Forced update cycle (TOP+BACK key combo, {@code /control?action=check-updates}). Queued ahead of
     * nothing: the call's only job is to put a check on the queue that a tick cannot downgrade.
     *
     * @param parent unused; kept because every caller passes the Activity it was pressed in.
     */
    public void checkForUpdates(Activity parent) {
        worker.post(false, KEY_CHECK, PRIORITY_HUMAN, "check (forced)", new SerialWorker.Task() {
            @Override
            public void run() {
                runCheck(true);
            }
        });
    }

    /**
     * Interval-gated cycle called from {@code runOnTimer()}. Posts the work rather than deciding it:
     * the decision needs the configuration document, and reading a file is not something a rotation
     * tick should do on the thread that paints.
     *
     * <p>Debug builds never check on their own — see {@link #runCheck}.
     */
    public void checkIfDue() {
        if (BuildConfig.DEBUG) {
            return;
        }
        worker.post(false, KEY_CHECK, 0, "check", new SerialWorker.Task() {
            @Override
            public void run() {
                runCheck(false);
            }
        });
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
     * Runs once, from the constructor, before anything can be queued: clears stale mid-cycle states,
     * confirms a completed install (new versionCode == expected), and demotes a stale
     * {@code INSTALL_PENDING} back to the retryable {@code STAGED}.
     *
     * <p>It used to be called from {@code onCreate} on every Activity recreation, and that was quietly
     * load-bearing: it was the thing that demoted a {@code CHECKING} stranded by a thread that died
     * mid-cycle. There is no recreation left to hook, so the worker's failure sink settles a state a
     * task abandoned instead — see {@link #settleStuckTask}. Both are named here because each is the
     * obvious thing to re-add to the other by accident.
     */
    private void reconcileOnStartup() {
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

            // A process that died mid-cycle used to have its partial download removed here, by a
            // branch keyed on the interrupted states. That is now sweepStagingTemps()'s job, run by
            // the constructor before the worker exists — and it covers the case this branch missed: a
            // public temp orphaned under a state that reads STAGED, because the crash landed after
            // publishStagedPair() committed the state but before the temp's rename.
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
            // Hash only when recovery could still apply. This runs synchronously from
            // MainActivity.onCreate(), and an up-to-100 MiB SHA-256 on the UI thread of this hardware
            // is seconds of launch time — while recoverInterruptedStage() returns its argument
            // untouched for any state but IDLE, which is the case for a parked STAGED or
            // INSTALL_PENDING update, i.e. the ordinary launch.
            UpdateState recovered = reconciled.state != UpdateState.State.IDLE ? reconciled
                    : recoverInterruptedStage(reconciled,
                    stagedIntactAs(reconciled.manifestSha256, reconciled.expectedSizeBytes),
                    stagedIntactAs(reconciled.fetchSha256, reconciled.fetchSizeBytes));
            if (recovered != reconciled) {
                reconciled = recovered;
                if (recoveredAlreadyRunning(reconciled, BuildConfig.VERSION_CODE)) {
                    // Recovered a stage that turns out to be the build already running — staged
                    // before an operator sideloaded the same version underneath it. reconcileState's
                    // own confirmation check had already run, against the interrupted state rather
                    // than this restored one, and runInstall() has no newer-than-installed gate of
                    // its own, so restoring it as installable would have the next tick reinstall the
                    // version it is running. Same treatment the branch above gives a confirmed
                    // update: drop the files, reset the identity, keep the clock.
                    long checkedMs = reconciled.lastCheckedMs;
                    reconciled = UpdateState.idle();
                    reconciled.lastCheckedMs = checkedMs;
                    deleteStagedFiles();
                    notifyDebug("self-update " + BuildConfig.VERSION_CODE
                            + " recovered already-installed; stage discarded");
                }
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
            writeState(state);
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
    /**
    /**
     * Package-visible for unit tests. Whether a stage recovered at startup could never install
     * anyway, and must therefore not be handed back as installable.
     *
     * <p>{@code <=} rather than {@code ==}: {@code runInstall()} has no newer-than-installed gate of
     * its own — that gate lives in {@code runCycle} and in {@code stageUploadedApk} — so a recovered
     * stage at <em>or below</em> the running version is a tick away from an attempt to downgrade. The
     * case is not exotic: an interrupted check whose staged build was 7, with the operator having
     * sideloaded 9 since, restores 7 as though it were an upgrade. An uploaded or legacy stage left
     * in that position is worse than useless, because it also blocks automatic update checks without
     * ever being installable. {@code expectedVersionCode == 0} is the blank state
     * {@link #reconcileState()} leaves on a confirmed install and must not be compared at all.
     */
    static boolean recoveredAlreadyRunning(UpdateState recovered, int installedVersionCode) {
        return recovered != null
                && recovered.state == UpdateState.State.STAGED
                && recovered.expectedVersionCode > 0
                && recovered.expectedVersionCode <= installedVersionCode;
    }

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
     * version, it did not take effect — roll back to the retryable {@code STAGED}.
     *
     * <p>Queued rather than performed on the UI thread, which is the whole point of this method: it
     * writes persisted state, and until now it was the one writer with nothing above it but a call from
     * a lifecycle callback. Nothing has to prove the calling Activity is still the live one, because
     * the write happens wherever writes happen.
     */
    public void reconcileInstallOutcome() {
        worker.post("install outcome", new SerialWorker.Task() {
            @Override
            public void run() {
                rollBackUnsettledInstall();
            }
        });
    }

    /** Worker thread. See {@link #reconcileInstallOutcome()} for why an install needs a roll-back. */
    private void rollBackUnsettledInstall() {
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
            if (!settleWindowExpired(System.currentTimeMillis(), state.installFiredAtMs)) {
                // Coming back to the foreground is not evidence that an install failed, only that a
                // screen came back — and pm install can run for minutes. Rolling back here would clear
                // the settle window while the installer still holds the staged file, which is the one
                // thing that lets a retry replace an APK somebody is reading. The next tick decides.
                return;
            }
            installFiredInThisProcess = false;
            // Naming the reason matters here: canceled-or-refused means a person dismissed the dialog
            // we were shown, and anything else means a silent transport that promised more than it
            // delivered.
            boolean dialog = "intent".equals(lastInstallAttempt);
            state = withState(state, UpdateState.State.STAGED,
                    dialog ? "install-canceled-or-refused" : "install-did-not-complete");
            state.installFiredAtMs = 0L;
            writeState(state);
            rolledBack = true;
        }
        if (rolledBack) {
            notifyDebug("install did not complete in-process; back to STAGED (retried on the next tick)");
        }
    }

    /**
     * Whether an {@code INSTALL_PENDING} has earned the right to be called unfinished.
     *
     * <p>One predicate because the two callers of that question used to hold different answers: the
     * rotation tick waited the settle window out and the Activity's resume rolled back on sight, so a
     * resume landing two seconds into a {@code pm install} that can take minutes cleared the window
     * that exists to keep a retry from replacing a file the installer still holds. A pending state
     * with no timestamp is older than the field, so it counts as expired — the alternative is a frame
     * that waits forever for an install that already happened.
     *
     * <p>Pure and package-visible for the same reason: "did this install settle?" must be answerable
     * without an installer, an Activity, or a thread.
     */
    static boolean settleWindowExpired(long nowMs, long firedAtMs) {
        return firedAtMs <= 0L || nowMs - firedAtMs > INSTALL_SETTLE_MS;
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
        worker.post(false, KEY_INSTALL, humanRequested ? PRIORITY_HUMAN : 0,
                humanRequested ? "install (requested)" : "install", new SerialWorker.Task() {
                    @Override
                    public void run() {
                        runInstallAttempt(humanRequested);
                    }
                });
    }

    /**
     * An install item's body: the settle-window roll-back, then the ladder.
     *
     * <p>Two things used to stop this from running twice at once — an {@code installInFlight} flag and
     * a thread that owned the update work while it read the staged pair. The queue is what stops it
     * now, and the settle window is still a wall: it is expressed as {@code INSTALL_PENDING} plus a
     * clock, which has the merit of surviving the process that set it.
     */
    private void runInstallAttempt(boolean humanRequested) {
        String event = null; // a state change worth logging on an unattended tick too
        String note = null;  // a reply to a press; on a tick it would be noise every interval
        boolean staged = false;
        synchronized (this) {
            if (state.state == UpdateState.State.INSTALL_PENDING) {
                long firedAt = state.installFiredAtMs;
                long elapsedMs = System.currentTimeMillis() - firedAt;
                if (!settleWindowExpired(System.currentTimeMillis(), firedAt)) {
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
                    writeState(state);
                    event = "install fired earlier did not settle; back to STAGED";
                }
            }
            if (state.state == UpdateState.State.STAGED) {
                staged = true;
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
        if (!staged) {
            return;
        }
        try {
            runInstall(humanRequested);
        } catch (Throwable t) {
            Log.e(TAG, "install crashed", t);
            failState("install-crashed: " + t);
        }
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
                // pair goes. This is the only case that may delete.
                deleteStagedFiles();
                failState("staged-file-changed");
                return;
            }
            if (!repairPublicCopy(snapshot.expectedSizeBytes)) {
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
            case HEADLESS: {
                long attempt = armAndPersist("headless installPackage", snapshot.expectedVersionCode);
                if (attempt == 0L) {
                    return;
                }
                lastInstallAttempt = "headless";
                try {
                    // The stamp from the state just written, never snapshot.installFiredAtMs: that is
                    // the previous attempt's timestamp, normally zero, and zero matched everything.
                    fireHeadlessInstall(Uri.fromFile(stagedPublic), attempt);
                } catch (Throwable t) {
                    Log.e(TAG, "installPackage threw", t);
                    setStaged("installPackage-threw: " + t);
                }
                break;
            }

            case ADBD: {
                if (armedByUs) {
                    // The debt is recorded the moment the door opens, not on the way past the next
                    // three calls. Everything between here and the install can throw — the relaunch
                    // alarm reaches into AlarmManager — and a TCP adb listening as root with nothing
                    // in the state file naming it is the one exposure no successor will ever close,
                    // because closing it is driven by exactly that flag.
                    persistAdbArmed(true);
                }
                if (armAndPersist("adbd pm install", snapshot.expectedVersionCode) == 0L) {
                    // Nothing was dispatched — but an adbd this attempt started is still this attempt's
                    // to close, and the exposure is meant to be one action, never a boot. That is why
                    // the cancellation is handled here rather than inside armAndPersist: the teardown
                    // belongs to whoever opened the door, and it lives in a finally below.
                    closeAdbIfWeOpenedIt(armedByUs);
                    return;
                }
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
            if (!writeState(state)) {
                return;
            }
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

    /**
     * Readies the process for a silent install and records it as pending: the relaunch alarm first, so
     * that a frame dying anywhere in the rest of this call still gets itself back — the alarm is the
     * same one {@code uncaughtException} uses, and arming it after the write would leave a window in
     * which a crash is final. Nothing dispatches unless the write landed: {@code INSTALL_PENDING} is
     * what makes a later process able to tell a stalled install from a successful one, and an install
     * nobody can attribute is worse than one that never happened.
     *
     * @return the attempt stamp the installer must be bound to, or {@code 0} when the pending state
     *         could not be written and nothing may be dispatched
     */
    private long armAndPersist(String how, int expectedVersionCode) {
        armRelaunchAlarm();
        long attempt = persistInstallPending();
        if (attempt == 0L) {
            return 0L;
        }
        installFiredInThisProcess = true;
        notifyDebug("install: " + how + " (version " + expectedVersionCode + ")");
        return attempt;
    }

    private void fireInstallIntent(UpdateState snapshot) {
        // A person asked for this install, so the dialog is wanted: it is how a bootstrap frame
        // finishes an update, via an OTG keyboard/mouse at the frame. No relaunch alarm here — it
        // used to fire MainActivity back over the pending confirmation 120 s into it.
        // The stamp is not captured here because nothing can return it: the dialog path has no
        // observer, and its outcome is decided by the settle window plus the next resume.
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
    private void fireHeadlessInstall(Uri uri, long attempt) throws Exception {
        Class<?> observerClass =
                Class.forName("android.content.pm.PackageManager$PackageInstallObserver");
        Object observer = Proxy.newProxyInstance(
                context.getClassLoader(),
                new Class<?>[]{observerClass},
                new InstallObserver(attempt));
        Method installPackage = PackageManager.class.getMethod(
                "installPackage", Uri.class, observerClass, String.class);
        installPackage.invoke(context.getPackageManager(), uri, observer, null);
    }

    /**
     * Deletes the staged APKs and returns to {@code IDLE} (keeping the last-checked time).
     *
     * <p>Urgent, so an operator's reset is served ahead of any check or install waiting its turn. The
     * task already running finishes first — nothing here is taken away from it — and that is the price
     * of one worker: a reset costs the tail of a download rather than corrupting one. What it does not
     * cost is correctness, because a finished task's publish necessarily precedes this item, so the
     * stage it wrote is the stage this deletes. The old code needed a gate, a monitor, an ownership
     * check and a revocation to make that same argument.
     */
    public void resetUpdate() {
        worker.post(true, KEY_RESET, 0, "reset", new SerialWorker.Task() {
            @Override
            public void run() {
                discardEverything();
            }
        });
    }

    /** Worker thread. The files go first: they are what the state is about to stop claiming. */
    private void discardEverything() {
        deleteStagedFiles();
        // false: an upload queued behind this reset owns a live file under eo1-upload-, and a reset
        // is a request to discard what the frame has, not what somebody is still handing over.
        sweepStagingTemps(false);
        synchronized (this) {
            installFiredInThisProcess = false;
            state = withState(state, UpdateState.State.IDLE, "");
            state.attempts = 0;
            // withState carries stagedSource, and an IDLE state with no stage on disk must not keep
            // naming the source of one — nor a build some earlier cycle was still fetching, which
            // recovery would otherwise try to match against files that are now gone.
            state.stagedSource = UpdateState.SOURCE_UNKNOWN;
            clearFetch(state);
            writeState(state);
        }
        notifyDebug("update reset");
    }

    /**
     * Voids the identity of a build that is not pending anywhere. Five transitions need the whole
     * list: a {@code fetch*} field left behind has {@code /state} advertising a download that is not
     * running, and after a crash it lets recovery match unrelated bytes against a hash that never
     * described them.
     */
    static void clearFetch(UpdateState s) {
        s.fetchVersionCode = 0;
        s.fetchVersionName = "";
        s.fetchSha256 = "";
        s.fetchSizeBytes = 0L;
        s.fetchOrigin = UpdateState.SOURCE_UNKNOWN;
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
     * <p>Called from a web worker thread, and the one entry point that cannot simply be queued because
     * it owes its caller an answer. So the work is split: <em>this</em> thread moves the file into a
     * name the queue owns, and then blocks until the staging item has run. The move is not an
     * optimisation — {@code WebServer} unlinks the multipart temp directory the instant this method
     * returns, so bytes left in there would be deleted under a task still hashing them, and a worker
     * busy finishing a download can take minutes to reach this item.
     *
     * <p>While staging, the state is held in {@code VERIFYING} (the same in-flight marker a check cycle
     * uses) so a process killed mid-swap comes back to a state that still names the operator's bytes. A
     * rejected upload restores the previous state — when it replaced a staged APK, that is left
     * untouched; otherwise the state goes to {@code FAILED}.
     *
     * @param apk the uploaded file, inside the web server's temp directory under {@code filesDir}
     * @return the result (never null) with the computed SHA-256 for the user to verify against the
     *         published release. A {@code staging-queued} reason is <em>not</em> a rejection: the work
     *         is still running and {@code /state} will show what it decided.
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
        // Named after the request's own multipart directory, which the web server already made unique
        // with System.nanoTime(). The source's *parent* rather than a fresh nanoTime, because
        // rename(2) silently replaces an existing file: a collision would not fail, it would quietly
        // stage one operator's bytes under another's answer. If even that name is taken, refuse.
        String origin = apk.getParentFile() == null ? "" : apk.getParentFile().getName();
        final File slot = new File(updatesDir,
                "eo1-upload-" + (origin.isEmpty() ? Long.toString(System.nanoTime()) : origin)
                        + ".apk");
        if (slot.isFile()) {
            apk.delete();
            return ApkUploadResult.reject("", size, false, "upload-slot-taken");
        }
        if (!apk.renameTo(slot)) {
            // Same filesystem by construction — the multipart directory sits inside filesDir/updates —
            // so a failure here means a full or unhappy volume, not a race.
            apk.delete();
            return ApkUploadResult.reject("", size, false, "stage-rename-failed");
        }
        final ApkUploadResult[] answer = new ApkUploadResult[1];
        SerialWorker.Item item = worker.post(true, SerialWorker.NO_COALESCE, PRIORITY_HUMAN,
                "upload", new SerialWorker.Task() {
                    @Override
                    public void run() {
                        answer[0] = stageUploadedApkOnWorker(slot, size);
                    }
                });
        if (worker.await(item, UPLOAD_HANDBACK_MS)) {
            return answer[0] != null
                    ? answer[0]
                    : ApkUploadResult.reject("", size, false, "stage-crashed");
        }
        // Out of patience, not out of work: the item stays queued and will stage the file when the
        // worker reaches it, so this thread deletes nothing it still owns. A browser that timed out
        // must not be able to cancel an upload — that was the earlier behaviour here, and it turned a
        // slow response into a discarded APK.
        return ApkUploadResult.reject("", size, false, "staging-queued");
    }

    /** Worker thread. See {@link #stageUploadedApk(File)} for the caller's half. */
    private ApkUploadResult stageUploadedApkOnWorker(File apk, long size) {
        boolean replacingStaged = false;
        String previousLastError = "";
        try {
            synchronized (this) {
                if (state.state == UpdateState.State.INSTALL_PENDING) {
                    // The wall here is not another of our threads — it is the platform installer's open
                    // file descriptor on the public copy, which no in-process ordering can close. Say
                    // so without touching the state: an install waiting to settle is not a failure, and
                    // FAILED would stop the retry that is finishing right now.
                    return ApkUploadResult.reject("", size, false, "install-in-flight");
                }
                replacingStaged = state.state == UpdateState.State.STAGED;
                previousLastError = state.lastError;
                state = withState(state, UpdateState.State.VERIFYING, "");
                writeState(state);
            }
            String sha = sha256Safe(apk);
            if (sha.isEmpty()) {
                return failUpload(replacingStaged, previousLastError, sha, size, false, "sha256-failed");
            }
            X509Certificate installed = installedCert();
            if (installed == null) {
                return failUpload(replacingStaged, previousLastError, sha, size, false, "no-installed-cert");
            }
            ApkSignatureVerifier.Result signature = ApkSignatureVerifier.verify(apk, installed);
            if (!signature.valid) {
                return failUpload(replacingStaged, previousLastError, sha, size, false,
                        "signature-rejected: " + signature.reason);
            }
            ApkManifestInfo manifest = ApkManifestInfo.read(apk);
            if (manifest == null || manifest.versionCode == null) {
                return failUpload(replacingStaged, previousLastError, sha, size, true, "version-code-unreadable");
            }
            if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                return failUpload(replacingStaged, previousLastError, sha, size, true,
                        "not-newer: " + manifest.versionCode + " is not newer than installed " + BuildConfig.VERSION_CODE);
            }
            long required = size + STAGING_HEADROOM_BYTES;
            if (freeBytes(updatesDir) < required || freeBytes(stagedPublic.getParentFile()) < required) {
                return failUpload(replacingStaged, previousLastError, sha, size, true, "insufficient-space");
            }
            // Claim the bytes' identity BEFORE they replace anything on disk. A process killed between
            // the rename below and the metadata write at the end would otherwise come back describing
            // the stage it overwrote — and if that one was host-sourced, the next check would be free
            // to delete the operator's APK. fetchOrigin is what makes a recovered stage an upload
            // rather than a licence to discard it.
            boolean claimed;
            synchronized (this) {
                state.fetchVersionCode = manifest.versionCode;
                state.fetchVersionName = manifest.versionName == null ? "" : manifest.versionName;
                state.fetchSha256 = sha;
                state.fetchSizeBytes = size;
                state.fetchOrigin = UpdateState.SOURCE_UPLOAD;
                // Stamped here rather than at the commit, because the commit is a rename: the clock
                // read belongs with the decision, not with the inode swap.
                state.lastCheckedMs = System.currentTimeMillis();
                claimed = writeState(state);
            }
            if (!claimed) {
                // Nothing has been displaced yet, so the previous stage is fully intact and this is a
                // plain rejection. Swapping anyway would put the operator's bytes on disk under a state
                // that still describes some other build — and after a restart, under a source that
                // licenses a check to delete them.
                return failUpload(replacingStaged, previousLastError, sha, size, true, "state-write-failed");
            }
            // Publish exactly the way a check cycle does: copy into a name the installer can read, then
            // one section that renames the master, commits the identity, and renames the public copy.
            // Neither route deletes the pair on a failed rename any more: an upload rejected at the
            // rename used to unlink the previously staged APK first, which is how a bad signature could
            // leave a frame with nothing staged.
            if (!externalStorageWritable()) {
                return failUpload(replacingStaged, previousLastError, sha, size, true,
                        "external-storage-unavailable");
            }
            if (!copyFile(apk, publicPart) || publicPart.length() != size) {
                return failUpload(replacingStaged, previousLastError, sha, size, true, "stage-copy-failed");
            }
            if (!publishStagedPair(apk, publicPart)) {
                return failUpload(replacingStaged, previousLastError, sha, size, true, "stage-rename-failed");
            }
            // publishStagedPair() committed the identity — expected* from the fetch* written above,
            // and stagedSource from fetchOrigin — which is why nothing here names the source twice.
            String stagedMsg = "staged uploaded APK " + manifest.versionName + " ("
                    + manifest.versionCode + ", " + size + " B)";
            Log.i(TAG, stagedMsg);
            notifyDebug(stagedMsg);
            return ApkUploadResult.staged(sha, size);
        } catch (Exception e) {
            Log.e(TAG, "upload staging crashed", e);
            // The previous stage was never unlinked, so it is always restorable from here.
            return failUpload(replacingStaged, previousLastError, "", size,
                    false, "stage-crashed: " + e.getClass().getSimpleName());
        } finally {
            // One place rather than nine exits. After a successful publish there is nothing left at
            // either name to delete, and this is the task that owns both.
            apk.delete();
            publicPart.delete();
        }
    }

    /**
     * Puts the state back the way it was, or records the rejection. A rejected upload never disturbs
     * the stage that was already there — the product rule the whole swap order exists to keep.
     */
    private ApkUploadResult failUpload(boolean restoringStaged, String previousLastError,
            String sha, long size, boolean signatureValid, String reason) {
        synchronized (this) {
            if (restoringStaged) {
                // The previously staged APK is still on disk with its original metadata.
                state = withState(state, UpdateState.State.STAGED, previousLastError);
            } else {
                state = withState(state, UpdateState.State.FAILED, reason);
            }
            // The rejected upload is not pending anywhere; the identity claimed for it is void.
            clearFetch(state);
            writeState(state);
        }
        Log.w(TAG, "uploaded APK rejected: " + reason);
        notifyDebug("uploaded APK rejected: " + reason);
        return ApkUploadResult.reject(sha, size, signatureValid, reason);
    }

    // ---------------------------------------------------------------------- cycle

    /**
     * A check, from the interval gate or a forced press. Worker thread.
     *
     * <p>Everything {@code startCycle()} used to refuse — a replaced manager, an install in flight, a
     * state already mid-cycle, a second cycle while one was running — is now settled by there being one
     * queue and one thread, and the checks below are only the residue: what another process can do to
     * us, and what a bug in this one would look like.
     */
    private void runCheck(boolean force) {
        UpdateState prior = null;
        // Reported after the monitor is released. A refusal is worth a line in eo1-app.log, and the
        // logger writes to disk under its own monitor — which is the one thing a web thread waiting on
        // this lock must never have to sit behind.
        String refusal = null;
        synchronized (this) {
            if (!force) {
                Configuration config = configuration();
                if (config == null || !config.updatesEnabled()) {
                    return;
                }
                if (!checkDue(System.currentTimeMillis(), state, config.updateCheckIntervalMinutes)) {
                    return;
                }
            }
            switch (state.state) {
                case CHECKING:
                case DOWNLOADING:
                case VERIFYING:
                    // Unreachable while one thread runs the work: the state only says CHECKING because
                    // a task is inside this method, and no second task runs meanwhile. A frame that
                    // gets here has a state a throwing task left behind, which settleStuckTask exists
                    // to clean up — so this branch is a report, not a defence.
                    refusal = "update cycle refused (state=" + state.state + ")";
                    break;
                case INSTALL_PENDING:
                    // The platform installer has stagedPublic open right now. A cycle that replaces
                    // that pair mid-install would have the installer read a file that is being
                    // rewritten under it, and runInstall's own re-hash gate would then throw away a
                    // stage that was about to land. This wall is another process's, not ours.
                    refusal = "update cycle refused (install awaiting its settle window)";
                    break;
                default:
                    break;
            }
            if (refusal == null) {
                // A STAGED update does not stop a check: checkDue lets the timer re-read the host for a
                // stage the host itself produced, and a forced check gets here for any stage. Either
                // way runCycleBody consults judgeStaged before it overwrites anything, so a stage this
                // app did not download is still untouched when the cycle decides what it may do.
                prior = state;
                state = withState(prior, UpdateState.State.CHECKING, "");
                state.attempts = prior.attempts + 1;
                state.lastCheckedMs = System.currentTimeMillis();
                writeState(state);
            }
        }
        if (refusal != null) {
            notifyDebug(refusal);
            return;
        }
        try {
            runCycleBody(prior);
        } catch (Throwable t) {
            Log.e(TAG, "update cycle crashed", t);
            failOrRestore(prior, "cycle-crashed: " + t);
        } finally {
            part.delete();
            publicPart.delete();
        }
    }

    /** The configuration, from whoever the live Activity says owns it. */
    private Configuration configuration() {
        SettingsManager s = settingsManager;
        return s == null ? null : s.getConfiguration();
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
     * The body of one check, with the {@code CHECKING} reservation already written.
     *
     * @param prior the state as it was before this cycle claimed {@code CHECKING} — the stage a
     *              failure must go back to, if there was one.
     */
    private void runCycleBody(UpdateState prior) {
        // CHECKING: fetch + validate the manifest.
        Configuration config = configuration();
        String manifestUrl = config != null ? config.updateManifestUrl : null;
        if (manifestUrl == null || manifestUrl.trim().isEmpty()) {
            // Nothing to compare against. A stage already on disk stays a stage — the host being
            // unconfigured is not a verdict on it, and deleting it here would be an edit to the
            // config silently becoming an uninstall.
            if (prior.state == UpdateState.State.STAGED) {
                restoreStaged(prior, "manifest-url-not-configured");
            } else {
                cycleSetIdle("manifest-url-not-configured");
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
            // discardStageAndSettle() writes the IDLE transition itself: setIdle here afterwards
            // would be the second, unguarded state write this call exists to eliminate.
            notifyUpdateChecked(false);
            discardStageAndSettle();
            return;
        }
        notifyUpdateChecked(true);

        // What the cycle is going to fetch — deliberately NOT written into the expected* fields,
        // which keep describing the APK that is actually on disk until the swap commits below.
        // Overwriting them here is what made an interrupted check unrecoverable.
        boolean claimed;
        synchronized (this) {
            state.fetchVersionCode = manifest.versionCode;
            state.fetchVersionName = manifest.versionName == null ? "" : manifest.versionName;
            state.fetchSha256 = manifest.sha256.trim().toLowerCase(Locale.US);
            state.fetchSizeBytes = manifest.sizeBytes;
            // Whichever way these bytes land, a stage made of them is the host's to replace next.
            state.fetchOrigin = UpdateState.SOURCE_MANIFEST;
            claimed = writeState(state);
        }
        if (!claimed) {
            // The identity of the thing about to be downloaded never reached disk. Proceeding would
            // mean a later swap could not be attributed after a restart, which is the exact failure
            // these fields exist to prevent — so stop while nothing has been disturbed.
            failOrRestore(prior, "state-write-failed");
            return;
        }

        // Space pre-check (advisory; the installer is the final authority).
        long required = manifest.sizeBytes + STAGING_HEADROOM_BYTES;
        if (freeBytes(updatesDir) < required || freeBytes(stagedPublic.getParentFile()) < required) {
            failOrRestore(prior, "insufficient-space");
            return;
        }

        cycleTransition(UpdateState.State.DOWNLOADING);

        // DOWNLOADING: stream to the private .part (32 KB chunks, size-capped).
        part.delete();
        try {
            long total = download(manifest.apkUrl.trim(), part, manifest.sizeBytes);
            if (total != manifest.sizeBytes) {
                part.delete();
                failOrRestore(prior, "size-mismatch");
                return;
            }
        } catch (SupersededException e) {
            // A reset or an upload is already queued behind this cycle, so nothing is wrong: record
            // that this one stood aside, and keep whatever stage the frame already had. Going
            // FAILED here would stop the install retry over a transfer nobody wanted any more —
            // which is the same mistake the old code called out about a superseded public copy.
            part.delete();
            standAside(prior);
            return;
        } catch (Exception e) {
            part.delete();
            failOrRestore(prior, "download-failed: " + e + TrustCensus.gateNote(e));
            return;
        }

        cycleTransition(UpdateState.State.VERIFYING);

        // VERIFYING: SHA-256 → JAR signature (incl. signer-certificate match) → version gate.
        try {
            String sha = sha256Safe(part);
            if (!sha.equalsIgnoreCase(state.fetchSha256)) {
                part.delete();
                failOrRestore(prior, "sha256-mismatch");
                return;
            }
            X509Certificate installed = installedCert();
            if (installed == null) {
                part.delete();
                failOrRestore(prior, "no-installed-cert");
                return;
            }
            ApkSignatureVerifier.Result result = ApkSignatureVerifier.verify(part, installed);
            if (!result.valid) {
                part.delete();
                failOrRestore(prior, "signature-rejected: " + result.reason);
                return;
            }
            if (!manifest.isNewerThan(BuildConfig.VERSION_CODE)) {
                part.delete();
                failOrRestore(prior, "not-newer");
                return;
            }
        } catch (Exception e) {
            part.delete();
            failOrRestore(prior, "verify-crashed: " + e);
            return;
        }

        // STAGED. Nothing below writes to a shared name: the bytes are copied into this worker's own
        // temp inside the installer-readable directory, and the two shared files are replaced only by
        // rename from those temps, inside one gated section that also commits the state. The order
        // inside that section is master, then state, then the public copy — the master is what
        // runInstall() re-hashes against, so the state has to follow the master and never lead it.
        //
        // This replaces an earlier arrangement where renaming over the master was the point of no
        // return (rename(2) unlinks the previous build there) and every rung after it deleted the
        // pair, so a build that had cleared all four gates could still leave a frame with nothing
        // staged and its install retry dead.
        try {
            if (!externalStorageWritable()) {
                // Nothing has been touched yet, so the stage this frame already holds survives.
                failOrRestore(prior, "external-storage-unavailable");
                return;
            }
            if (!copyFile(part, publicPart) || publicPart.length() != manifest.sizeBytes) {
                // Nothing shared has been touched, so the previous stage survives intact.
                failOrRestore(prior, "stage-copy-failed");
                return;
            }
            // The last irreversible step, and the metadata that names it, in one section: a reset
            // landing between the two would leave the new bytes on disk under a state that says
            // nothing is staged. Queuing is what makes that section unnecessary — no second writer can
            // reach these names while this one is running — but the order inside it still matters, and
            // for a reason a queue has nothing to do with: see publishStagedPair().
            if (!publishStagedPair(part, publicPart)) {
                // The master rename failed. rename(2) replaces its target, so a failure means the
                // source is gone or the filesystem is unhappy; either way there is nothing to restore,
                // and failOrRestore is the honest reporter of that.
                failOrRestore(prior, "stage-publish-failed");
                return;
            }
        } catch (Exception e) {
            failOrRestore(prior, "stage-failed: " + e);
            return;
        }

        // commitFetchedStage() already ran, immediately after the rename it describes. Calling it again
        // here would promote from the fields it cleared, zeroing the stage's identity out from under
        // the files that are now on disk.
        notifyDebug("staged update " + manifest.versionName + " (" + manifest.versionCode + ")");

        // Proceed straight to the install; if this process dies before firing, the next
        // tick's installStaged(false) retries from STAGED. Queued rather than run inline, so the check
        // that staged these bytes is not also the task holding the installer's file open.
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

    /** Streams {@code url} to {@code dest} in 32 KB chunks. @return total bytes written. */
    private long download(String url, File dest, long sizeBytes) throws Exception {
        Request request = new Request.Builder().url(url).build();
        Response response = client().newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new java.io.IOException("http " + response.code());
            }
            InputStream in = response.body().byteStream();
            FileOutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[BUFFER];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > sizeBytes || total > UpdateManifest.MAX_APK_BYTES) {
                        throw new java.io.IOException("download exceeds expected size");
                    }
                    // A reset or an upload can be waiting minutes into this loop. Nothing depends on
                    // noticing — no other task can run while this one is in here, so there is no
                    // result "guaranteed to be discarded" — but finishing a download for bytes someone
                    // has already asked to throw away wastes radio, and this is the one place in the
                    // file where a task runs long enough for that to matter. It is the only read of the
                    // preemption flag, and deleting it changes no outcome, only latency.
                    if (worker.preemptionRequested()) {
                        throw new SupersededException();
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
                    // Bounded to a minute, and this is the reason: with one worker, a socket that
                    // accepts bytes at zero rate parks every update action behind it — including the
                    // operator's reset, which cannot preempt a blocking read no flag is checked
                    // inside. Ten minutes of that looked harmless when a wedged cycle was just one
                    // thread among several and installs still retried around it. A read timeout is
                    // per-read rather than per-transfer, so this costs a genuinely slow link nothing:
                    // 18 MB took about three minutes to reach a frame on this Wi-Fi with bytes
                    // arriving continuously, and a stalled transfer is what the interval's retry is
                    // for.
                    .readTimeout(60, TimeUnit.SECONDS);
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
            writeState(withState(state, newState, ""));
        }
    }

    private void failState(String reason) {
        synchronized (this) {
            writeState(UpdateState.failed(state, reason));
        }
        Log.e(TAG, "update FAILED: " + reason);
        notifyDebug("update FAILED: " + reason);
    }

    /**
     * The one place persisted state is replaced. Callers hold the monitor.
     *
     * <p>It no longer tests who is calling, which is what seven rounds of this file were: the question
     * "may this writer write?" has exactly one answer now, because there is one thread that reaches
     * here and the queue decided its order before it ran. What remains to decide is whether the write
     * landed.
     *
     * @return false when the state file could not be written, which callers must not treat as success
     */
    private boolean writeState(UpdateState next) {
        state = next;
        return stateStore.write(state);
    }

    /**
     * A cycle's state transition. The names {@code cycleTransition} and {@code cycleSetIdle} are kept
     * because they say who is allowed to move the state to a running marker and back, which is still a
     * distinction worth reading at the call site.
     */
    private void cycleTransition(UpdateState.State next) {
        synchronized (this) {
            writeState(withState(state, next, ""));
        }
    }

    private void cycleSetIdle(String reason) {
        synchronized (this) {
            writeState(withState(state, UpdateState.State.IDLE, reason));
        }
        notifyDebug("update: " + reason);
    }

    /**
     * Backstop for a task that died before it could settle itself. Without it the state stays
     * {@code CHECKING} forever and {@link #checkDue} refuses every later tick, which is a frame that
     * quietly stopped updating: the per-recreation {@code reconcileOnStartup()} that used to clean
     * this up is gone, because there are no further recreations of the manager.
     */
    private void settleStuckTask(String description, Throwable error) {
        Log.e(TAG, "update task threw: " + description, error);
        synchronized (this) {
            if (state.state == UpdateState.State.CHECKING
                    || state.state == UpdateState.State.DOWNLOADING
                    || state.state == UpdateState.State.VERIFYING) {
                writeState(UpdateState.failed(state, "task-threw-in-" + description + ": " + error));
            }
        }
        notifyDebug("update: a queued task threw in " + description + "; state settled");
    }

    /**
     * Publishes verified bytes into the two shared names by rename, and writes the state that names
     * them, in one section. No gate and no ownership check: while this runs nothing else in the
     * process can touch these names, because nothing else is running.
     *
     * <p>What the ordering inside it protects is not a thread but a <em>process</em> — the installer,
     * which reads {@code stagedPublic} under another uid. Master first, state second, public copy
     * last: a failure after the master is self-healing, because {@code runInstall()} re-makes a
     * divergent public copy from the master, whereas the reverse order could leave the installer
     * reading a new file whose private master is still the old one — which {@code runInstall()}'s
     * re-hash gate would then call corruption and delete. That reason outlives every reason the gate
     * had.
     *
     * @return false when the master rename failed, having disturbed nothing
     */
    private boolean publishStagedPair(File from, File to) {
        boolean publicCopyFailed;
        try {
            synchronized (this) {
                if (!atomicReplace(from, stagedMaster)) {
                    return false;
                }
                commitFetchedStage();
                publicCopyFailed = !atomicReplace(to, stagedPublic);
            }
        } finally {
            // Whatever is left of the temps goes; a rename that succeeded already consumed them.
            from.delete();
            to.delete();
        }
        if (publicCopyFailed) {
            notifyDebug("update: staged privately; the installer-readable copy will be"
                    + " re-made from the master when this installs");
        }
        return true;
    }

    /**
     * Rebuilds the installer-readable copy from the private master, through a temp rather than into
     * {@code stagedPublic} directly: opening the shared name would truncate it for an installer
     * already mid-read, and this file is the one thing another process has open while we work.
     */
    private boolean repairPublicCopy(long expectedSize) {
        publicPart.delete();
        if (!copyFile(stagedMaster, publicPart) || publicPart.length() != expectedSize) {
            publicPart.delete();
            return false;
        }
        synchronized (this) {
            return atomicReplace(publicPart, stagedPublic);
        }
    }

    /**
     * One {@code rename(2)}, which on the same filesystem already replaces an existing regular file
     * atomically. There is deliberately no delete-then-retry: a rename that fails is failing because
     * the source is gone or the filesystem is unhappy, and in neither case does deleting the
     * destination make the retry work — it only destroys the verified build that was there. That
     * retry was inherited from the original staging code, and it is the reason a failed publish could
     * cost a frame a stage it could otherwise still install.
     */
    private boolean atomicReplace(File from, File to) {
        return from.renameTo(to);
    }

    /**
     * Discards the stage if — and only if — it is the host's to discard, then goes
     * {@code IDLE}: decision, deletion and transition in one step. The rule is the product rule, not a
     * concurrency rule, and it survives the queue unchanged: an APK an operator uploaded is never
     * deleted on a manifest's say-so, however pointless the host thinks it has become.
     */
    private void discardStageAndSettle() {
        synchronized (this) {
            if (mayDiscardStage(state.stagedSource)) {
                deleteStagedFiles();
            }
            state = withState(state, UpdateState.State.IDLE, "up-to-date");
            writeState(state);
        }
        notifyDebug("update: up-to-date");
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
            long checkedMs = state.lastCheckedMs;
            int att = state.attempts;
            state = withState(prior, UpdateState.State.STAGED, reason);
            // Nothing is pending any more — the cycle decided not to replace this stage.
            clearFetch(state);
            state.needsHumanConfirmation = prior.needsHumanConfirmation;
            state.lastCheckedMs = checkedMs;
            state.attempts = att;
            writeState(state);
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
        if (prior != null && prior.state == UpdateState.State.STAGED
                && stagedIntactAs(prior.manifestSha256, prior.expectedSizeBytes)) {
            restoreStaged(prior, reason);
            notifyDebug("update cycle failed; keeping staged "
                    + prior.expectedVersionName + " (" + prior.expectedVersionCode + ")");
            return;
        }
        failState(reason);
    }

    /**
     * Settles a cycle that stood aside for a later request. Not a failure: the bytes it was fetching
     * are ones somebody has just asked the frame not to want, and the stage it may have been
     * replacing is still there, still installable, still owed its retry.
     */
    private void standAside(UpdateState prior) {
        if (prior != null && prior.state == UpdateState.State.STAGED
                && stagedIntactAs(prior.manifestSha256, prior.expectedSizeBytes)) {
            restoreStaged(prior, "superseded-by-a-later-request");
        } else {
            synchronized (this) {
                // Same rule every other exit from a check follows: nothing is pending any more, so
                // stop naming the build that was. An IDLE state carrying fetch* is one the next
                // crash-recovery would try to match against bytes that never arrived.
                clearFetch(state);
            }
            cycleSetIdle("superseded-by-a-later-request");
        }
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

    /**
     * Writes the pending state for this dispatch and returns the attempt stamp it just wrote.
     *
     * <p>Returns the value rather than a boolean because the caller has to hand <em>this</em> stamp to
     * the installer and nothing else can identify the attempt: the state the caller is holding was
     * captured before the dispatch, so its {@code installFiredAtMs} is the previous attempt's — or
     * zero, which every pending attempt used to match.
     *
     * @return the stamp, or {@code 0} when the write failed and nothing may be dispatched
     */
    private long persistInstallPending() {
        synchronized (this) {
            UpdateState next = withState(state, UpdateState.State.INSTALL_PENDING, "");
            next.installFiredAtMs = System.currentTimeMillis();
            return writeState(next) ? next.installFiredAtMs : 0L;
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
            clearFetch(state);
            writeState(withState(state, UpdateState.State.STAGED, ""));
        }
    }

    private void setStaged(String lastError) {
        synchronized (this) {
            writeState(withState(state, UpdateState.State.STAGED, lastError));
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
            UpdateState next = withState(state, UpdateState.State.STAGED, REASON_NEEDS_HUMAN);
            next.needsHumanConfirmation = true;
            // Nothing was fired, so nothing is owed a settle window.
            next.installFiredAtMs = 0L;
            if (!writeState(next)) {
                return;
            }
        }
        notifyDebug("install: deferred — no silent transport on this frame;"
                + " use \"Install on the frame\" in the web UI");
    }

    /**
     * The {@code /state} "update" block. Called from a web-server thread — the one entry point that
     * cannot be queued, because the worker may be inside a minutes-long task and a polled diagnostic
     * must never wait for it. So this reads the state under the monitor, holds it for the length of a
     * field copy rather than a report, and builds everything else off-thread.
     *
     * <p>{@code command}, {@code commandAgeMs}, {@code queueDepth} and {@code workerRunning} are here
     * because a serialized worker trades forty attribution bugs for one new failure mode: everything
     * update-related is stopped while one task is not finishing. "Why did my button do nothing for
     * three minutes" has to be answerable from this document, because nothing else watches the queue.
     */
    public JsonObject stateJson() {
        // Both of these reach outside the process — a binder call and a configuration read — so they
        // go before the lock: the worker needs that same lock to record anything, and a busy
        // system_server must not be able to stall an update by way of a page being refreshed.
        boolean headless = holdsInstallPermission();
        Configuration config = configuration();
        String url = config != null ? config.updateManifestUrl : null;
        UpdateState s;
        synchronized (this) {
            s = state.copy();
        }
        String running = worker.currentDescription();
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
        // `present` is a disjunction over two names, so one length cannot describe it. The public
        // copy is what the installer reads; the master is what survives a `public-copy-failed` stage
        // and what survives the external-storage directory being cleared under it. Reading
        // length() off the public copy alone reported {present: true, bytes: 0} for a frame holding
        // a complete installable APK -- and 0 is a claim about size, not about absence, so absence
        // is reported by omitting the key.
        if (present) {
            staged.addProperty("bytes", (stagedPublic.exists() ? stagedPublic : stagedMaster).length());
        }
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
        // The queue's own health. `command` is what the one worker thread is doing right now, and
        // `commandAgeMs` how long it has been doing it: a reset that will not land, or an upload that
        // answers "staging-queued", is diagnosable from these two and nothing else.
        o.addProperty("command", running == null ? "" : running);
        o.addProperty("commandAgeMs", running == null ? 0L : worker.currentAgeMs());
        o.addProperty("queueDepth", worker.queueDepth());
        o.addProperty("workerRunning", worker.isAlive());
        return o;
    }

    // ------------------------------------------------------------------- listeners

    /**
     * InvocationHandler for the reflective {@code PackageManager$PackageInstallObserver}
     * proxy. API 19 signatures: {@code packageInstalled(int userId)} and
     * {@code packageInstallFailed(String errorMsg, int userId)} — dispatched by method name
     * so the interface itself is never referenced at compile time.
     */
    /**
     * The install observer, bound to the dispatch that created it.
     *
     * <p>One instance per {@code installPackage} call, carrying the {@code installFiredAtMs} of the
     * attempt it was handed to. Without that, a result that arrives late — after a reset, a fresh
     * stage and a second dispatch — would be indistinguishable from the second attempt's own result,
     * and would roll a legitimate {@code INSTALL_PENDING} back to {@code STAGED}, clearing the settle
     * window that keeps the frame from replacing a file the first installer may still be reading. A
     * queue orders our own work; it cannot tell two events from the platform apart.
     */
    private final class InstallObserver implements InvocationHandler {
        private final long attempt;

        InstallObserver(long attempt) {
            this.attempt = attempt;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            // A stray exception must never escape to the global UncaughtExceptionHandler
            // (which calls System.exit(2)).
            try {
                String name = method.getName();
                if ("packageInstalled".equals(name)) {
                    onInstalled(attempt, observerIntArg(args, 0));
                    return null;
                }
                if ("packageInstallFailed".equals(name)) {
                    onInstallFailed(attempt, observerStringArg(args, 0), observerIntArg(args, 1));
                    return null;
                }
                if ("toString".equals(name)) {
                    return "UpdateInstallObserver/" + attempt;
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
    }

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

    /**
     * The install result, from a framework binder thread that was never handed the work. That single
     * fact is why {@code processOwner} existed: thread identity could not attribute this callback to
     * the manager that started the install, so the manager had to. Now the callback's whole job is to
     * put the result on the queue, which any thread may do, and the answer is decided by ordering.
     */
    private void onInstalled(final long attempt, int userId) {
        Log.i(TAG, "install observer: packageInstalled (user " + userId + ")");
        worker.post("install result", new SerialWorker.Task() {
            @Override
            public void run() {
                recordInstallResult(attempt, "");
            }
        });
    }

    private void onInstallFailed(final long attempt, String errorMsg, int userId) {
        Log.w(TAG, "install observer: packageInstallFailed (user " + userId + "): " + errorMsg);
        final String reason = "install-failed: " + (errorMsg == null ? "" : errorMsg);
        worker.post("install result", new SerialWorker.Task() {
            @Override
            public void run() {
                recordInstallResult(attempt, reason);
            }
        });
    }

    /**
     * Worker thread. A successful {@code installPackage} kills this process, so success is confirmed
     * by the next process's startup reconcile either way; rolling back to {@code STAGED} is harmless,
     * because that path handles both states. A result that arrives with nothing pending is a stale
     * one — the settle window already rolled it back — and changing no state is the correct answer.
     */
    private void recordInstallResult(long attempt, String reason) {
        boolean stale = false;
        synchronized (this) {
            if (attempt != state.installFiredAtMs) {
                // A result for some earlier dispatch. The pending state now belongs to a newer
                // attempt, whose settle window is the only thing stopping a re-stage from replacing
                // an APK an installer still has open, so this event gets reported and dropped.
                // The stamp the dispatch bound its observer to, compared against the pending state as
                // written. Equality is the only thing that makes this event about <em>this</em>
                // install, so there is deliberately no "zero matches anything" case: a dispatch that
                // could not record a stamp does not fire at all.
                stale = true;
            } else {
                installFiredInThisProcess = false;
                if (state.state == UpdateState.State.INSTALL_PENDING) {
                    state = withState(state, UpdateState.State.STAGED, reason);
                    state.installFiredAtMs = 0L;
                    writeState(state);
                }
            }
        }
        if (stale) {
            notifyDebug("install: a result for an earlier attempt arrived; current attempt untouched");
        }
    }

    /**
     * Reports one update event: on screen and into the durable log, exactly once.
     *
     * <p>The split is the point of this method. {@code debugInformationProvided} is the single write
     * path into {@link AppLogger}, so calling both would duplicate every line — but the listener is an
     * Activity, and this manager now outlives every Activity it is attached to. With nobody attached,
     * the message would reach logcat only, and on these frames logcat holds about thirteen minutes
     * while {@code eo1-app.log} survives. So: the listener when there is one, the log directly when
     * there is not.
     *
     * <p>It cannot throw, and that is not defensive padding. Several callers sit inside a cycle that
     * wraps them in {@code catch (Throwable) → failOrRestore}, and the busiest of those calls is the
     * one that runs <em>after</em> {@code publishStagedPair()} has committed verified bytes: a logging
     * statement that escapes would then report a successful stage as a crashed cycle, put the state at
     * {@code FAILED}, skip the install the same cycle was about to attempt, and re-download the whole
     * APK on every interval after that. A report must never be able to decide an outcome.
     */
    private void notifyDebug(String value) {
        try {
            UpdateManagerListener target = listener.get();
            if (target != null) {
                target.debugInformationProvided(new DebugInformation("Update", value));
                return;
            }
        } catch (Throwable listenerFailed) {
            // Fall through to the log: a listener that threw must not cost the durable record.
        }
        try {
            AppLogger log = Eo1Application.loggerFor(context);
            if (log != null) {
                // Same body as the attached path, which writes "Update: <value>" under the activity's
                // tag (MainActivity.debugInformationProvided). The tag differs because the activity is
                // the one thing not here to write it, and a line that says which component spoke is
                // the honest version of that.
                log.info(TAG, "Update: " + value);
            }
        } catch (Throwable loggingFailed) {
            Log.w(TAG, "could not report: " + value);
        }
    }

    private void notifyUpdateChecked(Boolean available) {
        try {
            UpdateManagerListener target = listener.get();
            if (target != null) {
                target.updateChecked(available);
            }
        } catch (Throwable ignored) {
            // Same rule: whether the toast went through is not part of the update's verdict.
        }
    }

    /**
     * A later request asked this download to stop. Its own type because the difference between "the
     * wire failed" and "somebody changed their mind" is what {@code FAILED} versus {@code IDLE} turns
     * on, and an exception message is too fragile a thing to decide that on.
     */
    private static final class SupersededException extends java.io.IOException {
        SupersededException() {
            super("update cycle superseded");
        }
    }

    private static final class ManifestHttpException extends Exception {
        final int statusCode;

        ManifestHttpException(int statusCode) {
            this.statusCode = statusCode;
        }
    }
}
