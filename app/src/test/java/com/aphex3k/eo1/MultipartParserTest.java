package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

public class MultipartParserTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long CAP = 512L * 1024 * 1024;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File outDir;

    @Before
    public void setUp() throws IOException {
        outDir = temp.newFolder("out");
    }

    @Test
    public void parsesSingleFilePart() throws Exception {
        String boundary = "XYZ123";
        byte[] body = multipart(boundary, part("file", "a.jpg", "image/jpeg", "hello world".getBytes(UTF8)));

        List<MultipartParser.Part> parts = MultipartParser.parse(new ByteArrayInputStream(body), boundary, outDir, CAP);
        assertEquals(1, parts.size());
        MultipartParser.Part p = parts.get(0);
        assertTrue(p.isFile());
        assertEquals("a.jpg", p.filename);
        assertEquals("file", p.name);
        assertEquals(11, p.size);
        assertEquals("hello world", new String(readBytes(p.file), UTF8));
    }

    @Test
    public void parsesMultipleFileParts() throws Exception {
        String boundary = "BOUND42";
        byte[] a = "alpha".getBytes(UTF8);
        byte[] b = "bravo bravo".getBytes(UTF8);
        byte[] body = multipart(boundary,
                part("f1", "one.bin", "application/octet-stream", a),
                part("f2", "two.bin", "application/octet-stream", b));

        List<MultipartParser.Part> parts = MultipartParser.parse(new ByteArrayInputStream(body), boundary, outDir, CAP);
        assertEquals(2, parts.size());
        assertEquals("one.bin", parts.get(0).filename);
        assertEquals("alpha", new String(readBytes(parts.get(0).file), UTF8));
        assertEquals("two.bin", parts.get(1).filename);
        assertEquals("bravo bravo", new String(readBytes(parts.get(1).file), UTF8));
    }

    @Test
    public void parsesTextPart() throws Exception {
        String boundary = "T1";
        byte[] body = multipart(boundary, textPart("greeting", "hello there"));

        List<MultipartParser.Part> parts = MultipartParser.parse(new ByteArrayInputStream(body), boundary, outDir, CAP);
        assertEquals(1, parts.size());
        MultipartParser.Part p = parts.get(0);
        assertFalse(p.isFile());
        assertEquals("hello there", p.text);
        assertEquals("greeting", p.name);
    }

    @Test
    public void parsesEmptyFileBody() throws Exception {
        String boundary = "E1";
        byte[] body = multipart(boundary, part("f", "empty.txt", "text/plain", new byte[0]));

        List<MultipartParser.Part> parts = MultipartParser.parse(new ByteArrayInputStream(body), boundary, outDir, CAP);
        assertEquals(1, parts.size());
        assertEquals(0, parts.get(0).size);
        assertEquals(0, readBytes(parts.get(0).file).length);
    }

    @Test
    public void largeFileSurvivesChunkBoundaries() throws Exception {
        String boundary = "BIG";
        // 100_000 bytes with a recognizable pattern; larger than the 8 KB chunk so the boundary
        // detection must span multiple reads.
        byte[] data = new byte[100_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 & 0xff);
        }
        byte[] body = multipart(boundary, part("f", "big.bin", "application/octet-stream", data));

        List<MultipartParser.Part> parts = MultipartParser.parse(new ByteArrayInputStream(body), boundary, outDir, CAP);
        assertEquals(1, parts.size());
        assertEquals(data.length, parts.get(0).size);
        byte[] got = readBytes(parts.get(0).file);
        if (!java.util.Arrays.equals(data, got)) {
            fail("large file bytes differ");
        }
    }

    @Test
    public void binarySafeBody() throws Exception {
        String boundary = "BIN";
        byte[] data = new byte[4096];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        byte[] body = multipart(boundary, part("f", "bin.dat", "application/octet-stream", data));

        List<MultipartParser.Part> parts = MultipartParser.parse(new ByteArrayInputStream(body), boundary, outDir, CAP);
        byte[] got = readBytes(parts.get(0).file);
        if (!java.util.Arrays.equals(data, got)) {
            fail("binary bytes differ");
        }
    }

    @Test
    public void overCapThrows() throws Exception {
        String boundary = "CAP";
        byte[] body = multipart(boundary, part("f", "big.txt", "text/plain", "hello world".getBytes(UTF8)));
        try {
            MultipartParser.parse(new ByteArrayInputStream(body), boundary, outDir, 5L);
            fail("expected MultipartException");
        } catch (MultipartParser.MultipartException expected) {
            // expected
        }
    }

    @Test
    public void boundaryFromContentType() {
        assertEquals("abc", MultipartParser.boundaryFromContentType("multipart/form-data; boundary=abc"));
        assertEquals("xyz", MultipartParser.boundaryFromContentType("multipart/form-data; boundary=\"xyz\""));
        assertEquals("a-b_c", MultipartParser.boundaryFromContentType(
                "multipart/form-data; boundary=a-b_c; charset=UTF-8"));
        assertNull(MultipartParser.boundaryFromContentType("multipart/form-data"));
        assertNull(MultipartParser.boundaryFromContentType(null));
    }

    @Test
    public void missingBoundaryThrows() {
        try {
            MultipartParser.parse(new ByteArrayInputStream(new byte[0]), null, outDir, CAP);
            fail("expected MultipartException");
        } catch (MultipartParser.MultipartException expected) {
            // expected
        } catch (IOException e) {
            fail("wrong exception: " + e);
        }
    }

    // --- helpers ---

    private static final class Spec {
        String name;
        String filename;
        String contentType;
        byte[] data;

        Spec(String name, String filename, String contentType, byte[] data) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.data = data;
        }
    }

    private static Spec part(String name, String filename, String contentType, byte[] data) {
        return new Spec(name, filename, contentType, data);
    }

    private static Spec textPart(String name, String text) {
        return new Spec(name, null, "text/plain", text.getBytes(UTF8));
    }

    private static byte[] multipart(String boundary, Spec... specs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Spec s : specs) {
            write(out, "--" + boundary + "\r\n");
            write(out, "Content-Disposition: form-data; name=\"" + s.name + "\"");
            if (s.filename != null) {
                write(out, "; filename=\"" + s.filename + "\"");
            }
            write(out, "\r\n");
            write(out, "Content-Type: " + s.contentType + "\r\n");
            write(out, "\r\n");
            out.write(s.data, 0, s.data.length);
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(UTF8);
        out.write(b, 0, b.length);
    }

    private static byte[] readBytes(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
