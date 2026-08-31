package com.aphex3k.eo1;

import com.aphex3k.eo1.ffmpeg.ProbeResult;
import com.aphex3k.eo1.ffmpeg.TranscodeOptions;
import com.aphex3k.eo1.ffmpeg.TranscodeResult;
import com.aphex3k.eo1.ffmpeg.VideoProbe;
import com.aphex3k.eo1.ffmpeg.VideoTranscoder;

import java.io.File;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates video probe and transcode with cache naming, timeouts, and disk guards.
 */
public class VideoTranscodeManager {

    public static final String TRANSCODE_SUFFIX = "_eo1.mp4";
    static final long TRANSCODE_TIMEOUT_MS = 10L * 60L * 1000L;
    static final int MIN_FREE_DISK_MULTIPLIER = 2;

    private final VideoProbe videoProbe;
    private final VideoTranscoder videoTranscoder;
    private final ReentrantLock transcodeLock = new ReentrantLock();

    public VideoTranscodeManager() {
        this(new VideoProbe(), new VideoTranscoder());
    }

    public VideoTranscodeManager(VideoProbe videoProbe, VideoTranscoder videoTranscoder) {
        this.videoProbe = videoProbe;
        this.videoTranscoder = videoTranscoder;
    }

    public boolean isAvailable() {
        return videoTranscoder.isAvailable();
    }

    public static String transcodeCacheFileName(String assetId) {
        return assetId + TRANSCODE_SUFFIX;
    }

    public static boolean isTranscodedFile(File file) {
        return file != null && file.getName().endsWith(TRANSCODE_SUFFIX);
    }

    public static File getTranscodeCacheFile(File cacheDir, String assetId) {
        return new File(cacheDir, transcodeCacheFileName(assetId));
    }

    /**
     * Proactively prepare a downloaded video for playback.
     *
     * @return the original file if no transcode is needed, the transcoded file on success,
     *         or {@code null} when a transcode was required but failed
     */
    public File prepareForPlayback(File cacheDir, String assetId, File downloaded) {
        if (downloaded == null || !downloaded.exists()) {
            return null;
        }
        if (!videoTranscoder.isAvailable()) {
            return downloaded;
        }

        ProbeResult probe = videoProbe.probe(downloaded);
        if (!probe.isSuccess() || !probe.needsTranscode()) {
            return downloaded;
        }

        return transcodeToCache(cacheDir, assetId, downloaded);
    }

    /**
     * Reactively transcode after MediaPlayer failure.
     *
     * @return transcoded file on success, or {@code null} if transcode was skipped or failed
     */
    public File attemptReactiveTranscode(File cacheDir, String assetId, File source) {
        if (source == null || !source.exists() || isTranscodedFile(source)) {
            return null;
        }
        if (!videoTranscoder.isAvailable()) {
            return null;
        }

        File cached = getTranscodeCacheFile(cacheDir, assetId);
        if (cached.exists() && cached.lastModified() >= source.lastModified() && cached.length() > 0) {
            return cached;
        }

        return transcodeToCache(cacheDir, assetId, source);
    }

    private File transcodeToCache(File cacheDir, String assetId, File source) {
        File output = getTranscodeCacheFile(cacheDir, assetId);

        if (output.exists() && output.lastModified() >= source.lastModified() && output.length() > 0) {
            return output;
        }

        if (!hasDiskSpace(cacheDir, source)) {
            return null;
        }

        transcodeLock.lock();
        try {
            TranscodeResult result = transcodeWithTimeout(source, output);
            if (result.isSuccess()) {
                return output;
            }
            return null;
        } finally {
            transcodeLock.unlock();
        }
    }

    static boolean hasDiskSpace(File cacheDir, File source) {
        long required = source.length() * MIN_FREE_DISK_MULTIPLIER;
        long usable = cacheDir.getUsableSpace();
        return usable >= required;
    }

    private TranscodeResult transcodeWithTimeout(File input, File output) {
        final TranscodeResult[] holder = new TranscodeResult[1];
        final Exception[] error = new Exception[1];

        Thread worker = new Thread(() -> {
            try {
                holder[0] = videoTranscoder.transcode(input, output, TranscodeOptions.defaults());
            } catch (Exception e) {
                error[0] = e;
            }
        }, "eo1-video-transcode");

        worker.start();
        try {
            worker.join(TRANSCODE_TIMEOUT_MS);
            if (worker.isAlive()) {
                videoTranscoder.cancel();
                worker.interrupt();
                worker.join(5000);
                if (output.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    output.delete();
                }
                return TranscodeResult.failure("Transcode timed out");
            }
        } catch (InterruptedException e) {
            videoTranscoder.cancel();
            Thread.currentThread().interrupt();
            return TranscodeResult.failure("Transcode interrupted");
        }

        if (error[0] != null) {
            return TranscodeResult.failure(error[0].getMessage());
        }
        if (holder[0] == null) {
            return TranscodeResult.failure("Transcode did not complete");
        }
        return holder[0];
    }
}
