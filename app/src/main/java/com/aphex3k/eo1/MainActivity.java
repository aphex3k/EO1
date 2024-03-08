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
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.dd.crop.TextureVideoView;
import com.google.gson.stream.MalformedJsonException;

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

public class MainActivity extends AppCompatActivity implements BrightnessManagerListener, EventManagerListener, SettingsManagerListener, UpdateManagerListener, MediaManagerListener, Thread.UncaughtExceptionHandler, ConnectionManagerListener {

    /**
    Amount of milliseconds in a minute
     */
    private static final long MILLIS = 60000;
    private View lastVisibleView;
    private String lastVisibleAsset = "";
    private ImageView imageView;
    private TextureVideoView videoView;
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

    @SuppressLint({"ServiceCast", "WrongConstant"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        videoView = findViewById(R.id.videoView);
        debugOverlay = findViewById(R.id.debugOverlay);

        this.brightnessManager = new BrightnessManager(this, (SensorManager) getSystemService(SENSOR_SERVICE));
        this.eventManager = new EventManager(this);
        this.updateManager = new UpdateManager(this);
        this.settingsManager = new SettingsManager(this);
        this.mediaManager = new MediaManager(this, this.settingsManager);
        this.connectionManager = new ConnectionManager(this);

        Thread.setDefaultUncaughtExceptionHandler(this);

        pendingIntent = PendingIntent.getActivity(
                getBaseContext(),
                0,
                new Intent(getIntent()),
                getIntent().getFlags());
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

        if (!this.settingsManager.showSetupDialogIfNeeded(this)) {
            handler.removeCallbacks(this::runOnTimer);
            handler.post(this::runOnTimer);
            setupQuietHours();
        }

        this.connectionManager.registerListener(this);

        debugInformationProvided(new DebugInformation("version", BuildConfig.VERSION_NAME + "." + BuildConfig.VERSION_CODE));
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), this.connectionManager.isNetworkAvailable() ? "connected" : "disconnected"));

        final int startQuietHour = this.settingsManager.getConfiguration().startQuietHour;
        final int endQuietHour = this.settingsManager.getConfiguration().endQuietHour;
        final int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

        if (startQuietHour < now && endQuietHour > now) {
            turnScreenOff();
        }
        else {
            turnScreenOn();
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(this::runOnTimer);
        this.quietHoursTimer.cancel();
        this.quietHoursTimer.purge();
        this.connectionManager.unregisterListener(this);
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
        this.settingsManager.showSetupDialog(this);
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
        this.mediaManager.showNextImage(this);
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

        quietHoursTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (brightnessManager != null) {
                    if (Boolean.TRUE.equals(brightnessManager.getShouldTheScreenBeOn())) {
                        toggleScreenOn();
                    }
                    debugInformationProvided(new DebugInformation("startQuietHours", "Start of quiet hours triggered at " + debugDateFormatter.format(startCalendar)));
                }
            }
        }, startTime, period);

        quietHoursTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (brightnessManager != null) {
                    if (Boolean.FALSE.equals(brightnessManager.getShouldTheScreenBeOn())) {
                        toggleScreenOn();
                    }
                    debugInformationProvided(new DebugInformation("endQuietHours", "End of quiet hours triggered at " + debugDateFormatter.format(endCalendar)));
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
                settingsManager.showSetupDialogIfNeeded(this);
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

        debugInformationProvided(new DebugInformation("displayPictures", file.getAbsolutePath()));

        this.runOnUiThread(() -> {

            WeakReference<MainActivity> activityReference = new WeakReference<>(this);

            try {
                Glide.with(this)
                        .load(file)
                        .optionalCenterCrop()
                        .skipMemoryCache(true)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                                assetFallback(assetId, activityReference, false, file);
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                                if (lastVisibleView == videoView || lastVisibleView == null) {
                                    videoView.stop();
                                    videoView.setVisibility(View.INVISIBLE);
                                    imageView.setVisibility(View.VISIBLE);
                                    lastVisibleView = imageView;
                                }
                                lastVisibleAsset = file.getAbsolutePath();
                                return false;
                            }
                        })
                        .into(imageView);
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
    private void assetFallback(String assetId, WeakReference<MainActivity> activityReference, boolean isVideo, File file) {
        if (assetId != null) {
            MainActivity activity = activityReference.get();
            if (activity != null) {
                mediaManager.displayThumbnailAsset(activity, assetId, isVideo);
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

        debugInformationProvided(new DebugInformation("displayVideo", file.getAbsolutePath()));

        WeakReference<MainActivity> activityReference = new WeakReference<>(this);

        this.runOnUiThread(() -> {
            try {
                videoView.stop();
                videoView.setDataSource(file.getPath());
                videoView.setListener(new TextureVideoView.MediaPlayerListener() {
                    @Override
                    public void onVideoPrepared() {
                        try {
                            if (lastVisibleView == imageView || lastVisibleView == null) {
                                videoView.setVisibility(View.VISIBLE);
                                imageView.setVisibility(View.INVISIBLE);
                                lastVisibleView = videoView;
                            }
                            lastVisibleAsset = file.getAbsolutePath();
                            videoView.setLooping(true);
                            videoView.setFocusable(false);
                            videoView.setVolume(0,0);
                            videoView.play();
                        } catch (Exception e) {
                            assetFallback(assetId, activityReference, true, file);
                            handleException(e);
                        }
                    }

                    @Override
                    public void onVideoEnd() {
                    }

                    public boolean onError() {
                        assetFallback(assetId, activityReference, true, file);
                        return false;
                    }
                });
            } catch (Exception e) {
                assetFallback(assetId, activityReference, true, file);
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
}
