package com.aphex3k.eo1;

import com.aphex3k.eo1.ffmpeg.ProbeResult;
import com.aphex3k.eo1.ffmpeg.TranscodeOptions;
import com.aphex3k.eo1.ffmpeg.TranscodeResult;
import com.aphex3k.eo1.ffmpeg.VideoProbe;
import com.aphex3k.eo1.ffmpeg.VideoTranscoder;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates video probe and transcode with cache naming, timeouts, and disk guards.
 */
public class VideoTranscodeManager {

    public static final String TRANSCODE_SUFFIX = "_eo1.mp4";
    static final long TRANSCODE_TIMEOUT_MS = 10L * 60L * 1000L;
    static final int MIN_FREE_DISK_MULTIPLIER = 2;

    public interface DiskSpaceGuard {
        boolean ensureTranscodeSpace(File cacheDir, File source, Set<String> protectedPaths);
    }

    private final VideoProbe videoProbe;
    private final VideoTranscoder videoTranscoder;
    private final DiskSpaceGuard diskSpaceGuard;
    private final ReentrantLock transcodeLock = new ReentrantLock();
    private FfmpegStepListener stepListener;
    private Set<String> protectedCachePaths = Collections.emptySet();

    public interface FfmpegStepListener {
        void onStep(String step);
    }

    public void setFfmpegStepListener(FfmpegStepListener listener) {
        this.stepListener = listener;
    }

    public void setProtectedCachePaths(Set<String> protectedCachePaths) {
        this.protectedCachePaths = protectedCachePaths != null
                ? protectedCachePaths
                : Collections.<String>emptySet();
    }

    private void ffmpegStep(String step) {
        if (stepListener != null) {
            stepListener.onStep(step);
        }
    }

    public VideoTranscodeManager() {
        this(new VideoProbe(), new VideoTranscoder(), defaultDiskSpaceGuard(new MediaCacheManager()));
    }

    public VideoTranscodeManager(VideoProbe videoProbe, VideoTranscoder videoTranscoder) {
        this(videoProbe, videoTranscoder, defaultDiskSpaceGuard(new MediaCacheManager()));
    }

    public VideoTranscodeManager(VideoProbe videoProbe, VideoTranscoder videoTranscoder,
                                 DiskSpaceGuard diskSpaceGuard) {
        this.videoProbe = videoProbe;
        this.videoTranscoder = videoTranscoder;
        this.diskSpaceGuard = diskSpaceGuard != null
                ? diskSpaceGuard
                : defaultDiskSpaceGuard(new MediaCacheManager());
    }

    static DiskSpaceGuard defaultDiskSpaceGuard(final MediaCacheManager cacheManager) {
        return new DiskSpaceGuard() {
            @Override
            public boolean ensureTranscodeSpace(File cacheDir, File source, Set<String> protectedPaths) {
                return cacheManager.ensureTranscodeSpace(cacheDir, source, protectedPaths);
            }
        };
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
     * Locate a downloaded (non-transcoded) cache file for an asset when the caller's File reference
     * is stale or was deleted by cache cleanup.
     */
    public static File findCachedSourceFile(File cacheDir, String assetId) {
        if (cacheDir == null || assetId == null || assetId.isEmpty()) {
            return null;
        }
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (!file.isFile() || isTranscodedFile(file)) {
                continue;
            }
            String name = file.getName();
            if (name.startsWith(assetId + ".") || name.equals(assetId)) {
                return file;
            }
        }
        return null;
    }

    /**
     * Proactively prepare a downloaded video for playback.
     *
     * @return the original file if no transcode is needed, the transcoded file on success,
     *         or {@code null} when a transcode was required but failed
     */
    public File prepareForPlayback(File cacheDir, String assetId, File downloaded) {
        if (downloaded == null || !downloaded.exists()) {
            ffmpegStep("prepare: input missing");
            return null;
        }
        if (!videoTranscoder.isAvailable()) {
            ffmpegStep("prepare: ffmpeg unavailable" + unavailableSuffix(videoProbe) + ", using original");
            return downloaded;
        }

        ffmpegStep("prepare: probing " + downloaded.getName());
        ProbeResult probe = videoProbe.probe(downloaded);
        if (!probe.isSuccess()) {
            ffmpegStep("prepare: probe failed (" + probe.getErrorMessage() + "), using original");
            return downloaded;
        }
        ffmpegStep("prepare: probe " + probe.getVideoCodec() + " "
                + probe.getWidth() + "x" + probe.getHeight()
                + " audio=" + probe.hasAudio()
                + " needsTranscode=" + probe.needsTranscode());
        if (!probe.needsTranscode()) {
            ffmpegStep("prepare: no transcode needed, using original");
            return downloaded;
        }

        ffmpegStep("prepare: transcoding to cache");
        File result = transcodeToCache(cacheDir, assetId, downloaded, probe);
        if (result != null) {
            ffmpegStep("prepare: transcode ok -> " + result.getName());
        } else {
            ffmpegStep("prepare: transcode failed");
        }
        return result;
    }

    /**
     * Reactively transcode after MediaPlayer failure.
     *
     * @return transcoded file on success, or {@code null} if transcode was skipped or failed
     */
    public File attemptReactiveTranscode(File cacheDir, String assetId, File source) {
        source = resolveReactiveSource(cacheDir, assetId, source);
        if (source == null || !source.exists()) {
            ffmpegStep("reactive: source missing");
            return null;
        }
        if (isTranscodedFile(source)) {
            ffmpegStep("reactive: already transcoded file");
            return null;
        }
        if (!videoTranscoder.isAvailable()) {
            ffmpegStep("reactive: ffmpeg unavailable" + unavailableSuffix(videoProbe));
            return null;
        }

        File cached = getTranscodeCacheFile(cacheDir, assetId);
        if (cached.exists() && cached.lastModified() >= source.lastModified() && cached.length() > 0) {
            ffmpegStep("reactive: using cached " + cached.getName());
            return cached;
        }

        ffmpegStep("reactive: transcoding " + source.getName());
        ProbeResult probe = videoProbe.probe(source);
        if (!probe.isSuccess()) {
            ffmpegStep("reactive: probe failed (" + probe.getErrorMessage() + ")");
            return null;
        }
        File result = transcodeToCache(cacheDir, assetId, source, probe);
        if (result != null) {
            ffmpegStep("reactive: transcode ok -> " + result.getName());
        } else {
            ffmpegStep("reactive: transcode failed");
        }
        return result;
    }

    private File transcodeToCache(File cacheDir, String assetId, File source, ProbeResult probe) {
        File output = getTranscodeCacheFile(cacheDir, assetId);

        if (output.exists() && output.lastModified() >= source.lastModified() && output.length() > 0) {
            ffmpegStep("transcode: cache hit " + output.getName());
            return output;
        }

        if (!diskSpaceGuard.ensureTranscodeSpace(cacheDir, source, protectedCachePaths)) {
            ffmpegStep("transcode: insufficient disk space");
            return null;
        }

        transcodeLock.lock();
        try {
            if (probe != null && probe.isSuccess() && probe.hasAudio() && !probe.needsVideoReencode()) {
                ffmpegStep("transcode: stripping audio (video copy)");
            } else {
                ffmpegStep("transcode: running ffmpeg");
            }
            TranscodeResult result = transcodeWithTimeout(source, output, probe);
            if (result.isSuccess()) {
                return output;
            }
            ffmpegStep("transcode: ffmpeg error: " + result.getErrorMessage());
            return null;
        } finally {
            transcodeLock.unlock();
        }
    }

    /**
     * Raw check without eviction: free space must cover {@code 2 × source + safety margin}.
     */
    static boolean hasDiskSpace(File cacheDir, File source) {
        long required = source.length() * MIN_FREE_DISK_MULTIPLIER
                + MediaCacheManager.SAFETY_MARGIN_BYTES;
        long usable = cacheDir.getUsableSpace();
        return usable >= required;
    }

    private TranscodeResult transcodeWithTimeout(File input, File output, ProbeResult probe) {
        final TranscodeResult[] holder = new TranscodeResult[1];
        final Exception[] error = new Exception[1];

        Thread worker = new Thread(() -> {
            try {
                holder[0] = videoTranscoder.transcode(
                        input, output, TranscodeOptions.forProbe(probe));
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

    static File resolveReactiveSource(File cacheDir, String assetId, File source) {
        if (source != null && source.exists()) {
            return source;
        }
        return findCachedSourceFile(cacheDir, assetId);
    }

    private static String unavailableSuffix(VideoProbe videoProbe) {
        String reason = videoProbe.getUnavailableReason();
        if (reason == null || reason.isEmpty()) {
            return "";
        }
        return " (" + reason + ")";
    }
}
