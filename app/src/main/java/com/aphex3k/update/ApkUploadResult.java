package com.aphex3k.update;

/**
 * Outcome of staging an APK uploaded through the LAN web server ({@code POST /update}).
 *
 * <p>Pure Java (no Android imports), like the rest of the update package, so the web layer
 * can surface it without touching Android types.
 */
public final class ApkUploadResult {

    /** True when the APK passed every gate and was staged for install. */
    public final boolean ok;
    /** Lowercase hex SHA-256 of the uploaded file; {@code ""} when it could not be computed. */
    public final String sha256;
    /** The uploaded file's size in bytes. */
    public final long sizeBytes;
    /** Whether the JAR signature matched the installed app's signing certificate. */
    public final boolean signatureValid;
    /** Machine-readable rejection reason; {@code ""} when {@link #ok} is true. */
    public final String reason;

    private ApkUploadResult(boolean ok, String sha256, long sizeBytes, boolean signatureValid, String reason) {
        this.ok = ok;
        this.sha256 = sha256 == null ? "" : sha256;
        this.sizeBytes = sizeBytes;
        this.signatureValid = signatureValid;
        this.reason = reason == null ? "" : reason;
    }

    /** The APK passed all gates and is staged for install. */
    public static ApkUploadResult staged(String sha256, long sizeBytes) {
        return new ApkUploadResult(true, sha256, sizeBytes, true, "");
    }

    /** The APK was rejected (the file has already been deleted by the caller). */
    public static ApkUploadResult reject(String sha256, long sizeBytes, boolean signatureValid, String reason) {
        return new ApkUploadResult(false, sha256, sizeBytes, signatureValid, reason);
    }
}
