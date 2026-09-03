package com.aphex3k.eo1.ffmpeg;

public class ImageConvertOptions {

    private final String videoFilter;
    private final int threadCount;
    private final int jpegQuality;

    public ImageConvertOptions(String videoFilter, int threadCount, int jpegQuality) {
        this.videoFilter = videoFilter;
        this.threadCount = threadCount;
        this.jpegQuality = jpegQuality;
    }

    public static ImageConvertOptions defaults() {
        return new ImageConvertOptions(
                "scale='min(1920,iw)':'min(1920,ih)':force_original_aspect_ratio=decrease",
                2,
                2
        );
    }

    public String getVideoFilter() {
        return videoFilter;
    }

    public int getThreadCount() {
        return threadCount;
    }

    /**
     * FFmpeg {@code -q:v} for MJPEG/JPEG (2 = high quality).
     */
    public int getJpegQuality() {
        return jpegQuality;
    }
}
