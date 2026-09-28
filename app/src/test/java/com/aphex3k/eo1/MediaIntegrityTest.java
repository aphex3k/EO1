package com.aphex3k.eo1;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * JVM tests for {@link MediaIntegrity}. No Android framework classes may be used
 * (the local android.jar is stripped; Log in particular is unavailable).
 */
public class MediaIntegrityTest {

    private static final String ABC_SHA1_HEX = "a9993e364706816aba3e25717850c26c9cd0d89d";
    private static final String ABC_SHA1_BASE64 = "qZk+NkcGgWq6PiVxeFDCbJzQ2J0=";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File writeContent(String name, byte[] content) throws IOException {
        File file = new File(folder.getRoot(), name);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content);
        } finally {
            out.close();
        }
        return file;
    }

    @Test
    public void toHexMatchesKnownSha1Vector() throws Exception {
        byte[] digest = MediaIntegrity.sha1Of(writeContent("abc.txt", "abc".getBytes("UTF-8")));
        assertEquals(ABC_SHA1_HEX, MediaIntegrity.toHex(digest));
    }

    @Test
    public void toBase64MatchesKnownSha1Vector() throws Exception {
        byte[] digest = MediaIntegrity.sha1Of(writeContent("abc.txt", "abc".getBytes("UTF-8")));
        assertEquals(ABC_SHA1_BASE64, MediaIntegrity.toBase64(digest));
    }

    @Test
    public void toBase64PadsCorrectly() {
        assertEquals("", MediaIntegrity.toBase64(new byte[0]));
        assertEquals("AA==", MediaIntegrity.toBase64(new byte[]{0}));
        assertEquals("AQA=", MediaIntegrity.toBase64(new byte[]{1, 0}));
        assertEquals("AAEC", MediaIntegrity.toBase64(new byte[]{0, 1, 2}));
    }

    @Test
    public void digestMatchesAcceptsBase64HexAndCaseVariants() throws Exception {
        byte[] digest = MediaIntegrity.sha1Of(writeContent("abc.txt", "abc".getBytes("UTF-8")));
        assertTrue(MediaIntegrity.digestMatches(digest, ABC_SHA1_BASE64));
        assertTrue(MediaIntegrity.digestMatches(digest, ABC_SHA1_HEX));
        assertTrue(MediaIntegrity.digestMatches(digest, ABC_SHA1_HEX.toUpperCase()));
        assertTrue(MediaIntegrity.digestMatches(digest, "  " + ABC_SHA1_BASE64 + " "));
    }

    @Test
    public void digestMatchesRejectsWrongOrNullValues() throws Exception {
        byte[] digest = MediaIntegrity.sha1Of(writeContent("abc.txt", "abc".getBytes("UTF-8")));
        assertFalse(MediaIntegrity.digestMatches(digest, "deadbeef"));
        assertFalse(MediaIntegrity.digestMatches(digest, ""));
        assertFalse(MediaIntegrity.digestMatches(digest, "   "));
        assertFalse(MediaIntegrity.digestMatches(digest, null));
        assertFalse(MediaIntegrity.digestMatches(null, ABC_SHA1_BASE64));
    }

    @Test
    public void sidecarForAppendsSuffix() {
        File media = new File("/cache/asset-uuid.mp4");
        assertEquals("/cache/asset-uuid.mp4" + MediaIntegrity.SIDECAR_SUFFIX,
                MediaIntegrity.sidecarFor(media).getPath());
    }

    @Test
    public void writeAndReadSidecarRoundTrip() throws Exception {
        File media = writeContent("media.mp4", new byte[]{1, 2, 3});
        MediaIntegrity.writeSidecar(media, ABC_SHA1_HEX);
        assertEquals(ABC_SHA1_HEX, MediaIntegrity.readSidecar(media));
    }

    @Test
    public void readSidecarIsNullOrTrimsWhitespaceWhenAbsentOrPadded() throws Exception {
        File media = writeContent("media.mp4", new byte[]{1, 2, 3});
        assertNull(MediaIntegrity.readSidecar(media));

        File sidecar = MediaIntegrity.sidecarFor(media);
        OutputStream out = new FileOutputStream(sidecar);
        try {
            out.write(("  " + ABC_SHA1_HEX + " \n").getBytes("UTF-8"));
        } finally {
            out.close();
        }
        assertEquals(ABC_SHA1_HEX, MediaIntegrity.readSidecar(media));
    }

    @Test
    public void writeStreamToFileWritesBytesAndReturnsDigest() throws Exception {
        File target = new File(folder.getRoot(), "out.mp4");
        byte[] payload = "hello world".getBytes("UTF-8");
        byte[] digest = MediaIntegrity.writeStreamToFile(target, new ByteArrayInputStream(payload));

        assertArrayEquals(payload, readAll(target));
        assertEquals(MediaIntegrity.toHex(MediaIntegrity.sha1Of(target)), MediaIntegrity.toHex(digest));
    }

    @Test
    public void writeStreamToFileTruncatesExistingContent() throws Exception {
        File target = writeContent("out.mp4", new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
        byte[] payload = "xy".getBytes("UTF-8");
        MediaIntegrity.writeStreamToFile(target, new ByteArrayInputStream(payload));
        assertArrayEquals(payload, readAll(target));
    }

    @Test
    public void isCacheFileUsableTrustsMatchingServerChecksum() throws Exception {
        File media = writeContent("media.mp4", "video bytes".getBytes("UTF-8"));
        byte[] digest = MediaIntegrity.sha1Of(media);
        assertTrue(MediaIntegrity.isCacheFileUsable(media, MediaIntegrity.toBase64(digest), null));
        // Checksum is authoritative: a wrong size report must not reject a matching file.
        assertTrue(MediaIntegrity.isCacheFileUsable(media, MediaIntegrity.toBase64(digest), 999999L));
    }

    @Test
    public void isCacheFileUsableRejectsMismatchedChecksum() throws Exception {
        File media = writeContent("media.mp4", "video bytes".getBytes("UTF-8"));
        assertFalse(MediaIntegrity.isCacheFileUsable(media, ABC_SHA1_BASE64, null));
        // Checksum beats sidecar: a matching sidecar does not save a checksum mismatch.
        MediaIntegrity.writeSidecar(media, MediaIntegrity.toHex(MediaIntegrity.sha1Of(media)));
        assertFalse(MediaIntegrity.isCacheFileUsable(media, ABC_SHA1_BASE64, null));
    }

    @Test
    public void isCacheFileUsableFallsBackToSizeThenSidecarWithoutChecksum() throws Exception {
        File media = writeContent("media.mp4", "some bytes".getBytes("UTF-8"));
        long size = media.length();

        // No reference values at all: legacy file is trusted.
        assertTrue(MediaIntegrity.isCacheFileUsable(media, null, null));
        // A blank checksum is treated as absent, so the matching size decides.
        assertTrue(MediaIntegrity.isCacheFileUsable(media, "  ", size));

        // Reported size mismatch rejects before any hashing.
        assertFalse(MediaIntegrity.isCacheFileUsable(media, null, size + 1));

        // Matching sidecar is honored when size matches.
        MediaIntegrity.writeSidecar(media, MediaIntegrity.toHex(MediaIntegrity.sha1Of(media)));
        assertTrue(MediaIntegrity.isCacheFileUsable(media, null, size));

        // Mismatched sidecar rejects.
        MediaIntegrity.writeSidecar(media, ABC_SHA1_HEX);
        assertFalse(MediaIntegrity.isCacheFileUsable(media, null, size));
    }

    @Test
    public void isCacheFileUsableRejectsMissingOrEmptyFiles() throws Exception {
        assertFalse(MediaIntegrity.isCacheFileUsable(null, null, null));
        File missing = new File(folder.getRoot(), "missing.mp4");
        assertFalse(MediaIntegrity.isCacheFileUsable(missing, null, null));
        File empty = writeContent("empty.mp4", new byte[0]);
        assertFalse(MediaIntegrity.isCacheFileUsable(empty, ABC_SHA1_BASE64, 0L));
    }

    @Test
    public void isDownloadValidSkipsValidationForFallbackStreams() throws Exception {
        File file = writeContent("fallback.mp4", new byte[]{1});
        assertTrue(MediaIntegrity.isDownloadValid(file, new byte[20], true, ABC_SHA1_BASE64, 12345L));
    }

    @Test
    public void isDownloadValidUsesChecksumAsAuthorityOtherwiseSize() throws Exception {
        File media = writeContent("media.mp4", "payload".getBytes("UTF-8"));
        byte[] digest = MediaIntegrity.sha1Of(media);

        // Matching checksum passes even if the size metadata is wrong.
        assertTrue(MediaIntegrity.isDownloadValid(media, digest, false, MediaIntegrity.toBase64(digest), 424242L));
        // Mismatching checksum fails even when the size matches.
        assertFalse(MediaIntegrity.isDownloadValid(media, digest, false, ABC_SHA1_BASE64, media.length()));
        // No checksum: size mismatch fails, size match or absent size passes.
        assertFalse(MediaIntegrity.isDownloadValid(media, digest, false, null, media.length() + 1));
        assertTrue(MediaIntegrity.isDownloadValid(media, digest, false, null, media.length()));
        assertTrue(MediaIntegrity.isDownloadValid(media, digest, false, null, null));
    }

    @Test
    public void deleteSidecarRemovesOnlyTheSidecarAndIsSafeOnNull() throws Exception {
        File media = writeContent("media.mp4", new byte[]{1, 2});
        MediaIntegrity.writeSidecar(media, ABC_SHA1_HEX);
        File sidecar = MediaIntegrity.sidecarFor(media);
        assertTrue(sidecar.exists());

        MediaIntegrity.deleteSidecar(null);
        MediaIntegrity.deleteSidecar(new File(folder.getRoot(), "absent.mp4"));

        MediaIntegrity.deleteSidecar(media);
        assertFalse(sidecar.exists());
        assertTrue(media.exists());
    }

    private static byte[] readAll(File file) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        } finally {
            in.close();
        }
        return buffer.toByteArray();
    }
}
