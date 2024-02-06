package com.aphex3k.eo1;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.stream.MalformedJsonException;
import com.squareup.picasso.Callback;
import com.squareup.picasso.MemoryPolicy;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements BrightnessManagerListener, EventManagerListener, SettingsManagerListener, UpdateManagerListener, MediaManagerListener, Thread.UncaughtExceptionHandler {

    /**
    Amount of milliseconds in a minute
     */
    private static final long MILLIS = 60000;
    private ImageView imageView;
    private VideoView videoView;
    private BrightnessManager brightnessManager;
    private TextView debugOverlay;
    private EventManager eventManager;
    private UpdateManager updateManager;
    private MediaManager mediaManager;
    private SettingsManager settingsManager;
    private final Map<String, String> debugInformation = new HashMap<>();
    private final Handler handler = new Handler();
    private MediaController mediaController;
    private PendingIntent pendingIntent;

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
        mediaManager.showNextImage(this);
        handler.postDelayed(this::runOnTimer, MILLIS * settingsManager.getConfiguration().interval);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        this.eventManager.onKeyDown(keyCode);
        return super.onKeyDown(keyCode, event);
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
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(this::runOnTimer);
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

        getWindow().setAttributes(layoutParams);
    }

    @Override
    public void checkForUpdates() {
        this.updateManager.checkForUpdates(this);
    }

    @Override
    public void toggleScreenOn() {
        this.brightnessManager.toggleShouldTheScreenBeOn();
        handler.removeCallbacks(this::runOnTimer);

        if (this.brightnessManager.getShouldTheScreenBeOn()) {
            getWindow().addFlags(FLAG_KEEP_SCREEN_ON);
            handler.post(this::runOnTimer);
        }
        else {
            getWindow().clearFlags(FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void showConfigurationUI() {
        this.settingsManager.showSetupDialog(this);
    }

    @Override
    public void adjustMinimumBrightness() {
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
    public void settingsChanged() {
        handler.removeCallbacks(this::runOnTimer);
        handler.post(this::runOnTimer);
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
                Toast.makeText(MainActivity.this, "User authentication failure. Check your configuration.", Toast.LENGTH_SHORT).show();
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

    @Override
    public void debugInformationProvided(DebugInformation debugInformation) {

        this.runOnUiThread(() -> {
            if (BuildConfig.DEBUG) {
                this.debugInformation.put(debugInformation.getKey(), debugInformation.getValue());

                StringBuilder debugText = new StringBuilder();

                Set<Map.Entry<String, String>> set = this.debugInformation.entrySet();

                for (Map.Entry<String, String> info : set) {
                    debugText.append(info.getKey()).append(": ").append(info.getValue()).append("\n");
                }

                this.debugOverlay.setText(debugText.toString().trim());
            }
        });
        Log.d(debugInformation.getKey(), debugInformation.getValue());
    }

    @Override
    public void displayPicture(File file, String assetId) {

        this.runOnUiThread(() -> {
            if (videoView.isPlaying()) {
                videoView.stopPlayback();
            }
            videoView.setVisibility(View.INVISIBLE);
            imageView.setVisibility(View.VISIBLE);

            try {
                WeakReference<MainActivity> activityReference = new WeakReference<>(this);

                Picasso.get()
                        .load(file)
                        .fit()
                        .centerInside()
                        .memoryPolicy(MemoryPolicy.NO_CACHE, MemoryPolicy.NO_STORE)
                        .into(imageView, new Callback() {
                    @Override
                    public void onSuccess() {
                        mediaManager.removeFromCache(file);
                    }

                    @Override
                    public void onError(Exception e) {
                        handleException(e);
                        if (assetId != null) {
                            mediaManager.tagAssetAsIncompatible(assetId);
                            MainActivity activity = activityReference.get();
                            if (activity != null) {
                                mediaManager.displayThumbnailAsset(activity, assetId, false);
                            }
                        }
                        else {
                            showNextImage();
                        }
                        mediaManager.removeFromCache(file);
                    }
                });
            }
            catch (Exception e) {
                handleException(e);
            }
        });
    }

    @Override
    public void displayVideo(File file, String assetId) {

        WeakReference<MainActivity> activityReference = new WeakReference<>(this);

        this.runOnUiThread(() -> {
            if (mediaController == null) {
                mediaController = new MediaController(MainActivity.this);
                mediaController.setAnchorView(videoView);
                mediaController.setVisibility(View.INVISIBLE);
                videoView.setMediaController(mediaController);

                videoView.setOnPreparedListener(mediaPlayer -> {
                    try {
                        mediaPlayer.setLooping(true);
                        mediaPlayer.setVolume(0f, 0f);
                    } catch (Exception e) {
                        handleException(new VideoPlaybackPreparedException(e));
                    }
                });
                videoView.setOnErrorListener((mediaPlayer, i, i1) -> {
                    try {
                        mediaManager.removeFromCache(file);
                        MainActivity activity = activityReference.get();
                        if (activity != null) {
                            mediaManager.displayThumbnailAsset(activity, assetId, true);
                        }
                    } catch (Exception e) {
                        handleException(new VideoPlaybackErrorException(e));
                    }
                    return true;
                });
            }

            try {
                videoView.setVisibility(View.VISIBLE);
                imageView.setVisibility(View.INVISIBLE);
                if (videoView.isPlaying()) {
                    videoView.stopPlayback();
                }
                videoView.setVideoPath(file.getPath());
                videoView.start();
            } catch (Exception e) {
                handleException(new VideoPlaybackPreparationException(e));
                MainActivity activity = activityReference.get();
                if (activity != null) {
                    mediaManager.displayThumbnailAsset(activity, assetId, true);
                }
            }
        });
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        AlarmManager mgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 2000, pendingIntent);
        System.exit(2);
    }
}
