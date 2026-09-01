package com.aphex3k.eo1.ffmpeg;

import java.io.File;

/**
 * Probes still-image files via FFprobe (same JSON path as {@link VideoProbe}).
 */
public class ImageProbe {

    private final FfmpegCommandRunner runner;

    public ImageProbe() {
        this(new FfmpegKitCommandRunner());
    }

    public ImageProbe(FfmpegCommandRunner runner) {
        this.runner = runner;
    }

    public boolean isAvailable() {
        return runner.isAvailable();
    }

    public String getUnavailableReason() {
        return runner.getUnavailableReason();
    }

    public ImageProbeResult probe(File input) {
        if (input == null || !input.exists()) {
            return ImageProbeResult.failure("Input file does not exist");
        }
        if (!runner.isAvailable()) {
            return ImageProbeResult.failure("FFprobe not available");
        }

        String[] command = new String[]{
                "ffprobe",
                "-v", "quiet",
                "-print_format", "json",
                "-show_streams",
                "-show_format",
                input.getAbsolutePath()
        };

        FfmpegCommandRunner.CommandResult result = runner.execute(command);
        if (!result.isSuccess()) {
            return ImageProbeResult.failure("FFprobe failed: " + result.getOutput());
        }

        return parseProbeJson(result.getOutput());
    }

    static ImageProbeResult parseProbeJson(String output) {
        String json = VideoProbe.extractJsonPayload(output);
        if (json == null || json.trim().isEmpty()) {
            return ImageProbeResult.failure("Empty FFprobe output");
        }

        try {
            String formatName = VideoProbe.extractFormatName(json);
            String videoStream = VideoProbe.findStreamJsonByCodecType(json, "video");
            if (videoStream == null) {
                // Some HEIF probes still expose dimensions under format; treat as failure for policy.
                if (KnownIncompatibleImageFormats.isKnownIncompatible(formatName)) {
                    return ImageProbeResult.success(null, formatName, 0, 0);
                }
                return ImageProbeResult.failure("No image stream found");
            }

            String codecName = extractStringField(videoStream, "codec_name");
            int width = extractIntField(videoStream, "width");
            int height = extractIntField(videoStream, "height");

            return ImageProbeResult.success(codecName, formatName, width, height);
        } catch (Exception e) {
            return ImageProbeResult.failure("Failed to parse FFprobe JSON: " + e.getMessage());
        }
    }

    private static String extractStringField(String json, String fieldName) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                String.format(java.util.Locale.US, "\"%s\"\\s*:\\s*\"([^\"]*)\"", fieldName));
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static int extractIntField(String json, String fieldName) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                String.format(java.util.Locale.US, "\"%s\"\\s*:\\s*(\\d+)", fieldName));
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }
}
