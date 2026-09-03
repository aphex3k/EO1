package com.aphex3k.eo1.ffmpeg;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VideoProbe {

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

    public String getUnavailableReason() {
        return runner.getUnavailableReason();
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

    static ProbeResult parseProbeJson(String output) {
        String json = extractJsonPayload(output);
        if (json == null || json.trim().isEmpty()) {
            return ProbeResult.failure("Empty FFprobe output");
        }

        try {
            String containerFormat = extractFormatName(json);
            String videoStream = findStreamJsonByCodecType(json, "video");
            if (videoStream == null) {
                return ProbeResult.failure("No video stream found");
            }

            String videoCodec = extractStringField(videoStream, "codec_name");
            if (videoCodec == null || videoCodec.isEmpty()) {
                return ProbeResult.failure("No video stream found");
            }

            int width = extractIntField(videoStream, "width");
            int height = extractIntField(videoStream, "height");
            boolean hasAudio = findStreamJsonByCodecType(json, "audio") != null;

            return ProbeResult.success(videoCodec, containerFormat, width, height, hasAudio);
        } catch (Exception e) {
            return ProbeResult.failure("Failed to parse FFprobe JSON: " + e.getMessage());
        }
    }

    static String extractJsonPayload(String output) {
        if (output == null) {
            return null;
        }
        int start = output.indexOf('{');
        if (start < 0) {
            return null;
        }
        int end = indexOfMatchingBrace(output, start);
        if (end < 0) {
            return null;
        }
        return output.substring(start, end + 1);
    }

    static String findStreamJsonByCodecType(String json, String codecType) {
        int streamsKey = json.indexOf("\"streams\"");
        if (streamsKey < 0) {
            return null;
        }
        int arrayStart = json.indexOf('[', streamsKey);
        if (arrayStart < 0) {
            return null;
        }

        int pos = arrayStart + 1;
        while (pos < json.length()) {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }
            if (pos >= json.length()) {
                break;
            }
            char ch = json.charAt(pos);
            if (ch == ']') {
                break;
            }
            if (ch == ',') {
                pos++;
                continue;
            }
            if (ch != '{') {
                break;
            }

            int end = indexOfMatchingBrace(json, pos);
            if (end < 0) {
                break;
            }

            String block = json.substring(pos, end + 1);
            if (codecType.equals(extractStringField(block, "codec_type"))) {
                return block;
            }
            pos = end + 1;
        }
        return null;
    }

    static String extractFormatName(String json) {
        int formatKey = json.indexOf("\"format\"");
        if (formatKey >= 0) {
            int objStart = json.indexOf('{', formatKey);
            if (objStart >= 0) {
                int objEnd = indexOfMatchingBrace(json, objStart);
                if (objEnd >= 0) {
                    String formatBlock = json.substring(objStart, objEnd + 1);
                    String formatName = extractStringField(formatBlock, "format_name");
                    if (formatName != null) {
                        return formatName;
                    }
                }
            }
        }
        return extractStringField(json, "format_name");
    }

    static int indexOfMatchingBrace(String text, int openIndex) {
        if (openIndex < 0 || openIndex >= text.length() || text.charAt(openIndex) != '{') {
            return -1;
        }

        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
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
