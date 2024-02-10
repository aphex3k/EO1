package com.aphex3k.eo1;

public class AuthenticationUnavailableException extends Exception {
    private final int code;
    public AuthenticationUnavailableException(int code) {
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }
}
