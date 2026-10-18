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
    /**
     * SharedPreferences key for the <em>Allow loopback ADB for update installs</em> flag. Like {@code trusted_network}
     * it is never part of {@code configuration.json} and never exported — it is a statement about
     * what this device may do to itself, not a preference to carry between devices. Off by default.
     */
    private static final String PREF_LOOPBACK_ADB = "loopback_adb";
    /**
     * SharedPreferences key for the backlight level the EO1's BACK button cycles. Stored as the
     * opaque step index {@link BrightnessManager} interprets, not as a brightness value. Per-device
     * hardware state like {@code trusted_network}: never in {@code configuration.json} and never
     * exported, because importing someone else's config must not silently change how bright this
     * frame is. It is also the only reason the level survives a restart at all — without it the
     * floor resets to its default every time the Activity is rebuilt.
     */
    private static final String PREF_MIN_BRIGHTNESS_STEP = "min_brightness_step";
    /**
     * SharedPreferences key for the operator's free-form label for <em>this</em> frame ("Kitchen").
     * Like the flags above it is never part of {@code configuration.json} and never exported: it
     * names one box, so carrying it to another on import would mislabel that one. Empty means unset —
     * every surface then identifies the frame by platform and IP address alone, exactly as before the
     * field existed.
     */
    private static final String PREF_DEVICE_NAME = "device_name";
    /**
     * Display bound for {@link #PREF_DEVICE_NAME}. Chars rather than code points: it is the same bound
     * the on-device field and the web page put on their inputs, so what an operator types is what is
     * stored. Cut on a code-point boundary, so a pair straddling the limit goes whole.
     */
    static final int DEVICE_NAME_MAX_CHARS = 64;
    /**
     * Overlay/log line this label is reported under. Shared because the map is keyed by it: the
     * resume-time seed ({@code MainActivity.onResume}) and every write have to land on one line.
     */
    static final String DEBUG_KEY_DEVICE_NAME = "device name";
    /** Byte bound, so a label of 4-byte code points still cannot outgrow a line of the state document. */
    static final int DEVICE_NAME_MAX_UTF8_BYTES = 128;
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
     * Whether this frame may start its own adbd (TCP 5555, root, bound to every interface) at all.
     * Off by default: it is a wider door than the frame's plain-HTTP web server, so it is never
     * opened as a side effect of anything. What it permits is one action at a time — an install while
     * an update is staged, or a watchdog rung the ladder has actually reached — and the caller closes
     * the port again when that action ends. Persisted in the app's default SharedPreferences, never
     * in {@code configuration.json}.
     */
    public boolean isLoopbackAdbAllowed() {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return false;
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            return prefs != null && prefs.getBoolean(PREF_LOOPBACK_ADB, false);
        } catch (Exception e) {
            return false;
        }
    }

    public void setLoopbackAdbAllowed(boolean allowed) {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return;
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            if (prefs != null) {
                prefs.edit().putBoolean(PREF_LOOPBACK_ADB, allowed).apply();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * The one question every caller actually has: may the frame arm loopback adbd right now? Both
     * flags must be on — <i>Trusted Network</i> says the wire is trusted, <i>Allow loopback ADB for update installs</i>
     * says opening a root shell on it was the operator's decision. Kept here so the install path and
     * the watchdog cannot drift apart on a security question.
     */
    public boolean isLoopbackAdbEnabled() {
        return isTrustedNetwork() && isLoopbackAdbAllowed();
    }

    /**
     * The persisted backlight step, or 0 (the floor) if nothing was ever stored. Range clamping is
     * {@link BrightnessManager}'s job — this is deliberately a dumb int, since what the number
     * means belongs to the brightness code and not to where it is kept.
     */
    public int getMinimumBrightnessStep() {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return 0;
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            return prefs != null ? prefs.getInt(PREF_MIN_BRIGHTNESS_STEP, 0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public void setMinimumBrightnessStep(int step) {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return;
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            if (prefs != null) {
                prefs.edit().putInt(PREF_MIN_BRIGHTNESS_STEP, step).apply();
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

    /**
     * The operator's label for this frame, for the debug overlay, the LAN page and {@code /state}.
     * Persisted in the app's default SharedPreferences, never in {@code configuration.json}.
     *
     * @return the stored name, sanitised; {@code ""} when unset, unreadable or the hosting activity is
     *         gone. Never {@code null} — every caller treats empty as "no name, show the IP".
     */
    public String deviceName() {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return "";
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            if (prefs == null) {
                return "";
            }
            // Sanitised on the way out, not just on the way in: the preferences file is writable
            // out of band (adb shell), and one surviving newline would forge a line of the overlay
            // and of eo1-app.log, both of which are formatted "key: value\n".
            return sanitizeDeviceName(prefs.getString(PREF_DEVICE_NAME, ""));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Stores {@code requested} as this frame's label and refreshes the overlay line. Both writers —
     * the options dialog and {@code POST /devicename} — go through here, so the sanitising and the
     * re-emit cannot drift apart. Deliberately does not call {@code settingsChanged()}: a rename
     * concerns neither the rotation nor the schedule.
     *
     * @param requested the typed name; empty clears it.
     * @return the name actually stored (so a caller can report the truncation it caused).
     */
    public String setDeviceName(String requested) {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return "";
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            if (prefs != null) {
                prefs.edit().putString(PREF_DEVICE_NAME, sanitizeDeviceName(requested)).apply();
            }
        } catch (Exception ignored) {
        }
        // Re-read rather than echo the argument, so under a concurrent write from the other surface
        // the line shows the last value that actually landed.
        String stored = deviceName();
        // The debug map is only ever added to, so a cleared name has to say so out loud — otherwise
        // the overlay keeps advertising a label nobody set any more.
        listener.debugInformationProvided(new DebugInformation(DEBUG_KEY_DEVICE_NAME,
                stored.isEmpty() ? "(unset)" : stored));
        return stored;
    }

    /**
     * The one canonical form of a device name: no control characters, no surrounding whitespace, no
     * longer than the two bounds above. Idempotent, which is what lets {@link #deviceName()} apply it
     * on reads as well as writes.
     */
    static String sanitizeDeviceName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                kept.append(c);
            }
        }
        String name = kept.toString().trim();
        if (name.length() > DEVICE_NAME_MAX_CHARS) {
            int end = DEVICE_NAME_MAX_CHARS;
            if (Character.isHighSurrogate(name.charAt(end - 1))) {
                end--;
            }
            name = name.substring(0, end);
        }
        int bytes = 0;
        int end = 0;
        while (end < name.length()) {
            int codePoint = name.codePointAt(end);
            int width = utf8Bytes(codePoint);
            if (bytes + width > DEVICE_NAME_MAX_UTF8_BYTES) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        // Cutting on the byte bound can leave a space that was interior before the cut.
        return name.substring(0, end).trim();
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
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
