package com.aphex3k.eo1;

import android.app.AlarmManager;
import android.content.Context;
import android.content.SharedPreferences;
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
    /**
     * SharedPreferences key for the trusted-network flag. The flag is intentionally NOT part of
     * {@code configuration.json} (never serialized, never read back from the file); it defaults
     * to off on a fresh install.
     */
    private static final String PREF_TRUSTED_NETWORK = "trusted_network";
    /**
     * SharedPreferences key for the per-device configuration token protecting the web
     * {@code /config*} endpoints. Like the flag, it is never written to
     * {@code configuration.json} and never exported — it is shown on the on-device options
     * dialog only while the trusted-network flag is on.
     */
    private static final String PREF_TRUSTED_NETWORK_TOKEN = "trusted_network_token";
    private Configuration configuration = new Configuration();
    public Configuration getConfiguration() {
        return this.configuration;
    }

    /**
     * Whether the device is on a trusted network (off by default). Gates the LAN web server's
     * configuration endpoints (full config incl. credentials, backend manipulation, export,
     * import). Persisted in the app's default SharedPreferences, not in {@code configuration.json}.
     */
    public boolean isTrustedNetwork() {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return false;
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            return prefs != null && prefs.getBoolean(PREF_TRUSTED_NETWORK, false);
        } catch (Exception e) {
            return false;
        }
    }

    public void setTrustedNetwork(boolean trusted) {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return;
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            if (prefs != null) {
                prefs.edit().putBoolean(PREF_TRUSTED_NETWORK, trusted).apply();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * The per-device configuration token that the web {@code /config*} endpoints require in
     * addition to the trusted-network flag. Generated once (128-bit {@code SecureRandom}, 32
     * hex chars) and persisted in the default SharedPreferences; every call returns the same
     * value. Never included in the configuration document or its export.
     */
    public synchronized String trustedNetworkToken() {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return "";
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            if (prefs == null) {
                return "";
            }
            String token = prefs.getString(PREF_TRUSTED_NETWORK_TOKEN, "");
            if (token.isEmpty()) {
                token = generateToken();
                prefs.edit().putString(PREF_TRUSTED_NETWORK_TOKEN, token).apply();
            }
            return token;
        } catch (Exception e) {
            return "";
        }
    }

    private static String generateToken() {
        byte[] random = new byte[16];
        new java.security.SecureRandom().nextBytes(random);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : random) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
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
                // An empty or corrupt file makes Gson return null; fall back to a blank config
                // instead of null so getConfiguration() never hands out null (an empty backend
                // list still routes startup into the setup dialog via validBackendCount()).
                this.configuration = loaded != null ? loaded : new Configuration();

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
            synchronized (this) {
                mirrorLegacyHostFields(configuration);
                Configuration.mirrorLegacyQuietHourFields(configuration);

                File file = new File(settingsManagerListener.getFilesDir(), CONFIG_FILENAME);

                Objects.requireNonNull(file.getParentFile()).mkdirs();

                String jsonString = prettyGson().toJson(configuration);

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
    }

    /**
     * Serializes the live configuration to JSON exactly as {@link #saveConfiguration()} writes
     * it to disk (same legacy mirror fields, credentials included). Safe to call from a web
     * worker thread.
     */
    public String exportConfigurationJson() {
        try {
            Configuration snapshot;
            synchronized (this) {
                snapshot = this.configuration != null
                        ? cloneConfiguration(this.configuration)
                        : new Configuration();
            }
            mirrorLegacyHostFields(snapshot);
            Configuration.mirrorLegacyQuietHourFields(snapshot);
            return prettyGson().toJson(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Parses {@code json} as a full device configuration, normalizes it, and writes it to the
     * device's configuration file; the live in-memory configuration is replaced. The caller is
     * responsible for notifying listeners (and updating the time zone) on a {@code null} return.
     *
     * @return a human-readable error message, or {@code null} on success.
     */
    public String importConfiguration(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "configuration is empty";
        }
        Configuration imported;
        try {
            imported = new Gson().fromJson(json, Configuration.class);
        } catch (Exception e) {
            return "not valid JSON: " + e.getMessage();
        }
        if (imported == null) {
            return "configuration is empty";
        }
        try {
            Configuration.normalize(imported);
        } catch (Exception e) {
            return "invalid configuration: " + e.getMessage();
        }
        if (imported.validBackendCount() == 0) {
            return "configuration has no usable backend";
        }
        synchronized (this) {
            this.configuration = imported;
            try {
                saveConfiguration();
            } catch (IOException e) {
                return "failed to write configuration: " + e.getMessage();
            }
        }
        return null;
    }

    /** Gson round-trip so export/never mutates the live object a UI thread may be editing. */
    private static Configuration cloneConfiguration(Configuration c) {
        return new Gson().fromJson(new Gson().toJson(c), Configuration.class);
    }

    private static Gson prettyGson() {
        return new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create();
    }

    /**
     * Keeps the deprecated flat {@code host/userid/password} fields in sync with the first
     * immich backend so older APK versions can still read the config file.
     */
    private static void mirrorLegacyHostFields(Configuration c) {
        ConfigurationBackendEntry first = null;
        for (ConfigurationBackendEntry entry : c.backendsOrEmpty()) {
            if (entry.isImmich()) {
                first = entry;
                break;
            }
        }
        if (first == null) {
            c.host = "";
            c.userid = "";
            c.password = "";
        } else {
            c.host = first.host;
            c.userid = first.userid;
            c.password = first.password;
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
