package com.aphex3k.eo1;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Single shared lock for all FFmpeg work (video transcode + image convert) on EO1's RAM/CPU budget.
 */
public final class FfmpegConvertLock {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private FfmpegConvertLock() {
    }

    public static ReentrantLock get() {
        return LOCK;
    }
}
