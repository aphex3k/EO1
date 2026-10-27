package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * JVM tests for VideoHeaderProbe using synthetic in-memory ISOBMFF boxes:
 * tkhd v0/v1 matrices (identity/90/180/270), the ISO 23001-8 {@code rot } override,
 * a moov at the file tail, missing/truncated moov, non-ISOBMFF extensions, and the mdhd/stsz
 * sample table behind {@link VideoHeaderProbe.StreamStats}.
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

    private static byte[] stsdEntry(String fourCc, int width, int height) {
        byte[] entry = cat(
                new byte[6],                 // SampleEntry pre_defined
                u16(1),                      // data_reference_index
                new byte[16],                // VisualSampleEntry pre_defined(2) + reserved(2) + reserved[3](12)
                u16(width), u16(height),
                u32(0x00480000), u32(0),     // horres, vertres
                new byte[4],                 // reserved
                u16(1),                      // num_sequences
                new byte[4]);                // codec payload padding
        byte[] entryBox = cat(u32(8 + entry.length), fourCc.getBytes(StandardCharsets.US_ASCII), entry);
        return box("stsd", cat(new byte[]{0, 0, 0, 0}, u32(1), entryBox));
    }

    private static byte[] trak(byte[] tkhd, int width, int height) {
        return trak(tkhd, "avc1", width, height);
    }

    private static byte[] trak(byte[] tkhd, String fourCc, int width, int height) {
        byte[] stbl = box("stbl", stsdEntry(fourCc, width, height));
        byte[] minf = box("minf", stbl);
        byte[] mdia = box("mdia", minf);
        return box("trak", tkhd, mdia);
    }

    private static byte[] rotBox(int degrees) {
        return box("rot ", u32(degrees * 65536));
    }

    /** mdhd v0: the media timescale and the track's duration in it. */
    private static byte[] mdhd(int timescale, int duration) {
        return box("mdhd", cat(new byte[]{0, 0, 0, 0}, u32(0), u32(0), u32(timescale), u32(duration)));
    }

    /** stsz with one size for every sample. */
    private static byte[] stszUniform(int sampleSize, int count) {
        return box("stsz", cat(new byte[]{0, 0, 0, 0}, u32(sampleSize), u32(count)));
    }

    /** stsz with a per-sample size list — the ordinary shape, and where the peak frame comes from. */
    private static byte[] stszSizes(int... sizes) {
        byte[] list = new byte[sizes.length * 4];
        for (int i = 0; i < sizes.length; i++) {
            System.arraycopy(u32(sizes[i]), 0, list, i * 4, 4);
        }
        return box("stsz", cat(new byte[]{0, 0, 0, 0}, u32(0), u32(sizes.length), list));
    }

    /** A trak the sample-table parsers can actually find things in: mdhd before minf, stsz in stbl. */
    private static byte[] trakWithStats(byte[] tkhd, String fourCc, int width, int height,
                                        int timescale, int duration, byte[] stsz) {
        return box("trak", tkhd,
                box("mdia", mdhd(timescale, duration), box("minf", box("stbl",
                        stsdEntry(fourCc, width, height), stsz))));
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
        assertEquals("avc1", p.codec);
    }

    @Test
    public void reportsTheSampleEntryFourccOfTheVideoTrack() throws Exception {
        File mov = writeFile("IMG_8740.MOV", box("moov", trak(tkhdV0(0, 1, -1, 0), "hvc1", 1920, 1080)));
        assertEquals("hvc1", VideoHeaderProbe.probe(mov).codec);
        assertEquals(270, VideoHeaderProbe.probe(mov).rotationDeg);
    }

    @Test
    public void audioTrackFirstStillReportsTheVideoCodec() throws Exception {
        // QuickTime files routinely carry the audio trak first; the entry the probe reports must
        // be a video one, or the codec gate has nothing to judge.
        byte[] moov = box("moov",
                trak(tkhdV0(1, 0, 0, 1), "mp4a", 0, 0),
                trak(tkhdV0(1, 0, 0, 1), "av01", 1920, 1080));
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(writeFile("av1.mov", moov));
        assertEquals("av01", p.codec);
        assertEquals(1920, p.codedWidth);
    }

    @Test
    public void videoTraksMatrixBeatsAnAudioTraksIdentity() throws Exception {
        // The case the test above cannot see: it gives both traks an identity matrix. A display matrix
        // orients the pixels of the track it is attached to, and an audio tkhd's is identity, so taking
        // the first readable matrix in the moov would report this turned phone clip as unturned. That
        // used to only mis-pick a player; MediaCompatibility.incompatibleVideoUpscale now skips the
        // original on the strength of it, which is the wrong direction to be wrong in.
        byte[] moov = box("moov",
                trak(tkhdV0(1, 0, 0, 1), "mp4a", 0, 0),
                trak(tkhdV0(0, -1, 1, 0), "avc1", 1920, 1080));
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(writeFile("rotated-first-audio.mov", moov));
        assertEquals("avc1", p.codec);
        assertEquals(1920, p.codedWidth);
        assertEquals(90, p.rotationDeg);
    }

    @Test
    public void aRotBoxStillOverridesEveryTrak() throws Exception {
        // The ISO 23001-8 path is moov-level, so the video-trak preference must not displace it.
        byte[] moov = box("moov", rotBox(270),
                trak(tkhdV0(1, 0, 0, 1), "mp4a", 0, 0),
                trak(tkhdV0(0, -1, 1, 0), "avc1", 1920, 1080));
        assertEquals(270, VideoHeaderProbe.probe(
                writeFile("rotbox.mov", moov)).rotationDeg);
    }

    @Test
    public void unrecognisedSampleEntryReportsNoCodec() throws Exception {
        byte[] moov = box("moov", trak(tkhdV0(1, 0, 0, 1), "tmcd", 0, 0));
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(writeFile("timecode.mov", moov));
        assertNull(p.codec);
        assertEquals(0, p.codedHeight);
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
        assertSame(VideoHeaderProbe.StreamStats.UNKNOWN, p.stats);
    }

    // --- sample-table throughput --------------------------------------------------------

    @Test
    public void uniformSampleTableReportsFpsBitrateAndBytes() throws Exception {
        // 60 samples over 1200/600 = 2 s, 1000 B each: 30 fps, 240 kbit/s.
        byte[] moov = box("moov", trakWithStats(tkhdV0(0, 1, -1, 0), "avc1", 1920, 1080,
                600, 1200, stszUniform(1000, 60)));
        VideoHeaderProbe.StreamStats s = VideoHeaderProbe.probe(writeFile("uniform.mp4", moov)).stats;
        assertEquals(30000, s.fpsMilli);
        assertEquals(1000, s.avgFrameBytes);
        assertEquals(1000, s.peakFrameBytes);
        assertEquals(240000, s.bitRateBps);
        assertEquals(2000, s.durationMs);
    }

    @Test
    public void reportsTheFilesOwnDurationWhenThePlayerRoundsItDown() throws Exception {
        // The measured shape of IMG_6518.MOV: 708 samples over 14173 ticks of a 600 timescale. That
        // is 23621 ms of media, and MediaPlayer reports 23000 ms for the same file — so anything
        // deciding when the clip has ended from the player's number truncates the last 0.6 s and
        // then waits forever for a position that only creeps. The file's own length has to be
        // readable separately, which is what durationMs is for.
        byte[] moov = box("moov", trakWithStats(tkhdV0(0, 1, -1, 0), "avc1", 1920, 1080,
                600, 14173, stszUniform(65005, 708)));
        VideoHeaderProbe.StreamStats s = VideoHeaderProbe.probe(writeFile("600ts.mov", moov)).stats;
        assertEquals(23621, s.durationMs);
        assertEquals(29972, s.fpsMilli);
    }

    @Test
    public void perSampleSizesGiveMeanAndPeakFrame() throws Exception {
        // The peak is the I-frame the decoder has to swallow in one frame time; no platform API
        // reports it without going through mediaserver, which is what this probe exists to avoid.
        byte[] moov = box("moov", trakWithStats(tkhdV0(1, 0, 0, 1), "avc1", 1920, 1080,
                1000, 1000, stszSizes(100, 300, 200)));
        VideoHeaderProbe.StreamStats s = VideoHeaderProbe.probe(writeFile("sizes.mp4", moov)).stats;
        assertEquals(3000, s.fpsMilli);
        assertEquals(200, s.avgFrameBytes);
        assertEquals(300, s.peakFrameBytes);
        assertEquals(4800, s.bitRateBps);
        assertEquals(1000, s.durationMs);
    }

    @Test
    public void audioTrackFirstDoesNotLendTheVideoTrackItsSampleTable() throws Exception {
        byte[] moov = box("moov",
                trakWithStats(tkhdV0(1, 0, 0, 1), "mp4a", 0, 0, 44100, 441000, stszUniform(160, 441)),
                trakWithStats(tkhdV0(1, 0, 0, 1), "avc1", 1920, 1080, 30000, 300000, stszUniform(4096, 300)));
        VideoHeaderProbe.StreamStats s =
                VideoHeaderProbe.probe(writeFile("audiofirst.mov", moov)).stats;
        assertEquals(30000, s.fpsMilli);
        assertEquals(4096, s.avgFrameBytes);
    }

    @Test
    public void noSampleTableIsUnknownStatsButStillRoutes() throws Exception {
        // Losing the throughput facts must never cost the caller the facts it already had.
        byte[] moov = box("moov", trak(tkhdV0(0, 1, -1, 0), 1920, 1080));
        VideoHeaderProbe.Probe p = VideoHeaderProbe.probe(writeFile("nostats.mov", moov));
        assertEquals(270, p.rotationDeg);
        assertEquals(1920, p.codedWidth);
        assertEquals("avc1", p.codec);
        assertEquals(-1, p.stats.fpsMilli);
        assertEquals(-1, p.stats.avgFrameBytes);
        assertEquals(-1, p.stats.bitRateBps);
        assertEquals(-1, p.stats.durationMs);
    }

    @Test
    public void truncatedSizeListLeavesBytesUnknownButStillReportsFps() throws Exception {
        // stsz claims more samples than the buffer holds. An average over only the part that
        // happened to be in range would be a wrong number wearing a right one.
        byte[] stsz = box("stsz", cat(new byte[]{0, 0, 0, 0}, u32(0), u32(1000),
                cat(u32(500), u32(500))));
        byte[] moov = box("moov", box("trak", tkhdV0(1, 0, 0, 1),
                box("mdia", mdhd(30000, 300000), box("minf", box("stbl",
                        stsdEntry("avc1", 1920, 1080), stsz)))));
        VideoHeaderProbe.StreamStats s = VideoHeaderProbe.probe(writeFile("truncstsz.mp4", moov)).stats;
        assertEquals(100000, s.fpsMilli);
        assertEquals(-1, s.avgFrameBytes);
        assertEquals(-1, s.bitRateBps);
    }

    // --- helpers ----------------------------------------------------------------------

    private static byte[] moovWithTkhd(byte[] tkhd) {
        return box("moov", trak(tkhd, 1920, 1080));
    }
}
