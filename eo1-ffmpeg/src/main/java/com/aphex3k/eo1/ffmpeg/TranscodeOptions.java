package com.aphex3k.eo1.ffmpeg;

public class TranscodeOptions {

    private final String videoFilter;
    private final int threadCount;

    public TranscodeOptions(String videoFilter, int threadCount) {
        this.videoFilter = videoFilter;
        this.threadCount = threadCount;
    }

    public static TranscodeOptions defaults() {
        return new TranscodeOptions(
                "scale='min(1920,iw)':'min(1920,ih)':force_original_aspect_ratio=decrease",
                2
        );
    }

    public String getVideoFilter() {
        return videoFilter;
    }

    public int getThreadCount() {
        return threadCount;
    }
}
