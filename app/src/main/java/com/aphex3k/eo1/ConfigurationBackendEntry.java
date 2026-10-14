package com.aphex3k.eo1;

import androidx.annotation.Keep;

import javax.annotation.Nullable;

/**
 * One configured media backend in {@link Configuration#backends}.
 *
 * <p>{@code "immich"} entries carry a host + credentials and an optional API version pin;
 * {@code "local"} entries expose this device's uploaded-media directory
 * ({@code filesDir/uploaded}, written by the LAN web server) and carry no credentials;
 * {@code "web"} entries display a web page, held in {@code host}, and carry no credentials.
 * At most one {@code local} entry is honored (see {@link Configuration#normalize}).
 */
@Keep
public class ConfigurationBackendEntry {
    public static final String TYPE_IMMICH = "immich";
    public static final String TYPE_LOCAL = "local";
    public static final String TYPE_WEB = "web";
    /** {@code apiVersion} value meaning "determine the server version automatically". */
    public static final String API_VERSION_AUTO = "auto";

    /** "immich", "local" or "web". */
    public String type = "";
    /** Stable instance id; auto-assigned when left blank ("immich-N" / "local"). */
    public String id = "";
    /**
     * Immich base URL (e.g. "https://immich.local/") for {@code immich} entries, or the page to
     * display (e.g. "http://192.168.1.20/dash") for {@code web} entries.
     */
    @Nullable public String host = "";
    /** Immich login email. Immich entries only. */
    @Nullable public String userid = "";
    /** Immich login password (cleartext on the device, as before). Immich entries only. */
    @Nullable public String password = "";
    /** "auto" (default) or a pinned server version such as "3.1.0". Immich entries only. */
    public String apiVersion = API_VERSION_AUTO;
    /**
     * Whether this backend participates in rotation. Default {@code true} so configuration files
     * written before the flag existed (no {@code "enabled"} key) keep their backends active.
     * Orthogonal to {@link #isValid()}: a disabled entry still counts as configured.
     */
    public boolean enabled = true;

    public boolean isLocal() {
        return TYPE_LOCAL.equalsIgnoreCase(type);
    }

    public boolean isImmich() {
        return TYPE_IMMICH.equalsIgnoreCase(type);
    }

    public boolean isWeb() {
        return TYPE_WEB.equalsIgnoreCase(type);
    }

    /** True when this entry is complete enough to drive rotation. */
    public boolean isValid() {
        if (isLocal()) {
            return true;
        }
        if (isWeb()) {
            return isHttpUrl(host);
        }
        if (!isImmich()) {
            return false;
        }
        return isHttpUrl(host)
                && userid != null && !userid.trim().isEmpty()
                && password != null && !password.trim().isEmpty();
    }

    /**
     * True when {@code value} is a non-blank absolute http(s) address. Never guesses a scheme:
     * {@code example.com} is invalid rather than silently rewritten to {@code https://}, so a
     * typo can't quietly send a frame to a public host.
     */
    static boolean isHttpUrl(@Nullable String value) {
        String lower = (value == null ? "" : value.trim()).toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
