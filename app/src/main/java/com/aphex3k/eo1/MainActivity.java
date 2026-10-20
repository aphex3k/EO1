package com.aphex3k.eo1;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP;
import static android.os.PowerManager.SCREEN_DIM_WAKE_LOCK;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaType;
import com.aphex3k.eo1.mqtt.MqttManager;
import com.aphex3k.eo1.mqtt.Payload;
import com.aphex3k.eo1.mqtt.PlaybackListener;
import com.aphex3k.update.ApkUploadResult;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.dd.crop.TextureVideoView;
import com.example.tsplayer.TsPlayerNative;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.gson.JsonObject;
import com.google.gson.stream.MalformedJsonException;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TimeZone;

import okhttp3.HttpUrl;

public class MainActivity extends AppCompatActivity implements BrightnessManagerListener, EventManagerListener, SettingsManagerListener, UpdateManagerListener, MediaManagerListener, ConnectionManagerListener, ApiServiceGenerator.ProgressListener, WebController, KioskWatchdog.Host {

    /**
    Amount of milliseconds in a minute
     */
    private static final long MILLIS = 60000;
    private static final long VIDEO_WATCHDOG_POLL_MS = 2000L;
    private static final long VIDEO_STALL_THRESHOLD_MS = 8000L;
    /**
     * Shortest window worth trusting as a playback-rate sample. Deliberately below
     * {@link #VIDEO_STALL_THRESHOLD_MS}, so a clip that is merely slow is reported as slow before
     * it is misread as stalled.
     */
    private static final long VIDEO_RATE_WINDOW_MS = 5000L;
    /**
     * Media progress below this (x100) counts as making no headway. Past the end of a clip the
     * position inches rather than stopping — measured at 0.00x to 0.02x — so anything above this is
     * still genuinely playing and must not be cut short.
     */
    private static final int LOOP_TAIL_RATE_X100 = 50;
    /**
     * How far past the end a position may sit and still be believed. Measured restarts land 5 to
     * 375 ms past the file's own length; anything orders of magnitude beyond that is a bogus read
     * from a player that has not settled, not a clip that has finished.
     */
    private static final int LOOP_END_SLACK_MS = 30000;
    /**
     * How long a page may take to show signs of life. Loads that neither paint nor report an error
     * (a black-holed TCP connect) are caught here rather than left covering the screen.
     */
    private static final long WEB_LOAD_TIMEOUT_MS = 30000L;
    /** Minimum gap between download-progress logcat lines. */
    private static final long DOWNLOAD_PROGRESS_LOG_INTERVAL_MS = 5000L;
    /** Logcat tag matched by debug.sh follow_logcat filter. */
    private static final String TAG = "EO1";
    /** Tag the options dialog is shown under, and the idempotency key for {@link #showConfigurationUI()}. */
    private static final String OPTIONS_DIALOG_TAG = "OptionsDialog";
    private View lastVisibleView;
    private String lastVisibleAsset = "";
    private String activeVideoAssetId = "";
    private ImageView imageView;
    private TextureVideoView mediaPlayerVideoView;
    private TsVideoView tsVideoView;
    /** Chrome-less browser view for web-page assets; created on first use only. */
    private WebView webView;
    private String activeWebAssetId = "";
    /** Page currently on screen; "" once it has been abandoned. Its url identifies live callbacks. */
    private String webUrlOnScreen = "";
    /** Set once the current load has painted, so a dead sub-resource is not a failed page. */
    private boolean webLoadProducedPaint;
    private Runnable webLoadWatchdogRunnable;
    /** Sticky: this ROM offered no usable WebView provider, so web assets are skipped from here on. */
    private boolean webViewUnavailable;
    private VideoPlayerController videoPlayer;
    private MediaPlayerController mediaPlayerController;
    private TsPlayerController tsPlayerController;
    private boolean preferTsPlayer;
    private int tsFallbackCount;
    /** Inputs of the last video routing decision, reported in the debug /state video block. */
    private int lastVideoRotation = -1;
    private int lastVideoWidth;
    private int lastVideoHeight;
    private String lastVideoCodec;
    private String lastFrameOrientation;
    /**
     * Throughput of the asset last routed. Held as one immutable reference rather than four fields
     * so the web-server thread reading /state cannot see stats from two different assets.
     */
    private VideoHeaderProbe.StreamStats lastVideoStats = VideoHeaderProbe.StreamStats.UNKNOWN;
    /** The clip's real length from its own header; the player's can be shorter than this. */
    private long lastVideoDurationMs = -1L;
    /** Measured playback rate of the playing video, x100: 100 is realtime, 60 is 0.6x slow. */
    private int videoPlaybackRateX100 = -1;
    /** Spread of the rate windows since this display was armed, and how many there were. */
    private int videoPlaybackRateMinX100 = -1;
    private int videoPlaybackRateMaxX100 = -1;
    private int videoPlaybackRateSamples;
    private LinearProgressIndicator progressIndicator;
    private MqttManager mqttManager;
    private BrightnessManager brightnessManager;
    private HardwareCapabilities capabilities;
    private TextView debugOverlay;
    private EventManager eventManager;
    private UpdateManager updateManager;
    private MediaManager mediaManager;
    private SettingsManager settingsManager;
    private final Map<String, String> debugInformation = new HashMap<>();
    private final Handler handler = new Handler();
    private PendingIntent pendingIntent;
    private Timer quietHoursTimer = new Timer(true);
    /** Parsed quiet-hour cron windows, replaced atomically on each setupQuietHours() re-arm. */
    private volatile List<CronExpression> quietCronExpressions = new ArrayList<CronExpression>();
    /** Last quiet-state applied by the scheduler; edge-triggering avoids fighting manual toggles. */
    private volatile boolean lastScheduledQuietState = false;
    /**
     * What {@link #writeScreenBrightness(float)} last put on the panel, for /state only. The
     * authority on what the brightness should be is {@link BrightnessManager#currentBrightness()};
     * keeping a second value like this one as an <em>input</em> anywhere is what made the frame
     * dim itself below the user's level. -1 means nothing has been applied yet.
     */
    private volatile float lastAppliedBrightness = -1.0f;
    private ConnectionManager connectionManager;
    private AppLogger appLogger;
    private WebServer webServer;
    /** True once the stale incoming_* cleanup has run for this process (first onCreate only). */
    private static volatile boolean sStaleIncomingCleaned = false;
    /**
     * onCreate count for this process. A frame that "resets itself" is either having its Activity
     * re-created (this climbs, {@link #PROCESS_START_MS} does not) or restarting (both climb), and
     * /state is the only place left to tell them apart once the device is back on the wall.
     */
    private static volatile int sActivityCreateCount = 0;
    /** Process start, for the same reason: uptime with a low create count means neither happened. */
    private static final long PROCESS_START_MS = System.currentTimeMillis();
    private PowerManager.WakeLock screenOffWakeLock;
    /**
     * Reconciles shouldTheScreenBeOn with the real panel. On the EO2 the physical button is a
     * KEY_POWER intercepted by the OS (PhoneWindowManager), so the app's key handlers never fire
     * and the two states drift apart. Broadcasts our own turnScreenOn/turnScreenOff cause arrive
     * with the state already matching and no-op.
     */
    private final BroadcastReceiver displayStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                reconcileScreenState(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
            }
            catch (Exception e) {
                handleException(e);
            }
        }
    };
    private Handler bannerHandler = new Handler(Looper.getMainLooper());
    private Runnable bannerFadeRunnable;
    private String currentTrackId = null;
    private Runnable videoWatchdogRunnable;
    private String videoWatchdogAssetId = "";
    private File videoWatchdogFile;
    private WeakReference<MainActivity> videoWatchdogActivityReference;
    /** Which rung of the display ladder the armed watchdog is watching; see {@link DisplayStage}. */
    private DisplayStage videoWatchdogStage = DisplayStage.ORIGINAL;
    private int videoWatchdogLastPosition = -1;
    private long videoWatchdogLastProgressMs = 0L;
    private boolean videoWatchdogRecoverAttempted = false;
    /** Start of the window the playback-rate estimate is measured across. */
    private long videoRateAnchorMs = 0L;
    private int videoRateAnchorPosition = -1;
    /** When the watchdog last read the position, so progress can be judged per poll. */
    private long videoWatchdogPrevPollMs = 0L;
    /** True after Immich slideshow / MQTT / version check have been started for this resume. */
    private boolean coreLoopStarted = false;
    /**
     * The single canonical rotation-timer Runnable. Handler callbacks are matched by Runnable
     * identity, and a method reference (this::runOnTimer) may yield a fresh lambda object on
     * each evaluation — posting one instance and removing a different one would leave a
     * duplicate pending tick (forked timer chain, rapid successive flips).
     */
    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            runOnTimer();
        }
    };
    /** The pending tick was explicitly cleared (onPause / network pause); the next start must re-arm it. */
    private boolean timerStopped = false;
    /** Rotation ticks since process start; a spike here means the timer chain forked. */
    private int timerTickCount = 0;
    /** Epoch ms of the next scheduled rotation tick; 0L when the rotation timer is stopped. */
    private long nextRotationAtMs = 0L;
    /** Avoid spamming "Waiting for network…" while still offline. */
    private boolean networkWaitNotified = false;
    /** Last percent value logged for the in-flight download; -1 until the first progress line. Guarded by downloadProgressLogLock. */
    private long lastLoggedDownloadPercent = -1L;
    /** Epoch ms of the last download-progress logcat line; 0L before the first. Guarded by downloadProgressLogLock. */
    private long lastDownloadProgressLogAtMs = 0L;
    private final Object downloadProgressLogLock = new Object();

    /** Liveness ladder; runs on its own timer thread, so it keeps working if we are wedged. */
    private KioskWatchdog kioskWatchdog;
    /**
     * When the display loop last did something visible — a rotation that reached the screen, or a
     * manual {@code next}. Deliberately also refreshed when the asset turns out to be the one
     * already on screen: the watchdog judges loop liveness, not decodability, and a one-asset pool
     * must not look like a wedge.
     */
    private volatile long lastRotationActivityMs = 0L;
    /** Start of the in-flight asset download; 0 when nothing is downloading. */
    private volatile long downloadStartedAtMs = 0L;
    /** Last byte-level progress event; lets a dead download stop counting as "in flight". */
    private volatile long downloadProgressAtMs = 0L;
    /**
     * OkHttp's read timeout is 25s, so past this much silence the response body is gone (finished,
     * failed, or abandoned) even though no {@code done} event ever arrived. Without this the marker
     * of one failed download would look like a permanent stall.
     */
    private static final long DOWNLOAD_PROGRESS_SILENCE_MS = 120_000L;
    /** Last ack from the main thread to a watchdog probe. */
    private volatile long mainLoopAckAtMs = 0L;
    /** Bumped on the main thread by every probe acknowledgement; see {@link #mainLoopProgressToken()}. */
    private volatile long mainLoopAcks = 0L;
    /** When we first noticed we had no network; 0 while connected. */
    private volatile long offlineSinceMs = 0L;
    /** True while the activity is not in the foreground (settings dialog, screen off by policy). */
    private volatile boolean activityPaused = true;
    private final Runnable mainLoopProbeAck = new Runnable() {
        @Override
        public void run() {
            mainLoopAckAtMs = System.currentTimeMillis();
            mainLoopAcks++;
        }
    };
    /** Guards against two repairs overlapping; the watchdog ticks faster than adbd round-trips. */
    private volatile boolean repairRunning = false;
    /** One vendor-OTA silencing attempt per process — see {@link #silenceVendorUpdater()}. */
    private volatile boolean vendorUpdaterAttempted = false;
    /** Whether this window holds input focus — the precondition for every key binding. */
    private volatile boolean windowFocused = false;
    /** Simple class name of the view holding focus, or "none". Kept on the UI thread only. */
    private volatile String focusedView = "none";
    /** The last key this window was actually offered, so a dead keyboard reads as one in /state. */
    private volatile int lastKeyEventCode = 0;
    private volatile long lastKeyEventAtMs = 0L;

    @SuppressLint({"ServiceCast", "WrongConstant"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        mediaPlayerVideoView = findViewById(R.id.videoView);
        tsVideoView = findViewById(R.id.tsVideoView);
        debugOverlay = findViewById(R.id.debugOverlay);
        progressIndicator = findViewById(R.id.progressIndicator);
        // This window needs a focusable view of its own, or focus stays wherever an editor left it:
        // hardware keys are then routed to that editor's input connection and never reach
        // dispatchKeyEvent, which silently kills C, Space and the soft buttons.
        findViewById(R.id.rootView).requestFocus();

        mediaPlayerController = new MediaPlayerController(mediaPlayerVideoView);
        tsPlayerController = new TsPlayerController(tsVideoView);
        preferTsPlayer = BuildConfig.USE_TSPLAYER && TsPlayerNative.isAvailable();
        videoPlayer = preferTsPlayer ? tsPlayerController : mediaPlayerController;
        Log.i(TAG, "video player: " + (preferTsPlayer ? "TsPlayer" : "MediaPlayer")
                + " (USE_TSPLAYER=" + BuildConfig.USE_TSPLAYER
                + " native=" + TsPlayerNative.isAvailable() + ")");

        // Hide the inactive SurfaceView/TextureView so only one compositor path is live.
        if (preferTsPlayer) {
            mediaPlayerVideoView.setVisibility(View.GONE);
        } else {
            tsVideoView.setVisibility(View.GONE);
        }

        progressIndicator.setVisibility(View.INVISIBLE);
        progressIndicator.setTrackColor(Color.argb(80,255,255,255));
        progressIndicator.setIndicatorColor(Color.argb(144,255,255,255));

        ViewCompat.setElevation(progressIndicator, ViewCompat.getElevation(imageView)+1);

        if (BuildConfig.DEBUG) {
            progressIndicator.setBackgroundColor(Color.blue(200));
            progressIndicator.setDrawingCacheBackgroundColor(Color.green(255));
        }

        sActivityCreateCount++;
        this.capabilities = HardwareCapabilitiesFactory.detect(Build.DEVICE, Build.MODEL);
        Log.i(TAG, "hardware: " + capabilities.platform() + " (device=" + Build.DEVICE
                + ", model=" + Build.MODEL + ")");
        // Settings first: the brightness manager restores the user's level from its store, and a
        // frame that never restores it is a frame that dims itself after the next restart.
        this.settingsManager = new SettingsManager(this);
        Log.i(TAG, "created: activity #" + sActivityCreateCount + " (uptime "
                + ((System.currentTimeMillis() - PROCESS_START_MS) / 1000L) + "s)");
        this.brightnessManager = new BrightnessManager(this,
                (SensorManager) getSystemService(SENSOR_SERVICE), capabilities, this.settingsManager);
        // Put the restored level on the panel now rather than at the first rotation tick, which can
        // be the whole interval away: a frame that comes back dim is a frame that looks broken.
        applyCurrentBrightness();
        this.eventManager = new EventManager(this, capabilities);
        this.updateManager = new UpdateManager(this, this, this.settingsManager);
        this.mediaManager = new MediaManager(this, this.settingsManager, this);
        this.connectionManager = new ConnectionManager(this);

        // Always-on LAN web server (debug / control / media upload).
        // The process's single AppLogger is built in Eo1Application, so the ring already holds the
        // pre-onCreate history by the time a crash report asks for its lead-up.
        this.appLogger = Eo1Application.loggerFor(this);
        if (this.appLogger == null) {
            // Only reachable if Eo1Application#onCreate itself failed.
            this.appLogger = new AppLogger(this);
        }
        if (CrashReporter.get() == null) {
            // ...and if its install failed too, the frame would otherwise be back on the framework
            // handler: a crash dialog on an unattended panel, and no relaunch alarm.
            CrashReporter.install(getApplicationContext());
        }
        CrashReporter reporter = CrashReporter.get();
        if (reporter != null) {
            // Either way the reporter points at the one live logger, not a discarded one.
            reporter.setLogger(this.appLogger);
        }
        // Remove stale upload temp dirs from a previously killed process. Only the first
        // onCreate of a given process may do this: after an Activity recreation the process
        // is still alive and an in-flight upload worker may own a live incoming_* dir.
        if (!sStaleIncomingCleaned) {
            sStaleIncomingCleaned = true;
            UploadedMedia.cleanStaleIncomingDirs(UploadedMedia.dirFor(this));
        }
        this.webServer = new WebServer(this);
        this.webServer.start();

        // Load config before the web server can answer /state and before startup update
        // reconciliation; configureMqttManager() no longer owns the load.
        settingsManager.loadConfiguration();
        updateManager.reconcileOnStartup();

        pendingIntent = PendingIntent.getActivity(
                getBaseContext(),
                0,
                new Intent(getIntent()),
                getIntent().getFlags());

        configureMqttManager();

        mainLoopAckAtMs = System.currentTimeMillis();
        // Start the staleness clock at process start: a frame that has never gotten anything on
        // screen — relaunched into a dead network, which is exactly the 2026-10-13 state — must age
        // out and escalate too, not read as "healthy because nothing has happened yet".
        lastRotationActivityMs = mainLoopAckAtMs;
        this.kioskWatchdog = new KioskWatchdog(this);
        this.kioskWatchdog.start();

        // The physical button on the EO2 is an OS-intercepted KEY_POWER: the display can
        // go dark or light without any key event reaching this activity. Watch for it.
        if (capabilities.supportsPowerButton()) {
            IntentFilter displayStateFilter = new IntentFilter();
            displayStateFilter.addAction(Intent.ACTION_SCREEN_ON);
            displayStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(displayStateReceiver, displayStateFilter);
        }

        silenceVendorUpdater();
    }

    private void configureMqttManager() {
        try {
            // Construct MQTT broker string from settings (loaded in onCreate, before this runs)
            String mqttProtocol = settingsManager.getConfiguration().mqttProtocol;
            String mqttHost = settingsManager.getConfiguration().mqttHost;
            int mqttPort = settingsManager.getConfiguration().mqttPort;
            if (mqttProtocol == null || mqttProtocol.isEmpty()) mqttProtocol = "tcp";
            if (mqttPort <= 0) mqttPort = 1883;

            if (mqttHost != null && !mqttHost.isEmpty()) {

                String mqttBroker = String.format("%s://%s:%d", mqttProtocol, mqttHost, mqttPort);

                this.mqttManager = new MqttManager(mqttBroker, new PlaybackListener() {

                    @Override
                    public void onPlaybackStarted(Payload payload) {
                        runOnUiThread(() -> {
                            var duration = payload.currentTrack.duration;
                            var title = payload.currentTrack.title;
                            var artist = payload.currentTrack.artist;
                            var groupName = payload.groupName;
                            var trackId = groupName + "_" + title + "_" + artist + "_" + duration; // crude unique id

                            var titleView = (TextView) findViewById(R.id.songTitleLabel);
                            var artistView = (TextView) findViewById(R.id.songArtistLabel);
                            var deviceLabel = (TextView) findViewById(R.id.deviceLabel);
                            var bannerView = (RelativeLayout) findViewById(R.id.sonosBannerView);

                            titleView.setText(title);
                            artistView.setText(artist);
                            deviceLabel.setText(groupName);
                            bannerView.setVisibility(View.VISIBLE);
                            bannerView.setAlpha(1f);

                            // Cancel previous timer if track changed
                            if (bannerFadeRunnable != null) {
                                bannerHandler.removeCallbacks(bannerFadeRunnable);
                            }
                            currentTrackId = trackId;

                            // Parse duration string (e.g., "03:45")
                            long durationMs = 120 * 1000L;
                            if (duration != null && duration.matches("\\d{1,2}:\\d{2}:\\d{2}")) {
                                String[] parts = duration.split(":");
                                int hours = Integer.parseInt(parts[0]);
                                int min = Integer.parseInt(parts[1]);
                                int sec = Integer.parseInt(parts[2]);
                                durationMs = (hours * 360L + min * 60L + sec) * 1000L;
                            }
                            if (durationMs > 0) {
                                bannerFadeRunnable = () -> {
                                    // Only fade if the same track is still displayed
                                    if (currentTrackId != null && currentTrackId.equals(trackId)) {
                                        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
                                        fadeOut.setDuration(1000);
                                        fadeOut.setFillAfter(true);
                                        bannerView.startAnimation(fadeOut);
                                        bannerView.setVisibility(View.GONE);
                                    }
                                };
                                bannerHandler.postDelayed(bannerFadeRunnable, durationMs);
                            }
                        });
                    }

                    @Override
                    public void onPlaybackStopped(Payload payload) {
                        runOnUiThread(() -> {
                            if (payload == null || payload.currentTrack == null) {
                                return;
                            }
                            var duration = payload.currentTrack.duration;
                            var title = payload.currentTrack.title;
                            var artist = payload.currentTrack.artist;
                            var groupName = payload.groupName;
                            var trackId = groupName + "_" + title + "_" + artist + "_" + duration; // crude unique id

                            // Cancel previous timer if track changed
                            if (bannerFadeRunnable != null && currentTrackId.equals(trackId)) {
                                bannerHandler.removeCallbacks(bannerFadeRunnable);
                                bannerHandler.post(bannerFadeRunnable);
                            }
                        });
                    }

                });
            }
        }
        catch (Exception e) {
            handleException(e);
        }
    }

    /**
     * This function is run every time the timer fires.
     */
    private void runOnTimer() {
        timerTickCount++;
        // Clamp: interval is user-editable (the options UI accepts any integer), and a persisted
        // 0/negative value would otherwise reschedule in a tight loop and burn through assets.
        long delayMs = MILLIS * Math.max(1, settingsManager.getConfiguration().interval);
        Log.i(TAG, "runOnTimer: tick " + timerTickCount + " (next in " + delayMs + "ms)");
        handler.postDelayed(timerTick, delayMs);
        nextRotationAtMs = System.currentTimeMillis() + delayMs;
        logScreenAndTimerState();
        if (brightnessManager.getShouldTheScreenBeOn()) {
            applyCurrentBrightness();
            lastRotationActivityMs = System.currentTimeMillis();
            mediaManager.showNextImage(this);
        } else {
            Log.i(TAG, "runOnTimer: skipping showNextImage (screen should be off / quiet hours)");
        }
        // Self-update: interval-gated check, plus auto-retry of a staged-but-uninstalled APK.
        updateManager.checkIfDue();
        updateManager.installStaged(false);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // Recorded before anything acts on the key, so a report of "the keyboard stopped working" is
        // answerable from /state alone: if lastKeyAtMs never moves, this window was never offered the
        // key — which is a focus problem, not a mapping one.
        if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
            View focus = getCurrentFocus();
            focusedView = focus == null ? "none" : focus.getClass().getSimpleName();
            lastKeyEventCode = event.getKeyCode();
            lastKeyEventAtMs = System.currentTimeMillis();
            debugInformationProvided(new DebugInformation("key",
                    android.view.KeyEvent.keyCodeToString(event.getKeyCode())
                            + " with focus " + focusedView));
            if (this.eventManager != null && this.eventManager.onKeyDown(event.getKeyCode())) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        this.windowFocused = hasFocus;
        if (hasFocus) {
            View focus = getCurrentFocus();
            this.focusedView = focus == null ? "none" : focus.getClass().getSimpleName();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.activityPaused = false;

        // If this process fired an install and is still alive, the install did not take
        // effect — roll the state back to STAGED so the next tick can retry.
        updateManager.reconcileInstallOutcome();

        if (BuildConfig.DEBUG) { hideSystemUI(); }

        debugOverlay.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);

        if (webView != null && lastVisibleView == webView) {
            webView.onResume();
            // A page left mid-load by onPause gets its fall-through timer back; without it a
            // stalled load would hold the screen with nothing on it.
            if (!webLoadProducedPaint && !webUrlOnScreen.isEmpty()) {
                startWebLoadWatchdog();
            }
        }

        int permissionCheckStorage = ContextCompat.checkSelfPermission(MainActivity.this, WRITE_EXTERNAL_STORAGE);
        if (permissionCheckStorage != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions( MainActivity.this, new String[]{WRITE_EXTERNAL_STORAGE}, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    0);
        }

        if (this.settingsManager.isSetupDialogIfNeeded()) {
            showConfigurationUI();
        } else {
            startCoreLoopIfNetworkReady();
        }

        this.connectionManager.registerListener(this);

        // Re-seeded every resume so a frame that was named before this process started still says
        // which one it is. Unset stays unmentioned: no line, same overlay as before the field.
        String deviceName = deviceName();
        if (!deviceName.isEmpty()) {
            debugInformationProvided(new DebugInformation(
                    SettingsManager.DEBUG_KEY_DEVICE_NAME, deviceName));
        }
        debugInformationProvided(new DebugInformation("version", BuildFacts.version()));
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key),
                this.connectionManager.isNetworkAvailable() ? "connected" : "waiting for network"));
    }

    /**
     * Start Immich slideshow, quiet hours, server check, and MQTT once the link is up.
     * Idempotent for a given resume cycle; no-ops while setup dialog is showing or offline.
     */
    private void startCoreLoopIfNetworkReady() {
        if (settingsManager.isSetupDialogIfNeeded()) {
            Log.i(TAG, "startCoreLoop: skipped (setup dialog needed)");
            return;
        }
        if (!connectionManager.isNetworkAvailable()) {
            Log.i(TAG, "startCoreLoop: network unavailable, pausing");
            pauseCoreLoopForNetwork();
            return;
        }
        if (coreLoopStarted) {
            if (!timerStopped) {
                Log.i(TAG, "startCoreLoop: timer already armed, skipping re-arm");
                return;
            }
            // Timer was cleared in onPause / pauseCoreLoopForNetwork — re-arm at a full
            // interval instead of an immediate second fetch.
            long delayMs = MILLIS * Math.max(1, settingsManager.getConfiguration().interval);
            Log.i(TAG, "startCoreLoop: re-arm timer in " + delayMs + "ms");
            handler.removeCallbacks(timerTick);
            handler.postDelayed(timerTick, delayMs);
            nextRotationAtMs = System.currentTimeMillis() + delayMs;
            timerStopped = false;
            // onPause cancels quietHoursTimer; this resume path must restore the quiet-hours
            // schedule (and the current screen state), otherwise 22:00/07:00 transitions stop
            // firing until a settings save or app restart. Idempotent after a network blip,
            // where the timer was not canceled, and self-corrects a missed transition.
            setupQuietHours();
            applyScheduledScreenState();
            return;
        }

        Log.i(TAG, "startCoreLoop: starting Immich slideshow / quiet hours / MQTT");
        coreLoopStarted = true;
        networkWaitNotified = false;
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), "connected"));

        handler.removeCallbacks(timerTick);
        handler.post(timerTick);
        nextRotationAtMs = System.currentTimeMillis();
        timerStopped = false;
        setupQuietHours();
        applyScheduledScreenState();
        connectMqttIfConfigured();
    }

    /**
     * Stop the Immich timer while offline so we do not hammer the server before Wi‑Fi is ready.
     */
    private void pauseCoreLoopForNetwork() {
        Log.i(TAG, "pauseCoreLoop: waiting for network (wasStarted=" + coreLoopStarted + ")");
        // Keep coreLoopStarted=true so reconnect takes the re-arm path (next flip after a
        // full interval) instead of the fresh-start path, which would fire an immediate
        // showNextImage on every Wi-Fi blip.
        handler.removeCallbacks(timerTick);
        timerStopped = true;
        nextRotationAtMs = 0L;
        logScreenAndTimerState();
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), "waiting for network"));
        if (!networkWaitNotified) {
            networkWaitNotified = true;
            Toast.makeText(this, "Waiting for network…", Toast.LENGTH_SHORT).show();
        }
    }

    private void connectMqttIfConfigured() {
        if (this.mqttManager == null) {
            return;
        }
        try {
            String mqttUser = settingsManager.getConfiguration().mqttUser;
            String mqttPassword = settingsManager.getConfiguration().mqttPassword;
            if (mqttPassword == null) {
                mqttPassword = "";
            }
            this.mqttManager.connect(mqttUser, mqttPassword);
        } catch (Exception e) {
            handleException(e);
        }
    }

    @Override
    protected void onPause() {
        this.activityPaused = true;
        cancelVideoWatchdog();
        cancelWebLoadWatchdog();
        if (webView != null && lastVisibleView == webView) {
            webView.onPause();
        }
        // Keep coreLoopStarted so onResume only re-arms the timer instead of
        // kicking a second immediate showNextImage (cold-start pause/resume thrash).
        handler.removeCallbacks(timerTick);
        timerStopped = true;
        nextRotationAtMs = 0L;
        this.quietHoursTimer.cancel();
        this.quietHoursTimer.purge();
        this.connectionManager.unregisterListener(this);
        try {
            if (this.mqttManager != null) {
                this.mqttManager.disconnect();
            }
        }
        catch (Exception e) {
            handleException(e);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (this.kioskWatchdog != null) {
            this.kioskWatchdog.stop();
        }
        if (this.brightnessManager != null) {
            // Recreation would otherwise leave the old light-sensor listener firing for an Activity
            // that no longer exists.
            this.brightnessManager.shutdown();
        }
        if (webView != null) {
            WebView view = webView;
            webView = null;
            view.stopLoading();
            view.destroy();
        }
        if (this.webServer != null) {
            this.webServer.shutdown();
        }
        if (capabilities.supportsPowerButton()) {
            unregisterReceiver(displayStateReceiver);
        }
        super.onDestroy();
    }

    @Override
    public void brightnessChanged(float targetScreenBrightness) {
        writeScreenBrightness(targetScreenBrightness);
    }

    /**
     * The only thing in the app that touches {@code screenBrightness}. It writes exactly what it is
     * handed, because the upward-only {@code Math.max} this replaced was the sole reason a BACK
     * tap survived the next rotation tick — and it stopped working the moment a recreated Activity
     * handed back fresh attributes reading {@code BRIGHTNESS_OVERRIDE_NONE}, which is when the
     * panel dropped to whatever a stale cache held.
     */
    private void writeScreenBrightness(float brightness) {
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.screenBrightness = brightness;
        getWindow().setAttributes(layoutParams);
        lastAppliedBrightness = brightness;
    }

    /** Asks the brightness manager what the panel should be showing and puts that on the hardware. */
    private void applyCurrentBrightness() {
        writeScreenBrightness(brightnessManager.currentBrightness());
    }

    @Override
    public void checkForUpdates() {
        this.updateManager.checkForUpdates(this);
    }

    /**
     * If the screen is on, turn it off and vice versa. Handle app logic appropriately
     */
    @Override
    public void toggleScreenOn() {
        this.brightnessManager.toggleShouldTheScreenBeOn();

        if (this.brightnessManager.getShouldTheScreenBeOn()) {
            turnScreenOn();
        }
        else {
            turnScreenOff();
        }
    }

    /**
     * Syncs app screen state with the display when they diverge. Only the physical power
     * button can cause a divergence; every in-app path (F2/web toggle, quiet hours,
     * brightness adjust) already leaves shouldTheScreenBeOn matching the panel it drives.
     */
    private void reconcileScreenState(boolean displayOn) {
        boolean appOn = this.brightnessManager.getShouldTheScreenBeOn();
        if (displayOn && !appOn) {
            Log.i(TAG, "reconcileScreenState: display woken outside app control; turning screen on");
            this.brightnessManager.setShouldTheScreenBeOn(true);
            turnScreenOn();
        }
        else if (!displayOn && appOn) {
            Log.i(TAG, "reconcileScreenState: display slept outside app control; turning screen off");
            this.brightnessManager.setShouldTheScreenBeOn(false);
            turnScreenOff();
        }
    }

    /**
     * Turn off the screen to conserve as much energy as possible without shutting down the device
     */
    private void turnScreenOff() {
        cancelVideoWatchdog();
        Window window = this.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (screenOffWakeLock != null) {
            screenOffWakeLock.release();
            debugInformationProvided(new DebugInformation("screenOffWakeLock", "released"));
        }

        videoPlayer.setVisibility(View.INVISIBLE);
        imageView.setVisibility(View.INVISIBLE);
        // Also stops the page's JavaScript and repaints for the whole quiet window.
        hideWebView();
    }

    /**
     * Turn on the screen and gain control over screen brightness
     */
    @SuppressLint("WakelockTimeout")
    private void turnScreenOn() {
        Window window = this.getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            PowerManager powerManager = (PowerManager) this.getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                screenOffWakeLock = powerManager.newWakeLock(ACQUIRE_CAUSES_WAKEUP | SCREEN_DIM_WAKE_LOCK, "com.aphex3k.eo1:WAKE_LOCK");
                if (screenOffWakeLock != null) {
                    screenOffWakeLock.acquire();
                    debugInformationProvided(new DebugInformation("screenOffWakeLock", "acquired"));
                }
            }
        } catch (Exception e) {
            handleException(e);
        }

        if (lastVisibleView != null) {
            lastVisibleView.setVisibility(View.VISIBLE);
        }
        if (webView != null && lastVisibleView == webView) {
            // hideWebView() paused the page; making it visible again would not unfreeze it.
            webView.onResume();
        }
        applyCurrentBrightness();
    }

    @Override
    public void showConfigurationUI() {
        this.runOnUiThread(() -> {
            Fragment existing = getSupportFragmentManager().findFragmentByTag(OPTIONS_DIALOG_TAG);
            if (existing != null) {
                if (existing.isAdded()) {
                    return;
                }
                // Restored from a recreation and never given its SettingsManager back —
                // OptionsDialogFragment has no no-arg constructor, so it is half-built. Drop it
                // rather than show a second window under the same tag.
                getSupportFragmentManager().beginTransaction()
                        .remove(existing)
                        .commitAllowingStateLoss();
            }
            OptionsDialogFragment dialog = new OptionsDialogFragment(this.settingsManager);
            dialog.show(getSupportFragmentManager(), OPTIONS_DIALOG_TAG);
        });
    }

    @Override
    public void adjustMinimumBrightness() {
        if (!capabilities.supportsScreenBrightness()) {
            Log.i(TAG, "adjustMinimumBrightness: ignored (" + capabilities.platform() + " has no screen brightness control)");
            return;
        }
        // A tap is also "wake up" — quiet hours may have the panel dark on a level the user
        // already chose. Everything after that is the manager's: it steps, persists, and applies.
        this.brightnessManager.setShouldTheScreenBeOn(true);
        this.brightnessManager.raiseMinimumBrightnessStep();
    }

    @Override
    public void showNextImage() {
        if (this.brightnessManager.getShouldTheScreenBeOn()) {
            this.mediaManager.showNextImage(this);
        }
    }

    @Override
    public void openSystemSettings() {
        //noinspection deprecation
        startActivityForResult(new Intent(android.provider.Settings.ACTION_SETTINGS), 0);
    }

    @Override
    public void openUpdateWebsite() {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aphex3k/EO1/releases"));
        startActivity(browserIntent);
    }

    @Override
    public void settingsChanged() {
        coreLoopStarted = false;
        networkWaitNotified = false;
        handler.removeCallbacks(timerTick);
        nextRotationAtMs = 0L;
        mediaManager.invalidatePoolIfStale();
        startCoreLoopIfNetworkReady();
    }

    private void applyScheduledScreenState() {
        if (settingsManager == null || brightnessManager == null) {
            return;
        }

        Configuration configuration = settingsManager.getConfiguration();
        String tzId = configuration.selectedTimeZoneId;
        Calendar localNow = QuietHours.calendarInTimeZone(tzId);
        boolean inQuietHours = QuietHours.isQuiet(quietCronExpressions, localNow);
        boolean shouldBeOn = !inQuietHours;

        Log.i(TAG, "quietHours: tz=" + QuietHours.resolveTimeZone(tzId).getID()
                + " localTime=" + String.format(Locale.US, "%02d:%02d",
                        localNow.get(Calendar.HOUR_OF_DAY), localNow.get(Calendar.MINUTE))
                + " windows=" + quietCronExpressions
                + " inQuiet=" + inQuietHours
                + " screenOn=" + shouldBeOn);

        if (brightnessManager.getShouldTheScreenBeOn() != shouldBeOn) {
            brightnessManager.setShouldTheScreenBeOn(shouldBeOn);
            if (shouldBeOn) {
                turnScreenOn();
            } else {
                turnScreenOff();
            }
        }
        logScreenAndTimerState();
    }

    /** Logcat debug lines for the screen's should-be state and the rotation-timer state. */
    private void logScreenAndTimerState() {
        Log.i(TAG, "state: screen should be "
                + (brightnessManager.getShouldTheScreenBeOn() ? "ON" : "OFF"));
        if (timerStopped) {
            Log.i(TAG, "state: rotation timer not running");
        } else {
            long remainingMs = Math.max(0L, nextRotationAtMs - System.currentTimeMillis());
            Log.i(TAG, "state: next rotation in " + ((remainingMs + 999L) / 1000L) + "s");
        }
    }

    private void setupQuietHours() {

        if (this.settingsManager == null) {
            return;
        }

        try {
            quietHoursTimer.cancel();
            quietHoursTimer.purge();
        }
        catch (Exception e) {
            handleException(e);
        }

        final Configuration configuration = this.settingsManager.getConfiguration();
        final String tzId = configuration.selectedTimeZoneId;

        final List<CronExpression> expressions = new ArrayList<>();
        for (String raw : configuration.quietHoursOrEmpty()) {
            CronExpression parsed = CronExpression.parse(raw);
            if (parsed != null) {
                expressions.add(parsed);
            } else {
                debugInformationProvided(new DebugInformation("quietHours", "Ignored invalid cron expression: " + raw));
            }
        }
        this.quietCronExpressions = expressions;
        this.lastScheduledQuietState = QuietHours.isQuiet(expressions, QuietHours.calendarInTimeZone(tzId));

        if (expressions.isEmpty()) {
            return;
        }

        quietHoursTimer = new Timer(true);

        final TimeZone quietTimeZone = QuietHours.resolveTimeZone(tzId);
        final DateFormat debugDateFormatter = new SimpleDateFormat("MMM d, yyyy HH:mm a", Locale.US);
        debugDateFormatter.setTimeZone(quietTimeZone);

        // Fire at the next whole-minute boundary in wall-clock time, then every minute after.
        // Evaluating the wall clock each tick is DST-correct by construction (a fixed 24h
        // period drifts across DST transitions), and cheap: one calendar fill + a few mask
        // checks per minute. The state only changes at window boundaries, and the state
        // applied there is idempotent, so a stray duplicate tick is harmless.
        final long firstFire = (System.currentTimeMillis() / 60000L + 1L) * 60000L;
        final List<String> summary = new ArrayList<>();
        for (CronExpression expression : expressions) {
            summary.add(expression.raw());
        }
        Log.i(TAG, "Quiet hours schedule armed: " + summary + " (" + quietTimeZone.getID() + ")");

        quietHoursTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    boolean inQuiet = QuietHours.isQuiet(expressions, QuietHours.calendarInTimeZone(tzId));
                    if (inQuiet != lastScheduledQuietState) {
                        lastScheduledQuietState = inQuiet;
                        handler.post(MainActivity.this::applyScheduledScreenState);
                        String key = inQuiet ? "startQuietHours" : "endQuietHours";
                        String text = (inQuiet ? "Quiet hours started at " : "Quiet hours ended at ")
                                + debugDateFormatter.format(new Date())
                                + " (" + quietTimeZone.getID() + ")";
                        debugInformationProvided(new DebugInformation(key, text));
                    }
                }
                catch (Exception e) {
                    handleException(e);
                }
            }
        }, new Date(firstFire), 60000L);

        debugInformationProvided(new DebugInformation("quietHoursSchedule",
                "Quiet hours armed (" + summary + "), checked every minute (" + quietTimeZone.getID() + ")"));
    }


    @Override
    public void updateChecked(Boolean updateAvailable) {
        debugInformationProvided(new DebugInformation("Update Available", String.valueOf(updateAvailable)));
    }

    @Override
    public void handleException(Exception e) {

        this.runOnUiThread(() -> {
            if (e.getClass() == InvalidCredentialsException.class) {
                if (settingsManager.isSetupDialogIfNeeded()) {
                    showConfigurationUI();
                }
                Toast.makeText(MainActivity.this, "User authentication failure. Check your configuration.", Toast.LENGTH_LONG).show();
            }
            if (e.getClass() == MalformedJsonException.class) {
                Toast.makeText(MainActivity.this, "It appears there is an issue with the format of the configuration file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            if (e.getClass() == AuthenticationFailedException.class) {
                Toast.makeText(MainActivity.this, "Server failed authentication: Invalid username or password.", Toast.LENGTH_SHORT).show();
            }
            if (e.getClass() == AuthenticationUnavailableException.class) {
                Toast.makeText(MainActivity.this, "Server authentication unavailable. Check your server setup.", Toast.LENGTH_SHORT).show();
            }
            if (e.getClass() == NoMediaFoundException.class) {
                Toast.makeText(MainActivity.this, "No Media found", Toast.LENGTH_LONG).show();
            }
            if (e.getClass() == BackendUnavailableException.class) {
                String cause = e.getCause() != null ? String.valueOf(e.getCause()) : "";
                Toast.makeText(MainActivity.this,
                        "Media backend unavailable: " + (cause.isEmpty() ? e.getMessage() : cause),
                        Toast.LENGTH_LONG).show();
            }
        });
        Log.e(TAG, e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : ""), e);

        if (this.appLogger != null) {
            this.appLogger.error(TAG, e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
        }
        debugInformationProvided(new DebugInformation("Last Exception", e.toString()));
    }

    /**
     * A backend is running degraded rather than broken: toast it, and let
     * {@link #debugInformationProvided} record it where it outlives logcat's ~13 minute
     * ring (the /logs page and, in debug builds, the overlay).
     */
    @Override
    public void warnUser(String message) {
        this.runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
        debugInformationProvided(new DebugInformation("compatibility", message));
    }

    public void debugInformationProvided(@NonNull DebugInformation debugInformation) {

        this.runOnUiThread(() -> {
            if (BuildConfig.DEBUG) {
                synchronized (this.debugInformation) {
                    this.debugInformation.put(debugInformation.getKey(), debugInformation.getValue());

                    Set<Map.Entry<String, String>> set = this.debugInformation.entrySet();

                    ArrayList<String> debugList = new ArrayList<>();

                    for (Map.Entry<String, String> info : set) {
                        StringBuilder text = new StringBuilder();

                        text = text.append(info.getKey().trim()).append(": ").append(info.getValue().trim()).append("\n");

                        int index = debugList.size();

                        for (String s : debugList) {
                            if (s.length() > text.length()) {
                                index = debugList.indexOf(s);
                                break;
                            }
                        }
                        debugList.add(index, text.toString());
                    }

                    StringBuilder debugText = new StringBuilder();

                    for (String text : debugList) {
                        debugText.append(text);
                    }

                    this.debugOverlay.setText(debugText.toString().trim());
                }
            }
        });
        Log.i(TAG, debugInformation.getKey() + ": " + debugInformation.getValue());
        if (this.appLogger != null) {
            this.appLogger.info(TAG, debugInformation.getKey() + ": " + debugInformation.getValue());
        }
    }

    @Override
    public void displayPicture(File file, String assetId) {
        displayPicture(file, assetId, DisplayStage.ORIGINAL);
    }

    @Override
    public void displayFallbackPicture(File file, String assetId) {
        displayPicture(file, assetId, DisplayStage.FALLBACK);
    }

    private void displayPicture(File file, String assetId, DisplayStage stage) {

        if (file.getAbsolutePath().equals(lastVisibleAsset)) {
            Log.i(TAG, "displayPicture: skip same asset " + assetId);
            return;
        }

        if (!this.brightnessManager.getShouldTheScreenBeOn()) {
            Log.i(TAG, "displayPicture: skip (screen should be off) asset=" + assetId);
            return;
        }

        lastRotationActivityMs = System.currentTimeMillis();
        debugInformationProvided(new DebugInformation("displayPictures", file.getAbsolutePath()));

        this.runOnUiThread(() -> {
            cancelVideoWatchdog();
            // No video is active while an image shows; a stale video callback (incl. one from a
            // null-assetId fallback stream) must not match activeVideoAssetId.
            activeVideoAssetId = "";

            WeakReference<MainActivity> activityReference = new WeakReference<>(this);

            try {
                // Release so a previous fallback video frees the exclusive Amlogic video
                // pipeline before the next asset may run on TsPlayer.
                videoPlayer.release();
                videoPlayer.setVisibility(View.INVISIBLE);
                if (!MainActivity.this.isDestroyed()) {
                    Glide.with(this).clear(imageView);

                    if (MediaTypeHelper.isGifFile(file)) {
                        Glide.with(this)
                                .asGif()
                                .load(file)
                                .centerCrop()
                                .listener(new RequestListener<GifDrawable>() {
                                    @Override
                                    public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model, @NonNull Target<GifDrawable> target, boolean isFirstResource) {
                                        assetFallback(assetId, activityReference, false, file, stage);
                                        return false;
                                    }

                                    @Override
                                    public boolean onResourceReady(@NonNull GifDrawable resource, @NonNull Object model, Target<GifDrawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                                        resource.setLoopCount(GifDrawable.LOOP_FOREVER);
                                        hideWebView();
                                        imageView.setVisibility(View.VISIBLE);
                                        lastVisibleView = imageView;
                                        lastVisibleAsset = file.getAbsolutePath();
                                        return false;
                                    }
                                })
                                .into(imageView);
                    } else {
                        Glide.with(this)
                                .load(file)
                                .centerCrop()
                                .listener(new RequestListener<Drawable>() {
                                    @Override
                                    public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                                        assetFallback(assetId, activityReference, false, file, stage);
                                        return false;
                                    }

                                    @Override
                                    public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                                        hideWebView();
                                        imageView.setVisibility(View.VISIBLE);
                                        lastVisibleView = imageView;
                                        lastVisibleAsset = file.getAbsolutePath();
                                        return false;
                                    }
                                })
                                .dontAnimate()
                                .into(imageView);
                    }
                }
            }
            catch (Exception e) {
                handleException(e);
                assetFallback(assetId, activityReference, false, file, stage);
            }
        });
    }

    // ------------------------------------------------------------- web-page assets

    /** How a media type is named in the {@code /state} payload. */
    private static String mediaTypeName(@Nullable MediaType type) {
        if (type == MediaType.VIDEO) {
            return "video";
        }
        if (type == MediaType.WEB) {
            return "web";
        }
        return type == MediaType.IMAGE ? "image" : "unknown";
    }

    /**
     * Displays a configured web page fullscreen in the chrome-less browser view. Unlike the two
     * file-based display methods, whether this succeeded is only known later: a page that fails to
     * load or render is reported back through {@link MediaManager#webDisplayFailed} so the cycle
     * advances to a photo. The page is re-fetched on every call — that is the point of showing it.
     */
    @Override
    public void displayWebsite(String url, String assetId) {
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "displayWebsite: no page address for asset " + assetId);
            return;
        }
        if (!this.brightnessManager.getShouldTheScreenBeOn()) {
            Log.i(TAG, "displayWebsite: skip (screen should be off) asset=" + assetId);
            return;
        }

        lastRotationActivityMs = System.currentTimeMillis();
        debugInformationProvided(new DebugInformation("displayWebsite", url));

        this.runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }
            cancelVideoWatchdog();
            // No video is active while a page shows; a stale video callback must not match.
            activeVideoAssetId = "";
            // Asset handoff: free the exclusive Amlogic video pipeline before the browser view.
            videoPlayer.release();
            videoPlayer.setVisibility(View.INVISIBLE);

            if (!ensureWebView()) {
                mediaManager.webDisplayFailed(MainActivity.this, assetId);
                return;
            }

            activeWebAssetId = assetId;
            webUrlOnScreen = url;
            webLoadProducedPaint = false;
            // The image stays loaded (and visible) underneath, so a page that fails to render
            // reveals the last photo instead of a black panel.
            showWebView();
            lastVisibleView = webView;
            lastVisibleAsset = url;

            try {
                webView.loadUrl(url);
            } catch (Exception e) {
                Log.w(TAG, "displayWebsite: could not start loading " + url, e);
                abandonWebPage();
                return;
            }
            startWebLoadWatchdog();
        });
    }

    /**
     * Creates the browser view on first use, inserted just above {@link #imageView} so a page is
     * never covered by the wallpaper while staying underneath the debug overlay and progress bar.
     * Deliberately not in the layout file: a frame with no web backend must never pay for a WebView
     * (it drags in the ROM's browser provider on an ~800MB device).
     *
     * @return false when this ROM has no usable WebView provider; remembered, so the failure does
     *         not repeat on every tick
     */
    private boolean ensureWebView() {
        if (webView != null) {
            return true;
        }
        if (webViewUnavailable) {
            return false;
        }
        try {
            WebView view = new WebView(this);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
            settings.setTextZoom(100);
            // Fit the page to the 1920x1080 panel instead of a 980px viewport with scrollbars.
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setGeolocationEnabled(false);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(false);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setSaveFormData(false);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setDefaultTextEncodingName("utf-8");

            view.setBackgroundColor(Color.BLACK);
            view.setHorizontalScrollBarEnabled(false);
            view.setVerticalScrollBarEnabled(false);
            view.setOverScrollMode(View.OVER_SCROLL_NEVER);
            // Keys belong to EventManager; a focused WebView would eat them.
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
            view.setVisibility(View.INVISIBLE);
            view.setWebViewClient(new KioskWebViewClient());
            view.setWebChromeClient(new KioskWebChromeClient());
            view.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                           String mimetype, long contentLength) {
                    Log.i(TAG, "web: ignored download request for " + url);
                }
            });

            RelativeLayout root = (RelativeLayout) imageView.getParent();
            root.addView(view, root.indexOfChild(imageView) + 1, new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
            webView = view;
            Log.i(TAG, "ensureWebView: kiosk browser view ready");
            return true;
        } catch (Throwable t) {
            // Must not escape: the default handler restarts the process, which on every tick would
            // look like a crash loop caused by the photo pipeline.
            webViewUnavailable = true;
            Log.e(TAG, "ensureWebView: no usable WebView provider on this device; web assets will be skipped", t);
            debugInformationProvided(new DebugInformation("web", "WebView unavailable: " + t));
            return false;
        }
    }

    /** Keeps the page inside the kiosk and reports a page that never rendered. */
    private final class KioskWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
            // Stay inside the kiosk: at this API level any other scheme is handed to an external
            // activity (market:, tel:, intent:, mailto:), which would eject the frame from home.
            return !(lower.startsWith("http://") || lower.startsWith("https://"));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!Objects.equals(url, webUrlOnScreen)) {
                return;
            }
            webLoadProducedPaint = true;
            cancelWebLoadWatchdog();
            Log.i(TAG, "web: rendered " + url);
            debugInformationProvided(new DebugInformation("web", "rendered " + url));
        }

        @Override
        @SuppressWarnings("deprecation") // the non-deprecated overload is API 23+
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (webLoadProducedPaint || !Objects.equals(failingUrl, webUrlOnScreen)) {
                Log.w(TAG, "web: sub-resource failure in " + failingUrl + " (" + errorCode
                        + " " + description + ") — page stays");
                return;
            }
            Log.w(TAG, "web: " + failingUrl + " failed (" + errorCode + "): " + description);
            debugInformationProvided(new DebugInformation("web", "failed " + failingUrl
                    + ": " + description));
            abandonWebPage();
        }
    }

    /** An unattended frame must never wait on a script dialog nobody can dismiss. */
    private final class KioskWebChromeClient extends WebChromeClient {

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                  JsPromptResult result) {
            result.cancel();
            return true;
        }
    }

    /**
     * Gives up on the page that was on screen: the browser view goes away, whatever image is still
     * loaded underneath reappears, and the rotation moves on to the next asset.
     */
    private void abandonWebPage() {
        cancelWebLoadWatchdog();
        String failedAssetId = activeWebAssetId;
        webUrlOnScreen = "";
        activeWebAssetId = "";
        hideWebView();
        imageView.setVisibility(View.VISIBLE);
        lastVisibleView = imageView;
        // Cleared, not set to the image: the screen content changed behind the display methods'
        // same-asset check, so the next tick must be allowed to re-show this very photo.
        lastVisibleAsset = "";
        if (mediaManager != null) {
            mediaManager.webDisplayFailed(this, failedAssetId);
        }
    }

    /** Shows the browser view and lets its page run. */
    private void showWebView() {
        if (webView == null) {
            return;
        }
        webView.setVisibility(View.VISIBLE);
        webView.onResume();
    }

    /**
     * Hides the browser view and stops its page from consuming the CPU.
     *
     * <p>{@code onPause()}/{@code onResume()} rather than {@code pauseTimers()}/{@code
     * resumeTimers()}: those two are static on API 19 and instance methods from API 29 on, so
     * against this compileSdk they would link to a virtual call that does not exist on the frame.
     */
    private void hideWebView() {
        if (webView == null) {
            return;
        }
        webView.onPause();
        webView.setVisibility(View.INVISIBLE);
    }

    private void startWebLoadWatchdog() {
        cancelWebLoadWatchdog();
        webLoadWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                webLoadWatchdogRunnable = null;
                if (webUrlOnScreen.isEmpty() || webLoadProducedPaint) {
                    return;
                }
                Log.w(TAG, "web: " + webUrlOnScreen + " showed nothing within "
                        + WEB_LOAD_TIMEOUT_MS / 1000L + "s; advancing to the next asset");
                debugInformationProvided(new DebugInformation("web", "timeout " + webUrlOnScreen));
                abandonWebPage();
            }
        };
        handler.postDelayed(webLoadWatchdogRunnable, WEB_LOAD_TIMEOUT_MS);
    }

    private void cancelWebLoadWatchdog() {
        if (webLoadWatchdogRunnable != null) {
            handler.removeCallbacks(webLoadWatchdogRunnable);
            webLoadWatchdogRunnable = null;
        }
    }

    /**
     * If the image produces an error of any kind during playback attempt, try to switch to the
     * thumbnail. If that fails as well, load the next asset instead
     * @param assetId the asset in question
     * @param activityReference weak reference to the calling activity
     * @param isVideo true if it is a video
     * @param file file to load
     */
    private void restoreImageViewAfterVideoFailure() {
        cancelVideoWatchdog();
        videoPlayer.release();
        videoPlayer.setVisibility(View.INVISIBLE);
        hideWebView();
        imageView.setVisibility(View.VISIBLE);
        lastVisibleView = imageView;
    }

    /**
     * Which rung of the display ladder a display call belongs to. A failure moves strictly
     * down it — never up, never twice on the same rung — so one bad asset cannot spin the
     * rotation loop: {@link #ORIGINAL} → {@link #ORIGINAL_REPLAY} → {@link #FALLBACK} → flagged.
     */
    private enum DisplayStage {
        /** The asset's original bytes, first attempt. */
        ORIGINAL,
        /** The same original bytes again on the other player engine; after this, the fallback. */
        ORIGINAL_REPLAY,
        /** The backend's degraded source: a preview thumbnail or a transcoded stream. */
        FALLBACK
    }

    private void assetFallback(String assetId, WeakReference<MainActivity> activityReference,
                               boolean isVideo, File file, DisplayStage stage) {
        // assetId may be null (a display that came from no pool asset); Objects.equals keeps
        // this guard null-safe (a bare assetId.equals() would NPE and kill the app).
        if (isVideo && !Objects.equals(assetId, activeVideoAssetId)) {
            return;
        }
        playbackFallback(assetId, activityReference, isVideo, file, stage);
    }

    /**
     * Moves one asset one rung down the display ladder. Nothing is flagged here: an asset is
     * only incompatible with this device once its {@link DisplayStage#FALLBACK} display failed
     * too, which is the rung below.
     */
    private void playbackFallback(String assetId, WeakReference<MainActivity> activityReference,
                                  boolean isVideo, File file, DisplayStage stage) {
        // restoreImageViewAfterVideoFailure is called at each transition that stops showing
        // video, never before a rung that immediately starts another video.
        if (assetId == null) {
            Log.i(TAG, "playbackFallback: no asset for " + (file != null ? file.getName() : "?")
                    + "; deferring rotation to next tick");
            if (!isVideo) {
                mediaManager.removeFromCache(file);
            }
            return;
        }
        if (stage == DisplayStage.FALLBACK) {
            // Both rungs failed. Flag it for the operator — who can regenerate the asset's
            // thumbnails in Immich or drop it from the frame's albums — and let the next tick
            // rotate on. Drop the fallback artifact so a fix on the server is picked up at
            // once instead of being served from a poisoned cache.
            Log.w(TAG, "playbackFallback: " + assetId + " failed with its fallback source too;"
                    + " flagging it as incompatible with this device");
            if (isVideo) {
                restoreImageViewAfterVideoFailure();
            }
            mediaManager.tagAssetAsIncompatible(assetId);
            mediaManager.removeFromCache(file);
            return;
        }
        if (isVideo && stage == DisplayStage.ORIGINAL && file != null
                && mediaManager.isUsableLocalVideo(file)) {
            // The original's bytes are already on disk (routing is content-deterministic, so a
            // re-fetch through /video/playback would be the same bytes): replay them on the
            // other engine rather than re-downloading. ORIGINAL_REPLAY is the rung after this,
            // so a second failure here cannot loop back into another replay.
            Log.i(TAG, "playbackFallback: reusing local original for " + assetId);
            lastVisibleAsset = "";
            displayVideo(file, assetId, DisplayStage.ORIGINAL_REPLAY);
            return;
        }
        if (isVideo) {
            restoreImageViewAfterVideoFailure();
        }
        MainActivity activity = activityReference.get();
        if (activity != null) {
            mediaManager.displayThumbnailAsset(activity, assetId, isVideo);
        }
        // Local uploads may point at the persistent original in filesDir/uploaded;
        // removeFromCache's file-based guards (cache-dir match + owned-name check) make
        // this a no-op for those files.
        mediaManager.removeFromCache(file);
    }

    private void cancelVideoWatchdog() {
        if (videoWatchdogRunnable != null) {
            handler.removeCallbacks(videoWatchdogRunnable);
            videoWatchdogRunnable = null;
        }
        videoWatchdogAssetId = "";
        videoWatchdogFile = null;
        videoWatchdogActivityReference = null;
        videoWatchdogLastPosition = -1;
        videoWatchdogLastProgressMs = 0L;
        videoWatchdogRecoverAttempted = false;
        videoRateAnchorMs = 0L;
        videoRateAnchorPosition = -1;
        videoWatchdogPrevPollMs = 0L;
        // No watchdog, no measurement: TsPlayer skips this arm entirely, so leaving the previous
        // MediaPlayer asset's rate standing would attribute it to a clip that was never watched.
        videoPlaybackRateX100 = -1;
        videoPlaybackRateMinX100 = -1;
        videoPlaybackRateMaxX100 = -1;
        videoPlaybackRateSamples = 0;
    }

    private void startVideoWatchdog(String assetId, File file,
                                    WeakReference<MainActivity> activityReference,
                                    DisplayStage stage) {
        cancelVideoWatchdog();
        // A display with no asset behind it cannot be matched by the same-asset guard, so
        // there is nothing for the watchdog to attribute a stall to.
        if (assetId == null) {
            debugInformationProvided(new DebugInformation("video", "watchdog skipped (no asset id)"));
            return;
        }
        // TsPlayer getCurrentTime/duration are unreliable; position stalls false-trigger
        // MediaPlayer fallback and break looping. TsVideoView owns health via status poll.
        if (videoPlayer == tsPlayerController) {
            debugInformationProvided(new DebugInformation("video", "watchdog skipped (TsPlayer)"));
            return;
        }
        videoWatchdogAssetId = assetId;
        videoWatchdogFile = file;
        videoWatchdogActivityReference = activityReference;
        videoWatchdogStage = stage;
        videoWatchdogLastPosition = -1;
        videoWatchdogLastProgressMs = System.currentTimeMillis();
        videoWatchdogRecoverAttempted = false;
        scheduleVideoWatchdogCheck();
    }

    private void scheduleVideoWatchdogCheck() {
        if (videoWatchdogRunnable != null) {
            handler.removeCallbacks(videoWatchdogRunnable);
        }
        videoWatchdogRunnable = this::runVideoWatchdogCheck;
        handler.postDelayed(videoWatchdogRunnable, VIDEO_WATCHDOG_POLL_MS);
    }

    private void runVideoWatchdogCheck() {
        if (videoWatchdogRunnable == null || videoWatchdogAssetId.isEmpty()) {
            return;
        }

        final String assetId = videoWatchdogAssetId;
        final File file = videoWatchdogFile;
        final WeakReference<MainActivity> activityReference = videoWatchdogActivityReference;
        // Read before anything below cancels the watchdog: the ladder rung belongs to the
        // display that is being watched, not to whatever starts after it.
        final DisplayStage stage = videoWatchdogStage;

        if (!assetId.equals(activeVideoAssetId)
                || !brightnessManager.getShouldTheScreenBeOn()
                || videoPlayer.getVisibility() != View.VISIBLE) {
            cancelVideoWatchdog();
            return;
        }

        int position = videoPlayer.getCurrentPosition();
        long now = System.currentTimeMillis();
        measurePlaybackRate(position, now);

        // Both questions below are about the previous poll, so they have to be answered before the
        // bookkeeping replaces those readings.
        int previousPositionMs = videoWatchdogLastPosition;
        long sincePreviousMs = now - videoWatchdogPrevPollMs;
        videoWatchdogPrevPollMs = now;

        boolean parked = position == previousPositionMs;
        // Media advanced less than half as fast as the clock across one poll interval. Measured per
        // poll rather than from the rate window, which needs VIDEO_RATE_WINDOW_MS to fill and so
        // left the seam open for seconds even after the crawl had begun.
        boolean creeping = !parked && previousPositionMs >= 0 && sincePreviousMs > 0
                && (position - previousPositionMs) * 100L < sincePreviousMs * LOOP_TAIL_RATE_X100;

        if (!parked) {
            videoWatchdogLastPosition = position;
            videoWatchdogLastProgressMs = now;
            videoWatchdogRecoverAttempted = false;
        }

        // The end of the cycle, announced here because nothing else announces it. A clip whose last
        // frames sit past the duration the player reports never raises onCompletion, so
        // setLooping(true) has no end-of-stream to loop on and play() is a no-op while the player
        // still believes it is PLAY — on an EO2 that read as a decoder hung for 8.4 s every lap.
        //
        // Two things hide that boundary. The player's duration is shorter than the media it is
        // holding (23000 ms reported for a 23621 ms file, because the QuickTime mvhd timescale of
        // 600 cannot divide the frame period evenly), so ending on the player's number truncates
        // frames still running. And past that point the position does not freeze, it creeps
        // (23011, 23169, 23374, 23879 ms) — so "did not move between polls" is never true either,
        // and every change-based test re-arms the stall threshold instead of firing. Hence the
        // file's own length where the probe could read one, and no headway meaning stopped OR
        // creeping. Which end to demand depends on which of the two was seen: a position that has
        // not moved at all is at the end as far as the player is concerned, and some files stop dead
        // at the shortened number without ever passing it, so demanding the file's length there
        // would skip this branch and leave the 8 s threshold to notice.
        int reportedMs = videoPlayer.getDuration();
        long fileMs = lastVideoDurationMs;
        int cycleEndMs = parked ? reportedMs : (int) Math.max((long) reportedMs, fileMs);
        // A position absurdly far past the end is a bad reading rather than a finished clip: a
        // freshly created player has been observed reporting values near the epoch, and acting on
        // one every poll would turn the loop into a restart storm.
        if (cycleEndMs > 0 && position >= cycleEndMs
                && position - cycleEndMs <= LOOP_END_SLACK_MS && (parked || creeping)) {
            debugInformationProvided(new DebugInformation("video",
                    "loop restart pos=" + position + " end=" + cycleEndMs
                            + " (" + (parked ? "parked" : "creeping")
                            + " player=" + reportedMs + " file=" + fileMs + ")"
                            + " rate=" + describePlaybackRate()));
            try {
                videoPlayer.seekTo(0);
                videoPlayer.play();
            } catch (Exception e) {
                handleException(e);
            }
            videoWatchdogLastPosition = -1;
            videoWatchdogLastProgressMs = now;
            videoWatchdogRecoverAttempted = false;
            scheduleVideoWatchdogCheck();
            return;
        }

        if (!parked) {
            scheduleVideoWatchdogCheck();
            return;
        }

        if (now - videoWatchdogLastProgressMs < VIDEO_STALL_THRESHOLD_MS) {
            scheduleVideoWatchdogCheck();
            return;
        }

        if (!videoWatchdogRecoverAttempted) {
            videoWatchdogRecoverAttempted = true;
            int duration = videoPlayer.getDuration();
            int seekTarget = position;
            if (duration > 0 && position >= duration - 1000) {
                seekTarget = 0;
            }
            debugInformationProvided(new DebugInformation("video",
                    "stall recover pos=" + position + " seek=" + seekTarget + " duration=" + duration
                            + " rate=" + describePlaybackRate()));
            try {
                videoPlayer.seekTo(seekTarget);
                videoPlayer.play();
            } catch (Exception e) {
                handleException(e);
            }
            videoWatchdogLastProgressMs = System.currentTimeMillis();
            scheduleVideoWatchdogCheck();
            return;
        }

        debugInformationProvided(new DebugInformation("video",
                "stall fallback pos=" + position + " duration=" + videoPlayer.getDuration()));
        cancelVideoWatchdog();
        if (fallbackTsPlayerToMediaPlayer(assetId, file, activityReference, stage)) {
            return;
        }
        restoreImageViewAfterVideoFailure();
        assetFallback(assetId, activityReference, true, file, stage);
    }

    /**
     * Ratio of media time advanced to wall time across one window, x100.
     *
     * <p>This is the only signal that separates "smooth but slow" from "fine". Everything else in
     * the playback path — {@code onError}, {@code onInfo}, the stall check two lines above — needs
     * playback to <em>stop</em> to notice anything, and a decoder running at 0.6x never stops: it
     * advances, just not as fast as the clock. Re-anchors whenever position jumps backwards, which
     * is a native loop rather than a rate.
     *
     * @param position player position just read, in ms
     * @param now      wall clock that reading was taken on, in ms
     */
    private void measurePlaybackRate(int position, long now) {
        if (videoRateAnchorMs == 0L || position < videoRateAnchorPosition || now < videoRateAnchorMs) {
            videoRateAnchorMs = now;
            videoRateAnchorPosition = position;
            return;
        }
        long elapsed = now - videoRateAnchorMs;
        if (elapsed < VIDEO_RATE_WINDOW_MS) {
            return;
        }
        int rateX100 = (int) Math.min(Integer.MAX_VALUE,
                (position - videoRateAnchorPosition) * 100L / elapsed);
        videoPlaybackRateX100 = rateX100;
        // A single reading cannot tell a pipeline that is uniformly late from one that keeps up
        // and hitches, and those want different fixes; the first measurements of the slow clip
        // misled exactly because only the latest window was kept.
        if (videoPlaybackRateSamples == 0 || rateX100 < videoPlaybackRateMinX100) {
            videoPlaybackRateMinX100 = rateX100;
        }
        if (videoPlaybackRateSamples == 0 || rateX100 > videoPlaybackRateMaxX100) {
            videoPlaybackRateMaxX100 = rateX100;
        }
        videoPlaybackRateSamples++;
        videoRateAnchorMs = now;
        videoRateAnchorPosition = position;
    }

    /** Position of the player actually in use, or -1 when it cannot answer. */
    private int activePositionMs() {
        try {
            return videoPlayer != null ? videoPlayer.getCurrentPosition() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Duration the player actually in use believes the clip has, or -1 when it cannot answer. */
    private int activeDurationMs() {
        try {
            return videoPlayer != null ? videoPlayer.getDuration() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** The last measured playback rate as a multiple of realtime, or "n/a" before one completes. */
    private String describePlaybackRate() {
        return videoPlaybackRateX100 < 0 ? "n/a"
                : String.format(Locale.ROOT, "%.2fx", videoPlaybackRateX100 / 100.0);
    }

    // ------------------------------------------------------------------
    // KioskWatchdog.Host — what "the frame is still displaying" means, and how to restore it.
    // All of these run on the watchdog's timer thread unless noted.
    // ------------------------------------------------------------------

    @Override
    public long nowMs() {
        return System.currentTimeMillis();
    }

    @Override
    public boolean isPaused() {
        return activityPaused || settingsManager.isSetupDialogIfNeeded();
    }

    @Override
    public boolean screenShouldBeOn() {
        return brightnessManager.getShouldTheScreenBeOn();
    }

    @Override
    public long intervalMs() {
        return MILLIS * Math.max(1, settingsManager.getConfiguration().interval);
    }

    @Override
    public long lastDisplayAtMs() {
        return lastRotationActivityMs;
    }

    @Override
    public long downloadInFlightMs() {
        long last = downloadProgressAtMs;
        long started = downloadStartedAtMs;
        if (last == 0L || started == 0L || System.currentTimeMillis() - last > DOWNLOAD_PROGRESS_SILENCE_MS) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - started);
    }

    @Override
    public boolean networkAvailable() {
        return connectionManager.isNetworkAvailable();
    }

    @Override
    public long offlineMs() {
        long since = offlineSinceMs;
        return since == 0L ? 0L : Math.max(0L, System.currentTimeMillis() - since);
    }

    /**
     * Number of acknowledgements so far. A value unchanged across a whole tick interval is the
     * only evidence that counts as a dead main thread — a late acknowledgement is still proof the
     * queue drained, and this hardware legitimately needs minutes of main-thread work to start.
     */
    @Override
    public long mainLoopProgressToken() {
        return mainLoopAcks;
    }

    @Override
    public void postProbe() {
        handler.post(mainLoopProbeAck);
    }

    @Override
    public boolean autoRebootEnabled() {
        return settingsManager.getConfiguration().autoReboot;
    }

    @Override
    public void forceRotation() {
        runOnUiThread(() -> {
            Log.i(TAG, "watchdog: forcing a rotation");
            showNextImage();
        });
    }

    @Override
    public void recoverPlayback() {
        runOnUiThread(this::redrawCurrentAsset);
    }

    /**
     * Forces the full player teardown that an asset handoff would have done — the documented
     * recovery for a frozen Amlogic video plane — and then rotates. Clearing {@code lastVisibleAsset}
     * first is what makes it work: without it, landing back on the same asset would skip straight
     * past the recreate. Must run on the UI thread.
     */
    private void redrawCurrentAsset() {
        try {
            Log.i(TAG, "watchdog: forcing player teardown before the next display");
            // Deliberately does not touch lastRotationActivityMs: a recovery attempt is not a
            // display. If this rotation really lands, displayPicture/displayVideo/displayWebsite
            // refreshes the clock; if it does not, the ladder keeps climbing to the network and
            // reboot rungs.
            lastVisibleAsset = "";
            cancelVideoWatchdog();
            videoPlayer.release();
            videoPlayer.setVisibility(View.INVISIBLE);
            // The browser view sits above the image, so claiming the image is on screen only works
            // if the page is taken away first.
            hideWebView();
            imageView.setVisibility(View.VISIBLE);
            lastVisibleView = imageView;
            mediaManager.showNextImage(this);
        } catch (Exception e) {
            handleException(e);
        }
    }

    @Override
    public void repairNetwork() {
        runAdbdTask("network-repair", new AdbdTask() {
            @Override
            public List<DeviceRepair.Step> run(AdbdInstallClient client) throws InterruptedException {
                return DeviceRepair.repairWifi(client::exec, new DeviceRepair.Sleeper() {
                    @Override
                    public void sleep(long ms) throws InterruptedException {
                        Thread.sleep(ms);
                    }
                });
            }
        });
    }

    @Override
    public void rebootDevice() {
        runAdbdTask("reboot", new AdbdTask() {
            @Override
            public List<DeviceRepair.Step> run(AdbdInstallClient client) {
                return DeviceRepair.reboot(client::exec);
            }
        });
    }

    /** A repair sequence, executed against a live adbd client on a worker thread. */
    private interface AdbdTask {
        List<DeviceRepair.Step> run(AdbdInstallClient client) throws InterruptedException;
    }

    /**
     * Runs an adbd repair off the watchdog thread: adbd round-trips take seconds, and a timer task
     * that blocks cannot escalate. Only ever reached with {@link DeviceRepair}'s fixed commands.
     */
    private void runAdbdTask(String label, AdbdTask task) {
        if (repairRunning) {
            Log.i(TAG, "watchdog: " + label + " skipped — another repair is in flight");
            return;
        }
        repairRunning = true;
        final String name = label;
        new Thread(new Runnable() {
            @Override
            public void run() {
                AdbdInstallClient.Result armed = null;
                try {
                    // The ladder has reached this rung, so it may use adbd — but only for as long as
                    // this one action runs: start it now (if the operator allowed that at all), and
                    // the finally below closes it again. Between rungs nothing is listening.
                    armed = AdbdInstallClient.ensureTcpAdbd(
                            settingsManager != null && settingsManager.isLoopbackAdbEnabled());
                    if (armed.outcome != AdbdInstallClient.Outcome.SUCCESS
                            && armed.outcome != AdbdInstallClient.Outcome.ARMED) {
                        logWatchdogEvent(name + " skipped: adbd unavailable (" + armed.detail + ")", true);
                        return;
                    }
                    List<DeviceRepair.Step> steps = task.run(new AdbdInstallClient());
                    logWatchdogEvent(name + ": " + DeviceRepair.describe(steps), false);
                } catch (Throwable t) {
                    // A repair thread must never reach the global handler (System.exit(2)).
                    logWatchdogEvent(name + " failed: " + t, true);
                } finally {
                    if (armed != null && armed.outcome == AdbdInstallClient.Outcome.ARMED) {
                        AdbdInstallClient.teardownTcpAdbd();
                    }
                    repairRunning = false;
                }
            }
        }, "eo1-" + name).start();
    }

    /**
     * Best-effort, once per process, from {@code onCreate}: ask the device's own adbd to disable the
     * receivers behind the ROM's two OTA pollers (see {@link DeviceRepair#VENDOR_UPDATER_COMMANDS}).
     * Deliberately not part of the core loop — adbd is loopback, so Wi-Fi has nothing to do with it,
     * and a frame that boots into a dead network is precisely the one whose poller still fires every
     * 15 minutes. Kept out of the watchdog ladder too: this is not a repair and must never block the
     * caller. A frame whose ROM has no such package says so once in {@code /logs} and moves on. The
     * attempt is claimed before the thread starts, so an unreachable adbd costs one log line, not a
     * retry loop; the next launch tries again.
     */
    private void silenceVendorUpdater() {
        if (vendorUpdaterAttempted) {
            return;
        }
        vendorUpdaterAttempted = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!AdbdInstallClient.probe()) {
                        // Silencing the vendor pollers is not an action anyone asked for, so unlike a
                        // reached watchdog rung it never opens a root port on the LAN — it uses one
                        // that is already there. On an EO1 that makes this a logged no-op.
                        Log.i(TAG, "vendor updater: skipped, adbd is not listening");
                        debugInformationProvided(new DebugInformation("vendorUpdater",
                                "skipped: adbd is not listening"));
                        return;
                    }
                    AdbdInstallClient client = new AdbdInstallClient();
                    List<DeviceRepair.Step> steps = DeviceRepair.silenceVendorUpdater(client::exec);
                    String outcome = DeviceRepair.describe(steps);
                    Log.i(TAG, "vendor updater: " + outcome);
                    debugInformationProvided(new DebugInformation("vendorUpdater", outcome));
                } catch (Throwable t) {
                    // A background attempt must never reach the global handler (System.exit(2)).
                    Log.w(TAG, "vendor updater: " + t);
                }
            }
        }, "eo1-vendor-updater").start();
    }

    /**
     * Restarts our own process. The relaunch alarm is scheduled on a worker thread rather than
     * inline: {@code AlarmManager.set} is a binder call, and if the framework is the part that is
     * hung, blocking the watchdog thread here would cost us every remaining rung (including
     * reboot). The kill is what actually unsticks a wedged UI thread.
     */
    @Override
    public void selfRestart() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AlarmManager mgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                    if (mgr != null && pendingIntent != null) {
                        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 15000L, pendingIntent);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "watchdog: could not schedule relaunch: " + t);
                }
                try {
                    android.os.Process.killProcess(android.os.Process.myPid());
                } catch (Throwable ignored) {
                    // Nothing left to do from inside a process that cannot kill itself.
                }
            }
        }, "eo1-self-restart").start();
    }

    /**
     * One watchdog event. {@code debugInformationProvided} is the single write path into
     * {@code AppLogger} (it also drives the on-device debug overlay), so the file log gets exactly
     * one line per event — writing here as well duplicates every line.
     */
    @Override
    public void logWatchdogEvent(String message, boolean severe) {
        if (severe) {
            Log.w(TAG, "watchdog: " + message);
        } else {
            Log.i(TAG, "watchdog: " + message);
        }
        debugInformationProvided(new DebugInformation("watchdog", message));
    }

    /** The watchdog's view of itself, for {@code /state}. */
    private JsonObject watchdogStateJson() {
        JsonObject o = new JsonObject();
        long stale = kioskWatchdog != null ? kioskWatchdog.staleMsNow() : 0L;
        o.addProperty("staleMs", stale);
        o.addProperty("staleLimitMs", KioskWatchdog.staleLimitMs(intervalMs()));
        o.addProperty("level", kioskWatchdog != null ? kioskWatchdog.actedLevel() : KioskWatchdog.LEVEL_NONE);
        o.addProperty("lastAction", kioskWatchdog != null ? kioskWatchdog.lastAction() : "none");
        o.addProperty("lastActionAtMs", kioskWatchdog != null ? kioskWatchdog.lastActionAtMs() : 0L);
        o.addProperty("repairAttempts", kioskWatchdog != null ? kioskWatchdog.repairAttempts() : 0);
        o.addProperty("ticks", kioskWatchdog != null ? kioskWatchdog.ticks() : 0);
        o.addProperty("paused", isPaused());
        // "Alive" is the watchdog's own verdict: the main thread advanced at the last check. A
        // large mainLoopStallMs alongside it means slow startup, not a wedge.
        o.addProperty("mainLoopAlive", kioskWatchdog != null && kioskWatchdog.probeMisses() == 0);
        o.addProperty("mainLoopStallMs", Math.max(0L, System.currentTimeMillis() - mainLoopAckAtMs));
        o.addProperty("probeMisses", kioskWatchdog != null ? kioskWatchdog.probeMisses() : 0);
        o.addProperty("downloadInFlightMs", downloadInFlightMs());
        o.addProperty("offlineMs", offlineMs());
        o.addProperty("autoReboot", autoRebootEnabled());
        return o;
    }

    private String describeMediaInfo(int what, int extra) {
        switch (what) {
            case MediaPlayer.MEDIA_INFO_BUFFERING_START:
                return "buffering start extra=" + extra;
            case MediaPlayer.MEDIA_INFO_BUFFERING_END:
                return "buffering end extra=" + extra;
            case MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                return "rendering start extra=" + extra;
            default:
                return "what=" + what + " extra=" + extra;
        }
    }

    @Override
    public void displayVideo(File file, String assetId) {
        displayVideo(file, assetId, DisplayStage.ORIGINAL);
    }

    @Override
    public void displayFallbackVideo(File file, String assetId) {
        displayVideo(file, assetId, DisplayStage.FALLBACK);
    }

    private void displayVideo(File file, String assetId, DisplayStage stage) {

        if (file.getAbsolutePath().equals(lastVisibleAsset)) {
            Log.i(TAG, "displayVideo: skip same asset " + assetId);
            return;
        }

        if (!this.brightnessManager.getShouldTheScreenBeOn()) {
            Log.i(TAG, "displayVideo: skip (screen should be off) asset=" + assetId);
            return;
        }

        lastRotationActivityMs = System.currentTimeMillis();
        debugInformationProvided(new DebugInformation("displayVideo", file.getAbsolutePath()));

        WeakReference<MainActivity> activityReference = new WeakReference<>(this);
        activeVideoAssetId = assetId;

        this.runOnUiThread(() -> {
            try {
                cancelVideoWatchdog();
                selectVideoPlayer(routingAllowsTsPlayer(file));
                int videoDurationMs = mediaManager != null ? mediaManager.getVideoDurationMs(assetId) : -1;
                startVideoOnController(videoPlayer, file, assetId, videoDurationMs,
                        activityReference, true, stage);
            } catch (Exception e) {
                restoreImageViewAfterVideoFailure();
                assetFallback(assetId, activityReference, true, file, stage);
                handleException(e);
            }
        });
    }

    /**
     * TsPlayer is the default, but its .so renders with fixed fit/letterbox scaling and exposes
     * no rotation or scale-mode control (docs/TSPLAYER.md, "Scaling & orientation"). Route to
     * the MediaPlayer fallback when the video's display-matrix rotation is non-identity, or when
     * the video's display aspect is the opposite of the frame's current orientation (a landscape
     * video on a portrait frame would letterbox instead of fill). Square or unknown aspect and
     * unknown rotation keep today's TsPlayer default.
     */
    private boolean routingAllowsTsPlayer(File file) {
        VideoHeaderProbe.Probe probe = VideoHeaderProbe.probe(file);
        int rotation = probe.rotationDeg;
        int width = probe.codedWidth;
        int height = probe.codedHeight;
        String codec = probe.codec;
        MediaAsset asset = mediaManager != null ? mediaManager.getCurrentAsset() : null;
        if (asset != null) {
            if (width <= 0) {
                width = asset.width;
            }
            if (height <= 0) {
                height = asset.height;
            }
        }
        int effWidth = width;
        int effHeight = height;
        if (rotation == 90 || rotation == 270) {
            int t = effWidth;
            effWidth = effHeight;
            effHeight = t;
        }
        boolean framePortrait = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        boolean forceFallback = rotation > 0
                || (width > 0 && height > 0 && effWidth > effHeight && framePortrait)
                || (width > 0 && height > 0 && effHeight > effWidth && !framePortrait);
        lastVideoRotation = rotation;
        lastVideoWidth = width;
        lastVideoHeight = height;
        lastVideoCodec = codec;
        lastVideoStats = probe.stats;
        lastVideoDurationMs = probe.stats.durationMs;
        lastFrameOrientation = framePortrait ? "portrait" : "landscape";
        Log.i(TAG, "displayVideo routing: codec=" + codec + " rotation=" + rotation
                + " coded=" + width + "x" + height
                + " frame=" + lastFrameOrientation + " player="
                + (forceFallback ? "MediaPlayer" : "TsPlayer")
                + " " + describeStream(probe));
        debugInformationProvided(new DebugInformation("videoRouting",
                "codec=" + codec + " rotation=" + rotation + " dims=" + width + "x" + height
                        + " frame=" + lastFrameOrientation + " -> "
                        + (forceFallback ? "MediaPlayer" : "TsPlayer") + "; "
                        + describeStream(probe)));
        return !forceFallback;
    }

    /** Sample-table facts in one line, each part omitted when the file did not give it. */
    private static String describeStream(VideoHeaderProbe.Probe probe) {
        VideoHeaderProbe.StreamStats s = probe.stats;
        // Named out in full rather than abbreviated: megabytes per second is the other reading a
        // reader would take from "MB/s", and the two differ by three orders of magnitude here.
        long macroblocksPerSecond = MediaCompatibility.videoMacroblocksPerSecond(
                probe.codedWidth, probe.codedHeight, s.fpsMilli);
        if (s.fpsMilli <= 0 && s.avgFrameBytes <= 0 && s.bitRateBps <= 0
                && macroblocksPerSecond < 0) {
            return "stream unknown";
        }
        StringBuilder sb = new StringBuilder("stream");
        if (s.fpsMilli > 0) {
            sb.append(String.format(Locale.ROOT, " %.3ffps", s.fpsMilli / 1000.0));
        }
        if (s.avgFrameBytes > 0) {
            sb.append(' ').append(s.avgFrameBytes).append("B/frame");
        }
        if (s.peakFrameBytes > 0) {
            sb.append(" peak ").append(s.peakFrameBytes).append('B');
        }
        if (s.bitRateBps > 0) {
            sb.append(String.format(Locale.ROOT, " %.1f Mbps", s.bitRateBps / 1e6));
        }
        if (macroblocksPerSecond >= 0) {
            sb.append(' ').append(macroblocksPerSecond).append(" macroblocks/s");
        }
        return sb.toString();
    }

    /**
     * Prefer TsPlayer when enabled and the native library loaded; otherwise MediaPlayer.
     *
     * @param allowTsPlayer false forces MediaPlayer (used after TsPlayer failure for the same file)
     */
    private void selectVideoPlayer(boolean allowTsPlayer) {
        boolean useTs = allowTsPlayer && preferTsPlayer;
        VideoPlayerController next = useTs ? tsPlayerController : mediaPlayerController;
        if (videoPlayer != null && videoPlayer != next) {
            // Release, don't just stop: a stopped platform MediaPlayer stays prepared and keeps
            // the exclusive Amlogic video pipeline, which would starve the incoming TsPlayer.
            videoPlayer.release();
            videoPlayer.setVisibility(View.GONE);
        }
        videoPlayer = next;
        if (useTs) {
            mediaPlayerVideoView.setVisibility(View.GONE);
            // VISIBLE required for SurfaceView to create its surface (under ImageView).
            tsVideoView.setVisibility(View.VISIBLE);
        } else {
            tsVideoView.setVisibility(View.GONE);
            mediaPlayerVideoView.setVisibility(View.INVISIBLE);
        }
        debugInformationProvided(new DebugInformation("videoPlayer",
                useTs ? "TsPlayer" : "MediaPlayer"));
    }

    /**
     * After TsPlayer stalls or errors, hand the same file to MediaPlayer once. Swapping engine
     * is not swapping rung: the retry stays on {@code stage}, so its failure moves one rung
     * down the ladder like any other failure of that display.
     *
     * @return true if MediaPlayer playback was started
     */
    private boolean fallbackTsPlayerToMediaPlayer(String assetId, File file,
                                                  WeakReference<MainActivity> activityReference,
                                                  DisplayStage stage) {
        if (!preferTsPlayer || videoPlayer != tsPlayerController) {
            return false;
        }
        if (!Objects.equals(assetId, activeVideoAssetId)) {
            return false;
        }
        tsFallbackCount++;
        Log.i(TAG, "TsPlayer → MediaPlayer fallback #" + tsFallbackCount);
        debugInformationProvided(new DebugInformation("video", "TsPlayer → MediaPlayer fallback #" + tsFallbackCount));
        try {
            selectVideoPlayer(false);
            int videoDurationMs = mediaManager != null ? mediaManager.getVideoDurationMs(assetId) : -1;
            startVideoOnController(videoPlayer, file, assetId, videoDurationMs,
                    activityReference, false, stage);
            return true;
        } catch (Exception e) {
            handleException(e);
            return false;
        }
    }

    private void startVideoOnController(VideoPlayerController controller, File file, String assetId,
                                        int durationHintMs,
                                        WeakReference<MainActivity> activityReference,
                                        boolean allowTsFallback, DisplayStage stage) {
        controller.stop();
        // TsPlayer SurfaceView must be VISIBLE to get a surface; MediaPlayer TextureView can stay
        // invisible until prepared (ImageView covers both until then).
        if (controller == tsPlayerController) {
            controller.setVisibility(View.VISIBLE);
        } else {
            controller.setVisibility(View.INVISIBLE);
        }
        controller.setListener(new VideoPlayerListener() {
            @Override
            public void onVideoPrepared() {
                if (!Objects.equals(assetId, activeVideoAssetId)) {
                    return;
                }
                try {
                    if (!MainActivity.this.isDestroyed()) {
                        hideWebView();
                        Glide.with(MainActivity.this).clear(imageView);
                        imageView.setVisibility(View.INVISIBLE);
                        controller.setVisibility(View.VISIBLE);
                        lastVisibleView = controller.getView();
                        lastVisibleAsset = file.getAbsolutePath();
                        controller.setLooping(true);
                        controller.setFocusable(false);
                        controller.setVolume(0, 0);
                        controller.play();
                        debugInformationProvided(new DebugInformation("video",
                                "prepared duration=" + controller.getDuration()
                                    + " player=" + (controller == tsPlayerController
                                    ? "TsPlayer" : "MediaPlayer")));
                        startVideoWatchdog(assetId, file, activityReference, stage);
                    }
                } catch (Exception e) {
                    if (allowTsFallback && fallbackTsPlayerToMediaPlayer(assetId, file, activityReference, stage)) {
                        return;
                    }
                    restoreImageViewAfterVideoFailure();
                    assetFallback(assetId, activityReference, true, file, stage);
                    handleException(e);
                }
            }

            @Override
            public void onVideoEnd() {
                if (!Objects.equals(assetId, activeVideoAssetId)) {
                    return;
                }
                int position = controller.getCurrentPosition();
                int duration = controller.getDuration();
                debugInformationProvided(new DebugInformation("video",
                        "end pos=" + position + " duration=" + duration));
                if (!brightnessManager.getShouldTheScreenBeOn()) {
                    return;
                }
                // TsVideoView recreates itself for looping; calling play() here double-tears-down.
                if (controller == tsPlayerController && tsVideoView.isRecreatingForLoop()) {
                    debugInformationProvided(new DebugInformation("video", "loop recreate in progress"));
                    return;
                }
                debugInformationProvided(new DebugInformation("video", "restart after end"));
                controller.play();
                videoWatchdogLastPosition = -1;
                videoWatchdogLastProgressMs = System.currentTimeMillis();
                videoWatchdogRecoverAttempted = false;
            }

            @Override
            public boolean onError(int what, int extra) {
                if (!Objects.equals(assetId, activeVideoAssetId)) {
                    return false;
                }
                cancelVideoWatchdog();
                debugInformationProvided(new DebugInformation("video",
                        "error what=" + what + " extra=" + extra));
                if (allowTsFallback && fallbackTsPlayerToMediaPlayer(assetId, file, activityReference, stage)) {
                    return true;
                }
                restoreImageViewAfterVideoFailure();
                assetFallback(assetId, activityReference, true, file, stage);
                return false;
            }

            @Override
            public boolean onInfo(int what, int extra) {
                if (!Objects.equals(assetId, activeVideoAssetId)) {
                    return false;
                }
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START
                        || what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                        || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    debugInformationProvided(new DebugInformation("video", describeMediaInfo(what, extra)));
                }
                return false;
            }
        });
        controller.setDurationHint(durationHintMs);
        controller.setDisplayRotation(lastVideoRotation);
        controller.setDataSource(file.getPath());
        controller.setLooping(true);
    }

    @Override
    public void connected() {
        offlineSinceMs = 0L;
        Log.i(TAG, "ConnectionManager: connected");
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), "connected"));
        startCoreLoopIfNetworkReady();
    }

    @Override
    public void disconnected() {
        if (offlineSinceMs == 0L) {
            offlineSinceMs = System.currentTimeMillis();
        }
        Log.i(TAG, "ConnectionManager: disconnected");
        pauseCoreLoopForNetwork();
    }

    @Override
    public void clientProgressUpdates(long bytesRead, long contentLength, boolean done, @Nullable HttpUrl url) {

        long percent =  Math.round((100.0 * bytesRead) / contentLength);

        long progressNowMs = System.currentTimeMillis();
        if (done) {
            downloadStartedAtMs = 0L;
            downloadProgressAtMs = 0L;
        } else {
            if (downloadStartedAtMs == 0L) {
                downloadStartedAtMs = progressNowMs;
            }
            downloadProgressAtMs = progressNowMs;
        }

        // OkHttp invokes this on every read() of the response body, so logging every
        // event floods logcat with repeated "download: 9%" lines. Log the content-length
        // announcement once per download, a progress line only for a percent value not
        // logged yet, and at most one progress line per DOWNLOAD_PROGRESS_LOG_INTERVAL_MS.
        synchronized (downloadProgressLogLock) {
            long nowMs = System.currentTimeMillis();
            if (done || percent < lastLoggedDownloadPercent) {
                // A completed download, or a percent below the last logged value (a new
                // download after one that failed without a done event) resets the throttle.
                if (done) {
                    Log.i(TAG, "download: completed");
                }
                lastLoggedDownloadPercent = -1L;
                lastDownloadProgressLogAtMs = 0L;
            }
            if (!done) {
                if (percent == 0L && lastLoggedDownloadPercent < 0L) {
                    if (contentLength == -1) {
                        Log.i(TAG, "download: content-length unknown");
                    } else {
                        Log.i(TAG, "download: content-length " + contentLength);
                    }
                    lastLoggedDownloadPercent = 0L;
                    lastDownloadProgressLogAtMs = nowMs;
                } else if (contentLength != -1
                        && percent != lastLoggedDownloadPercent
                        && nowMs - lastDownloadProgressLogAtMs >= DOWNLOAD_PROGRESS_LOG_INTERVAL_MS) {
                    Log.d(TAG, "download: " + percent + "%");
                    lastLoggedDownloadPercent = percent;
                    lastDownloadProgressLogAtMs = nowMs;
                }
            }
        }

        // MediaManager only forwards progress events while a remote download is in
        // flight (catalog fetches and local playback never reach this callback).
        this.runOnUiThread(() -> {
            if (done) {
                //reset update progress indicator UI
                progressIndicator.setProgressCompat(0, false);
                progressIndicator.setVisibility(View.INVISIBLE);
            } else {
                //Update progress indicator UI percent
                progressIndicator.setMax(Math.toIntExact(contentLength));
                progressIndicator.setProgressCompat(Math.toIntExact(bytesRead), true);
                progressIndicator.setVisibility(View.VISIBLE);
            }
        });
    }

    // ------------------------------------------------------------------
    // WebController — always-on LAN web server (/state, /logs, /files, /control).
    // All of these are called from the web server's worker threads.
    // ------------------------------------------------------------------

    @Override
    public String localIp() {
        try {
            WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null) {
                    int ip = info.getIpAddress();
                    if (ip != 0) {
                        return DeviceTelemetry.ipToString(ip);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "localIp: " + e);
        }
        return "unknown";
    }

    @Override
    public String platform() {
        return capabilities != null ? capabilities.platform() : "EO1";
    }

    @Override
    public String appBuildSummary() {
        return BuildFacts.summary(this);
    }

    @Override
    public String deviceName() {
        return settingsManager != null ? settingsManager.deviceName() : "";
    }

    /** Called from the web server's worker threads; {@code SettingsManager} does the refresh. */
    @Override
    public String setDeviceName(String requested) {
        return settingsManager != null ? settingsManager.setDeviceName(requested) : "";
    }

    @Override
    public String buildStateJson() {
        JsonObject o = new JsonObject();

        JsonObject device = new JsonObject();
        device.addProperty("manufacturer", Build.MANUFACTURER);
        device.addProperty("model", Build.MODEL);
        device.addProperty("android", Build.VERSION.RELEASE);
        device.addProperty("platform", capabilities.platform());
        // Omitted, not empty, when the frame was never named — a reader then falls back to
        // platform and address, which is all there ever was.
        String deviceName = deviceName();
        if (!deviceName.isEmpty()) {
            device.addProperty("name", deviceName);
        }
        o.add("device", device);

        JsonObject caps = new JsonObject();
        caps.addProperty("lightSensor", capabilities.supportsLightSensor());
        caps.addProperty("screenBrightness", capabilities.supportsScreenBrightness());
        caps.addProperty("brightnessButton", capabilities.supportsBrightnessButton());
        caps.addProperty("powerButton", capabilities.supportsPowerButton());
        caps.addProperty("screenToggleKeyCode", capabilities.screenToggleKeyCode());
        o.add("capabilities", caps);

        JsonObject app = new JsonObject();
        app.addProperty("version", BuildFacts.version());
        app.addProperty("versionName", BuildFacts.versionName());
        app.addProperty("buildNumber", BuildFacts.buildNumber());
        app.addProperty("variant", BuildFacts.variant());
        app.addProperty("buildDate", BuildFacts.buildStamp());
        app.addProperty("gitSha", BuildFacts.gitSha());
        app.addProperty("gitDirty", "true".equals(BuildFacts.gitDirty()));
        app.addProperty("debug", BuildConfig.DEBUG);
        app.addProperty("tsPlayer", preferTsPlayer);
        app.addProperty("webPort", webServer != null ? webServer.getBoundPort() : -1);
        app.addProperty("trustedNetwork", settingsManager != null && settingsManager.isTrustedNetwork());
        app.addProperty("createCount", sActivityCreateCount);
        app.addProperty("processStartMs", PROCESS_START_MS);
        BuildFacts.Signer signer = BuildFacts.signing(this);
        JsonObject signerJson = new JsonObject();
        signerJson.addProperty("signed", signer.signed);
        signerJson.addProperty("fingerprint", signer.fingerprintHex);
        signerJson.addProperty("subject", signer.subject);
        signerJson.addProperty("serial", signer.serial);
        signerJson.addProperty("validFrom", signer.validFrom);
        signerJson.addProperty("validTo", signer.validTo);
        signerJson.addProperty("validity", signer.validityStatus);
        signerJson.addProperty("algorithm", signer.algorithm);
        signerJson.addProperty("keyVerdict", signer.keyVerdict);
        app.add("signer", signerJson);
        o.add("app", app);

        // The whole backlight story in one place: what the user set, what the sensor last saw, and
        // what is actually on the panel. A "it dimmed by itself" report is answerable from this.
        JsonObject brightness = new JsonObject();
        brightness.addProperty("supported", capabilities.supportsScreenBrightness());
        brightness.addProperty("step", brightnessManager.getMinimumBrightnessStep());
        brightness.addProperty("persistedStep", settingsManager.getMinimumBrightnessStep());
        brightness.addProperty("floor", brightnessManager.minimumBrightness());
        brightness.addProperty("applied", lastAppliedBrightness);
        brightness.addProperty("lastLightValue", brightnessManager.lastLightValue());
        brightness.addProperty("screenOn", brightnessManager.getShouldTheScreenBeOn());
        o.add("brightness", brightness);

        o.add("config", ConfigStateJson.configStateJson(settingsManager.getConfiguration()));

        JsonObject network = new JsonObject();
        network.addProperty("available", connectionManager.isNetworkAvailable());
        network.add("wifi", DeviceTelemetry.wifi(this));
        o.add("network", network);

        JsonObject media = new JsonObject();
        media.addProperty("rotationAssets", mediaManager.rotationListSize());
        media.addProperty("screenOn", brightnessManager.getShouldTheScreenBeOn());
        media.addProperty("lastDisplayAtMs", lastRotationActivityMs);
        MediaAsset currentAsset = mediaManager.getCurrentAsset();
        if (currentAsset != null) {
            JsonObject current = new JsonObject();
            current.addProperty("key", currentAsset.key());
            current.addProperty("type", mediaTypeName(currentAsset.type));
            current.addProperty("backendId", currentAsset.backendId);
            if (currentAsset.type == MediaType.WEB) {
                current.addProperty("url", webUrlOnScreen);
            }
            if (currentAsset.originalFileName != null) {
                current.addProperty("originalFileName", currentAsset.originalFileName);
            }
            current.addProperty("width", currentAsset.width);
            current.addProperty("height", currentAsset.height);
            current.addProperty("durationMs", currentAsset.durationMs);
            if (currentAsset.sizeBytes != null) {
                current.addProperty("sizeBytes", currentAsset.sizeBytes);
            }
            current.addProperty("local", currentAsset.localPath != null);
            media.add("current", current);
        }
        o.add("media", media);

        JsonObject input = new JsonObject();
        input.addProperty("windowFocused", windowFocused);
        input.addProperty("activityPaused", activityPaused);
        input.addProperty("focusedView", focusedView);
        input.addProperty("lastKeyCode", lastKeyEventCode);
        input.addProperty("lastKeyAtMs", lastKeyEventAtMs);
        o.add("input", input);

        o.add("update", updateManager.stateJson());
        o.add("watchdog", watchdogStateJson());
        o.add("crash", crashBlockJson());

        o.add("telemetry", DeviceTelemetry.snapshot(this));

        if (BuildConfig.DEBUG) {
            JsonObject video = new JsonObject();
            video.addProperty("active", videoPlayer == tsPlayerController ? "TsPlayer" : "MediaPlayer");
            video.addProperty("preferTsPlayer", preferTsPlayer);
            video.addProperty("tsAvailable", TsPlayerNative.isAvailable());
            video.addProperty("tsFallbacks", tsFallbackCount);
            video.addProperty("rotation", lastVideoRotation);
            video.addProperty("width", lastVideoWidth);
            video.addProperty("height", lastVideoHeight);
            if (lastVideoCodec != null) {
                video.addProperty("codec", lastVideoCodec);
            }
            if (lastFrameOrientation != null) {
                video.addProperty("frameOrientation", lastFrameOrientation);
            }
            VideoHeaderProbe.StreamStats stream = lastVideoStats;
            video.addProperty("fpsMilli", stream.fpsMilli);
            video.addProperty("avgFrameBytes", stream.avgFrameBytes);
            video.addProperty("peakFrameBytes", stream.peakFrameBytes);
            video.addProperty("bitRateBps", stream.bitRateBps);
            video.addProperty("fileDurationMs", stream.durationMs);
            video.addProperty("macroblocksPerSecond", MediaCompatibility.videoMacroblocksPerSecond(
                    lastVideoWidth, lastVideoHeight, stream.fpsMilli));
            video.addProperty("playbackRateX100", videoPlaybackRateX100);
            video.addProperty("playbackRateMinX100", videoPlaybackRateMinX100);
            video.addProperty("playbackRateMaxX100", videoPlaybackRateMaxX100);
            video.addProperty("playbackRateSamples", videoPlaybackRateSamples);
            // From whichever player is actually showing the clip. These used to be read off
            // tsVideoView unconditionally, so on the MediaPlayer path they reported 0 — which is
            // how the slow-playback investigation ended up reconstructing a lap from the stall
            // log's timestamps instead of reading the position it had already measured.
            video.addProperty("durationMs", activeDurationMs());
            video.addProperty("positionMs", activePositionMs());
            if (tsVideoView != null) {
                video.addProperty("generation", tsVideoView.getLoopGeneration());
                video.addProperty("errors", tsVideoView.getVideoErrorCount());
                video.addProperty("playerCreated", tsVideoView.isPlayerCreated());
                video.addProperty("surfaceReady", tsVideoView.isSurfaceReady());
                video.addProperty("recreatingForLoop", tsVideoView.isRecreatingForLoop());
                video.addProperty("path", tsVideoView.getActivePath());
                JsonObject loopPerf = new JsonObject();
                loopPerf.addProperty("restartLatencyMs", tsVideoView.getLastRestartLatencyMs());
                loopPerf.addProperty("createPlayerMs", tsVideoView.getLastCreatePlayerMs());
                loopPerf.addProperty("setSurfaceMs", tsVideoView.getLastSetSurfaceMs());
                loopPerf.addProperty("startMs", tsVideoView.getLastStartMs());
                loopPerf.addProperty("restartToPlayingMs", tsVideoView.getLastRestartToPlayingMs());
                loopPerf.addProperty("endToPlayingMs", tsVideoView.getLastEndToPlayingMs());
                loopPerf.addProperty("loopCycleMs", tsVideoView.getLastLoopCycleMs());
                loopPerf.addProperty("posAtTriggerMs", tsVideoView.getLastPosAtTriggerMs());
                loopPerf.addProperty("restartMethod", tsVideoView.getLastRestartMethod());
                video.add("loopPerf", loopPerf);
            }
            o.add("video", video);
        }

        if (BuildConfig.DEBUG && !debugInformation.isEmpty()) {
            JsonObject debug = new JsonObject();
            synchronized (debugInformation) {
                for (Map.Entry<String, String> entry : debugInformation.entrySet()) {
                    debug.addProperty(entry.getKey(), entry.getValue());
                }
            }
            o.add("debug", debug);
        }

        return o.toString();
    }

    @Override
    public String getLogTail(int lines) {
        return appLogger != null ? appLogger.tailJson(lines) : "[]";
    }

    @Override
    public String getFileLogTail(int lines) {
        return appLogger != null ? appLogger.tailFile(lines) : "";
    }

    @Override
    public File uploadedDir() {
        return UploadedMedia.dirFor(this);
    }

    @Override
    public UploadedMedia.FileInfo[] listUploadedFiles() {
        return UploadedMedia.list(UploadedMedia.dirFor(this));
    }

    @Override
    public File uploadedFile(String name) {
        return UploadedMedia.resolve(UploadedMedia.dirFor(this), name);
    }

    @Override
    public boolean deleteUploadedFile(String name) {
        return UploadedMedia.delete(UploadedMedia.dirFor(this), name);
    }

    @Override
    public File crashDir() {
        return CrashStore.dirFor(this);
    }

    @Override
    public CrashStore.Info[] listCrashDumps() {
        return CrashStore.list(CrashStore.dirFor(this));
    }

    @Override
    public File crashDumpFile(String name) {
        return CrashStore.resolve(CrashStore.dirFor(this), name);
    }

    @Override
    public boolean deleteCrashDump(String name) {
        return CrashStore.delete(CrashStore.dirFor(this), name);
    }

    /**
     * The {@code /state} {@code crash} block. Wrapped because a throw anywhere inside
     * {@link #buildStateJson} would otherwise 500 the whole document — the same reason
     * {@code WebServer} wraps every out-of-route controller call in a degraded default.
     */
    private JsonObject crashBlockJson() {
        JsonObject o = new JsonObject();
        try {
            o.add("store", CrashStore.statsJson(CrashStore.dirFor(this)));
            JsonObject sources = new JsonObject();
            CrashSources.Availability a = CrashSources.last();
            sources.addProperty("logcat", a.logcat);
            sources.addProperty("dropbox", a.dropbox);
            sources.addProperty("anr", a.anr);
            sources.addProperty("copies", a.copies);
            sources.addProperty("readLogs", hasReadLogsPermission() ? "granted" : "denied");
            o.add("sources", sources);
        } catch (Throwable t) {
            // Report whatever was assembled before the failure; never fail /state over this.
        }
        return o;
    }

    /** Whether this install was granted the signature-level READ_LOGS (false on a sideload). */
    private boolean hasReadLogsPermission() {
        try {
            return checkPermission("android.permission.READ_LOGS",
                    android.os.Process.myPid(), android.os.Process.myUid())
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public File apkUploadDir() {
        return updateManager != null ? updateManager.updatesDir() : null;
    }

    /**
     * Verifies an uploaded APK the same way as the self-update download path and stages it
     * for install. Runs on the web server's worker thread; never auto-installs.
     */
    @Override
    public ApkUploadResult uploadApk(File apk) {
        if (updateManager == null || apk == null) {
            return ApkUploadResult.reject("", 0, false, "update-manager-unavailable");
        }
        return updateManager.stageUploadedApk(apk);
    }

    /**
     * Mirrors a hardware key press from the web UI. Every action is posted to the UI thread
     * because most of them touch views or start activities — except {@code keyevent}, which
     * is injected from the calling web worker thread: {@code Instrumentation#sendKeyDownUpSync}
     * blocks on the UI thread's looper and would deadlock if posted to that thread.
     *
     * <p>{@code keyevent} is a debug-build-only admin action: it injects arbitrary keycodes,
     * so a release build refuses it (the web server also requires POST for it).
     *
     * <p>{@code show} is the other debug-only action, and the only one that is not a key press: it
     * pins one rotation asset by ID as the next tick's display target, through
     * {@link MediaManager#queueAssetDisplay} rather than by adding a second way to render an asset.
     */
    @Override
    public boolean control(String action, Map<String, String> params) {
        if (action == null) {
            return false;
        }
        if ("keyevent".equals(action)) {
            if (!BuildConfig.DEBUG) {
                return false;
            }
            String rawCode = params != null ? params.get("code") : null;
            int keyCode;
            try {
                keyCode = Integer.parseInt(rawCode);
            } catch (NumberFormatException e) {
                return false;
            }
            if (keyCode < 0 || keyCode > 0xFFFF) {
                return false;
            }
            return sendKeyCode(keyCode);
        }
        if ("watchdog".equals(action)) {
            // Debug builds only: rungs escalate over hours on real hardware, and the whole point of
            // the ladder is that nobody has to be there when it fires. level 1..5 forces one.
            if (!BuildConfig.DEBUG || kioskWatchdog == null) {
                return false;
            }
            String rawLevel = params != null ? params.get("level") : null;
            int level;
            try {
                level = Integer.parseInt(rawLevel);
            } catch (NumberFormatException e) {
                level = KioskWatchdog.LEVEL_FORCE_ROTATION;
            }
            if (level < KioskWatchdog.LEVEL_FORCE_ROTATION || level > KioskWatchdog.LEVEL_REBOOT) {
                return false;
            }
            // Called from the web thread on purpose: every rung dispatches to whichever thread owns
            // the work, so forcing the self-restart rung still works when the UI thread is the
            // thing that is stuck.
            kioskWatchdog.forceAction(level);
            return true;
        }
        final Runnable task;
        switch (action) {
            case "next":
                task = this::showNextImage;
                break;
            case "show": {
                // Debug-only: "the clip that misbehaves is #600 of 1000", which ?action=next cannot
                // promise to reach. Checked and parked from this thread, which may read the pool's
                // key set but never its records; the rotation worker picks the record up.
                if (!BuildConfig.DEBUG || mediaManager == null) {
                    return false;
                }
                final String assetKey = params != null ? params.get("asset") : null;
                if (!mediaManager.queueAssetDisplay(assetKey)) {
                    return false;
                }
                task = () -> {
                    if (!brightnessManager.getShouldTheScreenBeOn()) {
                        // Standing down for quiet hours means no tick will consume the pin. Dropping
                        // it here keeps a request made while the panel was dark from surfacing
                        // unprompted the moment it wakes.
                        mediaManager.cancelAssetDisplay(assetKey);
                    }
                    showNextImage();
                };
                break;
            }
            case "screen":
                task = this::toggleScreenOn;
                break;
            case "brightness":
                task = this::adjustMinimumBrightness;
                break;
            case "config":
                task = this::showConfigurationUI;
                break;
            case "settings":
                task = this::openSystemSettings;
                break;
            case "update-site":
                task = this::openUpdateWebsite;
                break;
            case "check-updates":
                task = this::checkForUpdates;
                break;
            case "install-staged":
                task = () -> {
                    turnScreenOn();
                    updateManager.installStaged(true);
                };
                break;
            case "update-reset":
                task = updateManager::resetUpdate;
                break;
            default:
                return false;
        }
        runOnUiThread(task);
        return true;
    }

    @Override
    public boolean trustedNetwork() {
        return settingsManager != null && settingsManager.isTrustedNetwork();
    }

    /**
     * The trusted-network flag lives in the app's default SharedPreferences (not in
     * {@code configuration.json}). The stripped compile-time android.jar lacks
     * {@code Context.getDefaultSharedPreferences()}, so use the PreferenceManager entry point.
     */
    @Override
    @SuppressWarnings("deprecation")
    public SharedPreferences getDefaultSharedPreferences() {
        return android.preference.PreferenceManager.getDefaultSharedPreferences(this);
    }

    @Override
    public String trustedNetworkToken() {
        return settingsManager != null ? settingsManager.trustedNetworkToken() : "";
    }

    @Override
    public String exportConfigurationJson() {
        return settingsManager != null ? settingsManager.exportConfigurationJson() : "{}";
    }

    /**
     * Imports a full device configuration from the web UI. On success the time zone is
     * re-applied and the core loop restarted, mirroring the on-device Save flow.
     */
    @Override
    public String importConfigurationJson(String json) {
        if (settingsManager == null) {
            return "settings manager unavailable";
        }
        final String error = settingsManager.importConfiguration(json);
        if (error == null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    settingsManager.updateTimeZone(MainActivity.this);
                    settingsChanged();
                }
            });
        }
        return error;
    }

    /**
     * Injects a key press into the activity (web-admin keycode probe; ADB is not
     * available on the frame). Runs on the web server's worker thread —
     * {@code sendKeyDownUpSync()} posts to the UI thread's looper and would deadlock
     * if called from that thread itself.
     */
    @Override
    public boolean sendKeyCode(int keyCode) {
        try {
            Log.i(TAG, "web keyevent: injecting keyCode=" + keyCode);
            new Instrumentation().sendKeyDownUpSync(keyCode);
            return true;
        }
        catch (Exception e) {
            Log.e(TAG, "web keyevent: injection failed for keyCode=" + keyCode, e);
            return false;
        }
    }

    private void hideSystemUI() {

        Window window = this.getWindow();
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        window.getDecorView().setSystemUiVisibility(
        // Do not let system steal touches for showing the navigation bar
        View.SYSTEM_UI_FLAG_IMMERSIVE
                // Hide the nav bar and status bar
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                // Keep the app content behind the bars even if user swipes them up
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        // make navbar translucent - do this already in hideSystemUI() so that the bar
        // is translucent if user swipes it up
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
    }
}
