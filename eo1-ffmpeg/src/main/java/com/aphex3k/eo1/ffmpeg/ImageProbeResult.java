package com.aphex3k.eo1.ffmpeg;

/**
 * Probe result for still images. Shares {@link ProbeResult#MAX_LONGEST_AXIS} policy with video.
 */
public class ImageProbeResult {

    private final boolean success;
    private final String codecName;
    private final String formatName;
    private final int width;
    private final int height;
    private final String errorMessage;

    public ImageProbeResult(boolean success, String codecName, String formatName,
                            int width, int height, String errorMessage) {
        this.success = success;
        this.codecName = codecName;
        this.formatName = formatName;
        this.width = width;
        this.height = height;
        this.errorMessage = errorMessage;
    }

    public static ImageProbeResult failure(String message) {
        return new ImageProbeResult(false, null, null, 0, 0, message);
    }

    public static ImageProbeResult success(String codecName, String formatName,
                                           int width, int height) {
        return new ImageProbeResult(true, codecName, formatName, width, height, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCodecName() {
        return codecName;
    }

    public String getFormatName() {
        return formatName;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isKnownIncompatible() {
        return KnownIncompatibleImageFormats.isKnownIncompatible(formatName)
                || KnownIncompatibleImageFormats.isKnownIncompatible(codecName);
    }

    public int getLongestAxis() {
        return Math.max(width, height);
    }

    public boolean exceedsMaxLongestAxis() {
        return getLongestAxis() > ProbeResult.MAX_LONGEST_AXIS;
    }

    public boolean needsConvert() {
        return isKnownIncompatible() || exceedsMaxLongestAxis();
    }
}
