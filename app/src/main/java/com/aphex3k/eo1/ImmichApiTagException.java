package com.aphex3k.eo1;

/**
 * The Immich tag write did not happen — the tag could not be found, created, or applied.
 * Carries the server's response so the operator can see why from {@code /logs}.
 */
public class ImmichApiTagException extends Exception {

    public ImmichApiTagException() {
        super();
    }

    public ImmichApiTagException(String message) {
        super(message);
    }

    public ImmichApiTagException(Exception cause) {
        super(cause);
    }
}
