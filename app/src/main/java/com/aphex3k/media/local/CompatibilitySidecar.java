package com.aphex3k.media.local;

import android.os.Build;

import com.aphex3k.eo1.BuildConfig;
import com.aphex3k.media.MediaType;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Plaintext compatibility report written next to a local upload that fails a
 * {@code com.aphex3k.eo1.MediaCompatibility} check, so the user can see why the
 * frame is skipping it and how to fix it.
 *
 * <p>The sidecar is a {@value #SUFFIX} file in the same directory as the asset.
 * While it exists, the asset stays out of the rotation — it is the authoritative
 * exclusion marker. It is written once, on first failure; this class never
 * overwrites, renames, or deletes anything.
 */
public final class CompatibilitySidecar {

    /** Suffix appended to the asset file name to hold its compatibility report. */
    public static final String SUFFIX = ".incompat.txt";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private CompatibilitySidecar() {
    }

    public static File sidecarFor(File asset) {
        return new File(asset.getAbsolutePath() + SUFFIX);
    }

    public static boolean exists(File asset) {
        return sidecarFor(asset).isFile();
    }

    /**
     * Writes the compatibility report for {@code asset} if one is not already present.
     *
     * @param check either {@code "extension"} or {@code "content"} — which compatibility
     *              check produced the failure
     * @return {@code true} when a sidecar was written, {@code false} when one already
     *         existed (first failure is authoritative and is never rewritten)
     * @throws IOException when the report could not be written
     */
    public static boolean writeIfAbsent(File asset, MediaType type, String check, String reason,
                                        long sizeBytes, int width, int height, String backendId)
            throws IOException {
        File sidecar = sidecarFor(asset);
        if (sidecar.exists()) {
            return false;
        }
        OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(sidecar), UTF_8);
        try {
            writer.write(render(asset.getName(), type, check, reason, sizeBytes, width, height,
                    backendId));
        } finally {
            writer.close();
        }
        return true;
    }

    static String render(String fileName, MediaType type, String check, String reason,
                         long sizeBytes, int width, int height, String backendId) {
        String typeLabel = type == null ? "unknown" : type.name().toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Local media compatibility report\n");
        sb.append("================================\n\n");
        sb.append("File:    ").append(fileName).append('\n');
        sb.append("Type:    ").append(typeLabel).append('\n');
        sb.append("Size:    ").append(sizeBytes).append(" bytes\n");
        sb.append("Checked: ").append(timestampUtc()).append(" UTC\n\n");
        sb.append("Reason:  ").append(reason).append('\n');
        sb.append('\n');
        sb.append("How to fix:\n");
        sb.append(fixInstruction(typeLabel)).append('\n');
        sb.append("• While this sidecar file (").append(fileName).append(SUFFIX)
                .append(") exists, ").append(fileName)
                .append(" stays out of the rotation. Delete this .txt file");
        sb.append(" (web UI: Files table → Delete, or on disk) to have the frame");
        sb.append(" re-check it on the next rotation tick.\n");
        sb.append("• The frame never deletes media files or sidecar files on its own.\n\n");
        sb.append("Developer information (JSON):\n");
        sb.append(infoJson(fileName, typeLabel, check, reason, sizeBytes, width, height,
                backendId).toString());
        sb.append('\n');
        return sb.toString();
    }

    private static String fixInstruction(String typeLabel) {
        if ("video".equals(typeLabel)) {
            return "• Re-encode this video to H.264 in an MP4 container (long side ≤ "
                    + com.aphex3k.eo1.MediaCompatibility.MAX_VIDEO_SIDE_PX + " px) and upload "
                    + "that instead. This device cannot decode the video's codec.";
        }
        return "• Convert this still to JPEG or PNG and upload that instead. This device"
                + " cannot decode HEIF/AV1 stills.";
    }

    private static JsonObject infoJson(String fileName, String typeLabel, String check,
                                       String reason, long sizeBytes, int width, int height,
                                       String backendId) {
        JsonObject o = new JsonObject();
        o.addProperty("asset", fileName);
        o.addProperty("backendId", backendId);
        o.addProperty("mediaType", typeLabel);
        o.addProperty("sizeBytes", sizeBytes);
        o.addProperty("width", width);
        o.addProperty("height", height);
        o.addProperty("check", check);
        o.addProperty("reason", reason);
        o.addProperty("timestampUtc", timestampUtc());
        o.addProperty("manufacturer", Build.MANUFACTURER);
        o.addProperty("model", Build.MODEL);
        o.addProperty("android", Build.VERSION.RELEASE);
        o.addProperty("version", BuildConfig.VERSION_NAME + "." + BuildConfig.VERSION_CODE);
        return o;
    }

    private static String timestampUtc() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
