package com.aphex3k.eo1;

public class InvalidCredentialsException extends Exception {

    public InvalidCredentialsException() {
        super("the Immich account configured on this frame is not a usable user");
    }
}
