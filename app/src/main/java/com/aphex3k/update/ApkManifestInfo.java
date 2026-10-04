package com.aphex3k.update;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Minimal reader for the binary (AXML) {@code AndroidManifest.xml} inside an APK: extracts
 * {@code android:versionCode} and {@code android:versionName} from the root
 * {@code <manifest>} element.
 *
 * <p>The file may be an APK (zip container) or a raw AXML document. For an APK, the
 * central directory is located (end-of-central-directory scan of the file tail) and the
 * {@code AndroidManifest.xml} entry is read via the local header it points at; the local
 * file-header chain is not walked forward — inter-entry padding and data-descriptor entries
 * make that unreliable, while the central directory is authoritative. Stored entries are read
 * in place, deflated ones are inflated. Only the
 * string-pool chunk and the root start-element chunk (attributes are embedded in it, not
 * separate chunks) are parsed. The manifest is capped at a few MB of memory — cheap on the
 * ~800 MB frame. Fails closed: any missing piece, malformed structure, or a versionCode
 * that is not a plain int (e.g. a resource reference) yields {@code null}.
 */
public final class ApkManifestInfo {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Charset UTF_16LE = Charset.forName("UTF-16LE");

    private static final int CHUNK_FILE_HEADER = 0x0003;
    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_START_ELEMENT = 0x0102;

    private static final int POOL_FLAG_UTF8 = 0x100;
    /** The manifest's AXML is small; the cap bounds memory use on a hostile file. */
    private static final int MAX_AXML_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CHUNK_BYTES = 1 * 1024 * 1024;
    /** Hard cap on central-directory entries to scan before declaring the container malformed. */
    private static final int MAX_ZIP_ENTRIES = 4096;

    private static final int RES_VALUE_TYPE_STRING = 0x03;
    private static final int RES_VALUE_TYPE_INT_DEC = 0x10;
    private static final int RES_VALUE_TYPE_INT_HEX = 0x11;

    /**
     * aapt2 encodes the {@code android:} namespace as the pool index of this URI (see the
     * resource-id-map chunk); toolchains without that chunk use the reserved magic value
     * instead. Both forms are accepted.
     */
    private static final String ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android";
    private static final int NS_ANDROID_MAGIC = 0x01000000;

    public final Integer versionCode;
    public final String versionName;

    private ApkManifestInfo(Integer versionCode, String versionName) {
        this.versionCode = versionCode;
        this.versionName = versionName;
    }

    /**
     * @return the root manifest's {@code android:versionCode} and {@code android:versionName},
     *         or {@code null} when the file is not an APK/AXML with a {@code <manifest>} root
     *         or {@code android:versionCode} is absent/not a plain int.
     */
    public static ApkManifestInfo read(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        RandomAccessFile in;
        try {
            in = new RandomAccessFile(file, "r");
        } catch (IOException e) {
            return null;
        }
        try {
            byte[] axml = extractAxml(in);
            return axml == null ? null : parse(axml);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Locates the AXML manifest bytes: inside an APK (zip) via its central directory, else
     * directly from the start of the file.
     */
    private static byte[] extractAxml(RandomAccessFile in) throws IOException {
        long length = in.length();
        if (length < 32) {
            return null;
        }
        in.seek(0);
        byte[] head = readFully(in, 4);
        if (head[0] == 'P' && head[1] == 'K' && head[2] == 0x03 && head[3] == 0x04) {
            return extractFromZip(in, length);
        }
        // Not a zip: treat as a raw AXML document.
        in.seek(0);
        byte[] fileHeader = readFully(in, 8);
        if (fileHeader == null || u16le(fileHeader, 0) != CHUNK_FILE_HEADER) {
            return null;
        }
        long totalSize = u32le(fileHeader, 4);
        if (totalSize < 36 || totalSize > length || totalSize > MAX_AXML_BYTES) {
            return null;
        }
        byte[] buf = new byte[(int) totalSize];
        System.arraycopy(fileHeader, 0, buf, 0, 8);
        return readFully(in, buf, 8, (int) totalSize - 8);
    }

    /**
     * Locates the {@code AndroidManifest.xml} entry via the central directory (every real APK
     * carries one) and reads its data through the local file header the entry points at.
     * Stored entries are read in place, deflated ones are inflated; an entry whose local
     * header defers its sizes to a data descriptor (flag 0x08) is served from the central
     * directory's sizes. Anything else — a missing end-of-central-directory record, a broken
     * central directory, an unknown compression method, an oversized entry — fails closed.
     */
    private static byte[] extractFromZip(RandomAccessFile in, long length) throws IOException {
        long eocd = findEndOfCentralDirectory(in, length);
        if (eocd < 0) {
            return null;
        }
        byte[] rec = readAt(in, eocd + 12, 8);
        if (rec == null) {
            return null;
        }
        long cdSize = u32le(rec, 0);
        long cdOffset = u32le(rec, 4);
        if (cdOffset + cdSize > eocd || cdOffset + cdSize > length) {
            return null;
        }
        long cdEnd = cdOffset + cdSize;
        long p = cdOffset;
        int scanned = 0;
        while (p + 46 <= cdEnd && scanned < MAX_ZIP_ENTRIES) {
            byte[] h = readAt(in, p, 46);
            if (h == null || u32le(h, 0) != 0x02014B50L) {
                return null;
            }
            int method = u16le(h, 10);
            long csize = u32le(h, 20);
            long usize = u32le(h, 24);
            int nameLen = u16le(h, 28);
            int extraLen = u16le(h, 30);
            int commentLen = u16le(h, 32);
            long localOffset = u32le(h, 42);
            long nameStart = p + 46;
            if (nameStart + nameLen + extraLen + commentLen > cdEnd) {
                return null;
            }
            String name = new String(readAt(in, nameStart, nameLen), UTF_8);
            if ("AndroidManifest.xml".equals(name)) {
                return readEntryData(in, length, localOffset, method, csize, usize);
            }
            p = nameStart + nameLen + extraLen + commentLen;
            scanned++;
        }
        return null;
    }

    /**
     * Finds the end-of-central-directory record (signature {@code 0x06054B50}) in the file
     * tail, or -1 when absent. The record is the last 22 bytes of the zip plus an optional
     * comment of at most 65535 bytes, so only that tail needs scanning.
     */
    private static long findEndOfCentralDirectory(RandomAccessFile in, long length)
            throws IOException {
        if (length < 22) {
            return -1;
        }
        long tailStart = Math.max(0L, length - 22 - 65535L);
        byte[] tail = readAt(in, tailStart, (int) (length - tailStart));
        if (tail == null) {
            return -1;
        }
        for (long j = tail.length - 22L; j >= 0; j--) {
            if (u32le(tail, (int) j) != 0x06054B50L) {
                continue;
            }
            long commentLen = u16le(tail, (int) j + 20);
            if (tailStart + j + 22L + commentLen == length) {
                return tailStart + j;
            }
        }
        return -1;
    }

    /**
     * Reads one entry's data at its local file header offset. The entry's sizes and method
     * come from the central directory (authoritative; the local header only locates the
     * data via its name and extra field lengths).
     */
    private static byte[] readEntryData(RandomAccessFile in, long length, long localOffset,
            int method, long csize, long usize) throws IOException {
        if (localOffset + 30 > length) {
            return null;
        }
        byte[] h = readAt(in, localOffset, 30);
        if (h == null || u32le(h, 0) != 0x04034B50L) {
            return null;
        }
        int nameLen = u16le(h, 26);
        int extraLen = u16le(h, 28);
        long dataStart = localOffset + 30 + nameLen + extraLen;
        if (dataStart + csize > length || csize > MAX_AXML_BYTES || usize > MAX_AXML_BYTES) {
            return null;
        }
        byte[] data = readAt(in, dataStart, (int) csize);
        if (data == null) {
            return null;
        }
        if (method == 0) {
            return data;
        }
        if (method == 8) {
            return inflate(data);
        }
        return null;
    }

    private static byte[] inflate(byte[] compressed) {
        byte[] out = new byte[Math.max(4096, compressed.length * 4)];
        int pos = 0;
        Inflater inflater = new Inflater(true); // raw deflate, no zlib wrapper
        try {
            inflater.setInput(compressed);
            while (true) {
                if (pos == out.length) {
                    int grown = (int) Math.min((long) out.length * 2L, (long) MAX_AXML_BYTES);
                    if (grown <= pos) {
                        return null;
                    }
                    byte[] bigger = new byte[grown];
                    System.arraycopy(out, 0, bigger, 0, pos);
                    out = bigger;
                }
                int n = inflater.inflate(out, pos, out.length - pos);
                pos += n;
                if (inflater.finished()) {
                    break;
                }
                if (n == 0) {
                    return null; // stalled without finishing: corrupt stream
                }
            }
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
        byte[] result = new byte[pos];
        System.arraycopy(out, 0, result, 0, pos);
        return result;
    }

    private static ApkManifestInfo parse(byte[] axml) {
        int length = axml.length;
        if (length < 36 || u16le(axml, 0) != CHUNK_FILE_HEADER) {
            return null;
        }
        if (u16le(axml, 8) != CHUNK_STRING_POOL) {
            return null;
        }
        int poolSize = (int) u32le(axml, 12);
        if (poolSize < 28 || 8L + poolSize > length || poolSize > MAX_AXML_BYTES) {
            return null;
        }
        String[] strings = parseStringPool(axml, 8, poolSize);
        if (strings == null) {
            return null;
        }
        int manifestIdx = indexOf(strings, "manifest");
        int versionCodeIdx = indexOf(strings, "versionCode");
        int versionNameIdx = indexOf(strings, "versionName");
        if (manifestIdx < 0 || versionCodeIdx < 0) {
            return null;
        }

        Integer code = null;
        String name = "";
        boolean sawStart = false;
        long pos = 8L + poolSize;
        while (pos + 8 <= length) {
            int start = (int) pos;
            int chunkType = u16le(axml, start);
            long chunkSize = u32le(axml, start + 4);
            if (chunkSize < 8 || chunkSize > MAX_CHUNK_BYTES || pos + chunkSize > length) {
                return null;
            }
            pos += chunkSize;

            // The resource-id-map and namespace chunks are skipped; the first start-element
            // is the document root.
            if (chunkType != CHUNK_START_ELEMENT) {
                continue;
            }
            sawStart = true;
            if (chunkSize < 36) {
                return null;
            }
            int elementName = (int) u32le(axml, start + 20);
            if (elementName != manifestIdx) {
                return null; // root is not <manifest>
            }
            int attrCount = (int) u32le(axml, start + 28);
            int attrSize = u16le(axml, start + 26);
            if (attrCount < 0 || attrCount > 256 || attrSize < 20
                    || 36L + (long) attrCount * attrSize > chunkSize) {
                return null;
            }
            for (int i = 0; i < attrCount; i++) {
                int base = start + 36 + i * attrSize;
                long ns = u32le(axml, base);
                int attrName = (int) u32le(axml, base + 4);
                int dataType = axml[base + 15] & 0xFF;
                int data = (int) u32le(axml, base + 16);
                if (!isAndroidNamespace(ns, strings)) {
                    continue;
                }
                if (attrName == versionCodeIdx
                        && (dataType == RES_VALUE_TYPE_INT_DEC || dataType == RES_VALUE_TYPE_INT_HEX)) {
                    code = data;
                } else if (attrName == versionNameIdx && dataType == RES_VALUE_TYPE_STRING
                        && data >= 0 && data < strings.length) {
                    name = strings[data];
                }
            }
            break;
        }
        if (!sawStart || code == null) {
            return null;
        }
        return new ApkManifestInfo(code, name);
    }

    /**
     * String pool chunk (offset relative to the chunk start): stringCount @8, flags @16,
     * stringsStart @20 (chunk-relative), offset array @28 + i*4.
     */
    private static String[] parseStringPool(byte[] axml, int poolOffset, int poolSize) {
        int stringCount = (int) u32le(axml, poolOffset + 8);
        int flags = (int) u32le(axml, poolOffset + 16);
        int stringsStart = (int) u32le(axml, poolOffset + 20);
        if (stringCount < 0 || stringCount > 0x400000) {
            return null;
        }
        if (stringsStart < 28 + stringCount * 4 || stringsStart > poolSize) {
            return null;
        }
        int poolEnd = poolOffset + poolSize;
        int stringsBase = poolOffset + stringsStart;
        String[] out = new String[stringCount];
        boolean utf8 = (flags & POOL_FLAG_UTF8) != 0;
        for (int i = 0; i < stringCount; i++) {
            int offset = (int) u32le(axml, poolOffset + 28 + i * 4);
            int dataPos = stringsBase + offset;
            if (dataPos < stringsBase || dataPos >= poolEnd) {
                return null;
            }
            String s = utf8 ? readUtf8String(axml, dataPos, poolEnd)
                    : readUtf16String(axml, dataPos, poolEnd);
            if (s == null) {
                return null;
            }
            out[i] = s;
        }
        return out;
    }

    /** AOSP UTF-16 pool string: uint16 char count, UTF-16LE chars, null terminator. */
    private static String readUtf16String(byte[] pool, int pos, int limit) {
        if (pos < 0 || pos + 2 > limit) {
            return null;
        }
        int charCount = u16le(pool, pos);
        if (charCount * 2L > limit - pos - 2) {
            return null;
        }
        return new String(pool, pos + 2, charCount * 2, UTF_16LE);
    }

    /**
     * AOSP UTF-8 pool string: length-prefixed (1 byte, 2 bytes, or 5 bytes in AOSP's
     * encoding) followed by UTF-8 data and a null byte.
     */
    private static String readUtf8String(byte[] pool, int pos, int limit) {
        if (pos < 0 || pos + 1 > limit) {
            return null;
        }
        int b0 = pool[pos] & 0xFF;
        int byteCount;
        if (b0 < 0x80) {
            byteCount = b0;
            pos += 1;
        } else if ((b0 & 0xC0) == 0x80) {
            if (pos + 2 > limit) {
                return null;
            }
            byteCount = ((b0 & 0x1F) << 8) | (pool[pos + 1] & 0xFF);
            pos += 2;
        } else {
            if (pos + 5 > limit) {
                return null;
            }
            byteCount = (pool[pos + 2] & 0xFF)
                    | ((pool[pos + 3] & 0xFF) << 8)
                    | ((pool[pos + 4] & 0xFF) << 16);
            pos += 5;
        }
        if (pos + byteCount + 1 > limit) {
            return null;
        }
        return new String(pool, pos, byteCount, UTF_8);
    }

    /**
     * An attribute record's namespace field names the {@code android} namespace either by the
     * reserved magic value (toolchains without a resource-id-map chunk) or by the pool index of
     * the android namespace URI (aapt2, which also emits the resource-id-map chunk).
     */
    private static boolean isAndroidNamespace(long ns, String[] strings) {
        if (ns == NS_ANDROID_MAGIC) {
            return true;
        }
        if (ns >= 0 && ns < strings.length) {
            return ANDROID_NAMESPACE_URI.equals(strings[(int) ns]);
        }
        return false;
    }

    private static int indexOf(String[] strings, String name) {
        for (int i = 0; i < strings.length; i++) {
            if (name.equals(strings[i])) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] readAt(RandomAccessFile in, long pos, int length) throws IOException {
        in.seek(pos);
        return readFully(in, length);
    }

    private static byte[] readFully(RandomAccessFile in, int length) throws IOException {
        return readFully(in, new byte[length], 0, length);
    }

    private static byte[] readFully(RandomAccessFile in, byte[] target, int off, int length)
            throws IOException {
        int pos = off;
        while (pos < off + length) {
            int n = in.read(target, pos, off + length - pos);
            if (n < 0) {
                return null;
            }
            pos += n;
        }
        return target;
    }

    private static int u16le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32le(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }
}
