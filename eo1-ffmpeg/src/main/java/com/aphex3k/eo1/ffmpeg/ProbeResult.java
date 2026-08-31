package com.aphex3k.eo1.ffmpeg;

public class ProbeResult {

    public static final int MAX_LONGEST_AXIS = 1920;

    private final boolean success;
    private final String videoCodec;
    private final String containerFormat;
    private final int width;
    private final int height;
    private final boolean hasAudio;
    private final String errorMessage;

    public ProbeResult(boolean success, String videoCodec, String containerFormat,
                       int width, int height, boolean hasAudio, String errorMessage) {
        this.success = success;
        this.videoCodec = videoCodec;
        this.containerFormat = containerFormat;
        this.width = width;
        this.height = height;
        this.hasAudio = hasAudio;
        this.errorMessage = errorMessage;
    }

    public static ProbeResult failure(String message) {
        return new ProbeResult(false, null, null, 0, 0, false, message);
    }

    public static ProbeResult success(String videoCodec, String containerFormat,
                                      int width, int height, boolean hasAudio) {
        return new ProbeResult(true, videoCodec, containerFormat, width, height, hasAudio, null);
    }

    public static ProbeResult success(String videoCodec, String containerFormat, int width, int height) {
        return success(videoCodec, containerFormat, width, height, false);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getVideoCodec() {
        return videoCodec;
    }

    public String getContainerFormat() {
        return containerFormat;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean hasAudio() {
        return hasAudio;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isKnownIncompatible() {
        return KnownIncompatibleCodecs.isKnownIncompatible(videoCodec);
    }

    public int getLongestAxis() {
        return Math.max(width, height);
    }

    public boolean exceedsMaxLongestAxis() {
        return getLongestAxis() > MAX_LONGEST_AXIS;
    }

    public boolean needsVideoReencode() {
        return isKnownIncompatible() || exceedsMaxLongestAxis();
    }

    public boolean needsTranscode() {
        return needsVideoReencode() || hasAudio;
    }
}
