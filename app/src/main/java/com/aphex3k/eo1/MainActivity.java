package com.aphex3k.eo1;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.aphex3k.giteaApi.GiteaApiGetReleasesResponse;
import com.aphex3k.giteaApi.GiteaApiService;
import com.aphex3k.giteaApi.GiteaAsset;
import com.aphex3k.immichApi.ImmichApiAssetResponse;
import com.aphex3k.immichApi.ImmichApiGetAlbumResponse;
import com.aphex3k.immichApi.ImmichApiLogin;
import com.aphex3k.immichApi.ImmichApiLoginResponse;
import com.aphex3k.immichApi.ImmichApiService;
import com.aphex3k.immichApi.ImmichExifInfo;
import com.aphex3k.immichApi.ImmichThumbnailFormat;
import com.aphex3k.immichApi.ImmichType;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;
import com.vdurmont.semver4j.Semver;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    public int interval = 5;
    private final long millis = 60000;
    private final Handler handler = new Handler();
    private ImageView imageView;
    private VideoView videoView;
    private String userid = "";
    private String password = "";
    private String host = "";
    private int startQuietHour = -1;
    private int endQuietHour = -1;
    private String selectedTimeZoneId = "";
    final private ArrayList<ImmichApiAssetResponse> immichAssets = new ArrayList<>();
    private boolean isInQuietHours = false;
    private float lastLightLevel;
    private float brightnessMod = 0; // Modify auto-brightness minimum
    private final float minBrightness = 0.5f; // Minimum brightness value (0 to 1)
    private boolean slideshowPaused = false;
    private ProgressBar progress;
    boolean screenOn = true;
    boolean autoBrightness = true;
    float brightnessLevel = 0.5f;
    private final String configFilename = "configuration.json";
    private int lastKeyCode = 0;
    private Date lastKeyCodeDate = new Date();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        File cacheDir = new File(getCacheDir(), "picasso-cache");
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            for (File file : Objects.requireNonNull(cacheDir.listFiles())) {
                file.delete();
            }
        }

        try {
            Configuration configuration = loadConfiguration();

            userid = configuration.userid != null ? configuration.userid : userid;
            password = configuration.password != null ? configuration.password : password;
            host = configuration.host != null ? configuration.host : host;
            startQuietHour = configuration.startQuietHour >= 0 ? configuration.startQuietHour : startQuietHour;
            endQuietHour = configuration.endQuietHour >= 0 ? configuration.endQuietHour : endQuietHour;
            interval = configuration.interval != 0 ? configuration.interval : interval;
            selectedTimeZoneId = configuration.selectedTimeZoneId != null ? configuration.selectedTimeZoneId : selectedTimeZoneId;
            autoBrightness = configuration.autoBrightness;
            brightnessLevel = configuration.brightnessLevel != 0 ? configuration.brightnessLevel : brightnessLevel;
        }
        catch (FileNotFoundException e)
        {

        }

        if (userid.isEmpty() || password.isEmpty()) {
            showSetupDialog();
        }

        imageView = findViewById(R.id.imageView);
        videoView = findViewById(R.id.videoView);
        progress = findViewById(R.id.progressBar);

        SensorManager mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (Math.abs(event.values[0] - lastLightLevel) >= 10.0f) {
                    adjustScreenBrightness(event.values[0]);
                }
            }
            @Override
            public void onAccuracyChanged(Sensor sensor, int i) {
            }
        };
        mSensorManager.registerListener(listener, mLightSensor, SensorManager.SENSOR_DELAY_UI);

        if (BuildConfig.DEBUG) {
            View.OnTouchListener openSetup = new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    showSetupDialog();
                    return false;
                }
            };
            imageView.setOnTouchListener(openSetup);
            videoView.setOnTouchListener(openSetup);
            progress.setOnTouchListener(openSetup);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!userid.isEmpty() && !password.isEmpty()) {
            loadImagesFromImmich();
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    @SuppressLint("InvalidWakeLockTag")
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {

        // Trigger update check if both buttons have been pressed "at the same time"
        if (
                ((keyCode == KeyEvent.EO1_TOP_BUTTON && lastKeyCode == KeyEvent.EO1_BACK_BUTTON) ||
                        (keyCode == KeyEvent.EO1_BACK_BUTTON && lastKeyCode == KeyEvent.EO1_TOP_BUTTON))
                && ((new Date()).getTime() - lastKeyCodeDate.getTime() < 250))
        {
            checkForUpdates(false);
            return true;
        }

        lastKeyCode = keyCode;
        lastKeyCodeDate = new Date();

        if (keyCode == KeyEvent.KEYCODE_A) {
            Toast.makeText(MainActivity.this, "sensor = " + lastLightLevel, Toast.LENGTH_SHORT).show();
        } else if (BuildConfig.DEBUG) {
            Toast.makeText(MainActivity.this, "keyCode = " + keyCode, Toast.LENGTH_SHORT).show();
        }

        if (keyCode == KeyEvent.KEYCODE_C) {
            showSetupDialog();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            progress.setVisibility(View.VISIBLE);
            imageView.setVisibility(View.INVISIBLE);
            videoView.setVisibility(View.INVISIBLE);
            slideshowPaused = false;
            showNextImage();
        }

        if (keyCode == KeyEvent.EO1_TOP_BUTTON) {
            WindowManager.LayoutParams params = getWindow().getAttributes();

            screenOn = !screenOn;
            params.screenBrightness = screenOn ? 10 : 0;
            slideshowPaused = !screenOn;

            getWindow().setAttributes(params);

            if (screenOn) {
                getWindow().clearFlags(FLAG_KEEP_SCREEN_ON);
            }
            else {
                getWindow().addFlags(FLAG_KEEP_SCREEN_ON);
            }
        }

        if (keyCode == KeyEvent.EO1_BACK_BUTTON && screenOn) {
            final float adjustedAmount = 0.1f;
            brightnessMod = brightnessMod + adjustedAmount + minBrightness >= 1 ? 0 : brightnessMod + adjustedAmount;
            adjustScreenBrightness(lastLightLevel);
        }

        return super.onKeyDown(keyCode, event);
    }

    private void checkForUpdates(boolean suppressUI) {
        if (!suppressUI || BuildConfig.DEBUG) {
            Toast.makeText(MainActivity.this, "Checking for app update...", Toast.LENGTH_SHORT).show();
        }
        new Thread(() -> {
            try {
                    final Semver currentVersion = new Semver(BuildConfig.VERSION_NAME);
                    Semver latestVersion = currentVersion;
                    GiteaApiGetReleasesResponse updateTo = null;

                    GiteaApiService apiService = ApiServiceGenerator.createService(GiteaApiService.class, "https://gitea.codingmerc.com/");

                    Response<List<GiteaApiGetReleasesResponse>> response = apiService.getReleases("michael", "EO1").execute();

                    for (GiteaApiGetReleasesResponse release: response.body()) {
                        boolean isHigher = release.version().isGreaterThan(latestVersion);
                        boolean isStable = latestVersion.isStable();
                        if (isHigher && (BuildConfig.DEBUG || isStable)) {
                            latestVersion = release.version();
                            updateTo = release;
                        }
                    }

                    if (updateTo != null) {

                        String downloadUrlString = null;

                        for (GiteaAsset asset: updateTo.getAssets()) {
                            if (BuildConfig.DEBUG && asset.getName().equals("app-debug.apk")) {
                                downloadUrlString = asset.getBrowserDownloadUrl();
                            }
                            else if (!BuildConfig.DEBUG && asset.getName().equals("app-release.apk")) {
                                downloadUrlString = asset.getBrowserDownloadUrl();
                            }
                        }

                        if (downloadUrlString == null) {
                            throw new FileNotFoundException("Couldn't find expected download url for asset.");
                        }

                        final String fileName = "app-" + latestVersion.toStrict() + ".apk";

                        final String downloadedFilePath = Util.downloadFileSync(downloadUrlString, ApiServiceGenerator.getNewHttpClient(), fileName);

                        if (downloadedFilePath == null) {
                            throw new FileNotFoundException("File failed downloading...");
                        }
                        else {
                            runOnUiThread(() -> {
                                Uri apkUri = Uri.fromFile(new File(downloadedFilePath));
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(intent);
                            });
                        }
                    }
                }
                catch (Exception e)
                {
                    if (!suppressUI || BuildConfig.DEBUG) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Update check failure: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }
        }).start();
    }

    private void showSetupDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View customLayout = getLayoutInflater().inflate(R.layout.options, null);
        builder.setView(customLayout);

        final EditText userIdEditText = customLayout.findViewById(R.id.editTextUserId);
        final EditText passwordEditText = customLayout.findViewById(R.id.editTextPassword);
        final EditText hostEditText = customLayout.findViewById(R.id.editTextHost);
        final Spinner startHourSpinner = customLayout.findViewById(R.id.startHourSpinner);
        final Spinner endHourSpinner = customLayout.findViewById(R.id.endHourSpinner);
        final Button btnLoadConfig = customLayout.findViewById(R.id.btnLoadConfig);
        final EditText editTextInterval = customLayout.findViewById(R.id.editTextInterval);
        final CheckBox cbAutoBrightness = customLayout.findViewById(R.id.cbBrightnessAuto);
        final SeekBar sbBrightness = customLayout.findViewById(R.id.sbBrightness);
        final Spinner tzSpinner = customLayout.findViewById(R.id.tzSpinner);

        userIdEditText.setText(userid);
        passwordEditText.setText(password);
        hostEditText.setText(host);
        editTextInterval.setText(String.valueOf(interval));
        if (autoBrightness) {
            cbAutoBrightness.setChecked(true);
            sbBrightness.setVisibility(View.GONE);
        }

        sbBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                brightnessLevel = i / 10f;
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.screenBrightness = brightnessLevel;
                getWindow().setAttributes(params);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sbBrightness.setProgress((int) (brightnessLevel * 10));

        cbAutoBrightness.setOnCheckedChangeListener((compoundButton, b) -> {
            autoBrightness = b;
            if (b)
                sbBrightness.setVisibility(View.GONE);
            else
                sbBrightness.setVisibility(View.VISIBLE);
        });

        // Set up the Spinners for start and end hour
        String[] hours = new String[24];
        for (int i = 0; i < 24; i++) {
            hours[i] = String.format("%02d", i);
        }
        ArrayAdapter<String> hourAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, hours);
        hourAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        startHourSpinner.setAdapter(hourAdapter);
        if (startQuietHour != -1) startHourSpinner.setSelection(startQuietHour);
        endHourSpinner.setAdapter(hourAdapter);
        if (endQuietHour != -1) endHourSpinner.setSelection(endQuietHour);

        String[] allTimeZoneIds = TimeZone.getAvailableIDs();
        ArrayAdapter<String> tzAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, allTimeZoneIds);
        tzAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tzSpinner.setAdapter(tzAdapter);
        if (!selectedTimeZoneId.isEmpty()) tzSpinner.setSelection(Arrays.asList(allTimeZoneIds).indexOf(selectedTimeZoneId));

        View.OnClickListener load = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    Configuration configuration = loadConfiguration();

                    userIdEditText.setText(configuration.userid);
                    passwordEditText.setText(configuration.password);
                    hostEditText.setText(configuration.host);
                    cbAutoBrightness.setActivated(configuration.autoBrightness);
                    tzSpinner.setSelection(Arrays.asList(allTimeZoneIds).indexOf(configuration.selectedTimeZoneId), true);
                    startHourSpinner.setSelection(configuration.startQuietHour, true);
                    endHourSpinner.setSelection(configuration.endQuietHour, true);
                    editTextInterval.setText(String.valueOf(configuration.interval));
                    sbBrightness.setProgress((int) (configuration.brightnessLevel * 10));
                }
                catch (FileNotFoundException fne) {
                    Toast.makeText(MainActivity.this, "Configuration file not found.", Toast.LENGTH_SHORT).show();
                }
            }
        };

        btnLoadConfig.setOnClickListener(load);
        btnLoadConfig.callOnClick();

        builder.setTitle("Setup")
                .setCancelable(false)
                .setView(customLayout)
                .setPositiveButton("Save", (dialog, which) -> {
                    userid = userIdEditText.getText().toString().trim();
                    password = passwordEditText.getText().toString().trim();
                    host = hostEditText.getText().toString().trim();
                    startQuietHour = Integer.parseInt(startHourSpinner.getSelectedItem().toString());
                    endQuietHour = Integer.parseInt(endHourSpinner.getSelectedItem().toString());
                    interval = Integer.parseInt(editTextInterval.getText().toString().trim());
                    autoBrightness = cbAutoBrightness.isChecked();
                    selectedTimeZoneId = tzSpinner.getSelectedItem().toString();

                    if (!userid.isEmpty() && !password.isEmpty() && !host.isEmpty()) {
                        try {
                            saveConfiguration();
                            Toast.makeText(MainActivity.this, "Saved!  Hit 'C' to come back here later.", Toast.LENGTH_SHORT).show();

                            loadImagesFromImmich();
                            updateTimeZone();
                        }
                        catch (IOException e) {
                            Toast.makeText(MainActivity.this, "Failed saving configuration", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(MainActivity.this, "Please enter User ID and API Key", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        builder.show();
    }

    protected Configuration loadConfiguration() throws FileNotFoundException {
        File file = new File(this.getFilesDir(), configFilename);

        return new Gson().fromJson(new FileReader(file), Configuration.class);
    }

    protected void saveConfiguration() throws IOException {
        File file = new File(this.getFilesDir(), configFilename);

        file.getParentFile().mkdirs();

        Configuration configuration = new Configuration();

        configuration.autoBrightness = autoBrightness;
        configuration.brightnessLevel = brightnessLevel;
        configuration.endQuietHour = endQuietHour;
        configuration.startQuietHour = startQuietHour;
        configuration.interval = interval;
        configuration.host = host;
        configuration.password = password;
        configuration.selectedTimeZoneId = selectedTimeZoneId;
        configuration.userid = userid;

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create();

        String jsonString = gson.toJson(configuration);

        try
        {
            file.createNewFile();
            FileOutputStream fOut = new FileOutputStream(file);
            OutputStreamWriter myOutWriter = new OutputStreamWriter(fOut);
            myOutWriter.append(jsonString);

            myOutWriter.close();

            fOut.flush();
            fOut.close();
        }
        catch (IOException e)
        {
            Log.e("Exception", "File write failed: " + e.toString());
        }
    }

    private void updateTimeZone() {
        if (!selectedTimeZoneId.isEmpty()) {
            AlarmManager alarmManager=(AlarmManager)this.getSystemService(Context.ALARM_SERVICE);
            alarmManager.setTimeZone(selectedTimeZoneId);
        }
    }

    private void startSlideshow() {

        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Calendar calendar = Calendar.getInstance();
                int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
                int normalizedStart = (startQuietHour + 24) % 24;
                int normalizedEnd = (endQuietHour + 24) % 24;
                if ((currentHour >= normalizedStart && currentHour < normalizedEnd) ||
                        (normalizedStart > normalizedEnd && (currentHour >= normalizedStart || currentHour < normalizedEnd))) {
                    if (!isInQuietHours) {
                        //entering quiet, turn off screen
                        WindowManager.LayoutParams params = getWindow().getAttributes();
                        params.screenBrightness = 0;
                        getWindow().clearFlags(FLAG_KEEP_SCREEN_ON);
                        getWindow().setAttributes(params);
                        videoView.setVisibility(View.GONE);
                        imageView.setVisibility(View.GONE);

                        isInQuietHours = true;
                    }
                } else {
                    if (isInQuietHours) {
                        //exiting quiet, turn on screen
                        WindowManager.LayoutParams params = getWindow().getAttributes();
                        params.screenBrightness = 1f;
                        getWindow().addFlags(FLAG_KEEP_SCREEN_ON);
                        getWindow().setAttributes(params);

                        isInQuietHours = false;
                    }
                    showNextImage();
                }
                handler.postDelayed(this, millis * interval);
            }
        }, millis * interval);

        showNextImage();
    }

    private void showNextImage() {
        if (immichAssets.isEmpty()) {
            loadImagesFromImmich();
            return;
        }

        if (immichAssets != null && !immichAssets.isEmpty() && !slideshowPaused) {
            Collections.shuffle(immichAssets);
            try {
                ImmichApiAssetResponse asset = immichAssets.remove(0);

                if (BuildConfig.DEBUG) {
                    while (immichAssets.size() > 0 && asset.getType() != ImmichType.VIDEO) {
                        asset = immichAssets.remove(0);
                    }
                }

                ImmichType mediaType = asset.getType();
                ImmichExifInfo exif = asset.getExifInfo();

                if (mediaType != ImmichType.VIDEO && mediaType != ImmichType.IMAGE) {
                    showNextImage();
                } else if (exif != null && exif.getFileSizeInByte() > 40000000) {
                    showNextImage();
                } else {
                    String uuid = asset.getId();
//                    String extension = Files.getFileExtension(asset.getOriginalPath()).toLowerCase();
                    String extension = asset.getType() == ImmichType.VIDEO ? "mp4" : "jpeg";
                    new DownloadTask().execute(uuid, extension);
                }
            } catch (Exception ex) {
                progress.setVisibility(View.VISIBLE);
                if (BuildConfig.DEBUG) {
                    Toast.makeText(MainActivity.this, "showNextImage error > " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                }
                new android.os.Handler().postDelayed(this::showNextImage, 10000);
            }
        }
    }

    private void loadImagesFromImmich() {
        progress.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.INVISIBLE);
        videoView.setVisibility(View.INVISIBLE);

        if (!host.isEmpty() && !userid.isEmpty() && !password.isEmpty() && isNetworkAvailable())
        {
            ImmichApiService apiService = ApiServiceGenerator.createService(ImmichApiService.class, host);

            new Thread(() -> {
                try {
                    ImmichApiLoginResponse loginResponse = apiService.login(new ImmichApiLogin(userid, password)).execute().body();

                    String userId = loginResponse.getUserId();

                    if (!userId.equals("")) {
                        Response<List<ImmichApiGetAlbumResponse>> sharedAlbumsResponse = apiService.getAllAlbums(true, null).execute();
                        if (sharedAlbumsResponse.isSuccessful()) {

                            for (ImmichApiGetAlbumResponse r : sharedAlbumsResponse.body()) {
                                List<ImmichApiAssetResponse> assetList = r.getAssets();

                                if (assetList.isEmpty()) {
                                    assetList = apiService.getAlbumInfo(r.getId(), false, null).execute().body().getAssets();
                                }
                                immichAssets.addAll(assetList);

                            }
                        }

                        Response<List<ImmichApiGetAlbumResponse>> albumsResponse = apiService.getAllAlbums(false, null).execute();
                        if (albumsResponse.isSuccessful()) {

                            for (ImmichApiGetAlbumResponse r : albumsResponse.body()) {
                                List<ImmichApiAssetResponse> assetList = r.getAssets();

                                if (assetList.isEmpty()) {
                                    assetList = apiService.getAlbumInfo(r.getId(), false, null).execute().body().getAssets();
                                }
                                immichAssets.addAll(assetList);

                            }
                        }
                        if (!immichAssets.isEmpty()) {
                            try {
                                runOnUiThread(() -> startSlideshow());
                            } catch (Exception ex) {
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this, "Immich failure :", Toast.LENGTH_SHORT).show();
                                    showSetupDialog();
                                });
                            }
                        }
                    }
                }
                catch (Exception e)
                {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Immich failure: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        } else {
            // Retry loading images after delay
            new android.os.Handler().postDelayed(this::loadImagesFromImmich, 10000);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private void adjustScreenBrightness(float lightValue){
        if (autoBrightness) {
            if (!isInQuietHours) {
                // Determine the desired brightness range
                float maxBrightness = 1.0f; // Maximum brightness value (0 to 1)

                // Map the light sensor value (0 to 25) to the desired brightness range (0 to 1)
                float brightness = (lightValue / 30f) * (maxBrightness - minBrightness) + minBrightness + brightnessMod;

                // Make sure brightness is within the valid range
                brightness = Math.min(Math.max(brightness, minBrightness), maxBrightness);

                // Apply the brightness setting to the screen
                WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
                layoutParams.screenBrightness = brightness;
                getWindow().setAttributes(layoutParams);
            }
        }
        lastLightLevel = lightValue;
    }

    public class DownloadTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {
            String uuid = params[0];
            String extension = params[1];

            if ( uuid == null || extension == null ) {
                return "ERR: Missing arguments";
            }

            try {
                // Download the file with id
                ImmichApiService apiService = ApiServiceGenerator.createService(ImmichApiService.class, host);

                // Login to get a current auth cookie
                apiService.login(
                        new ImmichApiLogin(userid, password)
                ).execute();

                Response<ResponseBody> downloadResponse = apiService.serveFile(uuid, true, false, null).execute();

                if (downloadResponse.isSuccessful() && downloadResponse.body() != null) {

                    File tempFile = new File(getCacheDir(), "temp_" + uuid + "." + extension);
                    FileOutputStream outputStream = new FileOutputStream(tempFile);
                    InputStream inputStream = downloadResponse.body().byteStream();
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    outputStream.close();
                    inputStream.close();

                    return tempFile.getPath();
                }
                else throw new Exception("Failed downloading immich asset.");

            } catch (Exception e) {
                return "ERR: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String fileName) {

            progress.setVisibility(View.INVISIBLE);

            if (fileName.startsWith("ERR")) {
                if (BuildConfig.DEBUG) {
                    Toast.makeText(MainActivity.this, "Error loading " + fileName, Toast.LENGTH_LONG).show();
                }
                showNextImage();
            }
            else {
                final String extension = Files.getFileExtension(fileName).toLowerCase();
                final File file = new File(fileName);

                if (extension.equals("jpg")
                        || extension.equals("jpeg")
                        || extension.equals("png")
                        || extension.equals("bmp")
                        || extension.equals("webp")
                        || extension.equals("gif")
                ) {
                    try {
                        Picasso.get().load(file).fit().centerInside().into(imageView, new Callback() {
                            @Override
                            public void onSuccess() {
                                removeFromCache(file);
                            }

                            @Override
                            public void onError(Exception e) {
                                if (BuildConfig.DEBUG) {
                                    Toast.makeText(MainActivity.this, "Picasso: " + e, Toast.LENGTH_LONG).show();
                                }
                                removeFromCache(file);
                                showNextImage();
                            }
                        });
                    }
                    catch (Exception e) {
                        if (BuildConfig.DEBUG) {
                            Toast.makeText(MainActivity.this, "Picasso: " + e, Toast.LENGTH_LONG).show();
                        }
                        removeFromCache(file);
                        showNextImage();
                    }
                    videoView.setVisibility(View.INVISIBLE);
                    imageView.setVisibility(View.VISIBLE);
                    progress.setVisibility(View.INVISIBLE);

                } else if (fileName.endsWith("mp4") || fileName.endsWith("m4v") || fileName.endsWith("mov")) {

                    MediaController mediaController = new MediaController(getApplicationContext());
                    mediaController.setAnchorView(videoView);
                    mediaController.setVisibility(View.INVISIBLE);
                    videoView.setMediaController(mediaController);
                    videoView.setVisibility(View.VISIBLE);
                    imageView.setVisibility(View.INVISIBLE);
                    progress.setVisibility(View.INVISIBLE);
                    videoView.setVideoPath(fileName);

                    videoView.setOnPreparedListener(mediaPlayer -> {
                        mediaPlayer.setLooping(true);
                        mediaPlayer.setVolume(0f,0f);
                        videoView.start();
                    });
                    videoView.setOnErrorListener((mediaPlayer, i, i1) -> {
                        progress.setVisibility(View.VISIBLE);
                        Toast.makeText(MainActivity.this, "media player ERR> ", Toast.LENGTH_LONG).show();
                        removeFromCache(new File(fileName));
                        showNextImage();
                        return true;
                    });
                } else {
                    if (BuildConfig.DEBUG) {
                        Toast.makeText(MainActivity.this, "Uncaptured file extension: ." + extension, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    }

    private void removeFromCache (File file) {
        final File cacheDir = new File(getCacheDir(), "picasso-cache");
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            file.delete();
        }
    }
}