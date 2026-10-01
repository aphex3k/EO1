package com.aphex3k.update;

import androidx.annotation.Keep;

import com.google.gson.Gson;

import java.util.Locale;

/**
 * Self-update manifest served as plain JSON at the configured manifest URL.
 *
 * <p>Plain-HTTP delivery: the manifest is untrusted input and is fully validated
 * ({@link #isValid()}) before any download starts. Nothing in the manifest is ever executed.
 */
@Keep
public class UpdateManifest {

    /** Hard cap on a downloadable APK. */
    public static final long MAX_APK_BYTES = 100L * 1024 * 1024;

    public int versionCode;
    public String versionName = "";
    public String apkUrl = "";
    /** 64 hex characters, lowercase. */
    public String sha256 = "";
    public long sizeBytes;
    public String notes = "";

    /**
     * Parses a manifest body.
     *
     * @return the parsed manifest, or {@code null} when the body is null/blank or not valid JSON.
     */
    public static UpdateManifest parse(String json) {
        if (json == null) {
            return null;
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return new Gson().fromJson(trimmed, UpdateManifest.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return {@code null} when the manifest is usable, otherwise a short machine-readable reason.
     */
    public String validationError() {
        if (versionCode <= 0) {
            return "versionCode-must-be-positive";
        }
        String url = apkUrl == null ? "" : apkUrl.trim();
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "apkUrl-must-be-http";
        }
        String sha = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.US);
        if (sha.length() != 64 || !isHex(sha)) {
            return "sha256-must-be-64-hex";
        }
        if (sizeBytes <= 0) {
            return "sizeBytes-must-be-positive";
        }
        if (sizeBytes > MAX_APK_BYTES) {
            return "sizeBytes-exceeds-cap";
        }
        return null;
    }

    public boolean isValid() {
        return validationError() == null;
    }

    /** True when the manifest offers a strictly newer versionCode than the installed app. */
    public boolean isNewerThan(int installedVersionCode) {
        return versionCode > installedVersionCode;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
