package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
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
 * JVM tests of the device codec gate: the name-based pre-download check, and the byte checks for
 * files that already exist locally — the {@code ftyp} box for stills, the video sample
 * description for clips.
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
    public void webPagesAreNeverCodecChecked() {
        // A page address is not a file: ".../photo.jpg" must not trip the image-extension tier,
        // and no resolution gate applies to something rendered rather than decoded.
        assertNull(MediaCompatibility.incompatibleReason(MediaType.WEB, "http://h/photo.jpg", null));
        assertNull(MediaCompatibility.incompatibleReason(MediaType.WEB, "http://h/feed.php", null));
        assertNull(MediaCompatibility.incompatibleVideoResolution(MediaType.WEB, 4000, 4000));
    }

    @Test
    public void rejectsOversizedVideoResolutions() {
        // 4K and QHD: any side above 1920px.
        assertVideoResolutionRejected(3840, 2160);
        assertVideoResolutionRejected(2160, 3840); // portrait 4K
        assertVideoResolutionRejected(2560, 1440); // QHD
        assertVideoResolutionRejected(4096, 2160); // DCI 4K
        assertVideoResolutionRejected(7680, 4320); // 8K
        // Just over the limit on either side.
        assertVideoResolutionRejected(1921, 1080);
        assertVideoResolutionRejected(1080, 1921);
    }

    @Test
    public void acceptsVideoResolutionsUpTo1080p() {
        assertVideoResolutionAccepted(1920, 1080);
        assertVideoResolutionAccepted(1080, 1920); // portrait 1080p
        assertVideoResolutionAccepted(1280, 720);
        assertVideoResolutionAccepted(853, 480);
    }

    @Test
    public void unknownOrMissingResolutionDimsPass() {
        // 0 means "the backend did not report a dimension" — conservative pass.
        assertNull(MediaCompatibility.incompatibleVideoResolution(MediaType.VIDEO, 0, 0));
        assertNull(MediaCompatibility.incompatibleVideoResolution(MediaType.VIDEO, 3840, 0));
        assertNull(MediaCompatibility.incompatibleVideoResolution(MediaType.VIDEO, 0, 2160));
        assertNull(MediaCompatibility.incompatibleVideoResolution(MediaType.VIDEO, -1, 1080));
    }

    @Test
    public void resolutionGateAppliesToVideoOnly() {
        // Images have their own (accepted OOM) path for oversized content.
        assertNull(MediaCompatibility.incompatibleVideoResolution(MediaType.IMAGE, 3840, 2160));
        assertNull(MediaCompatibility.incompatibleVideoResolution(null, 3840, 2160));
    }

    @Test
    public void throughputGateAcceptsWhatTheDecoderKeepsUpWith() {
        // 1080p29.97 measured at 1.00x on a real EO2. The 720p60 row is the point of the whole gate:
        // it is the encode such assets get routed to, so if it failed here the ladder would descend
        // into a source it then refused for the same reason, forever.
        assertThroughputAccepted(1920, 1080, 29970);
        assertThroughputAccepted(1280, 720, 59940);
        assertThroughputAccepted(1920, 1080, 24000);
        assertThroughputAccepted(854, 480, 59940);
        // Portrait-coded video of the same pixel count must score identically: the product is what
        // matters, not which side is longer.
        assertThroughputAccepted(1080, 1920, 29970);
    }

    @Test
    public void throughputGateRejectsWhatTheDecoderRunsSlowlyOn() {
        // 1920x1080 at 59.97 fps measured 0.13x on that same EO2, with no error ever reported.
        assertThroughputRejected(1920, 1080, 59970);
        assertThroughputRejected(1080, 1920, 59970);
        assertThroughputRejected(1920, 1080, 50000);
        assertThroughputRejected(3840, 2160, 29970);
    }

    @Test
    public void unknownThroughputInputsPass() {
        // Conservative in both directions: an unknown frame rate is not evidence of a slow clip, and
        // rerouting healthy video to a lower-quality encode on missing data is its own harm.
        assertNull(MediaCompatibility.incompatibleVideoThroughput(
                MediaType.VIDEO, 0, 1080, 59970));
        assertNull(MediaCompatibility.incompatibleVideoThroughput(
                MediaType.VIDEO, 1920, -1, 59970));
        assertNull(MediaCompatibility.incompatibleVideoThroughput(
                MediaType.VIDEO, 1920, 1080, 0));
        assertNull(MediaCompatibility.incompatibleVideoThroughput(
                MediaType.VIDEO, 1920, 1080, -1));
    }

    @Test
    public void throughputGateAppliesToVideoOnly() {
        assertNull(MediaCompatibility.incompatibleVideoThroughput(
                MediaType.IMAGE, 3840, 2160, 59970));
        assertNull(MediaCompatibility.incompatibleVideoThroughput(
                MediaType.WEB, 3840, 2160, 59970));
        assertNull(MediaCompatibility.incompatibleVideoThroughput(
                null, 3840, 2160, 59970));
    }

    @Test
    public void macroblockRateIsTheNumberTheGateCompares() {
        // Pinned to the two measurements the threshold was set from, so a change to the formula has
        // to move these deliberately rather than silently.
        assertEquals(244555, MediaCompatibility.videoMacroblocksPerSecond(1920, 1080, 29970));
        assertEquals(489355, MediaCompatibility.videoMacroblocksPerSecond(1920, 1080, 59970));
        assertEquals(-1, MediaCompatibility.videoMacroblocksPerSecond(1920, 1080, 0));
    }

    @Test
    public void fileTierNeverReportsAThroughputVerdict() throws Exception {
        // The trap this guards is specific: LocalMediaBackend.fetchCatalog feeds this method's result
        // straight into CompatibilitySidecar, which excludes the file from rotation. A throughput
        // verdict belongs in none of that — the clip is playable, merely slow — so the check is
        // asserted absent here even for bytes that would fail it on their numbers.
        File file = writeBytes("slow.mov", new byte[]{0, 0, 0, 0});
        VideoHeaderProbe.Probe overBudget = new VideoHeaderProbe.Probe(
                270, 1920, 1080, "avc1",
                new VideoHeaderProbe.StreamStats(59970, 65005, 286193, 26034165L));
        assertNull(MediaCompatibility.incompatibleReasonForFile(
                MediaType.VIDEO, file, overBudget));
        assertTrue(MediaCompatibility.incompatibleVideoThroughput(
                MediaType.VIDEO, 1920, 1080, 59970) != null);
    }

    private static void assertThroughputAccepted(int width, int height, int fpsMilli) {
        assertNull(width + "x" + height + " at " + fpsMilli + " milli-fps should fit the budget",
                MediaCompatibility.incompatibleVideoThroughput(
                        MediaType.VIDEO, width, height, fpsMilli));
    }

    private static void assertThroughputRejected(int width, int height, int fpsMilli) {
        String reason = MediaCompatibility.incompatibleVideoThroughput(
                MediaType.VIDEO, width, height, fpsMilli);
        assertTrue("expected " + width + "x" + height + " at " + fpsMilli
                + " milli-fps to exceed the budget", reason != null);
    }

    private static void assertVideoResolutionRejected(int width, int height) {
        String reason = MediaCompatibility.incompatibleVideoResolution(
                MediaType.VIDEO, width, height);
        assertTrue("expected " + width + "x" + height + " video to be rejected", reason != null);
    }

    private static void assertVideoResolutionAccepted(int width, int height) {
        assertNull(width + "x" + height + " video should pass the resolution check",
                MediaCompatibility.incompatibleVideoResolution(MediaType.VIDEO, width, height));
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
    public void videoIsJudgedBySampleDescriptionNotByFtypBrands() throws Exception {
        // HEIF brands are a still-image verdict: a video whose bytes carry them is not thereby
        // undecodable, and a name that is not an ISOBMFF extension yields no codec at all.
        File heicBytesNamedJpg = writeBytes("x.jpg", ftyp("heic"));
        assertNull(MediaCompatibility.incompatibleReasonForFile(MediaType.VIDEO, heicBytesNamedJpg));
        assertNull(MediaCompatibility.incompatibleReasonForFile(MediaType.IMAGE, null));
        assertNull(MediaCompatibility.incompatibleReasonForFile(
                MediaType.IMAGE, new File(tmp.getRoot(), "missing.jpg")));
    }

    @Test
    public void rejectsCodecsThisDeviceHasNoDecoderFor() {
        assertIncompatibleCodec("hvc1");
        assertIncompatibleCodec("hev1");
        assertIncompatibleCodec("av01");
        assertIncompatibleCodec("vvc1");
        assertIncompatibleCodec("dvh1");
    }

    @Test
    public void acceptsCodecsThisDeviceCanDecodeAndUnknownOnes() {
        assertCompatibleCodec("avc1");
        assertCompatibleCodec("mp4v");
        assertCompatibleCodec("s263");
        assertCompatibleCodec(null);
        // A video codec fourcc in an image asset is nobody's verdict.
        assertNull(MediaCompatibility.incompatibleVideoCodec(MediaType.IMAGE, "hvc1"));
    }

    @Test
    public void rejectsHevcBytesInsideAContainerNamedMov() throws Exception {
        // The whole point of the sample-description check: iPhone .MOV files are HEVC, and the
        // extension says nothing either way.
        File mov = writeBytes("IMG_8740.mov", moovWithSampleEntry("hvc1"));
        String reason = MediaCompatibility.incompatibleReasonForFile(MediaType.VIDEO, mov);
        assertTrue("HEVC in a .mov must not reach a player", reason != null);
        assertTrue(reason, reason.contains("hvc1"));
    }

    @Test
    public void acceptsAvcBytesInsideAContainerNamedMov() throws Exception {
        assertNull(MediaCompatibility.incompatibleReasonForFile(
                MediaType.VIDEO, writeBytes("clip.mov", moovWithSampleEntry("avc1"))));
    }

    @Test
    public void unknownSampleDescriptionIsNoReason() throws Exception {
        // An audio entry is not a video codec verdict, and neither is an entry the probe does not
        // recognise: an unidentified file is always given to the decoder.
        assertNull(MediaCompatibility.incompatibleReasonForFile(
                MediaType.VIDEO, writeBytes("clip.mov", moovWithSampleEntry("mp4a"))));
        assertNull(MediaCompatibility.incompatibleReasonForFile(
                MediaType.VIDEO, writeBytes("clip.mov", new byte[64])));
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

    private static void assertIncompatibleCodec(String fourCc) {
        String reason = MediaCompatibility.incompatibleVideoCodec(MediaType.VIDEO, fourCc);
        assertTrue("expected " + fourCc + " to be undecodable here", reason != null);
    }

    private static void assertCompatibleCodec(String fourCc) {
        assertNull(fourCc + " should be decodable here",
                MediaCompatibility.incompatibleVideoCodec(MediaType.VIDEO, fourCc));
    }

    private static byte[] u32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] u16(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
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
        return cat(u32(8 + body.length), type.getBytes(StandardCharsets.US_ASCII), body);
    }

    /** moov/trak/mdia/minf/stbl/stsd wrapping one sample entry named {@code fourCc}. */
    private static byte[] moovWithSampleEntry(String fourCc) {
        byte[] entry = cat(new byte[6], u16(1), new byte[16], u16(1920), u16(1080));
        byte[] entryBox = cat(u32(8 + entry.length), fourCc.getBytes(StandardCharsets.US_ASCII), entry);
        return box("moov", box("trak", box("mdia", box("minf",
                box("stbl", box("stsd", new byte[]{0, 0, 0, 0}, u32(1), entryBox))))));
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
