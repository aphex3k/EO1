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

import com.aphex3k.eo1.mqtt.MqttManager;
import com.aphex3k.eo1.mqtt.Payload;
import com.aphex3k.eo1.mqtt.PlaybackListener;
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

public class MainActivity extends AppCompatActivity implements BrightnessManagerListener, EventManagerListener, SettingsManagerListener, UpdateManagerListener, MediaManagerListener, Thread.UncaughtExceptionHandler, ConnectionManagerListener, ApiServiceGenerator.ProgressListener, WebController {

    /**
    Amount of milliseconds in a minute
     */
    private static final long MILLIS = 60000;
    private static final long VIDEO_WATCHDOG_POLL_MS = 2000L;
    private static final long VIDEO_STALL_THRESHOLD_MS = 8000L;
    /** Logcat tag matched by debug.sh follow_logcat filter. */
    private static final String TAG = "EO1";
    private View lastVisibleView;
    private String lastVisibleAsset = "";
    private String activeVideoAssetId = "";
    private ImageView imageView;
    private TextureVideoView mediaPlayerVideoView;
    private TsVideoView tsVideoView;
    private VideoPlayerController videoPlayer;
    private MediaPlayerController mediaPlayerController;
    private TsPlayerController tsPlayerController;
    private boolean preferTsPlayer;
    private int tsFallbackCount;
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
    private float lastScreenBrightness = 0.3f;
    private ConnectionManager connectionManager;
    private AppLogger appLogger;
    private WebServer webServer;
    /** True once the stale incoming_* cleanup has run for this process (first onCreate only). */
    private static volatile boolean sStaleIncomingCleaned = false;
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
    private int videoWatchdogLastPosition = -1;
    private long videoWatchdogLastProgressMs = 0L;
    private boolean videoWatchdogRecoverAttempted = false;
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

        this.capabilities = HardwareCapabilitiesFactory.detect(Build.DEVICE, Build.MODEL);
        Log.i(TAG, "hardware: " + capabilities.platform() + " (device=" + Build.DEVICE
                + ", model=" + Build.MODEL + ")");
        this.brightnessManager = new BrightnessManager(this,
                (SensorManager) getSystemService(SENSOR_SERVICE), capabilities);
        this.eventManager = new EventManager(this, capabilities);
        this.settingsManager = new SettingsManager(this);
        this.updateManager = new UpdateManager(this, this, this.settingsManager);
        this.mediaManager = new MediaManager(this, this.settingsManager, this);
        this.connectionManager = new ConnectionManager(this);

        // Always-on LAN web server (debug / control / media upload).
        this.appLogger = new AppLogger(this);
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

        Thread.setDefaultUncaughtExceptionHandler(this);

        pendingIntent = PendingIntent.getActivity(
                getBaseContext(),
                0,
                new Intent(getIntent()),
                getIntent().getFlags());

        configureMqttManager();

        // The physical button on the EO2 is an OS-intercepted KEY_POWER: the display can
        // go dark or light without any key event reaching this activity. Watch for it.
        if (capabilities.supportsPowerButton()) {
            IntentFilter displayStateFilter = new IntentFilter();
            displayStateFilter.addAction(Intent.ACTION_SCREEN_ON);
            displayStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(displayStateReceiver, displayStateFilter);
        }
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
            brightnessChanged(lastScreenBrightness);
            mediaManager.showNextImage(this);
        } else {
            Log.i(TAG, "runOnTimer: skipping showNextImage (screen should be off / quiet hours)");
        }
        // Self-update: interval-gated check, plus auto-retry of a staged-but-uninstalled APK.
        updateManager.checkIfDue();
        updateManager.installStaged(false);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {

        if (this.eventManager.onKeyDown(keyCode)) {
            return true;
        }
        else {
            return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // If this process fired an install and is still alive, the install did not take
        // effect — roll the state back to STAGED so the next tick can retry.
        updateManager.reconcileInstallOutcome();

        if (BuildConfig.DEBUG) { hideSystemUI(); }

        debugOverlay.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);

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

        debugInformationProvided(new DebugInformation("version", BuildConfig.VERSION_NAME + "." + BuildConfig.VERSION_CODE));
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
        cancelVideoWatchdog();
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

        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();

        if (this.brightnessManager.getShouldTheScreenBeOn()) {

            layoutParams.screenBrightness = Math.max(targetScreenBrightness, layoutParams.screenBrightness);
        }
        else {
            layoutParams.screenBrightness = 0;
        }

        lastScreenBrightness = layoutParams.screenBrightness;

        getWindow().setAttributes(layoutParams);
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
        brightnessChanged(lastScreenBrightness);
    }

    @Override
    public void showConfigurationUI() {
        this.runOnUiThread(() -> {
            OptionsDialogFragment dialog = new OptionsDialogFragment(this.settingsManager);
            dialog.show(getSupportFragmentManager(), "OptionsDialog");
        });
    }

    @Override
    public void adjustMinimumBrightness() {
        if (!capabilities.supportsScreenBrightness()) {
            Log.i(TAG, "adjustMinimumBrightness: ignored (" + capabilities.platform() + " has no screen brightness control)");
            return;
        }
        this.brightnessManager.setShouldTheScreenBeOn(true);
        this.brightnessManager.adjustMinimumBrightness();
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.screenBrightness = this.brightnessManager.minBrightness;
        getWindow().setAttributes(layoutParams);
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
            this.appLogger.error(TAG, e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
        debugInformationProvided(new DebugInformation("Last Exception", e.toString()));
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

        if (file.getAbsolutePath().equals(lastVisibleAsset)) {
            Log.i(TAG, "displayPicture: skip same asset " + assetId);
            return;
        }

        if (!this.brightnessManager.getShouldTheScreenBeOn()) {
            Log.i(TAG, "displayPicture: skip (screen should be off) asset=" + assetId);
            return;
        }

        debugInformationProvided(new DebugInformation("displayPictures", file.getAbsolutePath()));

        this.runOnUiThread(() -> {
            cancelVideoWatchdog();
            // No video is active while an image shows; a stale video callback (incl. one from a
            // null-assetId fallback stream) must not match activeVideoAssetId.
            activeVideoAssetId = "";

            WeakReference<MainActivity> activityReference = new WeakReference<>(this);

            try {
                videoPlayer.stop();
                videoPlayer.setVisibility(View.INVISIBLE);
                Glide.with(this).clear(imageView);

                if (MediaTypeHelper.isGifFile(file)) {
                    Glide.with(this)
                            .asGif()
                            .load(file)
                            .centerCrop()
                            .listener(new RequestListener<GifDrawable>() {
                                @Override
                                public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model, @NonNull Target<GifDrawable> target, boolean isFirstResource) {
                                    assetFallback(assetId, activityReference, false, file);
                                    return false;
                                }

                                @Override
                                public boolean onResourceReady(@NonNull GifDrawable resource, @NonNull Object model, Target<GifDrawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                                    resource.setLoopCount(GifDrawable.LOOP_FOREVER);
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
                                    assetFallback(assetId, activityReference, false, file);
                                    return false;
                                }

                                @Override
                                public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
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
            catch (Exception e) {
                handleException(e);
                assetFallback(assetId, activityReference, false, file);
            }
        });
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
        videoPlayer.stop();
        videoPlayer.setVisibility(View.INVISIBLE);
        imageView.setVisibility(View.VISIBLE);
        lastVisibleView = imageView;
    }

    private void assetFallback(String assetId, WeakReference<MainActivity> activityReference, boolean isVideo, File file) {
        // assetId may be null for the /video/playback fallback stream; Objects.equals keeps
        // this guard null-safe (a bare assetId.equals() would NPE and kill the app).
        if (isVideo && !Objects.equals(assetId, activeVideoAssetId)) {
            return;
        }
        playbackFallback(assetId, activityReference, isVideo, file);
    }

    private void playbackFallback(String assetId, WeakReference<MainActivity> activityReference, boolean isVideo, File file) {
        if (isVideo) {
            restoreImageViewAfterVideoFailure();
        }
        if (assetId != null) {
            MainActivity activity = activityReference.get();
            if (activity != null) {
                mediaManager.displayThumbnailAsset(activity, assetId, isVideo);
            }
            mediaManager.tagAssetAsIncompatible(assetId);
        } else {
            showNextImage();
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
    }

    private void startVideoWatchdog(String assetId, File file, WeakReference<MainActivity> activityReference) {
        cancelVideoWatchdog();
        // The /video/playback fallback stream is displayed with a null asset id; the
        // same-asset guard in runVideoWatchdogCheck cannot match it, so skip arming.
        if (assetId == null) {
            debugInformationProvided(new DebugInformation("video", "watchdog skipped (fallback stream, no asset id)"));
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

        if (!assetId.equals(activeVideoAssetId)
                || !brightnessManager.getShouldTheScreenBeOn()
                || videoPlayer.getVisibility() != View.VISIBLE) {
            cancelVideoWatchdog();
            return;
        }

        int position = videoPlayer.getCurrentPosition();
        long now = System.currentTimeMillis();

        if (position != videoWatchdogLastPosition) {
            videoWatchdogLastPosition = position;
            videoWatchdogLastProgressMs = now;
            videoWatchdogRecoverAttempted = false;
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
                    "stall recover pos=" + position + " seek=" + seekTarget + " duration=" + duration));
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
        if (fallbackTsPlayerToMediaPlayer(assetId, file, activityReference)) {
            return;
        }
        restoreImageViewAfterVideoFailure();
        assetFallback(assetId, activityReference, true, file);
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

        if (file.getAbsolutePath().equals(lastVisibleAsset)) {
            Log.i(TAG, "displayVideo: skip same asset " + assetId);
            return;
        }

        if (!this.brightnessManager.getShouldTheScreenBeOn()) {
            Log.i(TAG, "displayVideo: skip (screen should be off) asset=" + assetId);
            return;
        }

        debugInformationProvided(new DebugInformation("displayVideo", file.getAbsolutePath()));

        WeakReference<MainActivity> activityReference = new WeakReference<>(this);
        activeVideoAssetId = assetId;

        this.runOnUiThread(() -> {
            try {
                cancelVideoWatchdog();
                selectVideoPlayer(true);
                int videoDurationMs = mediaManager != null ? mediaManager.getVideoDurationMs(assetId) : -1;
                startVideoOnController(videoPlayer, file, assetId, videoDurationMs, activityReference, true);
            } catch (Exception e) {
                restoreImageViewAfterVideoFailure();
                assetFallback(assetId, activityReference, true, file);
                handleException(e);
            }
        });
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
            videoPlayer.stop();
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
     * After TsPlayer stalls or errors, hand the same file to MediaPlayer once.
     *
     * @return true if MediaPlayer playback was started
     */
    private boolean fallbackTsPlayerToMediaPlayer(String assetId, File file,
                                                  WeakReference<MainActivity> activityReference) {
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
            startVideoOnController(videoPlayer, file, assetId, videoDurationMs, activityReference, false);
            return true;
        } catch (Exception e) {
            handleException(e);
            return false;
        }
    }

    private void startVideoOnController(VideoPlayerController controller, File file, String assetId,
                                        int durationHintMs,
                                        WeakReference<MainActivity> activityReference,
                                        boolean allowTsFallback) {
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
                    startVideoWatchdog(assetId, file, activityReference);
                } catch (Exception e) {
                    if (allowTsFallback && fallbackTsPlayerToMediaPlayer(assetId, file, activityReference)) {
                        return;
                    }
                    restoreImageViewAfterVideoFailure();
                    assetFallback(assetId, activityReference, true, file);
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
                if (allowTsFallback && fallbackTsPlayerToMediaPlayer(assetId, file, activityReference)) {
                    return true;
                }
                restoreImageViewAfterVideoFailure();
                assetFallback(assetId, activityReference, true, file);
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
        controller.setDataSource(file.getPath());
        controller.setLooping(true);
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        if (this.appLogger != null) {
            this.appLogger.error(TAG, "FATAL uncaught on " + thread.getName() + ": " + throwable);
        }
        AlarmManager mgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 120000, pendingIntent);
        System.exit(2);
    }

    @Override
    public void connected() {
        Log.i(TAG, "ConnectionManager: connected");
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), "connected"));
        startCoreLoopIfNetworkReady();
    }

    @Override
    public void disconnected() {
        Log.i(TAG, "ConnectionManager: disconnected");
        pauseCoreLoopForNetwork();
    }

    @Override
    public void clientProgressUpdates(long bytesRead, long contentLength, boolean done, @Nullable HttpUrl url) {

        long percent =  Math.round((100.0 * bytesRead) / contentLength);

        if (done) {
            Log.i(TAG, "download: completed");
        } else if (percent == 0.0) {
            if (contentLength == -1) {
                Log.i(TAG, "download: content-length unknown");
            } else {
                Log.i(TAG, "download: content-length " + contentLength);
            }
        }

        if (contentLength != -1 && !done) {
            Log.d(TAG, "download: " + percent + "%");
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
    public String buildStateJson() {
        JsonObject o = new JsonObject();

        JsonObject device = new JsonObject();
        device.addProperty("manufacturer", Build.MANUFACTURER);
        device.addProperty("model", Build.MODEL);
        device.addProperty("android", Build.VERSION.RELEASE);
        device.addProperty("platform", capabilities.platform());
        o.add("device", device);

        JsonObject caps = new JsonObject();
        caps.addProperty("lightSensor", capabilities.supportsLightSensor());
        caps.addProperty("screenBrightness", capabilities.supportsScreenBrightness());
        caps.addProperty("brightnessButton", capabilities.supportsBrightnessButton());
        caps.addProperty("powerButton", capabilities.supportsPowerButton());
        caps.addProperty("screenToggleKeyCode", capabilities.screenToggleKeyCode());
        o.add("capabilities", caps);

        JsonObject app = new JsonObject();
        app.addProperty("version", BuildConfig.VERSION_NAME + "." + BuildConfig.VERSION_CODE);
        app.addProperty("debug", BuildConfig.DEBUG);
        app.addProperty("tsPlayer", preferTsPlayer);
        app.addProperty("webPort", webServer != null ? webServer.getBoundPort() : -1);
        app.addProperty("trustedNetwork", settingsManager != null && settingsManager.isTrustedNetwork());
        o.add("app", app);

        o.add("config", ConfigStateJson.configStateJson(settingsManager.getConfiguration()));

        JsonObject network = new JsonObject();
        network.addProperty("available", connectionManager.isNetworkAvailable());
        network.add("wifi", DeviceTelemetry.wifi(this));
        o.add("network", network);

        JsonObject media = new JsonObject();
        media.addProperty("rotationAssets", mediaManager.rotationListSize());
        media.addProperty("screenOn", brightnessManager.getShouldTheScreenBeOn());
        o.add("media", media);

        o.add("update", updateManager.stateJson());

        o.add("telemetry", DeviceTelemetry.snapshot(this));

        if (BuildConfig.DEBUG) {
            JsonObject video = new JsonObject();
            video.addProperty("active", videoPlayer == tsPlayerController ? "TsPlayer" : "MediaPlayer");
            video.addProperty("preferTsPlayer", preferTsPlayer);
            video.addProperty("tsAvailable", TsPlayerNative.isAvailable());
            video.addProperty("tsFallbacks", tsFallbackCount);
            if (tsVideoView != null) {
                video.addProperty("generation", tsVideoView.getLoopGeneration());
                video.addProperty("errors", tsVideoView.getVideoErrorCount());
                video.addProperty("boundaryResets", tsVideoView.getBoundaryResets());
                video.addProperty("playerCreated", tsVideoView.isPlayerCreated());
                video.addProperty("surfaceReady", tsVideoView.isSurfaceReady());
                video.addProperty("recreatingForLoop", tsVideoView.isRecreatingForLoop());
                video.addProperty("path", tsVideoView.getActivePath());
                video.addProperty("durationMs", tsVideoView.getDuration());
                video.addProperty("positionMs", tsVideoView.getCurrentPosition());
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

    /**
     * Mirrors a hardware key press from the web UI. Every action is posted to the UI thread
     * because most of them touch views or start activities — except {@code keyevent}, which
     * is injected from the calling web worker thread: {@code Instrumentation#sendKeyDownUpSync}
     * blocks on the UI thread's looper and would deadlock if posted to that thread.
     *
     * <p>{@code keyevent} is a debug-build-only admin action: it injects arbitrary keycodes,
     * so a release build refuses it (the web server also requires POST for it).
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
        final Runnable task;
        switch (action) {
            case "next":
                task = this::showNextImage;
                break;
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
