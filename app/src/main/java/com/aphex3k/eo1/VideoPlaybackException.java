package com.aphex3k.eo1;

public class VideoPlaybackException extends Exception {
    private final Exception innerException;
    public VideoPlaybackException(Exception innerException) {
        super();
        this.innerException = innerException;
    }

    public Exception getInnerException() {
        return innerException;
    }
}
