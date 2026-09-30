package com.aphex3k.eo1;

import androidx.annotation.Keep;

import com.aphex3k.media.immich.ImmichClientRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

@Keep
public class Configuration {
    /**
     * Configured media backends (source of truth for rotation). Each entry drives one
     * {@code com.aphex3k.media.MediaBackend} instance; "local" entries may appear at most once.
     */
    @Nullable public List<ConfigurationBackendEntry> backends;

    /** @deprecated legacy single-host field; kept for migration and older-APK compatibility. */
    @Deprecated @Nullable public String host = "";
    /** @deprecated legacy single-host field; kept for migration and older-APK compatibility. */
    @Deprecated @Nullable public String userid = "";
    /** @deprecated legacy single-host field; kept for migration and older-APK compatibility. */
    @Deprecated @Nullable public String password = "";

    @Nullable public String selectedTimeZoneId = "";

    /**
     * Quiet-hour windows as 5-field cron expressions
     * (minute hour day-of-month month day-of-week); the screen is off whenever any
     * expression matches the current minute in {@link #selectedTimeZoneId}. Overlapping
     * windows simply OR together.
     */
    @Nullable public List<String> quietHours;

    /** @deprecated migrated to {@link #quietHours} on load; mirrored on save for older APKs. */
    @Deprecated public int startQuietHour = -1;
    /** @deprecated migrated to {@link #quietHours} on load; mirrored on save for older APKs. */
    @Deprecated public int endQuietHour = -1;

    public int interval = 5;
    // MQTT fields
    @Nullable public String mqttHost = "";
    public int mqttPort = 1883;
    @Nullable public String mqttProtocol = "";
    @Nullable public String mqttUser = "";
    @Nullable public String mqttPassword = "";

    public List<ConfigurationBackendEntry> backendsOrEmpty() {
        return backends != null ? backends : new ArrayList<ConfigurationBackendEntry>();
    }

    public List<String> quietHoursOrEmpty() {
        return quietHours != null ? quietHours : new ArrayList<String>();
    }

    /** Number of backend entries complete enough to drive rotation. */
    public int validBackendCount() {
        int n = 0;
        for (ConfigurationBackendEntry e : backendsOrEmpty()) {
            if (e.isValid()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Normalizes the configuration in place; returns true when anything changed. Idempotent.
     *
     * <ol>
     *   <li>Legacy migration: when no backends are configured but the legacy flat
     *       host/userid/password triple is present, synthesize one immich entry.</li>
     *   <li>Trims string fields; blank {@code apiVersion} becomes "auto"; an unparseable
     *       pinned version is relaxed to "auto" rather than silently disabling the backend.</li>
     *   <li>Blank ids get auto-ids ("immich-N" 1-based, "local").</li>
     *   <li>At most one local entry is kept (first wins); duplicate immich entries
     *       (same host + userid) are dropped.</li>
     *   <li>Immich hosts get a trailing "/" appended when missing (Retrofit baseUrl).</li>
     *   <li>Quiet hours: when no {@code quietHours} list is present but the legacy
     *       {@code startQuietHour}/{@code endQuietHour} pair is, migrate it to a single
     *       cron expression. When a list is present, entries are trimmed and any
     *       unparseable entry is dropped.</li>
     * </ol>
     */
    static boolean normalize(Configuration c) {
        if (c == null) {
            return false;
        }

        boolean changed = false;

        // 1. Legacy migration: flat triple -> single immich backend.
        if ((c.backends == null || c.backends.isEmpty())
                && c.host != null && !c.host.trim().isEmpty()) {
            ConfigurationBackendEntry e = new ConfigurationBackendEntry();
            e.type = ConfigurationBackendEntry.TYPE_IMMICH;
            e.id = "immich";
            e.host = c.host;
            e.userid = c.userid;
            e.password = c.password;
            e.apiVersion = ConfigurationBackendEntry.API_VERSION_AUTO;
            c.backends = new ArrayList<ConfigurationBackendEntry>();
            c.backends.add(e);
            changed = true;
        }
        if (c.backends == null) {
            c.backends = new ArrayList<ConfigurationBackendEntry>();
            changed = true;
        }

        // 2. Trim strings.
        for (ConfigurationBackendEntry e : c.backends) {
            changed |= trimEntry(e);
        }

        // 3. apiVersion sanity: blank -> auto; unparseable pin -> auto.
        for (ConfigurationBackendEntry e : c.backends) {
            if (!e.isImmich()) {
                if (!e.apiVersion.isEmpty()) {
                    e.apiVersion = "";
                    changed = true;
                }
                continue;
            }
            if (e.apiVersion.isEmpty()) {
                e.apiVersion = ConfigurationBackendEntry.API_VERSION_AUTO;
                changed = true;
            } else if (!ConfigurationBackendEntry.API_VERSION_AUTO.equalsIgnoreCase(e.apiVersion)
                    && !isParseableSemver(e.apiVersion)) {
                e.apiVersion = ConfigurationBackendEntry.API_VERSION_AUTO;
                changed = true;
            }
        }

        // 4. Dedupe: at most one local entry; drop duplicate (host, userid) immich entries.
        List<ConfigurationBackendEntry> kept = new ArrayList<ConfigurationBackendEntry>();
        boolean sawLocal = false;
        Set<String> seenImmich = new HashSet<String>();
        for (ConfigurationBackendEntry e : c.backends) {
            if (e.isLocal()) {
                if (sawLocal) {
                    changed = true;
                    continue;
                }
                sawLocal = true;
            } else if (e.isImmich()) {
                String key = e.host + "\u0000" + e.userid;
                if (!seenImmich.add(key)) {
                    changed = true;
                    continue;
                }
            }
            kept.add(e);
        }
        if (kept.size() != c.backends.size()) {
            c.backends = kept;
        }

        // 5. Auto-ids and trailing slash for immich hosts.
        int immichCount = 0;
        for (ConfigurationBackendEntry e : c.backends) {
            if (e.isLocal()) {
                if (e.id.isEmpty()) {
                    e.id = "local";
                    changed = true;
                }
            } else if (e.isImmich()) {
                immichCount++;
                if (e.id.isEmpty()) {
                    e.id = "immich-" + immichCount;
                    changed = true;
                }
                if (e.host != null && hasHttpScheme(e.host) && !e.host.endsWith("/")) {
                    e.host = e.host + "/";
                    changed = true;
                }
            }
        }

        // 6. Quiet hours: migrate the legacy start/end pair; trim + drop bad entries.
        if ((c.quietHours == null || c.quietHours.isEmpty())
                && c.startQuietHour >= 0 && c.endQuietHour >= 0
                && c.startQuietHour != c.endQuietHour) {
            c.quietHours = new ArrayList<String>();
            c.quietHours.add(legacyQuietHoursExpression(c.startQuietHour, c.endQuietHour));
            changed = true;
        }
        if (c.quietHours != null) {
            List<String> keptWindows = new ArrayList<String>();
            for (String raw : c.quietHours) {
                if (raw == null) {
                    changed = true;
                    continue;
                }
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || CronExpression.parse(trimmed) == null) {
                    changed = true;
                    continue;
                }
                if (!trimmed.equals(raw)) {
                    changed = true;
                }
                keptWindows.add(trimmed);
            }
            c.quietHours = keptWindows;
        }
        return changed;
    }

    /**
     * Renders the legacy hour-granular quiet span as a cron expression: quiet from
     * {@code start:00} until {@code end:00} (end-exclusive), wrapping midnight when
     * {@code start > end}.
     */
    private static String legacyQuietHoursExpression(int start, int end) {
        StringBuilder hours = new StringBuilder();
        if (start < end) {
            hours.append(hourRange(start, end - 1));
        } else {
            hours.append(hourRange(start, 23));
            if (end > 1) {
                hours.append(',');
                hours.append(hourRange(0, end - 1));
            } else if (end == 1) {
                hours.append(",0");
            }
        }
        return "* " + hours + " * * *";
    }

    private static String hourRange(int lo, int hi) {
        return lo == hi ? String.valueOf(lo) : lo + "-" + hi;
    }

    /**
     * Keeps the deprecated {@code startQuietHour}/{@code endQuietHour} fields in sync with
     * {@code quietHours} so older APKs keep working. Only a single whole-hour window
     * (minute 0, no day/month/dow restriction, contiguous hours) is mirrorable; anything
     * richer degrades to "quiet hours off" (-1/-1) rather than wrong hours.
     */
    static void mirrorLegacyQuietHourFields(Configuration c) {
        c.startQuietHour = -1;
        c.endQuietHour = -1;
        List<String> windows = c.quietHoursOrEmpty();
        if (windows.size() != 1) {
            return;
        }
        CronExpression e = CronExpression.parse(windows.get(0));
        // Only a whole-hour window (minute field all minutes, or just minute 0) maps onto
        // the legacy hour-granular fields; any other minute pattern has no legacy spelling.
        if (e == null || (!e.isAllMinutes() && !e.isMinuteZeroOnly())
                || !e.isDomUnrestricted() || !e.isMonthUnrestricted() || !e.isDowUnrestricted()) {
            return;
        }
        int[] hours = e.matchedHours();
        if (hours.length == 0 || hours.length == 24) {
            return;
        }
        boolean[] present = new boolean[24];
        for (int h : hours) {
            present[h] = true;
        }
        // Window start = an hour in the set whose predecessor hour is not in the set
        // (handles overnight windows, where hour 0 is NOT the start).
        int start = -1;
        for (int h = 0; h < 24; h++) {
            if (present[h] && !present[(h + 23) % 24]) {
                start = h;
                break;
            }
        }
        if (start < 0) {
            return;
        }
        // Contiguity: every hour start..start+length-1 (mod 24) must be selected.
        for (int i = 0; i < hours.length; i++) {
            if (!present[(start + i) % 24]) {
                return;
            }
        }
        c.startQuietHour = start;
        c.endQuietHour = (start + hours.length) % 24;
    }

    /** Trims the non-secret text fields of an entry; never touches the password. */
    private static boolean trimEntry(ConfigurationBackendEntry e) {
        String before = join(e);
        if (e.type == null) {
            e.type = "";
        } else {
            e.type = e.type.trim();
        }
        e.id = e.id == null ? "" : e.id.trim();
        e.host = e.host == null ? "" : e.host.trim();
        e.userid = e.userid == null ? "" : e.userid.trim();
        e.apiVersion = e.apiVersion == null ? "" : e.apiVersion.trim();
        return !before.equals(join(e));
    }

    private static String join(ConfigurationBackendEntry e) {
        return e.type + "\u0000" + (e.id == null ? "" : e.id)
                + "\u0000" + (e.host == null ? "" : e.host)
                + "\u0000" + (e.userid == null ? "" : e.userid)
                + "\u0000" + (e.apiVersion == null ? "" : e.apiVersion);
    }

    private static boolean hasHttpScheme(String host) {
        String h = host.toLowerCase();
        return h.startsWith("http://") || h.startsWith("https://");
    }

    /** Delegates to the client registry so pin parsing has a single source of truth. */
    static boolean isParseableSemver(String s) {
        return ImmichClientRegistry.parsePin(s) != null;
    }
}
