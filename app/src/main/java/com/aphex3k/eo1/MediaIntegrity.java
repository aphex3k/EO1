package com.aphex3k.eo1;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-1 integrity helpers for cached Immich media. Deliberately free of Android calls
 * (no Log) so it runs on the JVM.
 *
 * <p>Immich reports each asset's {@code checksum} as a Base64-encoded SHA-1 of the original
 * file. We compute the same digest of the bytes we download / read from disk and compare
 * against that value (or, when the server value is unavailable, against a hex sidecar
 * written at download time).</p>
 */
public final class MediaIntegrity {

    /** Suffix appended to a cached media file to hold its hex SHA-1. */
    public static final String SIDECAR_SUFFIX = ".sha1";

    private static final int BUFFER_SIZE = 32 * 1024;
    private static final String UTF_8 = "UTF-8";
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final String BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private MediaIntegrity() {
    }

    public static File sidecarFor(File mediaFile) {
        return new File(mediaFile.getAbsolutePath() + SIDECAR_SUFFIX);
    }

    public static MessageDigest newSha1Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 algorithm unavailable", e);
        }
    }

    /** Streams {@code file} into a fresh SHA-1 digest and returns the digest bytes. */
    public static byte[] sha1Of(File file) throws IOException {
        MessageDigest digest = newSha1Digest();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    /**
     * Writes {@code input} to {@code file} (truncating any existing content) while updating a
     * SHA-1 digest over the exact bytes written. Returns the digest bytes.
     * The caller remains responsible for closing {@code input}.
     */
    public static byte[] writeStreamToFile(File file, InputStream input) throws IOException {
        MessageDigest digest = newSha1Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        try (FileOutputStream output = new FileOutputStream(file)) {
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            sb.append(HEX_DIGITS[v >>> 4]);
            sb.append(HEX_DIGITS[v & 0x0F]);
        }
        return sb.toString();
    }

    /** Standard Base64 encoding (no line wrapping) of the given bytes. */
    public static String toBase64(byte[] bytes) {
        StringBuilder sb = new StringBuilder((bytes.length + 2) / 3 * 4);
        int i = 0;
        while (i < bytes.length) {
            int b0 = bytes[i++] & 0xFF;
            int b1 = i < bytes.length ? bytes[i++] & 0xFF : 0;
            int b2 = i < bytes.length ? bytes[i++] & 0xFF : 0;
            int triple = (b0 << 16) | (b1 << 8) | b2;
            sb.append(BASE64_ALPHABET.charAt((triple >> 18) & 0x3F));
            sb.append(BASE64_ALPHABET.charAt((triple >> 12) & 0x3F));
            sb.append(BASE64_ALPHABET.charAt((triple >> 6) & 0x3F));
            sb.append(BASE64_ALPHABET.charAt(triple & 0x3F));
        }
        int remainder = bytes.length % 3;
        if (remainder == 1) {
            sb.setCharAt(sb.length() - 2, '=');
            sb.setCharAt(sb.length() - 1, '=');
        } else if (remainder == 2) {
            sb.setCharAt(sb.length() - 1, '=');
        }
        return sb.toString();
    }

    /**
     * True when {@code digest} equals {@code expected} in either hex or Base64 form
     * (case-insensitive). The Immich API returns Base64; hex is accepted so a format
     * surprise can never turn into a re-download loop.
     */
    public static boolean digestMatches(byte[] digest, String expected) {
        if (digest == null || expected == null) {
            return false;
        }
        String trimmed = expected.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String hex = toHex(digest);
        return hex.equalsIgnoreCase(trimmed)
                || toBase64(digest).equalsIgnoreCase(trimmed);
    }

    /**
     * Decides whether a cached original can be played:
     * <ol>
     *   <li>non-empty file;</li>
     *   <li>if the server-reported checksum is present it is authoritative (a matching
     *       SHA-1 implies a matching size, so no separate size gate);</li>
     *   <li>otherwise: matching reported size, then matching the hex sidecar written at
     *       download time when present.</li>
     * </ol>
     * A file with no usable reference value (legacy cache, no sidecar) is trusted.
     */
    public static boolean isCacheFileUsable(File file, String expectedChecksum, Long expectedBytes) {
        if (file == null || !file.isFile() || file.length() == 0L) {
            return false;
        }
        if (expectedChecksum != null && !expectedChecksum.trim().isEmpty()) {
            try {
                return digestMatches(sha1Of(file), expectedChecksum);
            } catch (IOException e) {
                return false;
            }
        }
        if (expectedBytes != null && expectedBytes > 0L && file.length() != expectedBytes) {
            return false;
        }
        try {
            String stored = readSidecar(file);
            if (stored != null) {
                return stored.equalsIgnoreCase(toHex(sha1Of(file)));
            }
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Validates freshly written bytes after a download. Fallback streams are always
     * re-fetched, so nothing is validated for them. For originals the server checksum
     * is authoritative when present; otherwise the reported size is the signal.
     */
    public static boolean isDownloadValid(File file, byte[] digest, boolean fallback,
                                          String expectedChecksum, Long expectedBytes) {
        if (fallback) {
            return true;
        }
        if (expectedChecksum != null && !expectedChecksum.trim().isEmpty()) {
            return digestMatches(digest, expectedChecksum);
        }
        if (expectedBytes != null && expectedBytes > 0L && file.length() != expectedBytes) {
            return false;
        }
        return true;
    }

    public static void writeSidecar(File mediaFile, String sha1Hex) throws IOException {
        File sidecar = sidecarFor(mediaFile);
        OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(sidecar), Charset.forName(UTF_8));
        try {
            writer.write(sha1Hex);
        } finally {
            writer.close();
        }
    }

    /** @return the trimmed hex string stored next to {@code mediaFile}, or null when absent. */
    public static String readSidecar(File mediaFile) throws IOException {
        File sidecar = sidecarFor(mediaFile);
        if (!sidecar.isFile()) {
            return null;
        }
        try (FileInputStream input = new FileInputStream(sidecar)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), Charset.forName(UTF_8)).trim();
        }
    }

    public static void deleteSidecar(File mediaFile) {
        if (mediaFile == null) {
            return;
        }
        File sidecar = sidecarFor(mediaFile);
        if (sidecar.exists() && !sidecar.delete()) {
            // Best effort: an orphaned 40-byte sidecar is harmless.
        }
    }
}
