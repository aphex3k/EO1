package com.aphex3k.eo1.ffmpeg;

public class TranscodeOptions {

    private final String videoFilter;
    private final int threadCount;
    private final boolean videoCopy;

    public TranscodeOptions(String videoFilter, int threadCount, boolean videoCopy) {
        this.videoFilter = videoFilter;
        this.threadCount = threadCount;
        this.videoCopy = videoCopy;
    }

    public static TranscodeOptions defaults() {
        return new TranscodeOptions(
                "scale='min(1920,iw)':'min(1920,ih)':force_original_aspect_ratio=decrease",
                2,
                false
        );
    }

    public static TranscodeOptions forProbe(ProbeResult probe) {
        if (probe != null && probe.isSuccess() && probe.hasAudio() && !probe.needsVideoReencode()) {
            return new TranscodeOptions(null, 2, true);
        }
        return defaults();
    }

    public String getVideoFilter() {
        return videoFilter;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public boolean isVideoCopy() {
        return videoCopy;
    }
}
