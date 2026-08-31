package com.aphex3k.eo1.ffmpeg;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VideoProbe {

    private static final Pattern VIDEO_STREAM_BLOCK = Pattern.compile(
            "\\{[^{}]*\"codec_type\"\\s*:\\s*\"video\"[^{}]*\\}",
            Pattern.DOTALL);
    private static final Pattern STRING_FIELD = Pattern.compile(
            "\"(%s)\"\\s*:\\s*\"([^\"]*)\"");

    private final FfmpegCommandRunner runner;

    public VideoProbe() {
        this(new FfmpegKitCommandRunner());
    }

    public VideoProbe(FfmpegCommandRunner runner) {
        this.runner = runner;
    }

    public boolean isAvailable() {
        return runner.isAvailable();
    }

    public ProbeResult probe(File input) {
        if (input == null || !input.exists()) {
            return ProbeResult.failure("Input file does not exist");
        }
        if (!runner.isAvailable()) {
            return ProbeResult.failure("FFprobe not available");
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
            return ProbeResult.failure("FFprobe failed: " + result.getOutput());
        }

        return parseProbeJson(result.getOutput());
    }

    static ProbeResult parseProbeJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return ProbeResult.failure("Empty FFprobe output");
        }

        try {
            String containerFormat = extractStringField(json, "format_name");
            Matcher streamMatcher = VIDEO_STREAM_BLOCK.matcher(json);
            if (!streamMatcher.find()) {
                return ProbeResult.failure("No video stream found");
            }

            String videoStream = streamMatcher.group();
            String videoCodec = extractStringField(videoStream, "codec_name");
            if (videoCodec == null || videoCodec.isEmpty()) {
                return ProbeResult.failure("No video stream found");
            }

            int width = extractIntField(videoStream, "width");
            int height = extractIntField(videoStream, "height");

            return ProbeResult.success(videoCodec, containerFormat, width, height);
        } catch (Exception e) {
            return ProbeResult.failure("Failed to parse FFprobe JSON: " + e.getMessage());
        }
    }

    private static String extractStringField(String json, String fieldName) {
        Pattern pattern = Pattern.compile(
                String.format(Locale.US, "\"%s\"\\s*:\\s*\"([^\"]*)\"", fieldName));
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static int extractIntField(String json, String fieldName) {
        Pattern pattern = Pattern.compile(
                String.format(Locale.US, "\"%s\"\\s*:\\s*(\\d+)", fieldName));
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }
}
