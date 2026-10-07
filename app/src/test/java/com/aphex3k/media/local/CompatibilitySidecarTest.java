package com.aphex3k.media.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aphex3k.media.MediaType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;

/**
 * JVM tests for {@link CompatibilitySidecar}: naming, write-once semantics, and the
 * report/JSON shape.
 */
public class CompatibilitySidecarTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File asset;

    @Before
    public void setUp() throws Exception {
        asset = new File(temp.getRoot(), "clip.mp4");
        FileWriter w = new FileWriter(asset);
        try {
            w.write("data");
        } finally {
            w.close();
        }
    }

    @Test
    public void sidecarForAppendsSuffix() {
        assertEquals("clip.mp4.incompat.txt", CompatibilitySidecar.sidecarFor(asset).getName());
    }

    @Test
    public void sidecarForIsDistinctForDifferentExtensions() {
        File a = new File(temp.getRoot(), "photo.jpg");
        File b = new File(temp.getRoot(), "photo.jpeg");
        assertFalse(CompatibilitySidecar.sidecarFor(a).equals(CompatibilitySidecar.sidecarFor(b)));
    }

    @Test
    public void existsIsFalseBeforeWrite() {
        assertFalse(CompatibilitySidecar.exists(asset));
    }

    @Test
    public void writeIfAbsentWritesReport() throws Exception {
        assertTrue(CompatibilitySidecar.writeIfAbsent(asset, MediaType.VIDEO, "extension",
                "unsupported video codec .h265 cannot be decoded on this device",
                4, 0, 0, "local"));
        File sidecar = CompatibilitySidecar.sidecarFor(asset);
        assertTrue(sidecar.isFile());
        String content = read(sidecar);
        assertTrue(content.contains("unsupported video codec .h265 cannot be decoded on this device"));
        assertTrue(content.contains("How to fix"));
        assertTrue(content.contains("clip.mp4.incompat.txt"));
        JsonObject json = extractJson(content);
        assertEquals("clip.mp4", json.get("asset").getAsString());
        assertEquals("local", json.get("backendId").getAsString());
        assertEquals("video", json.get("mediaType").getAsString());
        assertEquals("extension", json.get("check").getAsString());
        assertEquals("unsupported video codec .h265 cannot be decoded on this device",
                json.get("reason").getAsString());
        assertEquals(4L, json.get("sizeBytes").getAsLong());
        assertTrue(json.has("timestampUtc"));
        assertTrue(json.has("model"));
        assertTrue(json.has("android"));
        assertTrue(json.has("version"));
    }

    @Test
    public void writeIfAbsentNeverOverwritesExisting() throws Exception {
        File sidecar = CompatibilitySidecar.sidecarFor(asset);
        FileWriter w = new FileWriter(sidecar);
        try {
            w.write("user-edited report");
        } finally {
            w.close();
        }
        assertFalse(CompatibilitySidecar.writeIfAbsent(asset, MediaType.VIDEO, "content",
                "some other reason", 99, 0, 0, "local"));
        assertEquals("user-edited report", read(sidecar));
    }

    @Test
    public void jsonBlockParsesForHostileFileNames() throws Exception {
        File hostile = new File(temp.getRoot(), "a\"b\\c.jpg");
        assertTrue(hostile.createNewFile());
        assertTrue(CompatibilitySidecar.writeIfAbsent(hostile, MediaType.IMAGE, "content",
                "unsupported image container heic detected in file contents (HEIF/AV1)",
                16, 800, 600, "local"));
        JsonObject json = extractJson(read(CompatibilitySidecar.sidecarFor(hostile)));
        assertEquals("a\"b\\c.jpg", json.get("asset").getAsString());
        assertEquals("content", json.get("check").getAsString());
        assertEquals("image", json.get("mediaType").getAsString());
        assertEquals(800, json.get("width").getAsInt());
        assertEquals(600, json.get("height").getAsInt());
    }

    private static String read(File f) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
    }

    /** Extracts the JSON object that follows the "Developer information (JSON):" marker. */
    private static JsonObject extractJson(String content) {
        int marker = content.indexOf("Developer information (JSON):");
        assertTrue(marker >= 0);
        int open = content.indexOf('{', marker);
        assertTrue(open > marker);
        return JsonParser.parseString(content.substring(open, content.lastIndexOf('}') + 1))
                .getAsJsonObject();
    }
}
