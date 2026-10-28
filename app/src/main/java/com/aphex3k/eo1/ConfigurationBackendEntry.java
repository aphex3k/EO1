package com.aphex3k.eo1;

import androidx.annotation.Keep;

import javax.annotation.Nullable;

/**
 * One configured media backend in {@link Configuration#backends}.
 *
 * <p>{@code "immich"} entries carry a host + credentials and an optional API version pin;
 * {@code "local"} entries expose this device's uploaded-media directory
 * ({@code filesDir/uploaded}, written by the LAN web server) and carry no credentials;
 * {@code "web"} entries display a web page, held in {@code host}, and carry no credentials;
 * {@code "flickr"} entries display someone's photostream or album, named in {@code source} and read
 * with the application key in {@code apiKey}, and ignore {@code host} entirely — their endpoint is a
 * compile-time constant, so there is nothing for a base URL to point at.
 * At most one {@code local} entry is honored (see {@link Configuration#normalize}).
 */
@Keep
public class ConfigurationBackendEntry {
    public static final String TYPE_IMMICH = "immich";
    public static final String TYPE_LOCAL = "local";
    public static final String TYPE_WEB = "web";
    public static final String TYPE_FLICKR = "flickr";
    /** {@code apiVersion} value meaning "determine the server version automatically". */
    public static final String API_VERSION_AUTO = "auto";

    /** "immich", "local", "web" or "flickr". */
    public String type = "";
    /** Stable instance id; auto-assigned when left blank ("immich-N" / "web-N" / "flickr-N" / "local"). */
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
     * Flickr application key (32 hex characters). Flickr entries only.
     *
     * <p>It identifies the <em>application</em>, not a user, so a Flickr entry is usable with nothing
     * but this: every listing method this backend calls answers an unauthenticated call. That is also
     * why the key is the one credential here — the app ships no default value for it, so a frame with
     * an empty key reads as not configured rather than falling back to somebody else's.
     */
    @Nullable public String apiKey = "";
    /**
     * What to display: a photostream or album URL, or a Flickr username. Flickr entries only.
     *
     * <p>Held as written, like a {@code web} entry's page address: it is a resource path, so the
     * trailing slash {@link Configuration#normalize} appends to an immich base URL would change which
     * stream is read.
     */
    @Nullable public String source = "";
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

    public boolean isFlickr() {
        return TYPE_FLICKR.equalsIgnoreCase(type);
    }

    /** True when this entry is complete enough to drive rotation. */
    public boolean isValid() {
        if (isLocal()) {
            return true;
        }
        if (isWeb()) {
            return isHttpUrl(host);
        }
        if (isFlickr()) {
            // A key with nowhere to point reads as configured and then fetches nothing, and a stream
            // with no key cannot be listed at all: Flickr answers every call with code 100. Both must
            // be invalid, so the frame says "not configured" instead of showing an empty wall.
            return notBlank(apiKey) && notBlank(source);
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
    static boolean notBlank(@Nullable String value) {
        return value != null && !value.trim().isEmpty();
    }

    static boolean isHttpUrl(@Nullable String value) {
        String lower = (value == null ? "" : value.trim()).toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
