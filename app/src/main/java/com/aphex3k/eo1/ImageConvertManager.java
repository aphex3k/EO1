package com.aphex3k.eo1;

import com.aphex3k.eo1.ffmpeg.ImageConvertOptions;
import com.aphex3k.eo1.ffmpeg.ImageConverter;
import com.aphex3k.eo1.ffmpeg.ImageProbe;
import com.aphex3k.eo1.ffmpeg.ImageProbeResult;
import com.aphex3k.eo1.ffmpeg.KnownIncompatibleImageFormats;
import com.aphex3k.eo1.ffmpeg.TranscodeResult;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates still-image probe and convert with cache naming, timeouts, and disk guards.
 * Shares {@link FfmpegConvertLock} with {@link VideoTranscodeManager}.
 */
public class ImageConvertManager {

    public static final String CONVERT_SUFFIX = "_eo1.jpg";
    static final long CONVERT_TIMEOUT_MS = 10L * 60L * 1000L;
    static final int MIN_FREE_DISK_MULTIPLIER = 2;

    public interface DiskSpaceGuard {
        boolean ensureTranscodeSpace(File cacheDir, File source, Set<String> protectedPaths);
    }

    private final ImageProbe imageProbe;
    private final ImageConverter imageConverter;
    private final DiskSpaceGuard diskSpaceGuard;
    private final ReentrantLock convertLock;
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

    public ImageConvertManager() {
        this(new ImageProbe(), new ImageConverter(),
                defaultDiskSpaceGuard(new MediaCacheManager()), FfmpegConvertLock.get());
    }

    public ImageConvertManager(ImageProbe imageProbe, ImageConverter imageConverter) {
        this(imageProbe, imageConverter, defaultDiskSpaceGuard(new MediaCacheManager()),
                FfmpegConvertLock.get());
    }

    public ImageConvertManager(ImageProbe imageProbe, ImageConverter imageConverter,
                               DiskSpaceGuard diskSpaceGuard) {
        this(imageProbe, imageConverter, diskSpaceGuard, FfmpegConvertLock.get());
    }

    public ImageConvertManager(ImageProbe imageProbe, ImageConverter imageConverter,
                               DiskSpaceGuard diskSpaceGuard, ReentrantLock convertLock) {
        this.imageProbe = imageProbe;
        this.imageConverter = imageConverter;
        this.diskSpaceGuard = diskSpaceGuard != null
                ? diskSpaceGuard
                : defaultDiskSpaceGuard(new MediaCacheManager());
        this.convertLock = convertLock != null ? convertLock : FfmpegConvertLock.get();
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
        return imageConverter.isAvailable();
    }

    public static String convertCacheFileName(String assetId) {
        return assetId + CONVERT_SUFFIX;
    }

    public static boolean isConvertedFile(File file) {
        return file != null && file.getName().endsWith(CONVERT_SUFFIX);
    }

    public static File getConvertCacheFile(File cacheDir, String assetId) {
        return new File(cacheDir, convertCacheFileName(assetId));
    }

    /**
     * Locate a downloaded (non-converted) cache file for an asset.
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
            if (!file.isFile() || isConvertedFile(file) || VideoTranscodeManager.isTranscodedFile(file)) {
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
     * Proactively prepare a downloaded still for display.
     *
     * @return the original file if no convert is needed, the converted file on success,
     *         or {@code null} when a convert was required but failed (caller should Immich-preview)
     */
    public File prepareForDisplay(File cacheDir, String assetId, File downloaded) {
        if (downloaded == null || !downloaded.exists()) {
            ffmpegStep("image-prepare: input missing");
            return null;
        }
        if (MediaTypeHelper.isGifFile(downloaded)) {
            ffmpegStep("image-prepare: gif, skip convert");
            return downloaded;
        }

        boolean heicByName = KnownIncompatibleImageFormats.isHeicExtension(downloaded.getName());

        if (!imageConverter.isAvailable()) {
            if (heicByName) {
                ffmpegStep("image-prepare: ffmpeg unavailable" + unavailableSuffix()
                        + ", heic needs Immich preview");
                return null;
            }
            ffmpegStep("image-prepare: ffmpeg unavailable" + unavailableSuffix() + ", using original");
            return downloaded;
        }

        ffmpegStep("image-prepare: probing " + downloaded.getName());
        ImageProbeResult probe = imageProbe.probe(downloaded);
        if (!probe.isSuccess()) {
            if (heicByName) {
                ffmpegStep("image-prepare: probe failed for heic (" + probe.getErrorMessage() + ")");
                return null;
            }
            ffmpegStep("image-prepare: probe failed (" + probe.getErrorMessage() + "), using original");
            return downloaded;
        }
        ffmpegStep("image-prepare: probe " + probe.getCodecName() + " "
                + probe.getWidth() + "x" + probe.getHeight()
                + " format=" + probe.getFormatName()
                + " needsConvert=" + probe.needsConvert());
        if (!probe.needsConvert()) {
            ffmpegStep("image-prepare: no convert needed, using original");
            return downloaded;
        }

        ffmpegStep("image-prepare: converting to cache");
        File result = convertToCache(cacheDir, assetId, downloaded);
        if (result != null) {
            ffmpegStep("image-prepare: convert ok -> " + result.getName());
        } else {
            ffmpegStep("image-prepare: convert failed");
        }
        return result;
    }

    /**
     * Reactively convert after Glide failure.
     *
     * @return converted file on success, or {@code null} if convert was skipped or failed
     */
    public File attemptReactiveConvert(File cacheDir, String assetId, File source) {
        source = resolveReactiveSource(cacheDir, assetId, source);
        if (source == null || !source.exists()) {
            ffmpegStep("image-reactive: source missing");
            return null;
        }
        if (isConvertedFile(source) || MediaTypeHelper.isGifFile(source)) {
            ffmpegStep("image-reactive: skip (already converted or gif)");
            return null;
        }
        if (!imageConverter.isAvailable()) {
            ffmpegStep("image-reactive: ffmpeg unavailable" + unavailableSuffix());
            return null;
        }

        File cached = getConvertCacheFile(cacheDir, assetId);
        if (cached.exists() && cached.lastModified() >= source.lastModified() && cached.length() > 0) {
            ffmpegStep("image-reactive: using cached " + cached.getName());
            return cached;
        }

        ffmpegStep("image-reactive: converting " + source.getName());
        File result = convertToCache(cacheDir, assetId, source);
        if (result != null) {
            ffmpegStep("image-reactive: convert ok -> " + result.getName());
        } else {
            ffmpegStep("image-reactive: convert failed");
        }
        return result;
    }

    private File convertToCache(File cacheDir, String assetId, File source) {
        File output = getConvertCacheFile(cacheDir, assetId);

        if (output.exists() && output.lastModified() >= source.lastModified() && output.length() > 0) {
            ffmpegStep("image-convert: cache hit " + output.getName());
            return output;
        }

        if (!diskSpaceGuard.ensureTranscodeSpace(cacheDir, source, protectedCachePaths)) {
            ffmpegStep("image-convert: insufficient disk space");
            return null;
        }

        convertLock.lock();
        try {
            ffmpegStep("image-convert: running ffmpeg");
            TranscodeResult result = convertWithTimeout(source, output);
            if (result.isSuccess()) {
                return output;
            }
            ffmpegStep("image-convert: ffmpeg error: " + result.getErrorMessage());
            return null;
        } finally {
            convertLock.unlock();
        }
    }

    private TranscodeResult convertWithTimeout(File input, File output) {
        final TranscodeResult[] holder = new TranscodeResult[1];
        final Exception[] error = new Exception[1];

        Thread worker = new Thread(() -> {
            try {
                holder[0] = imageConverter.convert(input, output, ImageConvertOptions.defaults());
            } catch (Exception e) {
                error[0] = e;
            }
        }, "eo1-image-convert");

        worker.start();
        try {
            worker.join(CONVERT_TIMEOUT_MS);
            if (worker.isAlive()) {
                imageConverter.cancel();
                worker.interrupt();
                worker.join(5000);
                if (output.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    output.delete();
                }
                return TranscodeResult.failure("Image convert timed out");
            }
        } catch (InterruptedException e) {
            imageConverter.cancel();
            Thread.currentThread().interrupt();
            return TranscodeResult.failure("Image convert interrupted");
        }

        if (error[0] != null) {
            return TranscodeResult.failure(error[0].getMessage());
        }
        if (holder[0] == null) {
            return TranscodeResult.failure("Image convert did not complete");
        }
        return holder[0];
    }

    static File resolveReactiveSource(File cacheDir, String assetId, File source) {
        if (source != null && source.exists()) {
            return source;
        }
        return findCachedSourceFile(cacheDir, assetId);
    }

    private String unavailableSuffix() {
        String reason = imageProbe.getUnavailableReason();
        if (reason == null || reason.isEmpty()) {
            return "";
        }
        return " (" + reason + ")";
    }
}
