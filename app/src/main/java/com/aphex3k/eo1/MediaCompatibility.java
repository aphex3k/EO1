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
 * HEVC-encoded ones) or AV1; HEVC (h265) video support is uncertain. Nothing here takes an asset
 * out of the rotation: at most it steers one to the backend's fallback source, or reports a
 * verdict on bytes that are already on disk. Whether an original is actually undisplayable is
 * settled by the decoder, not by a file name.
 *
 * <p>Three tiers:
 * <ul>
 *   <li>{@link #incompatibleReason(MediaType, String, String)} — an extension check, used only
 *       where there is no fallback to fall back to: the local upload backend, which files an
 *       operator-facing report beside the file instead. Container extensions ({@code .mp4},
 *       {@code .mov}, ...) that can also hold H.264 are deliberately not flagged.</li>
 *   <li>{@link #incompatibleVideoResolution(MediaType, int, int)} — a coded-dimension check
 *       that can run <em>before</em> a remote download, because the dimensions come from
 *       backend metadata. Videos with a side above {@link #MAX_VIDEO_SIDE_PX} (the panel is
 *       1080p) garble on the SoC's decoder <em>without raising an error</em>, so waiting for the
 *       original to fail would show garbage and never reach a fallback: the pipeline routes them
 *       to the backend's playback fallback (e.g. Immich's transcoded {@code /video/playback}
 *       stream) instead of downloading the original.</li>
 *   <li>{@link #incompatibleReasonForFile(MediaType, File)} — a content check for a file that
 *       already exists locally (uploaded, or already downloaded). For stills it detects HEIF/AVIF
 *       containers from the leading {@code ftyp} box even when the extension lies (e.g. HEIF
 *       content named {@code .jpg}); for video it reads the sample description through
 *       {@link VideoHeaderProbe} and so catches a codec the container extension could not declare
 *       (HEVC inside a perfectly-named {@code .MOV}).</li>
 * </ul>
 *
 * <p>Only the first two tiers can run before a download. No Immich asset response reports a
 * codec — {@code videoCodec} exists in the 3.3.3 OpenAPI spec only inside the admin transcoding
 * configuration — so a {@code .mov}'s codec is unknowable until its bytes are on disk, which is
 * what the third tier is for.
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
     * deliberately not flagged: those are judged by their sample description instead, once the
     * file is on disk — see {@link #incompatibleReasonForFile(MediaType, File)}.
     */
    private static final Set<String> INCOMPATIBLE_VIDEO_EXTENSIONS =
            Collections.unmodifiableSet(new HashSet<String>(
                    Arrays.asList(".h265", ".hevc", ".av1")));

    /**
     * Sample-description fourccs ({@code stsd} entries, lower-cased by {@link VideoHeaderProbe})
     * naming a codec this device has no decoder for. HEVC and AV1 are absent from Android 4.4
     * entirely (both arrived in later platform releases), and VVC is absent from any Android
     * build; the Dolby Vision fourcc is listed because a profile-5 {@code dvh1} track is HEVC
     * with no H.264 fallback. An 8-bit H.264/AVC, MPEG-4 Part 2 or H.263 track is decodable
     * here and is not in this set, so a container that mixes the two is never penalised for the
     * codec it does not use.
     */
    private static final Set<String> INCOMPATIBLE_VIDEO_CODECS =
            Collections.unmodifiableSet(new HashSet<String>(
                    Arrays.asList("hvc1", "hev1", "av01", "vvc1", "dvh1")));

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

    /**
     * Sustained macroblock throughput this device's video decoder keeps up with. Past it a clip
     * still decodes — it just decodes <em>slowly</em>, which is not a condition any player reports.
     *
     * <p>Measured on an EO2 with one file at two frame rates: 1920x1080 at 29.97 fps is 244,555
     * macroblocks/s and plays at 1.00x; the same size at 59.97 fps is 489,355 and runs at 0.13x.
     * This budget sits 23% above the first and 39% below the second, so 1080p30 and 720p60 both
     * pass and only genuinely over-specified video is rerouted.
     *
     * <p>It is a throughput limit, not a resolution one, which is why it cannot be expressed as a
     * side length or a frame rate alone: 720p60 fits where 1080p60 does not, and 1080p30 fits where
     * 1080p60 does not. Only the product of the two says the right thing.
     */
    public static final long MAX_VIDEO_MACROBLOCKS_PER_SECOND = 300_000L;

    /**
     * Sustained video-track bitrate this device's pipeline keeps up with, in SI bits/s (so 15 Mbit/s is
     * 15,000,000 — a binary 15*1048576 would let the motivating file pass). Past it a clip still decodes
     * correctly, it just runs slow, exactly like the macroblock budget above — which is why this too
     * routes to the fallback rather than tagging anything.
     *
     * <p>Measured on an EO2 with one clip at two bitrates: 14.4 Mbit/s plays buttery, 15.6 Mbit/s
     * stutters. The failing file (IMG_6518.MOV, 15,586,890 bps) is <em>within</em>
     * {@link #MAX_VIDEO_MACROBLOCKS_PER_SECOND} at ~244,571 macroblocks/s, so this is an orthogonal
     * limit, not a restatement of the throughput one: two clips at the same macroblock rate can ask very
     * different bandwidth of the decoder.
     *
     * <p>The quantity is the first video track's <em>average payload</em> bits/s (the {@code stsz} byte
     * total over its {@code mdhd} duration, as {@link VideoHeaderProbe.StreamStats#bitRateBps} computes
     * it) — not the container figure MediaInfo prints, which also counts audio and mux overhead and runs
     * higher. So a file advertised at 15.0-15.4 Mbit/s can pass this gate. It is deliberately the
     * <em>average</em>: no windowed peak bitrate is measured, and the only "peak" derivable from the
     * probe (the largest single frame, {@code peakFrameBytes}) is one I-frame's worth of bytes, not a
     * rate — turning it into one overstates a real clip by ~5-6x and would reroute healthy video.
     *
     * <p>Caveat, so this is not over-claimed: like the throughput gate, it only helps where the backend
     * actually has a lower-bitrate encode to route to. A server that answers {@code /video/playback} with
     * the original bytes has nothing to degrade to, and the routing path records that and keeps playing
     * the same slow clip.
     */
    public static final long MAX_VIDEO_BIT_RATE_BPS = 15_000_000L;

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
        if (type == MediaType.WEB) {
            // A web page is rendered, not decoded, so codec gates never apply. Guarded
            // explicitly because a page address ends in a file-looking suffix often enough
            // (".../feed.php", ".../photo.jpg") for extension sniffing to say something silly.
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
     * Sustained macroblock throughput a clip asks of the decoder — the number
     * {@link #MAX_VIDEO_MACROBLOCKS_PER_SECOND} is compared against, exposed so reporting and the
     * gate can never disagree about how it is derived.
     *
     * @param codedWidth coded content width in pixels, or {@code <= 0} when unknown
     * @param codedHeight coded content height in pixels, or {@code <= 0} when unknown
     * @param fpsMilli frame rate x1000, or {@code <= 0} when unknown
     * @return macroblocks per second, or -1 when any input is unknown
     */
    public static long videoMacroblocksPerSecond(int codedWidth, int codedHeight, int fpsMilli) {
        if (codedWidth <= 0 || codedHeight <= 0 || fpsMilli <= 0) {
            return -1;
        }
        long macroblocks = (long) ((codedWidth + 15) / 16) * ((codedHeight + 15) / 16);
        return macroblocks * fpsMilli / 1000L;
    }

    /**
     * Decode-throughput check for a video whose coded size and frame rate are known.
     *
     * <p>Like {@link #incompatibleVideoResolution} this only ever asks for a different source, and
     * for no other tier is that distinction more important: the asset it fires on <em>does</em>
     * play, so nothing downstream will ever conclude it is broken, and a check that reported
     * incompatibility here would tag thousands of perfectly usable clips. It must never reach
     * {@code tagAssetAsIncompatible} or {@code knownIncompatibleKeys}.
     *
     * <p>Conservative like the other tiers: an unknown dimension or an unknown frame rate yields
     * {@code null}, because rerouting healthy video to a lower-quality source on the strength of
     * missing data is worse than the slow playback being avoided.
     *
     * @param type media type of the asset
     * @param codedWidth coded content width in pixels, or {@code <= 0} when unknown
     * @param codedHeight coded content height in pixels, or {@code <= 0} when unknown
     * @param fpsMilli frame rate x1000 (59970 for 59.97 fps), or {@code <= 0} when unknown
     * @return a human-readable reason when the clip demands more throughput than this device has,
     *         or {@code null} when it fits or cannot be judged
     */
    @Nullable
    public static String incompatibleVideoThroughput(@Nullable MediaType type, int codedWidth,
                                                     int codedHeight, int fpsMilli) {
        if (type != MediaType.VIDEO) {
            return null;
        }
        long perSecond = videoMacroblocksPerSecond(codedWidth, codedHeight, fpsMilli);
        if (perSecond < 0 || perSecond <= MAX_VIDEO_MACROBLOCKS_PER_SECOND) {
            return null;
        }
        return "video " + codedWidth + "x" + codedHeight + " at " + (fpsMilli / 1000.0)
                + " fps needs " + perSecond + " macroblocks/s, above this device's "
                + MAX_VIDEO_MACROBLOCKS_PER_SECOND + " decode throughput limit";
    }

    /**
     * Sustained-bitrate check for a video whose average bits/s are known (read from the file by
     * {@link VideoHeaderProbe}, never from catalog metadata — no backend reports a bitrate up front).
     *
     * <p>Same routing-only contract as {@link #incompatibleVideoThroughput}, for the same reason: the
     * clip it fires on <em>does</em> play, merely too slowly, so nothing downstream will conclude it is
     * broken and this must never reach {@code tagAssetAsIncompatible} or {@code knownIncompatibleKeys}.
     *
     * <p>Conservative like the other throughput-style checks: an unknown bitrate ({@code <= 0}, e.g. the
     * {@link VideoHeaderProbe.StreamStats#UNKNOWN} sentinel or a truncated {@code stsz}) yields
     * {@code null}, because rerouting healthy video on missing data is worse than the slow playback being
     * avoided. This means the bitrate gate fires on strictly fewer files than the macroblock gate — the
     * correct direction, but it is coverage, not certainty.
     *
     * @param type media type of the asset
     * @param bitRateBps sustained video-track average bits/s, or {@code <= 0} when unknown
     * @return a human-readable reason when the clip asks more bandwidth than this device has, or
     *         {@code null} when it fits or cannot be judged
     */
    @Nullable
    public static String incompatibleVideoBitrate(@Nullable MediaType type, long bitRateBps) {
        if (type != MediaType.VIDEO || bitRateBps <= 0) {
            return null;
        }
        if (bitRateBps <= MAX_VIDEO_BIT_RATE_BPS) {
            return null;
        }
        return "video bitrate " + bitRateBps + " bps is above this device's "
                + MAX_VIDEO_BIT_RATE_BPS + " bps decode bandwidth limit";
    }

    /**
     * Codec check for a video whose sample description is known — read from the file by
     * {@link VideoHeaderProbe}, or reported by a backend that carries one.
     *
     * <p>Routing is the whole point: an asset whose codec no decoder on this device can service
     * should never reach a player, because a player that is handed bytes it cannot render may
     * report success and simply show nothing.
     *
     * @param type media type of the asset
     * @param codecFourCc lower-case {@code stsd} fourcc, or null when unknown
     * @return a human-readable reason when this device has no decoder for that codec, or
     *         {@code null} when it can decode it or the codec could not be identified
     */
    @Nullable
    public static String incompatibleVideoCodec(@Nullable MediaType type,
                                                @Nullable String codecFourCc) {
        if (type != MediaType.VIDEO || codecFourCc == null) {
            return null;
        }
        if (INCOMPATIBLE_VIDEO_CODECS.contains(codecFourCc)) {
            return "video codec " + codecFourCc + " has no decoder on this device";
        }
        return null;
    }

    /**
     * Content-based check for a file that already exists locally. Detects HEIF/AVIF
     * containers from their {@code ftyp} box even when the extension says something
     * else (e.g. HEIF content named {@code .jpg}), and detects an undecodable video codec
     * from its sample description even when the extension says something else (e.g. HEVC
     * content named {@code .mov}).
     *
     * @param type media type of the asset
     * @param file the on-disk file to inspect, or null
     * @return a human-readable reason when the bytes are a known-undecodable container,
     *         or {@code null} when the file looks decodable or cannot be identified
     */
    @Nullable
    public static String incompatibleReasonForFile(MediaType type, @Nullable File file) {
        return incompatibleReasonForFile(type, file, null);
    }

    /**
     * The same content check for a caller that already holds a {@link VideoHeaderProbe} result for
     * these bytes, so a video is never parsed twice to answer the codec and throughput questions.
     *
     * <p>The throughput check is deliberately <em>not</em> folded in here, even though both are
     * video-content checks with the same shape. Every caller of this method treats a non-null
     * result as "undecodable": {@code MediaManager} discards the bytes and
     * {@code LocalMediaBackend.fetchCatalog} turns it into a sidecar report that excludes the file
     * from rotation altogether. A throughput verdict belongs in neither of those places — it is
     * routed by its own call site, which is why it has one.
     *
     * @param probed a previous probe of {@code file}, or null to read it here
     */
    @Nullable
    public static String incompatibleReasonForFile(MediaType type, @Nullable File file,
                                                   @Nullable VideoHeaderProbe.Probe probed) {
        if (type == null || file == null || !file.isFile() || file.length() == 0) {
            return null;
        }
        if (type == MediaType.VIDEO) {
            VideoHeaderProbe.Probe probe = probed != null ? probed : VideoHeaderProbe.probe(file);
            return incompatibleVideoCodec(type, probe.codec);
        }
        if (type != MediaType.IMAGE) {
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
