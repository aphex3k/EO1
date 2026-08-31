package com.aphex3k.eo1.ffmpeg;

public class TranscodeException extends Exception {

    public TranscodeException(String message) {
        super(message);
    }

    public TranscodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
