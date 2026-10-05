package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * JVM tests for VideoHeaderProbe using synthetic in-memory ISOBMFF boxes:
 * tkhd v0/v1 matrices (identity/90/180/270), the ISO 23001-8 {@code rot } override,
 * a moov at the file tail, missing/truncated moov, and non-ISOBMFF extensions.
 */
public class VideoHeaderProbeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // --- box builders ---------------------------------------------------------------

    private static byte[] u32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] u16(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }

    /** Signed 16.16 fixed-point: integer part in the high 16 bits. */
    private static byte[] u1616(int value) {
        int raw = value << 16;
        return u32(raw);
    }

    private static byte[] cat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static byte[] box(String type, byte[]... payload) {
        byte[] body = cat(payload);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        return cat(u32(8 + body.length), typeBytes, body);
    }

    /** 9x16.16 matrix with the given m11, m12, m21, m22; m33 = 1, everything else 0. */
    private static byte[] matrix(int m11, int m12, int m21, int m22) {
        byte[] m = new byte[36];
        System.arraycopy(u1616(m11), 0, m, 0, 4);
        System.arraycopy(u1616(m12), 0, m, 4, 4);
        System.arraycopy(u1616(m21), 0, m, 12, 4);
        System.arraycopy(u1616(m22), 0, m, 16, 4);
        System.arraycopy(u1616(1), 0, m, 32, 4);
        return m;
    }

    private static byte[] tkhdV0(int m11, int m12, int m21, int m22) {
        byte[] payload = cat(
                u32(0),        // creation_time
                u32(0),        // modification_time
                u32(1),        // track_id
                u32(0),        // reserved
                u32(0),        // duration
                new byte[8],   // reserved
                u16(0), u16(0), u16(0), u16(0), // layer, alt_group, volume, reserved
                matrix(m11, m12, m21, m22),
                u32(1920),     // width
                u32(1080));    // height
        return box("tkhd", cat(new byte[]{0, 0, 0, 0}, payload));
    }

    private static byte[] tkhdV1(int m11, int m12, int m21, int m22) {
        byte[] payload = cat(
                u32(0), u32(0), // creation_time (8)
                u32(0), u32(0), // modification_time (8)
                u32(1),         // track_id
                u32(0),         // reserved
                u32(0), u32(0), // duration (8)
                new byte[8],    // reserved
                u16(0), u16(0), u16(0), u16(0),
                matrix(m11, m12, m21, m22),
                u32(0), u32(1920), // width (8)
                u32(0), u32(1080)); // height (8)
        return box("tkhd", cat(new byte[]{1, 0, 0, 0}, payload));
    }

    private static byte[] stsdAv1(int width, int height) {
        byte[] entry = cat(
                new byte[6],                 // SampleEntry pre_defined
                u16(1),                      // data_reference_index
                new byte[16],                // VisualSampleEntry pre_defined(2) + reserved(2) + reserved[3](12)
                u16(width), u16(height),
                u32(0x00480000), u32(0),     // horres, vertres
                new byte[4],                 // reserved
                u16(1),                      // num_sequences
                new byte[4]);                // codec payload padding
        byte[] entryBox = cat(u32(8 + entry.length), "avc1".getBytes(StandardCharsets.US_ASCII), entry);
        return box("stsd", cat(new byte[]{0, 0, 0, 0}, u32(1), entryBox));
    }

    private static byte[] trak(byte[] tkhd, int width, int height) {
        byte[] stbl = box("stbl", stsdAv1(width, height));
        byte[] minf = box("minf", stbl);
        byte[] mdia = box("mdia", minf);
        return box("trak", tkhd, mdia);
    }

    private static byte[] rotBox(int degrees) {
        return box("rot ", u32(degrees * 65536));
    }

    private File writeFile(String name, byte[] data) throws Exception {
        File f = new File(tmp.getRoot(), name);
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        return f;
    }

    // --- tests ----------------------------------------------------------------------

    @Test
    public void identityTkhdV0ReportsNoRotationAndCodedDims() throws Exception {
        byte[] moov = box("moov", trak(tkhdV0(1, 0, 0, 1), 1920, 1080));
        File f = new File(tmp.getRoot(), "a.mp4");
        byte[] data = cat(box("ftyp", u32(512), "isom".getBytes(StandardCharsets.US_ASCII)), moov);
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(f);
        assertEquals(0, p.rotationDeg);
        assertEquals(1920, p.codedWidth);
        assertEquals(1080, p.codedHeight);
    }

    @Test
    public void tkhdV0RotationMatrices() throws Exception {
        assertEquals(90, VideoHeaderProbe.probe(writeFile("r90.mp4", moovWithTkhd(tkhdV0(0, -1, 1, 0)))).rotationDeg);
        assertEquals(180, VideoHeaderProbe.probe(writeFile("r180.mp4", moovWithTkhd(tkhdV0(-1, 0, 0, -1)))).rotationDeg);
        assertEquals(270, VideoHeaderProbe.probe(writeFile("r270.mp4", moovWithTkhd(tkhdV0(0, 1, -1, 0)))).rotationDeg);
    }

    @Test
    public void tkhdV1RotationMatrix() throws Exception {
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(writeFile("v1.mp4", moovWithTkhd(tkhdV1(0, -1, 1, 0))));
        assertEquals(90, p.rotationDeg);
        assertEquals(1920, p.codedWidth);
        assertEquals(1080, p.codedHeight);
    }

    @Test
    public void rotBoxOverridesTkhd() throws Exception {
        byte[] moov = box("moov", rotBox(180), trak(tkhdV0(1, 0, 0, 1), 1920, 1080));
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(writeFile("rotbox.mp4", moov));
        assertEquals(180, p.rotationDeg);
    }

    @Test
    public void rotBoxNegativeValueNormalizes() throws Exception {
        byte[] moov = box("moov", rotBox(-90), trak(tkhdV0(1, 0, 0, 1), 1920, 1080));
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(writeFile("rotneg.mp4", moov));
        assertEquals(270, p.rotationDeg);
    }

    @Test
    public void moovAtTailIsFound() throws Exception {
        byte[] mdat = box("mdat", new byte[2 * 1024 * 1024 + 512 * 1024]); // pushes moov past the 2 MB head window
        byte[] moov = box("moov", trak(tkhdV0(0, -1, 1, 0), 640, 480));
        byte[] data = cat(box("ftyp", u32(512), "isom".getBytes(StandardCharsets.US_ASCII)), mdat, moov);
        File f = new File(tmp.getRoot(), "tail.mp4");
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(f);
        assertEquals(90, p.rotationDeg);
        assertEquals(640, p.codedWidth);
        assertEquals(480, p.codedHeight);
    }

    @Test
    public void missingMoovIsUnknown() throws Exception {
        byte[] data = cat(
                box("ftyp", u32(512), "isom".getBytes(StandardCharsets.US_ASCII)),
                box("mdat", new byte[1024 * 1024]));
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(writeFile("nomoov.mp4", data));
        assertEquals(-1, p.rotationDeg);
        assertEquals(0, p.codedWidth);
        assertEquals(0, p.codedHeight);
    }

    @Test
    public void truncatedMoovIsUnknown() throws Exception {
        byte[] full = cat(
                box("ftyp", u32(512), "isom".getBytes(StandardCharsets.US_ASCII)),
                box("moov", trak(tkhdV0(0, -1, 1, 0), 1920, 1080)));
        byte[] truncated = new byte[full.length - 40];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(writeFile("trunc.mp4", truncated));
        assertEquals(-1, p.rotationDeg);
        assertEquals(0, p.codedWidth);
    }

    @Test
    public void nonIsobmffExtensionIsNoOp() throws Exception {
        byte[] data = cat(
                box("ftyp", u32(512), "isom".getBytes(StandardCharsets.US_ASCII)),
                box("moov", trak(tkhdV0(0, -1, 1, 0), 1920, 1080)));
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(writeFile("clip.ts", data));
        assertEquals(0, p.rotationDeg);
        assertEquals(0, p.codedWidth);
        assertEquals(0, p.codedHeight);
    }

    @Test
    public void missingFileIsUnknown() {
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(new File(tmp.getRoot(), "absent.mp4"));
        assertEquals(-1, p.rotationDeg);
        assertEquals(0, p.codedWidth);
        assertEquals(0, p.codedHeight);
    }

    // --- helpers ----------------------------------------------------------------------

    private static byte[] moovWithTkhd(byte[] tkhd) {
        return box("moov", trak(tkhd, 1920, 1080));
    }
}
