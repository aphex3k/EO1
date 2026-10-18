package com.aphex3k.eo1;

public class AuthenticationUnavailableException extends Exception {
    private final int code;

    public AuthenticationUnavailableException(int code) {
        super(code == 404
                ? "the Immich server has no /api/auth/login endpoint to authenticate against"
                : "the Immich login returned no usable response (HTTP " + code + ")");
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }
}
