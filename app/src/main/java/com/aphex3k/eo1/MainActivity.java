package com.aphex3k.eo1;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.aphex3k.immichApi.ImmichApiAssetResponse;
import com.aphex3k.immichApi.ImmichApiGetAlbumResponse;
import com.aphex3k.immichApi.ImmichApiLogin;
import com.aphex3k.immichApi.ImmichApiLoginResponse;
import com.aphex3k.immichApi.ImmichApiService;
import com.aphex3k.immichApi.ImmichExifInfo;
import com.aphex3k.immichApi.ImmichThumbnailFormat;
import com.aphex3k.immichApi.ImmichType;
import com.google.common.io.Files;
import com.squareup.picasso.Picasso;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    public int interval = 5;
    private int millis = 60000;
    public int tempid = 0;
    private Handler handler = new Handler();
    private ImageView imageView;
    private VideoView videoView;
    private String userid = "";
    private String password = "";
    private String host = "";
    private int startQuietHour = -1;
    private int endQuietHour = -1;
    private List<ImmichApiAssetResponse> immichAssets;
    private boolean isInQuietHours = false;
    private SensorManager mSensorManager;
    private Sensor mLightSensor;
    private float lastLightLevel;
    private boolean slideshowpaused = false;
    private ProgressBar progress;
    boolean screenon = true;
    boolean autobrightness = true;
    float brightnesslevel = 0.5f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        File cacheDir = new File(getCacheDir(), "picasso-cache");
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            for (File file : cacheDir.listFiles()) {
                file.delete();
            }
        }

        SharedPreferences settings = getSharedPreferences("prefs", MODE_PRIVATE);
        userid = settings.getString("userid", "");
        password = settings.getString("password", "");
        host = settings.getString("host", "");
        startQuietHour = settings.getInt("startQuietHour", -1);
        endQuietHour = settings.getInt("endQuietHour", -1);
        interval = settings.getInt("interval", 5);
        autobrightness = settings.getBoolean("autobrightness", true);
        brightnesslevel = settings.getFloat("brightnesslevel", 0.5f);

        if (userid.isEmpty() || password.isEmpty()) {
            showSetupDialog();
        }

        imageView = findViewById(R.id.imageView);
        videoView = findViewById(R.id.videoView);
        progress = findViewById(R.id.progressBar);

        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
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
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!userid.isEmpty() && !password.isEmpty()) {
            loadImagesFromImmich();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    @SuppressLint("InvalidWakeLockTag")
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

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
            slideshowpaused = false;
            showNextImage();
        }

        if (keyCode == KeyEvent.KEYCODE_F2) {
            //top button pushed
            WindowManager.LayoutParams params = getWindow().getAttributes();
            if (screenon) {
                params.screenBrightness = 0;
                screenon = false;
            } else {
                params.screenBrightness = 10;
                screenon = true;
            }
            getWindow().setAttributes(params);
        }

        return super.onKeyDown(keyCode, event);
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

        userIdEditText.setText(userid);
        passwordEditText.setText(password);
        hostEditText.setText(host);
        editTextInterval.setText(String.valueOf(interval));
        if (autobrightness) {
            cbAutoBrightness.setChecked(true);
            sbBrightness.setVisibility(View.GONE);
        }

        sbBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                brightnesslevel = i / 10f;
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.screenBrightness = brightnesslevel;
                getWindow().setAttributes(params);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sbBrightness.setProgress((int) (brightnesslevel * 10));

        cbAutoBrightness.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                autobrightness = b;
                if (b)
                    sbBrightness.setVisibility(View.GONE);
                else
                    sbBrightness.setVisibility(View.VISIBLE);
            }
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

        btnLoadConfig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                File file = new File(downloadsDir, "config.txt");
                if (!file.exists()) {
                    Toast.makeText(MainActivity.this, "Can't find config.txt", Toast.LENGTH_SHORT).show();
                } else {
                    StringBuilder sb = new StringBuilder();
                    try {
                        BufferedReader br = new BufferedReader(new FileReader(file));
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                        br.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    userIdEditText.setText(sb.toString().split("\n")[0]);
                    passwordEditText.setText(sb.toString().split("\n")[1]);
                    hostEditText.setText(sb.toString().split("\n")[2]);
                }
            }
        });

        builder.setTitle("Setup")
                .setCancelable(false)
                .setView(customLayout)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        userid = userIdEditText.getText().toString().trim();
                        password = passwordEditText.getText().toString().trim();
                        host = hostEditText.getText().toString().trim();
                        startQuietHour = Integer.parseInt(startHourSpinner.getSelectedItem().toString());
                        endQuietHour = Integer.parseInt(endHourSpinner.getSelectedItem().toString());
                        interval = Integer.parseInt(editTextInterval.getText().toString().trim());
                        autobrightness = cbAutoBrightness.isChecked();

                        if (!userid.isEmpty() && !password.isEmpty() && !host.isEmpty()) {
                            SharedPreferences settings = getSharedPreferences("prefs", MODE_PRIVATE);
                            SharedPreferences.Editor editor = settings.edit();
                            editor.putString("userid", userid);
                            editor.putString("password", password);
                            editor.putString("host", host);
                            editor.putInt("startQuietHour", startQuietHour);
                            editor.putInt("endQuietHour", endQuietHour);
                            editor.putInt("interval", interval);
                            editor.putBoolean("autobrightness", autobrightness);
                            editor.putFloat("brightnesslevel", brightnesslevel);
                            editor.apply();

                            Toast.makeText(MainActivity.this, "Saved!  Hit 'C' to come back here later.", Toast.LENGTH_SHORT).show();

                            loadImagesFromImmich();
                        } else {
                            Toast.makeText(MainActivity.this, "Please enter User ID and API Key", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });

        builder.show();
    }

    private void startSlideshow() {

//        if (BuildConfig.DEBUG) {
//            millis = 1000;
//        }

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
        if (immichAssets != null && !immichAssets.isEmpty() && !slideshowpaused) {
            try {
                ImmichApiAssetResponse asset = immichAssets.remove(0);
                ImmichType mediaType = asset.getType();
                ImmichExifInfo exif = asset.getExifInfo();

                if (mediaType != ImmichType.VIDEO && mediaType != ImmichType.IMAGE) {
                    showNextImage();
                } else if (exif != null && exif.getFileSizeInByte() > 40000000) {
                    showNextImage();
                } else {
                    String uuid = asset.getId();
                    String extension = Files.getFileExtension(asset.getOriginalPath()).toLowerCase();

                    new DownloadTask().execute(uuid, extension);
                 }
            } catch (Exception ex) {
                progress.setVisibility(View.VISIBLE);
                if (BuildConfig.DEBUG) {
                    Toast.makeText(MainActivity.this, "shownextimage err > " + ex.getMessage().toString(), Toast.LENGTH_SHORT).show();
                }
                new android.os.Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        showNextImage();
                    }
                }, 10000);
            }
        }
    }

    private void loadImagesFromImmich() {
        progress.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.INVISIBLE);
        videoView.setVisibility(View.INVISIBLE);

        if (!host.isEmpty() && !userid.isEmpty() && !password.isEmpty() && isNetworkAvailable())
        {
            ImmichApiService apiService = ImmichApiServiceGenerator.createService(ImmichApiService.class, host);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ImmichApiLoginResponse loginResponse = apiService.login(new ImmichApiLogin(userid, password)).execute().body();

                        String userId = loginResponse.getUserId();

                        if (userId != "") {
                            Response<List<ImmichApiGetAlbumResponse>> albumsResponse = apiService.getAllAlbums(true, null).execute();
                            if (albumsResponse.isSuccessful()) {

                                List<ImmichApiAssetResponse> assets = new ArrayList<>();
                                for (ImmichApiGetAlbumResponse r: albumsResponse.body()) {
                                    List<ImmichApiAssetResponse> assetList = r.getAssets();

                                    if (assetList.isEmpty()) {
                                        assetList = apiService.getAlbumInfo(r.getId(), false, null).execute().body().getAssets();
                                    }
                                    assets.addAll(assetList);

                                }

                                if (!assets.isEmpty()) {
                                    try {
                                        immichAssets = assets;
                                        Collections.shuffle(immichAssets);
                                        startSlideshow();
                                    } catch (Exception ex) {
                                        Toast.makeText(MainActivity.this, "Immich failure :", Toast.LENGTH_SHORT).show();
                                        showSetupDialog();
                                    }
                                }
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "Immich failure: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }).start();
        } else {
            new android.os.Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    loadImagesFromImmich(); // Retry loading images after delay
                }
            }, 10000);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private View getLabel(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        return textView;
    }

    private void adjustScreenBrightness(float lightValue){
        if (autobrightness) {
            if (!isInQuietHours) {
                // Determine the desired brightness range
                float maxBrightness = 1.0f; // Maximum brightness value (0 to 1)
                float minBrightness = 0.5f; // Minimum brightness value (0 to 1)

                // Map the light sensor value (0 to 25) to the desired brightness range (0 to 1)
                float brightness = (lightValue / 30f) * (maxBrightness - minBrightness) + minBrightness;

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

    private int getSelectedOptionIndex(RadioGroup radioGroup) {
        int checkedRadioButtonId = radioGroup.getCheckedRadioButtonId();
        View checkedRadioButton = radioGroup.findViewById(checkedRadioButtonId);
        return radioGroup.indexOfChild(checkedRadioButton);
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
                ImmichApiService apiService = ImmichApiServiceGenerator.createService(ImmichApiService.class, host);

                // Login to get a current auth cookie
                Response<ImmichApiLoginResponse> loginresponse = apiService.login(
                        new ImmichApiLogin(userid, password)
                ).execute();

                Response<ResponseBody> downloadResponse = null;
                ImmichThumbnailFormat compatibleFormat = ImmichThumbnailFormat.JPEG;

                if (!BuildConfig.DEBUG &&(
                        extension.equals("mov")
                    )) {
                    // if the extension is unsupported, attempt to download a (high quality) thumbnail instead
                    downloadResponse = apiService.getAssetThumbnail(uuid, compatibleFormat, null).execute();
                    extension = compatibleFormat == ImmichThumbnailFormat.JPEG? "jpeg" : "webp";
                }
                else if (extension.equals("heic")) {
                    // if the extension is unsupported, attempt to download a (high quality) thumbnail instead
                    downloadResponse = apiService.getAssetThumbnail(uuid, compatibleFormat, null).execute();
                    extension = compatibleFormat == ImmichThumbnailFormat.JPEG? "jpeg" : "webp";
                }
                else {
                    downloadResponse = apiService.downloadFile(uuid).execute();
                }

                if (downloadResponse.isSuccessful() && downloadResponse.body() != null) {

                    File tempFile = new File(getCacheDir(), "temp" + tempid + "." + extension);
                    if (++tempid == 5) tempid=0;
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
        protected void onPostExecute(String file) {

            progress.setVisibility(View.INVISIBLE);

            if (file.startsWith("ERR")) {
                if (BuildConfig.DEBUG) {
                    Toast.makeText(MainActivity.this, "" + file, Toast.LENGTH_SHORT).show();
                }
                showNextImage();
            }
            else {
                final String extension = Files.getFileExtension(file).toLowerCase();

                if (extension.equals("jpg")
                        || extension.equals("jpeg")
                        || extension.equals("png")
                        || extension.equals("bmp")
                        || extension.equals("webp")
                        || extension.equals("gif")
                ) {
                    try {
                        Picasso.get().load(new File(file)).fit().centerInside().into(imageView);
                    }
                    catch (Exception e) {
                        if (BuildConfig.DEBUG) {
                            Toast.makeText(MainActivity.this, "Picasso: " + file, Toast.LENGTH_SHORT).show();
                        }
                        showNextImage();
                    }
                    videoView.setVisibility(View.INVISIBLE);
                    imageView.setVisibility(View.VISIBLE);
                    progress.setVisibility(View.INVISIBLE);

                } else if (file.endsWith("mp4") || file.endsWith("m4v") || file.endsWith("mov")) {

                    MediaController mediaController = new MediaController(getApplicationContext());
                    mediaController.setAnchorView(videoView);
                    mediaController.setVisibility(View.INVISIBLE);
                    videoView.setMediaController(mediaController);
                    videoView.setVisibility(View.VISIBLE);
                    imageView.setVisibility(View.INVISIBLE);
                    progress.setVisibility(View.INVISIBLE);
                    videoView.setVideoPath(file);

                    videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mediaPlayer) {
                            mediaPlayer.setLooping(true);
                            videoView.start();
                        }
                    });
                    videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer mediaPlayer, int i, int i1) {
                            progress.setVisibility(View.VISIBLE);
                            Toast.makeText(MainActivity.this, "mediaplayer ERR> ", Toast.LENGTH_SHORT).show();
                            showNextImage();
                            return true;
                        }
                    });
                } else {
                    if (BuildConfig.DEBUG) {
                        Toast.makeText(MainActivity.this, "Uncaptured file extension: ." + extension, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    }
}