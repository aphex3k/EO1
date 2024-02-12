package com.aphex3k.eo1;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.annotation.Keep;
import androidx.appcompat.app.AlertDialog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Objects;
import java.util.TimeZone;

@Keep
public class SettingsManager {

    private final WeakReference<SettingsManagerListener> listener;
    private static final String CONFIG_FILENAME = "configuration.json";
    private Configuration configuration = new Configuration();
    public Configuration getConfiguration() {
        return this.configuration;
    }

    protected SettingsManager (SettingsManagerListener listener) {
        this.listener = new WeakReference<>(listener);

        loadConfiguration();
    }

    protected void loadConfiguration() {

        SettingsManagerListener settingsManagerListener = this.listener.get();

        if (settingsManagerListener != null) {
            try {
                File file = new File(settingsManagerListener.getFilesDir(), CONFIG_FILENAME);

                this.configuration = new Gson().fromJson(new FileReader(file), Configuration.class);
            } catch (Exception e) {
                settingsManagerListener.handleException(e);
            }
        }
    }

    protected boolean showSetupDialogIfNeeded(Context context) {

        loadConfiguration();

        if (this.configuration == null ||
            this.configuration.userid == null ||
            this.configuration.userid.isEmpty() ||
            this.configuration.password == null ||
            this.configuration.password.isEmpty())
        {
            showSetupDialog(context);
            return true;
        }
        else {
            return false;
        }
    }

    /** @noinspection ResultOfMethodCallIgnored*/
    protected void saveConfiguration() throws IOException {
        SettingsManagerListener settingsManagerListener = this.listener.get();

        if (settingsManagerListener != null) {
            File file = new File(settingsManagerListener.getFilesDir(), CONFIG_FILENAME);

            Objects.requireNonNull(file.getParentFile()).mkdirs();

            Gson gson = new GsonBuilder()
                    .setPrettyPrinting()
                    .serializeNulls()
                    .create();

            String jsonString = gson.toJson(configuration);

            try {
                if (file.exists() && !file.delete()) {
                    settingsManagerListener.debugInformationProvided(new DebugInformation("Failed to delete outdated configuration file", file.getAbsolutePath()));
                }
                if (!file.createNewFile()) {
                    throw new IOException("Failed to create file: "+ file.getAbsolutePath());
                }
                try (FileOutputStream fOut = new FileOutputStream(file)) {
                    try (OutputStreamWriter myOutWriter = new OutputStreamWriter(fOut)) {
                        myOutWriter.append(jsonString);
                    }
                    fOut.flush();
                }
            } catch (IOException e) {
                settingsManagerListener.handleException(e);
            }
        }
    }

    private void updateTimeZone() {
        SettingsManagerListener settingsManagerListener = this.listener.get();
        if (configuration.selectedTimeZoneId != null && !configuration.selectedTimeZoneId.isEmpty() && settingsManagerListener != null) {

            AlarmManager alarmManager=(AlarmManager)settingsManagerListener.getSystemService(Context.ALARM_SERVICE);
            alarmManager.setTimeZone(configuration.selectedTimeZoneId);
        }
    }

    @SuppressLint("DefaultLocale")
    protected void showSetupDialog(Context context) {

        SettingsManagerListener settingsManagerListener = this.listener.get();

        if (settingsManagerListener != null) {

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View customLayout = settingsManagerListener.getLayoutInflater().inflate(R.layout.options, null);
            builder.setView(customLayout);

            final EditText userIdEditText = customLayout.findViewById(R.id.editTextUserId);
            final EditText passwordEditText = customLayout.findViewById(R.id.editTextPassword);
            final EditText hostEditText = customLayout.findViewById(R.id.editTextHost);
            final Spinner startHourSpinner = customLayout.findViewById(R.id.startHourSpinner);
            final Spinner endHourSpinner = customLayout.findViewById(R.id.endHourSpinner);
            final Button btnLoadConfig = customLayout.findViewById(R.id.btnLoadConfig);
            final EditText editTextInterval = customLayout.findViewById(R.id.editTextInterval);
            final Spinner tzSpinner = customLayout.findViewById(R.id.tzSpinner);

            userIdEditText.setText(configuration.userid);
            passwordEditText.setText(configuration.password);
            hostEditText.setText(configuration.host);
            editTextInterval.setText(String.valueOf(configuration.interval));


            // Set up the Spinners for start and end hour
            String[] hours = new String[24];
            for (int i = 0; i < 24; i++) {
                hours[i] = String.format("%02d", i);
            }
            ArrayAdapter<String> hourAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, hours);
            hourAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            startHourSpinner.setAdapter(hourAdapter);
            if (configuration.startQuietHour != -1) startHourSpinner.setSelection(configuration.startQuietHour);
            endHourSpinner.setAdapter(hourAdapter);
            if (configuration.endQuietHour != -1) endHourSpinner.setSelection(configuration.endQuietHour);

            String[] allTimeZoneIds = TimeZone.getAvailableIDs();
            ArrayAdapter<String> tzAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, allTimeZoneIds);
            tzAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            tzSpinner.setAdapter(tzAdapter);
            if (configuration.selectedTimeZoneId != null && configuration.selectedTimeZoneId.isEmpty())
                tzSpinner.setSelection(Arrays.asList(allTimeZoneIds).indexOf(configuration.selectedTimeZoneId));

            View.OnClickListener load = view -> {
                loadConfiguration();

                userIdEditText.setText(configuration.userid);
                passwordEditText.setText(configuration.password);
                hostEditText.setText(configuration.host);
                tzSpinner.setSelection(Arrays.asList(allTimeZoneIds).indexOf(configuration.selectedTimeZoneId), true);
                startHourSpinner.setSelection(configuration.startQuietHour, true);
                endHourSpinner.setSelection(configuration.endQuietHour, true);
                editTextInterval.setText(String.valueOf(configuration.interval));
            };

            btnLoadConfig.setOnClickListener(load);
            btnLoadConfig.callOnClick();

            builder.setTitle("Setup")
                    .setCancelable(false)
                    .setView(customLayout)
                    .setPositiveButton("Save", (dialog, which) -> {
                        configuration.userid = userIdEditText.getText().toString().trim();
                        configuration.password = passwordEditText.getText().toString().trim();
                        configuration.host = hostEditText.getText().toString().trim();
                        configuration.startQuietHour = Integer.parseInt(startHourSpinner.getSelectedItem().toString());
                        configuration.endQuietHour = Integer.parseInt(endHourSpinner.getSelectedItem().toString());
                        configuration.interval = Integer.parseInt(editTextInterval.getText().toString().trim());
                        configuration.selectedTimeZoneId = tzSpinner.getSelectedItem().toString();

                        if (!configuration.userid.isEmpty() && !configuration.password.isEmpty() && !configuration.host.isEmpty()) {
                            try {
                                saveConfiguration();
                                updateTimeZone();
                                settingsManagerListener.settingsChanged();
                            } catch (IOException e) {
                                settingsManagerListener.handleException(e);
                            }
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

            builder.show();
        }
    }
}
