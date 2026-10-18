package com.aphex3k.eo1;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Bounded, pure-Java ISOBMFF (.mov/.mp4/.m4v) header probe.
 *
 * <p>Reports three facts about a video file without touching the media stack:
 * <ul>
 *   <li>the codec, as the fourcc of the first recognised video sample description — the only
 *       place in the app where a file's actual codec is readable, since the container extension
 *       is a claim rather than a fact ({@code .mov} carries H.264 or HEVC equally well);</li>
 *   <li>the display-matrix rotation in degrees clockwise (0/90/180/270, or -1 when unknown)
 *       — read from the {@code tkhd} 16.16 matrix, overridden by an ISO 23001-8 {@code rot }
 *       box when one is present (that rare path returns the box's counter-clockwise value
 *       unchanged; every consumer here is direction-agnostic). The platform MediaPlayer
 *       applies the rotation itself when decoding, so callers use the value for player
 *       routing and scale-factor choices, never as a {@code Matrix.postRotate} angle;</li>
 *   <li>the coded video dimensions, from the first video sample entry in
 *       {@code moov/trak/mdia/minf/stbl/stsd}.</li>
 * </ul>
 *
 * <p>Deliberately no {@code MediaMetadataRetriever}: opening a file through mediaserver grabs
 * {@code /dev/amstream_vbuf} and EBUSY-starves a live in-process TsPlayer. I/O is bounded to a
 * 2 MB head scan plus an 8 MB tail scan (a found {@code moov} box is read at most 32 MB).
 * Anything beyond those caps, or unparseable, yields "unknown" ({@code -1 / 0,0 / null}), which
 * callers treat as "no change from today's behaviour" — an unknown codec is never a reason to
 * skip a file. Non-ISOBMFF extensions yield {@code (0, 0, 0, null)}: rotation identity,
 * dimensions and codec unknown.
 */
public final class VideoHeaderProbe {

    private static final int HEAD_SCAN_BYTES = 2 * 1024 * 1024;
    private static final int TAIL_SCAN_BYTES = 8 * 1024 * 1024;
    private static final long MAX_MOOV_BYTES = 32L * 1024 * 1024;

    private static final int FOURCC_MOOV = fourcc("moov");
    private static final int FOURCC_TRAK = fourcc("trak");
    private static final int FOURCC_TKHD = fourcc("tkhd");
    private static final int FOURCC_MDIA = fourcc("mdia");
    private static final int FOURCC_MINF = fourcc("minf");
    private static final int FOURCC_STBL = fourcc("stbl");
    private static final int FOURCC_STSD = fourcc("stsd");
    private static final int FOURCC_ROT = fourcc("rot ");

    private VideoHeaderProbe() {
    }

    /**
     * Probe result. {@code rotationDeg}: 0/90/180/270 or -1 (unknown). Dims: 0 = unknown.
     * {@code codec}: lower-case fourcc of the first recognised video sample description, or
     * null when the file gave no readable one.
     */
    public static final class Probe {
        public final int rotationDeg;
        public final int codedWidth;
        public final int codedHeight;
        public final String codec;

        public Probe(int rotationDeg, int codedWidth, int codedHeight, String codec) {
            this.rotationDeg = rotationDeg;
            this.codedWidth = codedWidth;
            this.codedHeight = codedHeight;
            this.codec = codec;
        }
    }

    /** Sample-description facts read out of one {@code trak} box. */
    private static final class SampleEntry {
        String codec;
        int width;
        int height;
    }

    /**
     * Probes {@code f}. Never throws; returns everything-unknown when nothing could be
     * determined and an all-zero result with a null codec for non-ISOBMFF file names.
     */
    public static Probe probe(File f) {
        if (f == null || !f.isFile()) {
            return new Probe(-1, 0, 0, null);
        }
        String name = f.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".mov") && !name.endsWith(".mp4") && !name.endsWith(".m4v")) {
            return new Probe(0, 0, 0, null);
        }
        try {
            long len = f.length();
            if (len < 32) {
                return new Probe(-1, 0, 0, null);
            }

            int headLen = (int) Math.min(len, HEAD_SCAN_BYTES);
            byte[] head = readAll(f, 0, headLen);
            long[] found = findMoovInHead(head, len);
            if (found != null) {
                int start = (int) found[0];
                int size = (int) found[1];
                byte[] moov = new byte[size];
                System.arraycopy(head, start, moov, 0, size);
                return parseMoov(moov);
            }

            int tailLen = (int) Math.min(len, TAIL_SCAN_BYTES);
            long tailStart = len - tailLen;
            byte[] tail = readAll(f, tailStart, tailLen);
            int rel = findMoovStartInTail(tail, len);
            if (rel >= 0) {
                long absStart = tailStart + rel;
                long size;
                long sizeField = u32(tail, rel);
                if (sizeField == 1) {
                    size = u64(tail, rel + 8);
                } else if (sizeField == 0) {
                    size = len - absStart;
                } else {
                    size = sizeField;
                }
                if (size >= 16 && size <= MAX_MOOV_BYTES && absStart + size <= len) {
                    byte[] moov = readAll(f, absStart, (int) size);
                    return parseMoov(moov);
                }
            }
            return new Probe(-1, 0, 0, null);
        } catch (Exception e) {
            return new Probe(-1, 0, 0, null);
        }
    }

    // --- moov discovery ---------------------------------------------------------------

    /** Walks top-level boxes from offset 0; returns {start, size} of a fully-contained moov, else null. */
    private static long[] findMoovInHead(byte[] data, long fileSize) {
        int off = 0;
        int n = data.length;
        while (off + 8 <= n) {
            long size = u32(data, off);
            int type = (int) u32(data, off + 4);
            if (size < 8) {
                return null;
            }
            long end;
            if (size == 1) {
                if (off + 16 > n) {
                    return null;
                }
                end = off + u64(data, off + 8);
            } else if (size == 0) {
                // Box runs to EOF; cannot be contained in this window — the tail scan handles it.
                return null;
            } else {
                end = off + size;
            }
            if (end > fileSize) {
                return null;
            }
            if (type == FOURCC_MOOV && end <= n) {
                return new long[]{off, end - off};
            }
            off = (int) end;
        }
        return null;
    }

    /** Signature scan of a tail window; returns the window-relative offset of a plausible moov box, or -1. */
    private static int findMoovStartInTail(byte[] data, long fileSize) {
        long base = fileSize - data.length;
        for (int i = 4; i + 4 <= data.length; i++) {
            if (data[i] != 'm' || (int) u32(data, i) != FOURCC_MOOV) {
                continue;
            }
            long absStart = base + i - 4;
            long size;
            long sizeField = u32(data, i - 4);
            if (sizeField == 1) {
                if (i + 8 > data.length) {
                    continue;
                }
                size = u64(data, i + 4);
            } else if (sizeField == 0) {
                size = fileSize - absStart;
            } else {
                size = sizeField;
            }
            if (size < 16 || size > MAX_MOOV_BYTES) {
                continue;
            }
            if (absStart + size > fileSize) {
                continue;
            }
            return i - 4;
        }
        return -1;
    }

    // --- moov / trak parsing ----------------------------------------------------------

    private static Probe parseMoov(byte[] moov) {
        int rotBox = -1;
        int rotTkhd = -1;
        SampleEntry video = new SampleEntry();
        int n = moov.length;
        int off = 8; // past the moov box's own size/type header
        while (off + 8 <= n) {
            long size = u32(moov, off);
            int type = (int) u32(moov, off + 4);
            int end = childEnd(moov, off, size, n);
            if (end <= off || end > n) {
                break;
            }
            if (type == FOURCC_ROT) {
                int r = parseRot(moov, off, end);
                if (r >= 0) {
                    rotBox = r;
                }
            } else if (type == FOURCC_TRAK) {
                int r = parseTrak(moov, off, end, video);
                if (rotTkhd < 0 && r >= 0) {
                    rotTkhd = r;
                }
            }
            off = end;
        }
        int rotation = rotBox >= 0 ? rotBox : rotTkhd;
        return new Probe(rotation, video.width, video.height, video.codec);
    }

    /** Rotation from a trak box's tkhd (-1 when it has none), plus that track's video entry. */
    private static int parseTrak(byte[] data, int start, int end, SampleEntry out) {
        int rotation = -1;
        int off = start + 8;
        while (off + 8 <= end) {
            long size = u32(data, off);
            int type = (int) u32(data, off + 4);
            int childEnd = childEnd(data, off, size, end);
            if (childEnd <= off || childEnd > end) {
                break;
            }
            if (type == FOURCC_TKHD) {
                int r = parseTkhdRotation(data, off, childEnd);
                if (rotation < 0 && r >= 0) {
                    rotation = r;
                }
            } else if (type == FOURCC_MDIA && out.codec == null) {
                parseMdia(data, off, childEnd, out);
            }
            off = childEnd;
        }
        return rotation;
    }

    /**
     * Rotation (clockwise degrees — the same convention Android's MediaMetadataRetriever
     * reports) from the tkhd matrix. The 9x16.16 matrix sits at box offset 48 in version 0
     * and 60 in version 1. Diagonal (1,1)=0, (-1,-1)=180, off-diagonal =90/270.
     */
    private static int parseTkhdRotation(byte[] data, int start, int end) {
        int off = start + 8 + 4; // past version+flags
        int version = data[start + 8] & 0xFF;
        int matrixOff;
        if (version == 1) {
            // creation(8) modification(8) trackId(4) reserved(4) duration(8) reserved(8)
            // layer(2) altGroup(2) volume(2) reserved(2)
            matrixOff = off + 8 + 8 + 4 + 4 + 8 + 8 + 2 + 2 + 2 + 2;
        } else {
            // creation(4) modification(4) trackId(4) reserved(4) duration(4) reserved(8)
            // layer(2) altGroup(2) volume(2) reserved(2)
            matrixOff = off + 4 + 4 + 4 + 4 + 4 + 8 + 2 + 2 + 2 + 2;
        }
        if (matrixOff + 36 > end) {
            return -1;
        }
        int a = fixed1616(data, matrixOff);
        int b = fixed1616(data, matrixOff + 4);
        int d = fixed1616(data, matrixOff + 12);
        int e = fixed1616(data, matrixOff + 16);
        if (a == 1 && e == 1 && b == 0 && d == 0) {
            return 0;
        }
        if (a == -1 && e == -1 && b == 0 && d == 0) {
            return 180;
        }
        if (a == 0 && e == 0 && b == -1 && d == 1) {
            return 90;
        }
        if (a == 0 && e == 0 && b == 1 && d == -1) {
            return 270;
        }
        return -1;
    }

    /** ISO 23001-8 {@code rot } box: value/65536 degrees, counter-clockwise positive. */
    private static int parseRot(byte[] data, int start, int end) {
        if (end - start < 12) {
            return -1;
        }
        double degrees = s32(data, start + 8) / 65536.0;
        degrees = ((degrees % 360.0) + 360.0) % 360.0;
        int rounded = (int) Math.round(degrees);
        if (Math.abs(rounded - degrees) > 1.0) {
            return -1;
        }
        int r = rounded % 360;
        return (r == 0 || r == 90 || r == 180 || r == 270) ? r : -1;
    }

    /**
     * Sample-description fourccs that name a video track. Anything else in a {@code stsd} is left
     * unidentified — an audio entry is not a codec verdict, and an unknown codec is never a reason
     * to skip a file.
     */
    private static final Set<String> VIDEO_SAMPLE_ENTRIES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("avc1", "avc3", "av01", "dvh1", "hev1", "hvc1",
                    "kavi", "mp4v", "s263", "s264", "s265", "vc1 ", "vvc1", "wvc1",
                    "3iv1", "3iv2", "3ivx")));

    /** Walks mdia → minf → stbl → stsd, filling {@code out} from this track's video entry. */
    private static void parseMdia(byte[] data, int start, int end, SampleEntry out) {
        int off = start + 8;
        while (off + 8 <= end) {
            int type = (int) u32(data, off + 4);
            int childEnd = childEnd(data, off, u32(data, off), end);
            if (childEnd <= off || childEnd > end) {
                return;
            }
            if (type == FOURCC_MINF) {
                parseMinf(data, off, childEnd, out);
                if (out.codec != null) {
                    return;
                }
            }
            off = childEnd;
        }
    }

    private static void parseMinf(byte[] data, int start, int end, SampleEntry out) {
        int off = start + 8;
        while (off + 8 <= end) {
            int type = (int) u32(data, off + 4);
            int childEnd = childEnd(data, off, u32(data, off), end);
            if (childEnd <= off || childEnd > end) {
                return;
            }
            if (type == FOURCC_STBL) {
                parseStbl(data, off, childEnd, out);
                if (out.codec != null) {
                    return;
                }
            }
            off = childEnd;
        }
    }

    private static void parseStbl(byte[] data, int start, int end, SampleEntry out) {
        int off = start + 8;
        while (off + 8 <= end) {
            int type = (int) u32(data, off + 4);
            int childEnd = childEnd(data, off, u32(data, off), end);
            if (childEnd <= off || childEnd > end) {
                return;
            }
            if (type == FOURCC_STSD) {
                parseStsd(data, off, childEnd, out);
                return;
            }
            off = childEnd;
        }
    }

    /** Coded width/height sit at 32/34 bytes into every VideoSampleEntry, after its fourcc. */
    private static void parseStsd(byte[] data, int start, int end, SampleEntry out) {
        int off = start + 8 + 4; // version/flags
        if (off + 4 > end) {
            return;
        }
        int count = (int) u32(data, off) & 0x7FFFFFFF;
        off += 4;
        for (int i = 0; i < count; i++) {
            if (off + 36 > end) {
                return;
            }
            long size = u32(data, off);
            if (size < 36) {
                return;
            }
            String fourCc = fourccString(data, off + 4);
            if (VIDEO_SAMPLE_ENTRIES.contains(fourCc)) {
                out.codec = fourCc;
                int w = u16(data, off + 32);
                int h = u16(data, off + 34);
                if (w > 0 && h > 0) {
                    out.width = w;
                    out.height = h;
                }
                return;
            }
            off += size == 1 ? (int) u64(data, off + 8) : (int) size;
        }
    }

    // --- byte helpers -----------------------------------------------------------------

    /** End offset of the box at {@code off}; {@code n} bounds the box for size==0 (to EOF). */
    private static int childEnd(byte[] data, int off, long size, int n) {
        if (size == 0) {
            return n;
        }
        if (size == 1) {
            if (off + 16 > n) {
                return off; // largesize would run past the buffer
            }
            long big = u64(data, off + 8);
            return off + (int) Math.min(big, Integer.MAX_VALUE);
        }
        return off + (int) size;
    }

    private static long u32(byte[] b, int off) {
        return (((long) (b[off] & 0xFF)) << 24)
                | (((long) (b[off + 1] & 0xFF)) << 16)
                | (((long) (b[off + 2] & 0xFF)) << 8)
                | (long) (b[off + 3] & 0xFF);
    }

    private static long u64(byte[] b, int off) {
        return (u32(b, off) << 32) | u32(b, off + 4);
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int s32(byte[] b, int off) {
        return (int) u32(b, off);
    }

    private static int fixed1616(byte[] b, int off) {
        return s32(b, off) >> 16;
    }

    private static int fourcc(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    private static String fourccString(byte[] b, int off) {
        return new String(b, off, 4, Charset.forName("US-ASCII")).toLowerCase(Locale.ROOT);
    }

    private static byte[] readAll(File f, long start, int len) throws IOException {
        byte[] buf = new byte[len];
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            raf.seek(start);
            int read = 0;
            while (read < len) {
                int r = raf.read(buf, read, len - read);
                if (r < 0) {
                    break;
                }
                read += r;
            }
            if (read < len) {
                throw new IOException("short read: " + read + " of " + len);
            }
        } finally {
            raf.close();
        }
        return buf;
    }
}
