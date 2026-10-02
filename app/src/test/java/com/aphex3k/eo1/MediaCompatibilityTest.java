package com.aphex3k.eo1;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.media.MediaType;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * JVM tests of the device codec gate: the name-based pre-download check and the
 * {@code ftyp} byte check for files that already exist locally.
 */
public class MediaCompatibilityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void rejectsHeifAndAvifImageExtensions() {
        assertIncompatible(MediaType.IMAGE, "photo.heic");
        assertIncompatible(MediaType.IMAGE, "photo.HEIF");
        assertIncompatible(MediaType.IMAGE, "photo.avif");
    }

    @Test
    public void rejectsHevcAndAv1VideoExtensions() {
        assertIncompatible(MediaType.VIDEO, "clip.h265");
        assertIncompatible(MediaType.VIDEO, "clip.hevc");
        assertIncompatible(MediaType.VIDEO, "clip.av1");
    }

    @Test
    public void acceptsContainerExtensionsAndCommonFormats() {
        assertCompatible(MediaType.VIDEO, "clip.mp4");
        assertCompatible(MediaType.VIDEO, "clip.mov");
        assertCompatible(MediaType.VIDEO, "clip.m4v");
        assertCompatible(MediaType.IMAGE, "photo.jpg");
        assertCompatible(MediaType.IMAGE, "photo.webp");
    }

    @Test
    public void extensionMustMatchMediaType() {
        assertCompatible(MediaType.VIDEO, "photo.heic");
        assertCompatible(MediaType.IMAGE, "clip.h265");
    }

    @Test
    public void fallsBackToPathWhenNameHasNoExtension() {
        assertIncompatible(MediaType.IMAGE, "photo", "/photos/photo.heic");
        assertCompatible(MediaType.IMAGE, "photo", "/photos/photo.jpg");
        // The name's extension wins over the path's.
        assertCompatible(MediaType.IMAGE, "photo.jpg", "/photos/photo.heic");
    }

    @Test
    public void missingNamesPass() {
        assertNull(MediaCompatibility.incompatibleReason(MediaType.IMAGE, null, null));
        assertNull(MediaCompatibility.incompatibleReason(MediaType.VIDEO, "no-ext", null));
        assertNull(MediaCompatibility.incompatibleReason(null, "photo.heic", null));
    }

    @Test
    public void rejectsHeifFtypMajorBrand() throws Exception {
        assertBytesIncompatible(ftyp("heic"));
    }

    @Test
    public void rejectsHeifBrandsInCompatibleList() throws Exception {
        assertBytesIncompatible(ftyp("isom", "iso2", "heif"));
        assertBytesIncompatible(ftyp("mif1"));
        assertBytesIncompatible(ftyp("avif"));
    }

    @Test
    public void acceptsNonHeifFtypContent() throws Exception {
        assertBytesCompatible(ftyp("isom", "iso2", "avc1"));
        assertBytesCompatible(ftyp("qt  "));
    }

    @Test
    public void acceptsNonIsoAndUndersizedBytes() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                'J', 'F', 'I', 'F', 0, 0};
        assertBytesCompatible(jpeg);
        assertBytesCompatible("short".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void skipsByteCheckForVideoAndNull() throws Exception {
        File heic = writeBytes("x.jpg", ftyp("heic"));
        assertNull(MediaCompatibility.incompatibleReasonForFile(MediaType.VIDEO, heic));
        assertNull(MediaCompatibility.incompatibleReasonForFile(MediaType.IMAGE, null));
        assertNull(MediaCompatibility.incompatibleReasonForFile(
                MediaType.IMAGE, new File(tmp.getRoot(), "missing.jpg")));
    }

    private static void assertIncompatible(MediaType type, String name) {
        assertIncompatible(type, name, null);
    }

    private static void assertIncompatible(MediaType type, String name, String path) {
        String reason = MediaCompatibility.incompatibleReason(type, name, path);
        assertTrue("expected " + name + " (" + path + ") to be incompatible", reason != null);
    }

    private static void assertCompatible(MediaType type, String name) {
        assertCompatible(type, name, null);
    }

    private static void assertCompatible(MediaType type, String name, String path) {
        assertNull(type + " " + name + " should pass the name check",
                MediaCompatibility.incompatibleReason(type, name, path));
    }

    private File writeBytes(String name, byte[] content) throws Exception {
        File f = new File(tmp.getRoot(), name);
        Files.write(f.toPath(), content);
        return f;
    }

    private void assertBytesIncompatible(byte[] content) throws Exception {
        assertTrue(MediaCompatibility.incompatibleReasonForFile(
                MediaType.IMAGE, writeBytes("sample.jpg", content)) != null);
    }

    private void assertBytesCompatible(byte[] content) throws Exception {
        assertNull(MediaCompatibility.incompatibleReasonForFile(
                MediaType.IMAGE, writeBytes("sample.jpg", content)));
    }

    /** ISO 14496-12 {@code ftyp} box: size + type + major brand + minor + compatible brands. */
    private static byte[] ftyp(String major, String... compat) {
        int size = 16 + compat.length * 4;
        byte[] b = new byte[size];
        b[0] = (byte) (size >> 24);
        b[1] = (byte) (size >> 16);
        b[2] = (byte) (size >> 8);
        b[3] = (byte) size;
        put(b, 4, "ftyp");
        put(b, 8, major);
        put(b, 12, "0000");
        for (int i = 0; i < compat.length; i++) {
            put(b, 16 + i * 4, compat[i]);
        }
        return b;
    }

    private static void put(byte[] b, int off, String fourCc) {
        System.arraycopy(fourCc.getBytes(StandardCharsets.US_ASCII), 0, b, off, 4);
    }
}
