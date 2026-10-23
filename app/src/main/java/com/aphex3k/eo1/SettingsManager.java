package com.aphex3k.eo1;

import android.app.AlarmManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

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

    private static final String TAG = "SettingsManager";
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
     * SharedPreferences key for whether the roots in {@code com.aphex3k.eo1.trust.RootBundle} are added
     * to this device's own anchors when a TLS client is built.
     *
     * <p><b>On by default</b>, which is the opposite of the two flags above and is deliberate. Those
     * grant a privilege — a root shell, an editable config over the LAN — so nothing happens until an
     * operator asks. This one changes nothing about the device: it trusts a fixed set of public root
     * certificates that shipped inside an APK the frame already verified by signature. The failure it
     * protects against is the quiet one, where a frame whose Immich server moved to a current
     * certificate shows a black screen and reports an error nobody can name, because the store it was
     * shipped with in 2013 cannot build a path. A switch that has to be found and turned on fixes
     * nothing on a frame behind glass.
     *
     * <p>It is still a switch, because an escape hatch from a trust decision has to be reachable
     * without shipping another APK: off restores exactly the behaviour of every earlier build. Never in
     * {@code configuration.json}, and never exported.
     *
     * <p>Public because {@code ApiServiceGenerator} reads it directly: a client can be built on a path
     * that has a {@code Context} and no {@code SettingsManager} instance.
     */
    public static final String PREF_TRUST_BUNDLE = "trust_bundle";
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
     * Whether the shipped root bundle extends this device's anchors. On unless an operator turned it
     * off — see {@link #PREF_TRUST_BUNDLE} for why this default is inverted from the other flags.
     */
    public boolean isTrustBundleEnabled() {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return true;
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            return prefs == null || prefs.getBoolean(PREF_TRUST_BUNDLE, true);
        } catch (Exception e) {
            // An unreadable store reads as on, never as off: "the setting could not be read" and
            // "the operator asked for the old behaviour" must not answer the same way, because the
            // second one is a decision and the first is a bug.
            return true;
        }
    }

    public void setTrustBundleEnabled(boolean enabled) {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            return;
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            if (prefs != null) {
                prefs.edit().putBoolean(PREF_TRUST_BUNDLE, enabled).apply();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * {@link #readMinimumBrightnessStep()} could not find the key at all: nobody has ever adjusted
     * this frame. A real answer, not a failure — it is what a fresh install looks like.
     */
    public static final int STEP_ABSENT = -1;
    /**
     * {@link #readMinimumBrightnessStep()} got no answer from the store: no host activity, no
     * preferences, or a read that threw. This is NOT "the frame has no level", and treating it as
     * such is how a frame dims itself and then writes the dimming back as if it had been asked to.
     */
    public static final int STEP_UNREADABLE = -2;
    /**
     * The last level any {@code SettingsManager} in this process saw on disk or wrote to it.
     *
     * <p>Static on purpose: {@code MainActivity.onCreate} builds a fresh {@code SettingsManager} (and
     * a fresh {@code BrightnessManager}) on every re-creation of the Activity, and a re-creation is
     * exactly the event this cache exists to survive. An instance field here would be empty at the
     * only moment it is ever read.
     */
    private static volatile int lastKnownGoodStep = STEP_ABSENT;

    /**
     * The level this process last knew was on disk, or a sentinel if it never knew one. Reads a
     * process value, but is an instance method so a caller's store — including a test's — is the
     * only thing it can talk to.
     */
    public int lastKnownGoodBrightnessStep() {
        return lastKnownGoodStep;
    }

    /**
     * Drops the process cache. Only for tests: a static outlives the case that filled it, and one
     * case's last-known-good leaking into the next would make a pass prove nothing.
     */
    static void forgetBrightnessLevel() {
        lastKnownGoodStep = STEP_ABSENT;
    }

    /**
     * The persisted backlight step, exactly: a step in range, {@link #STEP_ABSENT}, or
     * {@link #STEP_UNREADABLE}. Range clamping is {@link BrightnessManager}'s job — this is
     * deliberately a dumb int, since what the number means belongs to the brightness code and not to
     * where it is kept.
     *
     * <p>The two failures are kept apart because they deserve different handling, and because
     * collapsing them into {@code 0} — which used to happen, and which is also the dimmest level the
     * button has — is the whole reason an EO1 that dimmed itself could not be told apart from one
     * that was simply never adjusted.
     */
    public int readMinimumBrightnessStep() {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            Log.w(TAG, "backlight level unreadable: settings store has no host activity");
            return STEP_UNREADABLE;
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            if (prefs == null) {
                Log.w(TAG, "backlight level unreadable: no default shared preferences");
                return STEP_UNREADABLE;
            }
            if (!prefs.contains(PREF_MIN_BRIGHTNESS_STEP)) {
                return STEP_ABSENT;
            }
            int step = prefs.getInt(PREF_MIN_BRIGHTNESS_STEP, STEP_ABSENT);
            if (step == STEP_ABSENT) {
                // The key was there a moment ago and is not now; honest answer is "absent".
                return STEP_ABSENT;
            }
            lastKnownGoodStep = step;
            return step;
        } catch (Exception e) {
            Log.w(TAG, "backlight level unreadable", e);
            return STEP_UNREADABLE;
        }
    }

    /**
     * One of {@code "value"}, {@code "absent"}, {@code "unreadable"} — whether the store answered
     * just now, reported nothing stored, or would not say. Reported in {@code /state} so "the frame
     * dimmed itself" is answerable from the frame.
     */
    public String minimumBrightnessStoreState() {
        int stored = readMinimumBrightnessStep();
        if (stored >= 0) {
            return "value";
        }
        return stored == STEP_ABSENT ? "absent" : "unreadable";
    }

    /**
     * @return true when {@code step} is on disk. Written with {@code commit()}, not {@code apply()}:
     *         {@code apply()} hands the write to a background thread that a hard exit never reaches,
     *         and this app already lost a value that way once — see {@code ImmichSessionStore}, which
     *         commits for the same reason. A level that lived only in memory comes back as the
     *         default floor on the next boot, so the tap the frame was reported for would have been
     *         undone by the next crash even without the gate that did undo it.
     */
    public boolean setMinimumBrightnessStep(int step) {
        SettingsManagerListener listener = this.listener.get();
        if (listener == null) {
            Log.e(TAG, "backlight level NOT persisted: settings store has no host activity");
            return false;
        }
        try {
            SharedPreferences prefs = listener.getDefaultSharedPreferences();
            if (prefs == null) {
                Log.e(TAG, "backlight level NOT persisted: no default shared preferences");
                return false;
            }
            boolean durable = prefs.edit().putInt(PREF_MIN_BRIGHTNESS_STEP, step).commit();
            if (!durable) {
                Log.e(TAG, "backlight level NOT persisted: the preferences write failed");
            } else if (step >= 0) {
                lastKnownGoodStep = step;
            }
            return durable;
        } catch (Exception e) {
            Log.e(TAG, "backlight level NOT persisted", e);
            return false;
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
