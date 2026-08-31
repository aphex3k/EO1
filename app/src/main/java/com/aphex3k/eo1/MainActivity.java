package com.aphex3k.eo1;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP;
import static android.os.PowerManager.SCREEN_DIM_WAKE_LOCK;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.net.Uri;
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
import com.aphex3k.immichApi.ImmichApiServerVersionResponse;
import com.aphex3k.immichApi.ImmichApiService;
import com.aphex3k.immichApi.ImmichType;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.dd.crop.TextureVideoView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.gson.stream.MalformedJsonException;
import com.vdurmont.semver4j.Semver;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import okhttp3.HttpUrl;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity implements BrightnessManagerListener, EventManagerListener, SettingsManagerListener, UpdateManagerListener, MediaManagerListener, Thread.UncaughtExceptionHandler, ConnectionManagerListener, ApiServiceGenerator.ProgressListener {

    /**
    Amount of milliseconds in a minute
     */
    private static final long MILLIS = 60000;

    /** Inclusive lower bound for Immich server versions this APK is tested against. */
    public static final String IMMICH_MIN_VERSION = "3.0.0";
    /** Inclusive upper bound for Immich server versions this APK is tested against. */
    public static final String IMMICH_MAX_VERSION = "3.1.0";
    private View lastVisibleView;
    private String lastVisibleAsset = "";
    private ImageView imageView;
    private TextureVideoView videoView;
    private LinearProgressIndicator progressIndicator;
    private MqttManager mqttManager;
    private BrightnessManager brightnessManager;
    private TextView debugOverlay;
    private EventManager eventManager;
    private UpdateManager updateManager;
    private MediaManager mediaManager;
    private SettingsManager settingsManager;
    private final Map<String, String> debugInformation = new HashMap<>();
    private final Handler handler = new Handler();
    private PendingIntent pendingIntent;
    private Timer quietHoursTimer = new Timer(true);
    private float lastScreenBrightness = 0.3f;
    private ConnectionManager connectionManager;
    private PowerManager.WakeLock screenOffWakeLock;
    private Handler bannerHandler = new Handler(Looper.getMainLooper());
    private Runnable bannerFadeRunnable;
    private String currentTrackId = null;

    @SuppressLint({"ServiceCast", "WrongConstant"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        videoView = findViewById(R.id.videoView);
        debugOverlay = findViewById(R.id.debugOverlay);
        progressIndicator = findViewById(R.id.progressIndicator);

        progressIndicator.setVisibility(View.INVISIBLE);
        progressIndicator.setTrackColor(Color.argb(80,255,255,255));
        progressIndicator.setIndicatorColor(Color.argb(144,255,255,255));

        ViewCompat.setElevation(progressIndicator, ViewCompat.getElevation(imageView)+1);

        if (BuildConfig.DEBUG) {
            progressIndicator.setBackgroundColor(Color.blue(200));
            progressIndicator.setDrawingCacheBackgroundColor(Color.green(255));
        }

        this.brightnessManager = new BrightnessManager(this, (SensorManager) getSystemService(SENSOR_SERVICE));
        this.eventManager = new EventManager(this);
        this.updateManager = new UpdateManager(this);
        this.settingsManager = new SettingsManager(this);
        this.mediaManager = new MediaManager(this, this.settingsManager, this);
        this.connectionManager = new ConnectionManager(this);

        Thread.setDefaultUncaughtExceptionHandler(this);

        pendingIntent = PendingIntent.getActivity(
                getBaseContext(),
                0,
                new Intent(getIntent()),
                getIntent().getFlags());

        configureMqttManager();
    }

    private void configureMqttManager() {
        try {
            // Construct MQTT broker string from settings
            settingsManager.loadConfiguration();
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
        handler.postDelayed(this::runOnTimer, MILLIS * settingsManager.getConfiguration().interval);
        if (brightnessManager.getShouldTheScreenBeOn()) {
            brightnessChanged(lastScreenBrightness);
            mediaManager.showNextImage(this);
        }
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

        if (this.settingsManager.isSetupDialogIfNeeded())
        {
            showConfigurationUI();
        }
        else if (!this.settingsManager.isSetupDialogIfNeeded()) {
            handler.removeCallbacks(this::runOnTimer);
            handler.post(this::runOnTimer);
            setupQuietHours();

            final int startQuietHour = this.settingsManager.getConfiguration().startQuietHour;
            final int endQuietHour = this.settingsManager.getConfiguration().endQuietHour;
            final int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

            if (startQuietHour < now && endQuietHour > now) {
                turnScreenOff();
            }
            else {
                turnScreenOn();
            }

            checkServerCompatibility();
        }

        this.connectionManager.registerListener(this);

        if (this.mqttManager != null)
            try {
                String mqttUser = settingsManager.getConfiguration().mqttUser;
                String mqttPassword = settingsManager.getConfiguration().mqttPassword;
    //            if (mqttUser == null || mqttUser.isEmpty()) mqttUser = "eos1";
    //            if (mqttPassword == null) mqttPassword = "eos12345";
                if (mqttPassword == null) mqttPassword = "";
                this.mqttManager.connect(mqttUser, mqttPassword);
            }
            catch (Exception e) {
                handleException(e);
            }

        debugInformationProvided(new DebugInformation("version", BuildConfig.VERSION_NAME + "." + BuildConfig.VERSION_CODE));
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), this.connectionManager.isNetworkAvailable() ? "connected" : "disconnected"));
    }

    private void checkServerCompatibility() {
        new Thread(() -> {

            try {
                Configuration configuration = settingsManager.getConfiguration();
                ImmichApiService apiService = ApiServiceGenerator.createService(ImmichApiService.class, configuration.host, this, null);

                Response<ImmichApiServerVersionResponse> serverVersionResponse = apiService.getServerVersion().execute();

                if (serverVersionResponse.body() != null) {
                    Semver serverVersion = serverVersionResponse.body().getVersion();

                    runOnUiThread(() -> {
                        String message = null;
                        if (serverVersion == null) {
                            message = "Unable to parse Immich server version.";
                        } else if (serverVersion.isLowerThan(IMMICH_MIN_VERSION)) {
                            message = String.format(Locale.US, "Immich server version %s is incompatible!", serverVersion);
                        } else if (serverVersion.isGreaterThan(IMMICH_MAX_VERSION)) {
                            message = String.format(Locale.US, "Immich server version %s is unsupported.", serverVersion);
                        }

                        if (message != null) {
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            debugInformationProvided(new DebugInformation("serverVersion", message));
                        }
                    });
                }
            } catch (Exception e) {
                handleException(e);
            }

        }).start();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(this::runOnTimer);
        this.quietHoursTimer.cancel();
        this.quietHoursTimer.purge();
        this.connectionManager.unregisterListener(this);
        try {
            this.mqttManager.disconnect();
        }
        catch (Exception e) {
            handleException(e);
        }
        super.onPause();
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
     * Turn off the screen to conserve as much energy as possible without shutting down the device
     */
    private void turnScreenOff() {
        Window window = this.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (screenOffWakeLock != null) {
            screenOffWakeLock.release();
            debugInformationProvided(new DebugInformation("screenOffWakeLock", "released"));
        }

        videoView.setVisibility(View.INVISIBLE);
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
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://gitea.codingmerc.com/michael/EO1"));
        startActivity(browserIntent);
    }

    @Override
    public void settingsChanged() {
        handler.removeCallbacks(this::runOnTimer);
        handler.post(this::runOnTimer);
        setupQuietHours();
        checkServerCompatibility();
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

        quietHoursTimer = new Timer(true);

        final int startQuietHour = this.settingsManager.getConfiguration().startQuietHour;
        final int endQuietHour = this.settingsManager.getConfiguration().endQuietHour;

        final Calendar calendar = Calendar.getInstance();
        final Date time = calendar.getTime();

        final Calendar startCalendar = Calendar.getInstance();
        startCalendar.set(Calendar.HOUR_OF_DAY, startQuietHour);
        startCalendar.set(Calendar.MINUTE, 0);
        startCalendar.set(Calendar.SECOND, 0);

        if (startCalendar.getTime().before(time)) {
            startCalendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        final Calendar endCalendar = Calendar.getInstance();
        endCalendar.set(Calendar.HOUR_OF_DAY, endQuietHour);
        endCalendar.set(Calendar.MINUTE, 0);
        endCalendar.set(Calendar.SECOND, 0);

        if (endCalendar.getTime().before(time)) {
            endCalendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        final long period = 24 * 60 * MILLIS;
        final DateFormat debugDateFormatter = new SimpleDateFormat("MMM d, yyyy HH:mm a", Locale.US);

        final Date startTime = startCalendar.getTime();
        final Date endTime = endCalendar.getTime();

        quietHoursTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (brightnessManager != null) {
                        if (Boolean.TRUE.equals(brightnessManager.getShouldTheScreenBeOn())) {
                            eventManager.onKeyDown(KeyEvent.EO1_TOP_BUTTON);
                        }
                        debugInformationProvided(new DebugInformation("startQuietHours", "Start of quiet hours triggered at " + debugDateFormatter.format(startCalendar)));
                    }
                }
                catch (Exception e) {
                    handleException(e);
                }
            }
        }, startTime, period);

        quietHoursTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (brightnessManager != null) {
                        if (Boolean.FALSE.equals(brightnessManager.getShouldTheScreenBeOn())) {
                            eventManager.onKeyDown(KeyEvent.EO1_TOP_BUTTON);
                        }
                        debugInformationProvided(new DebugInformation("endQuietHours", "End of quiet hours triggered at " + debugDateFormatter.format(endCalendar)));
                    }
                }
                catch (Exception e) {
                    handleException(e);
                }
            }
        }, endTime, period);

        debugInformationProvided(new DebugInformation("startCalendar", "Start of quiet hours scheduled for " + debugDateFormatter.format(startTime)));
        debugInformationProvided(new DebugInformation("endCalendar", "End of quiet hours scheduled for " + debugDateFormatter.format(endTime)));
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
        });
        Log.e(e.getClass().toString(), e.getMessage() != null ? e.getMessage() : "");

        debugInformationProvided(new DebugInformation("Last Exception", e.toString()));
    }

    public void debugInformationProvided(@NonNull DebugInformation debugInformation) {

        this.runOnUiThread(() -> {
            if (BuildConfig.DEBUG) {
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
        });
        Log.d(debugInformation.getKey(), debugInformation.getValue());
    }

    @Override
    public void displayPicture(File file, String assetId) {

        if (file.getAbsolutePath().equals(lastVisibleAsset)) {
            return;
        }

        if (!this.brightnessManager.getShouldTheScreenBeOn()) {
            return;
        }

        debugInformationProvided(new DebugInformation("displayPictures", file.getAbsolutePath()));

        this.runOnUiThread(() -> {

            WeakReference<MainActivity> activityReference = new WeakReference<>(this);

            try {
                videoView.stop();
                videoView.setVisibility(View.INVISIBLE);
                Glide.with(this).clear(imageView);

                if (MediaTypeHelper.isGifFile(file)) {
                    Glide.with(this)
                            .asGif()
                            .load(file)
                            .centerCrop()
                            .listener(new RequestListener<GifDrawable>() {
                                @Override
                                public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model, @NonNull Target<GifDrawable> target, boolean isFirstResource) {
                                    assetFallback(assetId, ImmichType.IMAGE, activityReference, false, file);
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
                                    assetFallback(assetId, ImmichType.IMAGE, activityReference, false, file);
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
                assetFallback(assetId, ImmichType.IMAGE, activityReference, false, file);
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
        videoView.stop();
        videoView.setVisibility(View.INVISIBLE);
        imageView.setVisibility(View.VISIBLE);
        lastVisibleView = imageView;
    }

    private void assetFallback(String assetId, ImmichType type, WeakReference<MainActivity> activityReference, boolean isVideo, File file) {
        if (isVideo && mediaManager.shouldAttemptReactiveTranscode(assetId, file)) {
            MainActivity activity = activityReference.get();
            if (activity != null) {
                restoreImageViewAfterVideoFailure();
                mediaManager.attemptReactiveTranscode(activity, assetId, file, new MediaManager.ReactiveTranscodeCallback() {
                    @Override
                    public void onTranscodeSuccess(File transcodedFile) {
                        displayVideo(transcodedFile, assetId);
                    }

                    @Override
                    public void onTranscodeFailed() {
                        immichPlaybackFallback(assetId, type, activityReference, isVideo, file);
                    }
                });
                return;
            }
        }
        immichPlaybackFallback(assetId, type, activityReference, isVideo, file);
    }

    private void immichPlaybackFallback(String assetId, ImmichType type, WeakReference<MainActivity> activityReference, boolean isVideo, File file) {
        if (isVideo) {
            restoreImageViewAfterVideoFailure();
        }
        if (assetId != null) {
            MainActivity activity = activityReference.get();
            if (activity != null) {
                mediaManager.displayThumbnailAsset(activity, assetId, type, isVideo);
            }
            mediaManager.tagAssetAsIncompatible(assetId);
        } else {
            showNextImage();
        }
        mediaManager.removeFromCache(file);
    }

    @Override
    public void displayVideo(File file, String assetId) {

        if (file.getAbsolutePath().equals(lastVisibleAsset)) {
            return;
        }

        if (!this.brightnessManager.getShouldTheScreenBeOn()) {
            return;
        }

        debugInformationProvided(new DebugInformation("displayVideo", file.getAbsolutePath()));

        WeakReference<MainActivity> activityReference = new WeakReference<>(this);

        this.runOnUiThread(() -> {
            try {
                videoView.stop();
                videoView.setVisibility(View.INVISIBLE);
                videoView.setListener(new TextureVideoView.MediaPlayerListener() {
                    @Override
                    public void onVideoPrepared() {
                        try {
                            Glide.with(MainActivity.this).clear(imageView);
                            imageView.setVisibility(View.INVISIBLE);
                            videoView.setVisibility(View.VISIBLE);
                            lastVisibleView = videoView;
                            lastVisibleAsset = file.getAbsolutePath();
                            videoView.setLooping(true);
                            videoView.setFocusable(false);
                            videoView.setVolume(0, 0);
                            videoView.play();
                        } catch (Exception e) {
                            restoreImageViewAfterVideoFailure();
                            assetFallback(assetId, ImmichType.VIDEO, activityReference, true, file);
                            handleException(e);
                        }
                    }

                    @Override
                    public void onVideoEnd() {
                    }

                    public boolean onError() {
                        restoreImageViewAfterVideoFailure();
                        assetFallback(assetId, ImmichType.VIDEO, activityReference, true, file);
                        return false;
                    }

                    public boolean onInfo(int what, int extra) {
                        return false;
                    }
                });
                videoView.setDataSource(file.getPath());
                videoView.setLooping(true);
            } catch (Exception e) {
                restoreImageViewAfterVideoFailure();
                assetFallback(assetId, ImmichType.VIDEO, activityReference, true, file);
                handleException(e);
            }
        });
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        AlarmManager mgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 120000, pendingIntent);
        System.exit(2);
    }

    @Override
    public void connected() {
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), "connected"));
        showNextImage();
    }

    @Override
    public void disconnected() {
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), "disconnected"));
    }

    @Override
    public void clientProgressUpdates(long bytesRead, long contentLength, boolean done, @Nullable HttpUrl url) {

        long percent =  Math.round((100.0 * bytesRead) / contentLength);

        if (done) {
            Log.i("download", "completed");
        } else if (percent == 0.0) {
            if (contentLength == -1) {
                Log.i("download", "content-length: unknown");
            } else {
                Log.i("download", "content-length: " + contentLength);
            }
        }

        if (contentLength != -1 && !done) {
            Log.d("download", String.format("%d%% downloaded...\n",percent));
        }

        if (url != null && (url.toString().endsWith("video/playback") || url.toString().contains("/thumbnail"))) {

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
