package com.aphex3k.eo1;

public class MediaDownloadFailedException extends Exception {
    public MediaDownloadFailedException(String s) {
        super(s);
    }
    public MediaDownloadFailedException(Exception e) {
        super(e);
    }
}
