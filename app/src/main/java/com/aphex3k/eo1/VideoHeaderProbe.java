package com.aphex3k.eo1;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Bounded, pure-Java ISOBMFF (.mov/.mp4/.m4v) header probe.
 *
 * <p>Reports two facts about a video file without touching the media stack:
 * <ul>
 *   <li>the display-matrix rotation in degrees counter-clockwise (0/90/180/270, or -1 when
 *       unknown) — read from the {@code tkhd} 16.16 matrix, overridden by an ISO 23001-8
 *       {@code rot } box when one is present; apply it with
 *       {@code Matrix.postRotate(360 - value)} since Android's postRotate is clockwise;</li>
 *   <li>the coded video dimensions, from the first video sample entry in
 *       {@code moov/trak/mdia/minf/stbl/stsd}.</li>
 * </ul>
 *
 * <p>Deliberately no {@code MediaMetadataRetriever}: opening a file through mediaserver grabs
 * {@code /dev/amstream_vbuf} and EBUSY-starves a live in-process TsPlayer. I/O is bounded to a
 * 2 MB head scan plus an 8 MB tail scan (a found {@code moov} box is read at most 32 MB).
 * Anything beyond those caps, or unparseable, yields "unknown" ({@code -1 / 0,0}), which callers
 * treat as "no change from today's behaviour". Non-ISOBMFF extensions yield
 * {@code (0, 0, 0)}: rotation identity, dimensions unknown.
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

    /** Probe result. {@code rotationDeg}: 0/90/180/270 or -1 (unknown). Dims: 0 = unknown. */
    public static final class Probe {
        public final int rotationDeg;
        public final int codedWidth;
        public final int codedHeight;

        public Probe(int rotationDeg, int codedWidth, int codedHeight) {
            this.rotationDeg = rotationDeg;
            this.codedWidth = codedWidth;
            this.codedHeight = codedHeight;
        }
    }

    /**
     * Probes {@code f}. Never throws; returns {@code (-1, 0, 0)} when nothing could be
     * determined and {@code (0, 0, 0)} for non-ISOBMFF file names.
     */
    public static Probe probe(File f) {
        if (f == null || !f.isFile()) {
            return new Probe(-1, 0, 0);
        }
        String name = f.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".mov") && !name.endsWith(".mp4") && !name.endsWith(".m4v")) {
            return new Probe(0, 0, 0);
        }
        try {
            long len = f.length();
            if (len < 32) {
                return new Probe(-1, 0, 0);
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
            return new Probe(-1, 0, 0);
        } catch (Exception e) {
            return new Probe(-1, 0, 0);
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
        int width = 0;
        int height = 0;
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
                int[] t = parseTrak(moov, off, end);
                if (t != null) {
                    if (rotTkhd < 0 && t[0] >= 0) {
                        rotTkhd = t[0];
                    }
                    if (width == 0 && t[1] > 0 && t[2] > 0) {
                        width = t[1];
                        height = t[2];
                    }
                }
            }
            off = end;
        }
        int rotation = rotBox >= 0 ? rotBox : rotTkhd;
        return new Probe(rotation, width, height);
    }

    /** Returns {rotation, width, height} from a trak box; rotation -1 when no tkhd matrix. */
    private static int[] parseTrak(byte[] data, int start, int end) {
        int rotation = -1;
        int width = 0;
        int height = 0;
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
            } else if (type == FOURCC_MDIA) {
                if (width == 0) {
                    int[] dims = parseMdiaDims(data, off, childEnd);
                    if (dims != null) {
                        width = dims[0];
                        height = dims[1];
                    }
                }
            }
            off = childEnd;
        }
        return new int[]{rotation, width, height};
    }

    /**
     * Rotation (counter-clockwise degrees) from the tkhd matrix: the value is the CCW display
     * rotation the matrix encodes, so it can be applied with Matrix.postRotate(360 - value).
     * The 9x16.16 matrix sits at box offset 48 in version 0 and 60 in version 1. Diagonal
     * (1,1)=0, (-1,-1)=180, off-diagonal =90/270.
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

    /** Walks mdia → minf → stbl → stsd for the first video sample entry's coded dimensions. */
    private static int[] parseMdiaDims(byte[] data, int start, int end) {
        int off = start + 8;
        while (off + 8 <= end) {
            int type = (int) u32(data, off + 4);
            int childEnd = childEnd(data, off, u32(data, off), end);
            if (childEnd <= off || childEnd > end) {
                break;
            }
            if (type == FOURCC_MINF) {
                int[] dims = parseMinf(data, off, childEnd);
                if (dims != null) {
                    return dims;
                }
            }
            off = childEnd;
        }
        return null;
    }

    private static int[] parseMinf(byte[] data, int start, int end) {
        int off = start + 8;
        while (off + 8 <= end) {
            int type = (int) u32(data, off + 4);
            int childEnd = childEnd(data, off, u32(data, off), end);
            if (childEnd <= off || childEnd > end) {
                break;
            }
            if (type == FOURCC_STBL) {
                int[] dims = parseStbl(data, off, childEnd);
                if (dims != null) {
                    return dims;
                }
            }
            off = childEnd;
        }
        return null;
    }

    private static int[] parseStbl(byte[] data, int start, int end) {
        int off = start + 8;
        while (off + 8 <= end) {
            int type = (int) u32(data, off + 4);
            int childEnd = childEnd(data, off, u32(data, off), end);
            if (childEnd <= off || childEnd > end) {
                break;
            }
            if (type == FOURCC_STSD) {
                int[] dims = parseStsd(data, off, childEnd);
                if (dims != null) {
                    return dims;
                }
            }
            off = childEnd;
        }
        return null;
    }

    /** Width/height are fixed at 32/34 bytes into every VideoSampleEntry. */
    private static int[] parseStsd(byte[] data, int start, int end) {
        int off = start + 8 + 4; // version/flags
        if (off + 4 > end) {
            return null;
        }
        int count = (int) u32(data, off) & 0x7FFFFFFF;
        off += 4;
        for (int i = 0; i < count; i++) {
            if (off + 36 > end) {
                return null;
            }
            long size = u32(data, off);
            int type = (int) u32(data, off + 4);
            if (size < 36) {
                return null;
            }
            if (isVideoCodec(type)) {
                int w = u16(data, off + 32);
                int h = u16(data, off + 34);
                return (w > 0 && h > 0) ? new int[]{w, h} : null;
            }
            off += size == 1 ? (int) u64(data, off + 8) : (int) size;
        }
        return null;
    }

    private static boolean isVideoCodec(int fourCc) {
        return fourCc == fourcc("avc1") || fourCc == fourcc("avc3")
                || fourCc == fourcc("av01") || fourCc == fourcc("hvc1")
                || fourCc == fourcc("hev1") || fourCc == fourcc("mp4v")
                || fourCc == fourcc("x264") || fourCc == fourcc("s263")
                || fourCc == fourcc("s264") || fourCc == fourcc("s265")
                || fourCc == fourcc("vvc1") || fourCc == fourcc("vc1 ")
                || fourCc == fourcc("wvc1") || fourCc == fourcc("dvh1")
                || fourCc == fourcc("3iv1") || fourCc == fourcc("3iv2")
                || fourCc == fourcc("3ivx") || fourCc == fourcc("kavi");
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
