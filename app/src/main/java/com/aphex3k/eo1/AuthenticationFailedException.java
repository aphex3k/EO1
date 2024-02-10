package com.aphex3k.eo1;

public class AuthenticationFailedException extends Exception {
    private final int code;

    public AuthenticationFailedException(int code) {
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }
}
