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
    public int startQuietHour = -1;
    public int endQuietHour = -1;
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
        return changed;
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
