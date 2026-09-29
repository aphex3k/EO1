package com.aphex3k.eo1;

import androidx.annotation.Keep;

import javax.annotation.Nullable;

/**
 * One configured media backend in {@link Configuration#backends}.
 *
 * <p>{@code "immich"} entries carry a host + credentials and an optional API version pin;
 * {@code "local"} entries expose this device's uploaded-media directory
 * ({@code filesDir/uploaded}, written by the LAN web server) and carry no credentials.
 * At most one {@code local} entry is honored (see {@link Configuration#normalize}).
 */
@Keep
public class ConfigurationBackendEntry {
    public static final String TYPE_IMMICH = "immich";
    public static final String TYPE_LOCAL = "local";
    /** {@code apiVersion} value meaning "determine the server version automatically". */
    public static final String API_VERSION_AUTO = "auto";

    /** "immich" or "local". */
    public String type = "";
    /** Stable instance id; auto-assigned when left blank ("immich-N" / "local"). */
    public String id = "";
    /** Immich base URL, e.g. "https://immich.local/". Immich entries only. */
    @Nullable public String host = "";
    /** Immich login email. Immich entries only. */
    @Nullable public String userid = "";
    /** Immich login password (cleartext on the device, as before). Immich entries only. */
    @Nullable public String password = "";
    /** "auto" (default) or a pinned server version such as "3.1.0". Immich entries only. */
    public String apiVersion = API_VERSION_AUTO;

    public boolean isLocal() {
        return TYPE_LOCAL.equalsIgnoreCase(type);
    }

    public boolean isImmich() {
        return TYPE_IMMICH.equalsIgnoreCase(type);
    }

    /** True when this entry is complete enough to drive rotation. */
    public boolean isValid() {
        if (isLocal()) {
            return true;
        }
        if (!isImmich()) {
            return false;
        }
        String h = host != null ? host.trim() : "";
        String lower = h.toLowerCase();
        return (lower.startsWith("http://") || lower.startsWith("https://"))
                && userid != null && !userid.trim().isEmpty()
                && password != null && !password.trim().isEmpty();
    }
}
