package com.aphex3k.eo1;

import androidx.annotation.Nullable;

import com.aphex3k.media.MediaType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Device-format compatibility gate for EO1/EO2.
 *
 * <p>The Geniatech SoC runs Android 4.4.2 (API 19) and cannot decode HEIF stills (including
 * HEVC-encoded ones) or AV1; HEVC (h265) video support is uncertain. This class lets the
 * rotation pipeline skip such assets instead of wasting a download or a decoder attempt on them.
 *
 * <p>Three tiers:
 * <ul>
 *   <li>{@link #incompatibleReason(MediaType, String, String)} — an extension check that can
 *       run <em>before</em> a remote download, because the file name comes from backend
 *       metadata. Container extensions ({@code .mp4}, {@code .mov}, ...) that can also hold
 *       H.264 are deliberately not flagged.</li>
 *   <li>{@link #incompatibleVideoResolution(MediaType, int, int)} — a coded-dimension check
 *       that can run <em>before</em> a remote download, because the dimensions come from
 *       backend metadata. Videos with a side above {@link #MAX_VIDEO_SIDE_PX} (the panel is
 *       1080p) garble on the SoC's decoder; the pipeline routes them to the backend's
 *       playback fallback (e.g. Immich's transcoded {@code /video/playback} stream) instead
 *       of downloading the original.</li>
 *   <li>{@link #incompatibleReasonForFile(MediaType, File)} — a content check for a file that
 *       already exists locally (uploaded, or already downloaded). It detects HEIF/AVIF
 *       containers from the leading {@code ftyp} box even when the extension lies
 *       (e.g. HEIF content named {@code .jpg}).</li>
 * </ul>
 *
 * <p>The content check is conservative: only files positively identified as a HEIF/AVIF
 * container are rejected; everything else (including unknown formats) passes and is left to
 * the display-error path, which already handles undecodable media.
 *
 * <p>Both methods return a human-readable reason, or {@code null} when the asset passes.
 * No Android framework classes are used, so this runs on the JVM.
 */
public final class MediaCompatibility {

    /** Byte window inspected by the content check (covers a full {@code ftyp} box in practice). */
    private static final int HEAD_BYTES = 64;

    /** Image extensions that denote a container/codec this device cannot decode (lowercase, dotted). */
    private static final Set<String> INCOMPATIBLE_IMAGE_EXTENSIONS =
            Collections.unmodifiableSet(new HashSet<String>(
                    Arrays.asList(".heic", ".heif", ".avif")));

    /**
     * Video extensions that denote the codec itself, not just the container, so the asset is
     * known to be undecodable. {@code .mp4}/{@code .mov}/{@code .m4v} can hold H.264 and are
     * deliberately not flagged.
     */
    private static final Set<String> INCOMPATIBLE_VIDEO_EXTENSIONS =
            Collections.unmodifiableSet(new HashSet<String>(
                    Arrays.asList(".h265", ".hevc", ".av1")));

    /**
     * ISO 14496-12 / ISO 19378-12 {@code ftyp} brands that mark HEIF (still) containers,
     * including Apple's "mif"/"msf" brands for HEVC stills and the AV1-still brands. Any
     * HEIF-family container is undecodable on this device regardless of its item codec.
     */
    private static final Set<String> INCOMPATIBLE_FTYPE_BRANDS =
            Collections.unmodifiableSet(new HashSet<String>(
                    Arrays.asList("heic", "heix", "hevc", "hevx", "hevm", "hevs", "heif",
                            "mif1", "msf1", "mif2", "msf2", "miaf", "misf", "avif", "avis")));

    /**
     * Longest coded side (px) this device's video decoder reliably handles; the panel is
     * 1920x1080, so anything above adds decode load without visible detail.
     */
    public static final int MAX_VIDEO_SIDE_PX = 1920;

    private MediaCompatibility() {
    }

    /**
     * Extension-based check that can run before a remote download.
     *
     * @param type media type of the asset
     * @param originalFileName original file name as reported by the backend, or null
     * @param originalPath original path as reported by the backend, or null (used when the
     *                     name alone yields no extension)
     * @return a human-readable reason when the asset is known to be undecodable on this
     *         device, or {@code null} when it looks decodable
     */
    @Nullable
    public static String incompatibleReason(MediaType type, @Nullable String originalFileName,
                                            @Nullable String originalPath) {
        if (type == null) {
            return null;
        }
        String extension = MediaManager.extensionFromFileName(originalFileName);
        if (extension == null) {
            extension = MediaManager.extensionFromFileName(originalPath);
        }
        if (extension == null) {
            return null;
        }
        if (type == MediaType.IMAGE && INCOMPATIBLE_IMAGE_EXTENSIONS.contains(extension)) {
            return "unsupported image format " + extension
                    + " (HEIF/AV1 stills cannot be decoded on this device)";
        }
        if (type == MediaType.VIDEO && INCOMPATIBLE_VIDEO_EXTENSIONS.contains(extension)) {
            return "unsupported video codec " + extension + " cannot be decoded on this device";
        }
        return null;
    }

    /**
     * Coded-resolution check that can run before a remote download. The panel is 1920x1080
     * and the SoC decoder garbles (rather than fails cleanly) on video with a coded side
     * above {@link #MAX_VIDEO_SIDE_PX}, so such originals must not be downloaded: the
     * pipeline routes the asset to the backend's playback fallback instead.
     *
     * <p>Conservative like the other tiers: when either dimension is unknown ({@code <= 0})
     * the asset passes and is left to the display-error path.
     *
     * @param type media type of the asset
     * @param width coded content width in pixels, or 0 when the backend does not report one
     * @param height coded content height in pixels, or 0 when the backend does not report one
     * @return a human-readable reason when the original exceeds the device's decode limit,
     *         or {@code null} when it fits (or cannot be judged from the given dimensions)
     */
    @Nullable
    public static String incompatibleVideoResolution(@Nullable MediaType type, int width, int height) {
        if (type != MediaType.VIDEO || width <= 0 || height <= 0) {
            return null;
        }
        int longSide = Math.max(width, height);
        if (longSide <= MAX_VIDEO_SIDE_PX) {
            return null;
        }
        return "video resolution " + width + "x" + height
                + " exceeds this device's decode limit (" + MAX_VIDEO_SIDE_PX + "px side)";
    }

    /**
     * Content-based check for a file that already exists locally. Detects HEIF/AVIF
     * containers from their {@code ftyp} box even when the extension says something
     * else (e.g. HEIF content named {@code .jpg}).
     *
     * <p>Video files are not byte-checked: the codec of an MP4/MOV is stored deep in the
     * {@code stsd} box, and the display-error path (player error → fallback) already covers
     * undecodable video.
     *
     * @param type media type of the asset
     * @param file the on-disk file to inspect, or null
     * @return a human-readable reason when the bytes are a known-undecodable container,
     *         or {@code null} when the file looks decodable or cannot be identified
     */
    @Nullable
    public static String incompatibleReasonForFile(MediaType type, @Nullable File file) {
        if (type != MediaType.IMAGE || file == null || !file.isFile() || file.length() == 0) {
            return null;
        }
        byte[] head = new byte[HEAD_BYTES];
        int total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (total < HEAD_BYTES) {
                int read = input.read(head, total, HEAD_BYTES - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
        } catch (IOException e) {
            return null;
        }
        if (total < 16 || !"ftyp".equals(fourCc(head, 4))) {
            return null;
        }
        if (INCOMPATIBLE_FTYPE_BRANDS.contains(fourCc(head, 8))) {
            return "unsupported image container " + fourCc(head, 8)
                    + " detected in file contents (HEIF/AV1)";
        }
        int brandsEnd = Math.min(total, ftypBoxSize(head));
        for (int offset = 12; offset + 4 <= brandsEnd; offset += 4) {
            String brand = fourCc(head, offset);
            if (INCOMPATIBLE_FTYPE_BRANDS.contains(brand)) {
                return "unsupported image container " + brand
                        + " detected in file contents (HEIF/AV1)";
            }
        }
        return null;
    }

    /** {@code ftyp} box size (big-endian), clamped to the inspected window. */
    private static int ftypBoxSize(byte[] head) {
        int size = ((head[0] & 0xFF) << 24)
                | ((head[1] & 0xFF) << 16)
                | ((head[2] & 0xFF) << 8)
                | (head[3] & 0xFF);
        // 0 means "rest of file"; an implausibly small value means a malformed box.
        if (size <= 0 || size < 16) {
            return HEAD_BYTES;
        }
        return Math.min(size, HEAD_BYTES);
    }

    private static String fourCc(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, Charset.forName("US-ASCII"));
    }
}
