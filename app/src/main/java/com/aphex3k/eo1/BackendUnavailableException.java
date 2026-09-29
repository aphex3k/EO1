package com.aphex3k.eo1;

public class BackendUnavailableException extends Exception {
    public BackendUnavailableException(String s) {
        super(s);
    }
    public BackendUnavailableException(Exception e) {
        super(e);
    }
}
