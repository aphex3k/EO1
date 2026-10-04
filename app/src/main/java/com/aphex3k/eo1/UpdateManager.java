package com.aphex3k.eo1;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
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
 * <p>Install dispatch: when the frame holds {@code INSTALL_PACKAGES} (a normal permission on
 * API 19, persisted from the one-time bootstrap install) the APK is installed headlessly via
 * a reflective call to {@code PackageManager.installPackage} with a proxied
 * {@code PackageManager$PackageInstallObserver}; otherwise the proven install-intent fallback
 * is used.
 *
 * <p>Recovery across process death: the installer kills this process on success, so success
 * is detected only by the new process's {@link #reconcileOnStartup()}. A +120 s relaunch alarm
 * (the same one {@code uncaughtException} uses) is armed just before firing, so a fatal
 * crash mid-install still gets the frame back to a launchable state.
 */
public class UpdateManager {

    private static final String TAG = "EO1-update";
    private static final int BUFFER = 32 * 1024;
    private static final long MANIFEST_BODY_CAP = 64 * 1024;
    /** Free space required besides the APK itself, for the installer's headroom. */
    private static final long STAGING_HEADROOM_BYTES = 32L * 1024 * 1024;
    private static final long RELAUNCH_ALARM_MS = 120_000L;
    private static final long INSTALL_SETTLE_MS = 90_000L;

    private final WeakReference<UpdateManagerListener> listener;
    private final Context context;
    private final SettingsManager settingsManager;
    private final UpdateStateStore stateStore;
    private final File updatesDir;
    private final File partFile;
    private final File stagedMaster;
    private final File stagedPublic;

    private UpdateState state;
    private boolean installInFlight;
    private boolean installFiredInThisProcess;
    private long installFiredAtMs;
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
     * Package-visible for unit tests. True when a new check cycle may start: no cycle in
     * flight, nothing staged/pending, and the configured interval has elapsed.
     */
    static boolean checkDue(long nowMs, UpdateState state, int intervalMinutes) {
        if (state == null) {
            // No state file at all: never checked, so the first cycle runs now.
            return true;
        }
        if (state.state != null) {
            switch (state.state) {
                case CHECKING:
                case DOWNLOADING:
                case VERIFYING:
                case STAGED:
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
        synchronized (this) {
            UpdateState current = state;
            UpdateState reconciled = reconcileState(current, BuildConfig.VERSION_CODE);

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
            if (reconciled.state == UpdateState.State.STAGED
                    && !stagedPublic.exists()
                    && !stagedMaster.exists()) {
                // A staged state without its files can never install.
                reconciled = withState(reconciled, UpdateState.State.IDLE, "staged-files-missing");
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
     * Called from {@code onResume}: if this process fired an install and is still alive on
     * the old version, the install did not take effect (intent cancelled, headless install
     * refused) — roll back to the retryable {@code STAGED}.
     */
    public void reconcileInstallOutcome() {
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
            state = withState(state, UpdateState.State.STAGED, "install-canceled-or-refused");
            stateStore.write(state);
            Log.w(TAG, "install did not complete in-process; back to STAGED (retried on the next tick)");
        }
    }

    // ------------------------------------------------------------------- installing

    /**
     * Installs a staged update. With {@code force} it also reports via the listener when
     * there is nothing staged (used by the web button). Called on every timer tick, so a
     * failed/stuck install is retried automatically until it lands or {@link #resetUpdate()}.
     */
    public void installStaged(boolean force) {
        synchronized (this) {
            if (installInFlight) {
                return;
            }
            if (state.state == UpdateState.State.INSTALL_PENDING
                    && installFiredInThisProcess
                    && System.currentTimeMillis() - installFiredAtMs > INSTALL_SETTLE_MS) {
                // A fired install that never settled (no observer callback, process still
                // alive): roll back so the next attempt can retry.
                installFiredInThisProcess = false;
                state = withState(state, UpdateState.State.STAGED, "install-canceled-or-refused");
                stateStore.write(state);
                Log.w(TAG, "install fired earlier did not settle; back to STAGED");
            }
            if (state.state != UpdateState.State.STAGED) {
                if (force) {
                    notifyDebug("install-staged: no staged update (state=" + state.state + ")");
                }
                return;
            }
            installInFlight = true;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runInstall();
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

    private void runInstall() {
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
            if (!masterOk || !copyFile(stagedMaster, stagedPublic)
                    || stagedPublic.length() != snapshot.expectedSizeBytes) {
                deleteStagedFiles();
                failState("staged-file-changed");
                return;
            }
        }

        armRelaunchAlarm();
        persistInstallPending();
        installFiredInThisProcess = true;
        installFiredAtMs = System.currentTimeMillis();

        if (holdsInstallPermission()) {
            Log.i(TAG, "installing staged update headlessly (version " + snapshot.expectedVersionCode + ")");
            try {
                fireHeadlessInstall(Uri.fromFile(stagedPublic));
            } catch (Throwable t) {
                Log.e(TAG, "installPackage threw", t);
                setStaged("installPackage-threw: " + t);
            }
        } else {
            // One-time bootstrap frames without INSTALL_PACKAGES: proven intent fallback.
            wakeScreenForInstaller();
            Log.i(TAG, "installing staged update via install intent (version " + snapshot.expectedVersionCode + ")");
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
        synchronized (this) {
            deleteStagedFiles();
            partFile.delete();
            installFiredInThisProcess = false;
            state = withState(state, UpdateState.State.IDLE, "");
            state.attempts = 0;
            stateStore.write(state);
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
            // way startCycle() reserves CHECKING.
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
            // Swap: replace any previously staged update. Remember whether the old staged
            // files are actually gone: a later failure must only restore the STAGED state
            // when they are, otherwise the state would claim a staged APK that no longer
            // exists on disk.
            oldStagedGone = deleteStagedFiles();
            if (!apk.renameTo(stagedMaster)) {
                stagedMaster.delete();
                if (!apk.renameTo(stagedMaster)) {
                    return failUpload(null, oldStagedGone, previousLastError, sha, size, true,
                            "stage-rename-failed");
                }
            }
            if (!externalStorageWritable()) {
                deleteStagedFiles();
                return failUpload(null, oldStagedGone, previousLastError, sha, size, true,
                        "external-storage-unavailable");
            }
            if (!copyFile(stagedMaster, stagedPublic) || stagedPublic.length() != stagedMaster.length()) {
                deleteStagedFiles();
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
            switch (s.state) {
                case CHECKING:
                case DOWNLOADING:
                case VERIFYING:
                case STAGED:
                case INSTALL_PENDING:
                    // A cycle is already in flight, or a staged update is owned by
                    // installStaged()/reconciliation — do not fight it.
                    notifyDebug("update cycle refused (state=" + s.state + ")");
                    return;
                default:
                    break;
            }
            state = withState(s, UpdateState.State.CHECKING, "");
            state.attempts = s.attempts + 1;
            state.lastCheckedMs = System.currentTimeMillis();
            stateStore.write(state);
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runCycle();
                } catch (Throwable t) {
                    Log.e(TAG, "update cycle crashed", t);
                    failState("cycle-crashed: " + t);
                }
            }
        }, "EO1-update-cycle");
        thread.start();
    }

    private void runCycle() {
        // CHECKING: fetch + validate the manifest.
        Configuration config = settingsManager.getConfiguration();
        String manifestUrl = config != null ? config.updateManifestUrl : null;
        if (manifestUrl == null || manifestUrl.trim().isEmpty()) {
            setIdle("manifest-url-not-configured");
            return;
        }

        String body;
        try {
            body = fetchManifest(manifestUrl.trim());
        } catch (ManifestHttpException e) {
            failState("manifest-http-" + e.statusCode);
            return;
        } catch (Exception e) {
            failState("manifest-fetch: " + e);
            return;
        }

        UpdateManifest manifest = UpdateManifest.parse(body);
        if (manifest == null) {
            failState("manifest-unparseable");
            return;
        }
        String invalid = manifest.validationError();
        if (invalid != null) {
            failState("manifest-invalid: " + invalid);
            return;
        }
        if (!manifest.isNewerThan(BuildConfig.VERSION_CODE)) {
            notifyUpdateChecked(false);
            setIdle("up-to-date");
            return;
        }
        notifyUpdateChecked(true);

        synchronized (this) {
            state.expectedVersionCode = manifest.versionCode;
            state.expectedVersionName = manifest.versionName == null ? "" : manifest.versionName;
            state.manifestSha256 = manifest.sha256.trim().toLowerCase(Locale.US);
            state.expectedSizeBytes = manifest.sizeBytes;
            stateStore.write(state);
        }

        // Space pre-check (advisory; the installer is the final authority).
        long required = manifest.sizeBytes + STAGING_HEADROOM_BYTES;
        if (freeBytes(updatesDir) < required || freeBytes(stagedPublic.getParentFile()) < required) {
            failState("insufficient-space");
            return;
        }

        setState(UpdateState.State.DOWNLOADING);

        // DOWNLOADING: stream to the private .part (32 KB chunks, size-capped).
        partFile.delete();
        try {
            long total = download(manifest.apkUrl.trim(), manifest.sizeBytes);
            if (total != manifest.sizeBytes) {
                partFile.delete();
                failState("size-mismatch");
                return;
            }
        } catch (Exception e) {
            partFile.delete();
            failState("download-failed: " + e);
            return;
        }

        setState(UpdateState.State.VERIFYING);

        // VERIFYING: SHA-256 → JAR signature (incl. signer-certificate match) → version gate.
        try {
            String sha = sha256Safe(partFile);
            if (!sha.equalsIgnoreCase(state.manifestSha256)) {
                partFile.delete();
                failState("sha256-mismatch");
                return;
            }
            X509Certificate installed = installedCert();
            if (installed == null) {
                partFile.delete();
                failState("no-installed-cert");
                return;
            }
            ApkSignatureVerifier.Result result = ApkSignatureVerifier.verify(partFile, installed);
            if (!result.valid) {
                partFile.delete();
                failState("signature-rejected: " + result.reason);
                return;
            }
            if (!manifest.isNewerThan(BuildConfig.VERSION_CODE)) {
                partFile.delete();
                failState("not-newer");
                return;
            }
        } catch (Exception e) {
            partFile.delete();
            failState("verify-crashed: " + e);
            return;
        }

        // STAGED: private master + world-readable public copy.
        try {
            if (!partFile.renameTo(stagedMaster)) {
                stagedMaster.delete();
                if (!partFile.renameTo(stagedMaster)) {
                    failState("stage-rename-failed");
                    return;
                }
            }
            if (!externalStorageWritable()) {
                deleteStagedFiles();
                failState("external-storage-unavailable");
                return;
            }
            if (!copyFile(stagedMaster, stagedPublic) || stagedPublic.length() != stagedMaster.length()) {
                deleteStagedFiles();
                failState("stage-copy-failed");
                return;
            }
        } catch (Exception e) {
            deleteStagedFiles();
            failState("stage-failed: " + e);
            return;
        }

        setState(UpdateState.State.STAGED);
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
            httpClient = ApiServiceGenerator.enableTls12OnPreLollipop(builder).build();
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

    /** Same +120 s relaunch alarm MainActivity's uncaughtExceptionHandler uses. */
    private void armRelaunchAlarm() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(new ComponentName(context.getPackageName(), MainActivity.class.getName()));
            PendingIntent pi = PendingIntent.getActivity(context, 0, home, PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.RTC, System.currentTimeMillis() + RELAUNCH_ALARM_MS, pi);
        } catch (Exception e) {
            Log.w(TAG, "armRelaunchAlarm: " + e);
        }
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
        partFile.delete();
        synchronized (this) {
            state = UpdateState.failed(state, reason);
            stateStore.write(state);
        }
        Log.e(TAG, "update FAILED: " + reason);
        notifyDebug("update FAILED: " + reason);
    }

    private void persistInstallPending() {
        synchronized (this) {
            state = withState(state, UpdateState.State.INSTALL_PENDING, "");
            stateStore.write(state);
        }
    }

    private void setStaged(String lastError) {
        synchronized (this) {
            state = withState(state, UpdateState.State.STAGED, lastError);
            stateStore.write(state);
        }
    }

    private static UpdateState withState(UpdateState s, UpdateState.State newState, String lastError) {
        UpdateState c = new UpdateState();
        c.expectedVersionCode = s.expectedVersionCode;
        c.expectedVersionName = s.expectedVersionName;
        c.manifestSha256 = s.manifestSha256;
        c.expectedSizeBytes = s.expectedSizeBytes;
        c.lastCheckedMs = s.lastCheckedMs;
        c.attempts = s.attempts;
        c.state = newState;
        c.lastError = lastError == null ? "" : lastError;
        return c;
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
        JsonObject staged = new JsonObject();
        staged.addProperty("present", stagedPublic.exists() || stagedMaster.exists());
        staged.addProperty("bytes", stagedPublic.length());
        o.add("stagedApk", staged);
        o.addProperty("installPermissionHeld", headless);
        o.addProperty("installMode", headless ? "headless" : "intent-fallback");
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
