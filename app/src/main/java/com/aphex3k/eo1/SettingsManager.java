package com.aphex3k.eo1;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.Keep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.util.Objects;

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
    }

    protected boolean loadConfiguration() {

        SettingsManagerListener settingsManagerListener = this.listener.get();

        if (settingsManagerListener != null) {
            try {
                File file = new File(settingsManagerListener.getFilesDir(), CONFIG_FILENAME);

                Configuration loaded = new Gson().fromJson(new FileReader(file), Configuration.class);
                if (loaded != null && Configuration.normalize(loaded)) {
                    // Persist the migrated/normalized form (legacy flat host -> backends, etc.).
                    this.configuration = loaded;
                    try {
                        saveConfiguration();
                    } catch (IOException ignored) {
                        // In-memory config is already usable; the on-disk rewrite is best effort.
                    }
                }
                this.configuration = loaded;

                return true;
            } catch (Exception e) {
                settingsManagerListener.handleException(e);
            }
        }

        return false;
    }

    protected boolean isSetupDialogIfNeeded()
    {
        if (!loadConfiguration() ||
            this.configuration == null ||
            this.configuration.validBackendCount() == 0)
        {
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
            // Keep the file readable by older APKs: mirror the first immich backend into the
            // legacy flat host/userid/password fields, and the quiet window into the legacy
            // start/end hour fields, before serializing.
            mirrorLegacyHostFields();
            Configuration.mirrorLegacyQuietHourFields(configuration);

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

    /**
     * Keeps the deprecated flat {@code host/userid/password} fields in sync with the first
     * immich backend so older APK versions can still read the config file.
     */
    private void mirrorLegacyHostFields() {
        ConfigurationBackendEntry first = null;
        for (ConfigurationBackendEntry entry : configuration.backendsOrEmpty()) {
            if (entry.isImmich()) {
                first = entry;
                break;
            }
        }
        if (first == null) {
            configuration.host = "";
            configuration.userid = "";
            configuration.password = "";
        } else {
            configuration.host = first.host;
            configuration.userid = first.userid;
            configuration.password = first.password;
        }
    }

    void updateTimeZone(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            SettingsManagerListener settingsManagerListener = this.listener.get();
            if (configuration.selectedTimeZoneId != null && !configuration.selectedTimeZoneId.isEmpty() && settingsManagerListener != null) {

                AlarmManager alarmManager = (AlarmManager) settingsManagerListener.getSystemService(Context.ALARM_SERVICE);
                alarmManager.setTimeZone(configuration.selectedTimeZoneId);
            }
        }
    }

}
