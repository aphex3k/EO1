package com.aphex3k.eo1;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal, hand-rolled streaming {@code multipart/form-data} parser.
 *
 * <p>File parts are streamed straight to disk in 8 KB chunks using a rolling boundary window, so a
 * large upload never lives in memory. Text parts are read into a (bounded) string. The parser is
 * written against plain {@link java.io} primitives so it is unit-testable on the JVM with no
 * Android dependencies.
 */
public final class MultipartParser {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int CHUNK = 8192;

    private MultipartParser() {
    }

    /** Thrown when a part exceeds the configured size cap or the body is malformed. */
    public static class MultipartException extends IOException {
        public MultipartException(String msg) {
            super(msg);
        }
    }

    /** One parsed part. Exactly one of {@link #file} / {@link #text} is non-null. */
    public static final class Part {
        public final String name;
        public final String filename; // null for text parts
        public final File file;       // null for text parts
        public final String text;     // null for file parts
        public final long size;

        Part(String name, String filename, File file, String text, long size) {
            this.name = name;
            this.filename = filename;
            this.file = file;
            this.text = text;
            this.size = size;
        }

        public boolean isFile() {
            return file != null;
        }
    }

    /**
     * Extracts the {@code boundary} value from a {@code Content-Type} header.
     *
     * @return the boundary, or {@code null} if absent/unparseable.
     */
    public static String boundaryFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.US);
        int idx = lower.indexOf("boundary=");
        if (idx < 0) {
            return null;
        }
        int start = idx + "boundary=".length();
        int end = start;
        while (end < contentType.length() && contentType.charAt(end) != ';') {
            end++;
        }
        String b = contentType.substring(start, end).trim();
        if (b.length() >= 2 && b.charAt(0) == '"' && b.charAt(b.length() - 1) == '"') {
            b = b.substring(1, b.length() - 1);
        }
        return b.isEmpty() ? null : b;
    }

    /**
     * Parses the multipart body and writes file parts into {@code outDir} under temporary
     * {@code part_tmp_N} names. The caller renames them to their final safe names.
     *
     * @param body            the raw request body stream.
     * @param boundary        the boundary value (without leading {@code --}).
     * @param outDir          directory to write file parts into (must exist).
     * @param maxBytesPerFile cap per file; exceeding it throws {@link MultipartException}.
     */
    public static List<Part> parse(InputStream body, String boundary, File outDir, long maxBytesPerFile)
            throws IOException {
        if (boundary == null || boundary.isEmpty()) {
            throw new MultipartException("missing multipart boundary");
        }
        Reader r = new Reader(body);
        byte[] delim = ("--" + boundary).getBytes(UTF_8);
        List<Part> parts = new ArrayList<Part>();

        // Leading boundary.
        if (!r.skipToken(delim)) {
            return parts;
        }
        while (true) {
            int b1 = r.read();
            int b2 = r.read();
            if (b1 == '-' && b2 == '-') {
                break; // final --boundary--
            }
            if (!(b1 == '\r' && b2 == '\n')) {
                break; // malformed; stop
            }
            // Part headers.
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
                }
            }
            String disp = headers.get("content-disposition");
            String name = param(disp, "name");
            String filename = param(disp, "filename");

            byte[] endToken = ("\r\n--" + boundary).getBytes(UTF_8);
            if (filename != null) {
                File out = new File(outDir, "part_tmp_" + (parts.size() + 1));
                long size;
                try {
                    size = r.streamToFile(out, endToken, maxBytesPerFile);
                } catch (IOException e) {
                    deleteQuietly(out);
                    throw e;
                }
                parts.add(new Part(name, filename, out, null, size));
            } else {
                byte[] data = r.readToToken(endToken, maxBytesPerFile);
                parts.add(new Part(name, null, null, new String(data, UTF_8), data.length));
            }
            // Loop continues: the next read() consumes the bytes right after the boundary.
        }
        return parts;
    }

    private static String param(String contentDisposition, String key) {
        if (contentDisposition == null) {
            return null;
        }
        // Look for  key="value"  (value may be quoted).
        String needle = key + "=\"";
        int i = contentDisposition.toLowerCase(Locale.US).indexOf(needle);
        if (i < 0) {
            return null;
        }
        int start = i + needle.length();
        int end = start;
        while (end < contentDisposition.length() && contentDisposition.charAt(end) != '"') {
            end++;
        }
        if (end <= start || end >= contentDisposition.length()) {
            return null;
        }
        return contentDisposition.substring(start, end);
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            // best effort
        }
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        if (needle.length == 0 || hay.length < needle.length) {
            return -1;
        }
        int limit = hay.length - needle.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Byte reader over an {@link InputStream} with a pushback buffer and rolling-window token
     * scanners.
     */
    private static final class Reader {
        private final InputStream in;
        private final ByteArrayOutputStream unread = new ByteArrayOutputStream();
        private final byte[] one = new byte[1];

        Reader(InputStream in) {
            this.in = in;
        }

        int read() throws IOException {
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        int read(byte[] b, int off, int len) throws IOException {
            int total = 0;
            byte[] u = unread.toByteArray();
            int unreadLen = u.length;
            int take = Math.min(len, unreadLen);
            if (take > 0) {
                System.arraycopy(u, 0, b, off, take);
                unread.reset();
                if (take < unreadLen) {
                    unread.write(u, take, unreadLen - take);
                }
                total += take;
                off += take;
                len -= take;
            }
            if (len <= 0) {
                return total;
            }
            int n = in.read(b, off, len);
            if (n > 0) {
                return total + n;
            }
            if (n < 0 && total == 0) {
                return -1;
            }
            return total;
        }

        String readLine() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int c;
            while ((c = read()) != -1) {
                if (c == '\n') {
                    byte[] b = out.toByteArray();
                    int len = b.length;
                    if (len > 0 && b[len - 1] == '\r') {
                        len--;
                    }
                    return new String(b, 0, len, UTF_8);
                }
                out.write(c);
                if (out.size() > 8192) {
                    return null;
                }
            }
            return null;
        }

        /** Consumes bytes until {@code token} is found and consumed; returns false at EOF. */
        boolean skipToken(byte[] token) throws IOException {
            byte[] buf = new byte[CHUNK];
            int keep = token.length - 1;
            byte[] carry = new byte[keep];
            int carryLen = 0;
            while (true) {
                int n = read(buf, 0, buf.length);
                boolean more = n > 0;
                int wlen = carryLen + (more ? n : 0);
                if (!more && carryLen == 0) {
                    return false;
                }
                byte[] work = new byte[wlen];
                System.arraycopy(carry, 0, work, 0, carryLen);
                if (more) {
                    System.arraycopy(buf, 0, work, carryLen, n);
                }
                int found = indexOf(work, token);
                if (found >= 0) {
                    int after = wlen - (found + token.length);
                    if (after > 0) {
                        unread.write(work, found + token.length, after);
                    }
                    return true;
                }
                updateCarry(work, wlen, carry, keep);
                carryLen = Math.min(keep, wlen);
                if (!more) {
                    return false;
                }
            }
        }

        /**
         * Streams bytes to {@code out} until {@code token} is found and consumed.
         *
         * @return number of bytes written (the part body length).
         */
        long streamToFile(File out, byte[] token, long maxBytes) throws IOException {
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[CHUNK];
                int keep = token.length - 1;
                byte[] carry = new byte[keep];
                int carryLen = 0;
                long written = 0;
                while (true) {
                    int n = read(buf, 0, buf.length);
                    boolean more = n > 0;
                    int wlen = carryLen + (more ? n : 0);
                    if (!more && carryLen == 0) {
                        throw new MultipartException("unexpected end of upload body");
                    }
                    byte[] work = new byte[wlen];
                    System.arraycopy(carry, 0, work, 0, carryLen);
                    if (more) {
                        System.arraycopy(buf, 0, work, carryLen, n);
                    }
                    int found = indexOf(work, token);
                    if (found >= 0) {
                        int writeLen = found;
                        if (written + writeLen > maxBytes) {
                            throw new MultipartException("upload exceeds size limit");
                        }
                        if (writeLen > 0) {
                            fos.write(work, 0, writeLen);
                            written += writeLen;
                        }
                        int after = wlen - (found + token.length);
                        if (after > 0) {
                            unread.write(work, found + token.length, after);
                        }
                        return written;
                    }
                    int emit = wlen - keep;
                    if (emit > 0) {
                        if (written + emit > maxBytes) {
                            throw new MultipartException("upload exceeds size limit");
                        }
                        fos.write(work, 0, emit);
                        written += emit;
                    }
                    updateCarry(work, wlen, carry, keep);
                    carryLen = Math.min(keep, wlen);
                    if (!more) {
                        throw new MultipartException("unexpected end of upload body");
                    }
                }
            } finally {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }

        /**
         * Reads bytes into memory until {@code token} is found and consumed.
         *
         * @return the bytes before the token.
         */
        byte[] readToToken(byte[] token, long maxBytes) throws IOException {
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            byte[] buf = new byte[CHUNK];
            int keep = token.length - 1;
            byte[] carry = new byte[keep];
            int carryLen = 0;
            while (true) {
                int n = read(buf, 0, buf.length);
                boolean more = n > 0;
                int wlen = carryLen + (more ? n : 0);
                if (!more && carryLen == 0) {
                    throw new MultipartException("unexpected end of multipart body");
                }
                byte[] work = new byte[wlen];
                System.arraycopy(carry, 0, work, 0, carryLen);
                if (more) {
                    System.arraycopy(buf, 0, work, carryLen, n);
                }
                int found = indexOf(work, token);
                if (found >= 0) {
                    int writeLen = found;
                    if (acc.size() + writeLen > maxBytes) {
                        throw new MultipartException("part exceeds size limit");
                    }
                    if (writeLen > 0) {
                        acc.write(work, 0, writeLen);
                    }
                    int after = wlen - (found + token.length);
                    if (after > 0) {
                        unread.write(work, found + token.length, after);
                    }
                    return acc.toByteArray();
                }
                int emit = wlen - keep;
                if (emit > 0) {
                    if (acc.size() + emit > maxBytes) {
                        throw new MultipartException("part exceeds size limit");
                    }
                    acc.write(work, 0, emit);
                }
                updateCarry(work, wlen, carry, keep);
                carryLen = Math.min(keep, wlen);
                if (!more) {
                    throw new MultipartException("unexpected end of multipart body");
                }
            }
        }

        /**
         * Copies the last {@code keep} bytes of {@code work} (or all of it, if shorter) into
         * {@code carry}, preserving bytes that may form the start of a boundary straddling chunk
         * edges.
         */
        private static void updateCarry(byte[] work, int wlen, byte[] carry, int keep) {
            if (wlen >= keep) {
                System.arraycopy(work, wlen - keep, carry, 0, keep);
            } else if (wlen > 0) {
                System.arraycopy(work, 0, carry, 0, wlen);
            }
        }
    }
}
