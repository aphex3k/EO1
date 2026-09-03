package com.aphex3k.eo1.ffmpeg;

public class TranscodeResult {

    private final boolean success;
    private final String errorMessage;

    public TranscodeResult(boolean success, String errorMessage) {
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static TranscodeResult success() {
        return new TranscodeResult(true, null);
    }

    public static TranscodeResult failure(String message) {
        return new TranscodeResult(false, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
