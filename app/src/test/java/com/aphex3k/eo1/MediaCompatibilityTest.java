package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.media.MediaType;
import com.dd.crop.TextureVideoView;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * JVM tests of the device codec gate: the name-based pre-download check, and the byte checks for
 * files that already exist locally — the {@code ftyp} box for stills, the video sample
 * description for clips.
 */
public class MediaCompatibilityTest {

    /**
     * The EO2's portrait viewport, as that frame logged it ({@code view=1080x1920} in
     * docs/TSPLAYER.md). These fixtures deliberately use measured pixel pairs rather than words like
     * "portrait", because the rule under test is about a shape and an orientation flag cannot express
     * the difference between a 16:9 panel and a 16:10 one.
     */
    private static final MediaCompatibility.PanelShape PORTRAIT =
            MediaCompatibility.PanelShape.of(1080, 1920);

    /** The same panel unrotated — the figure {@code docs/EO2-CAPABILITIES.md} read off dumpsys. */
    private static final MediaCompatibility.PanelShape LANDSCAPE =
            MediaCompatibility.PanelShape.of(1920, 1080);

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
    public void fileTierNeverReportsABudgetVerdict() throws Exception {
        // The trap this guards is specific: LocalMediaBackend.fetchCatalog feeds this method's result
        // straight into CompatibilitySidecar, which excludes the file from rotation. A throughput or
        // bitrate verdict belongs in none of that — the clip is playable, merely slow — so both checks are
        // asserted absent here even for bytes that would fail them on their numbers.
        File file = writeBytes("slow.mov", new byte[]{0, 0, 0, 0});
        // IMG_1566.MOV's real numbers: 1920x1080 at 59.97 fps, 54,266 B/frame, 26.0 Mbps, 50.8 s.
        VideoHeaderProbe.Probe overBudget = new VideoHeaderProbe.Probe(
                270, 1920, 1080, "avc1",
                new VideoHeaderProbe.StreamStats(
                        59970, 54266, 309364, 26034165L, 50793));
        // The file tier stays silent on every budget...
        assertNull(MediaCompatibility.incompatibleReasonForFile(
                MediaType.VIDEO, file, overBudget));
        // ...while each standalone gate does fire on these same numbers, which is the point: they route,
        // they never reach this method. The fixture's 26.0 Mbps already exceeds the bitrate budget.
        assertNotNull(MediaCompatibility.incompatibleVideoThroughput(
                MediaType.VIDEO, 1920, 1080, 59970));
        assertNotNull(MediaCompatibility.incompatibleVideoBitrate(
                MediaType.VIDEO, overBudget.stats.bitRateBps));
        // The crop verdict belongs in the same exclusion. This fixture's 270-degree matrix makes
        // it display 1080x1920, so it trips the gate against a landscape panel — and a fill the panel
        // happens to be turned the wrong way for is still not a file this device cannot decode.
        assertNotNull(MediaCompatibility.incompatibleVideoUpscale(
                MediaType.VIDEO, overBudget.codedWidth, overBudget.codedHeight,
                overBudget.rotationDeg, LANDSCAPE, true));
    }

    @Test
    public void bitrateGateAcceptsWhatTheDecoderKeepsUpWith() {
        // 14.4 Mbit/s measured buttery on an EO2. The exact-limit row pins the comparison to <=, so a
        // rewrite to < fails here rather than silently rerouting every clip at the ceiling.
        assertBitrateAccepted(14_400_000L);
        assertBitrateAccepted(15_000_000L);
        assertBitrateAccepted(2_500_000L);
        assertBitrateAccepted(8_000_000L);
        assertBitrateAccepted(12_000_000L);
    }

    @Test
    public void bitrateGateRejectsWhatTheDecoderStrugglesWith() {
        // 15_600_000 is the load-bearing row: it exceeds a decimal 15,000,000 but is BELOW a binary
        // 15*1048576 = 15,728,640. If the constant is ever made binary, the file the operator measured
        // as choppy starts passing and the gate does nothing on the device that motivated it.
        assertBitrateRejected(15_000_001L);
        assertBitrateRejected(15_600_000L);
        assertBitrateRejected(26_034_165L);  // IMG_1566.MOV's real video-track average
        assertBitrateRejected(45_000_000L);
    }

    @Test
    public void unknownBitrateInputsPass() {
        // -1 is the StreamStats.UNKNOWN sentinel (a truncated stsz, or a non-ISOBMFF container); a
        // missing bitrate is never evidence of a slow clip.
        assertNull(MediaCompatibility.incompatibleVideoBitrate(MediaType.VIDEO, -1L));
        assertNull(MediaCompatibility.incompatibleVideoBitrate(MediaType.VIDEO, 0L));
    }

    @Test
    public void bitrateGateAppliesToVideoOnly() {
        assertNull(MediaCompatibility.incompatibleVideoBitrate(MediaType.IMAGE, 45_000_000L));
        assertNull(MediaCompatibility.incompatibleVideoBitrate(MediaType.WEB, 45_000_000L));
        assertNull(MediaCompatibility.incompatibleVideoBitrate(null, 45_000_000L));
    }

    @Test
    public void aLargeKeyframeIsNotAPeakBitrate() {
        // The trap in "peak": an ordinary I-frame (peakFrameBytes) is one sample, not a rate. A clip
        // whose sustained average fits the budget must pass on its average no matter how large its
        // biggest frame is — the gate reads bitRateBps, never peakFrameBytes.
        assertBitrateAccepted(12_000_000L);
    }

    @Test
    public void nativeAspectIsWhatThePanelCanBeFilledWithout() {
        assertFalse(differs(1920, 1080, 0, LANDSCAPE));
        assertFalse(differs(1080, 1920, 0, PORTRAIT));
        // The load-bearing pair: a clip turned by its own display matrix INTO alignment is native
        // material. Keying the rule on "rotation != 0" instead of on the displayed aspect would
        // reroute every phone clip that is hung the right way.
        assertFalse(differs(1920, 1080, 90, PORTRAIT));
        assertFalse(differs(1080, 1920, 270, LANDSCAPE));
        // Native is a shape, not a resolution: 720p and 640x360 are as native as 1080p.
        assertFalse(differs(1280, 720, 0, LANDSCAPE));
        assertFalse(differs(640, 360, 0, LANDSCAPE));
    }

    @Test
    public void everyOtherShapeDiffersFromThePanel() {
        // The reported bug. The old predicate compared a clip's two sides against each other, so a
        // square — which has no long side — could never be "opposite" anything and kept TsPlayer,
        // whose fixed scaling letterboxes it.
        assertTrue(differs(640, 640, 0, PORTRAIT));
        assertTrue(differs(1080, 1080, 0, PORTRAIT));
        assertTrue(differs(1080, 1080, 0, LANDSCAPE));
        // Same orientation as the panel and still not its shape: fixed scaling cannot fill this
        // either, which is the case the old test for "opposite" could not express at all.
        assertTrue(differs(1080, 1440, 0, PORTRAIT));
        assertTrue(differs(1920, 800, 0, LANDSCAPE));
        assertTrue(differs(1280, 720, 0, PORTRAIT));
        // Opposite is simply the largest case of differs, so everything the old rule caught stays.
        assertTrue(differs(1920, 1080, 0, PORTRAIT));
        assertTrue(differs(1080, 1920, 0, LANDSCAPE));
        // 180 degrees keeps the aspect: upside-down and still the wrong shape for this panel.
        assertTrue(differs(1920, 1080, 180, PORTRAIT));
        // Turned by its matrix INTO the opposite shape, so the matrix does not rescue it.
        assertTrue(differs(1080, 1920, 90, PORTRAIT));
    }

    @Test
    public void macroblockPaddingIsNativeAndNoLongerACliff() {
        // H.264 codes in 16px rows, so stsd reports 1088 for a 1080-line file. That is 0.74% off the
        // panel's shape and must read as native, or every ordinary 1080p encode is sent away from its
        // preferred engine. The tolerance is what carries this now, in place of a padded-height window
        // that could only ever see a padded height.
        assertFalse(differs(1920, 1088, 0, LANDSCAPE));
        assertFalse(differs(1088, 1920, 0, PORTRAIT));
        // Near-square material used to straddle an engine boundary: 1088x1080 was "opposite" a
        // landscape panel while 1080x1080 was not, so 0.7% of aspect flipped both the engine and the
        // composite. They now answer alike, because neither is anywhere near a 16:9 panel.
        assertTrue(differs(1080, 1080, 0, LANDSCAPE));
        assertTrue(differs(1088, 1080, 0, LANDSCAPE));
        assertTrue(differs(1080, 1088, 0, LANDSCAPE));
        // The band pinned from both sides: 1.46% is still native, 3.57% is not.
        assertFalse(differs(1920, 1096, 0, LANDSCAPE));
        assertTrue(differs(1920, 1120, 0, LANDSCAPE));
    }

    @Test
    public void anUnknowablePanelOrClipIsNeverEvidenceOfAMismatch() {
        // Every unknown answers "does not differ", which is the direction that costs nothing: the clip
        // keeps its default engine and its original. A frame whose layout has not settled must not
        // lose its preferred engine on a guess, since the only alternative this reaches is the slower
        // compositor.
        assertFalse(differs(0, 1080, 0, PORTRAIT));
        assertFalse(differs(1920, 0, 0, PORTRAIT));
        assertFalse(differs(1920, 1080, 0, null));
        assertFalse(differs(640, 640, 0, null));
        // An unreadable matrix is read as unturned here rather than refused — the asymmetry
        // docs/TSPLAYER.md records, and which cropGateRejectsKeepsUnknownMatrixDark pins from the
        // other side.
        assertTrue(differs(1920, 1080, -1, PORTRAIT));
    }

    @Test
    public void cropGateAcceptsWhatThePanelCanBeFilledWithout() {
        assertCropAccepted(1920, 1080, 0, LANDSCAPE, true);
        assertCropAccepted(1080, 1920, 0, PORTRAIT, true);
        // Turned by its own matrix into alignment: still native, still the original.
        assertCropAccepted(1920, 1080, 90, PORTRAIT, true);
        assertCropAccepted(1920, 1080, 270, PORTRAIT, true);
        assertCropAccepted(1920, 1088, 0, LANDSCAPE, true);
        // Above 1080p, incompatibleVideoResolution has already routed the asset; the two gates must
        // never both claim one file, or the reason string lies about which one acted.
        assertCropAccepted(3840, 2160, 0, PORTRAIT, true);
        assertTrue(MediaCompatibility.incompatibleVideoResolution(MediaType.VIDEO, 3840, 2160) != null);
    }

    @Test
    public void cropGateRejectsEverythingThatNeedsCenterCrop() {
        assertCropRejected(1080, 1080, 0, PORTRAIT, true);
        assertCropRejected(1080, 1440, 0, PORTRAIT, true);
        assertCropRejected(1920, 800, 0, LANDSCAPE, true);
        assertCropRejected(1920, 1080, 0, PORTRAIT, true);
        assertCropRejected(1080, 1920, 90, PORTRAIT, true);
        // Below 1080p now fires too, which is the widening: the crop is bounded by the viewport, not
        // by the clip, so a 720p mismatch costs the same composite as a 1080p one.
        assertCropRejected(1280, 720, 0, PORTRAIT, true);
        assertCropRejected(854, 480, 0, PORTRAIT, true);
    }

    @Test
    public void cropGateKeepsItsOriginalScopeOnAFrameWithOneEngine() {
        // The widening is scoped to frames whose engine assignment this rule actually changes. An EO1
        // has always been all-MediaPlayer, so nothing about what it downloads should move — and the
        // size pair is what holds that line. Both halves matter: the gate must narrow back to its old
        // reach, and must not switch off entirely.
        assertCropAccepted(1080, 1080, 0, PORTRAIT, false);
        assertCropAccepted(1280, 720, 0, PORTRAIT, false);
        assertCropAccepted(854, 480, 0, PORTRAIT, false);
        assertCropRejected(1920, 1080, 0, PORTRAIT, false);
        // Padded heights are still 1080p on this path, which is what keeps the slack constant live.
        assertCropRejected(1920, 1088, 0, PORTRAIT, false);
        assertCropRejected(1088, 1920, 90, PORTRAIT, false);
        // One row past the slack is not 1080p by the size pair's definition.
        assertCropAccepted(1920, 1096, 0, PORTRAIT, false);
    }

    @Test
    public void cropGateReasonNamesTheCompositorsOwnCoverScale() {
        // The reason reaches /logs and eo1-app.log, and it is what an operator reads at 3 a.m. It has
        // to name the magnification the compositor will actually apply — a property of the clip AND
        // the panel. The older text printed the clip's own display ratio, because the gate could not
        // read the panel, and that number was not what anyone was about to pay.
        //
        // Asserted against TextureVideoView.aspectScale rather than a literal, because the point is
        // that the two agree: a report that drifts from the compositor's arithmetic is worse than no
        // report, since it is the thing that decides whether a slow clip gets re-encoded.
        String reason = MediaCompatibility.incompatibleVideoUpscale(
                MediaType.VIDEO, 640, 640, 0, PORTRAIT, true);
        assertNotNull(reason);
        String expected = String.format(Locale.ROOT, "%.2fx",
                TextureVideoView.aspectScale(1080, 1920, 640, 640));
        assertTrue(reason + " should name the cover scale " + expected, reason.contains(expected));
        assertTrue(reason, reason.contains("1080x1920"));
        // The same clip against a panel it does match names no scale at all.
        assertNull(MediaCompatibility.incompatibleVideoUpscale(
                MediaType.VIDEO, 640, 640, 0,
                MediaCompatibility.PanelShape.of(640, 640), true));
    }

    @Test
    public void unknownCropInputsPass() {
        // An unmeasured panel keeps the gate dark: a frame that cannot say its own shape shows
        // originals, as every build before this gate did.
        assertNull(MediaCompatibility.incompatibleVideoUpscale(
                MediaType.VIDEO, 1920, 1080, 0, null, true));
        // -1 is VideoHeaderProbe's "no readable matrix" answer (a non-ISOBMFF container, or a tkhd
        // whose matrix is not an angle this reader recognises). The source gate refuses to decide on
        // it even though the engine rule above will judge the coded dimensions.
        assertNull(MediaCompatibility.incompatibleVideoUpscale(
                MediaType.VIDEO, 1920, 1080, -1, PORTRAIT, true));
        assertNull(MediaCompatibility.incompatibleVideoUpscale(
                MediaType.VIDEO, 0, 1080, 0, PORTRAIT, true));
        assertNull(MediaCompatibility.incompatibleVideoUpscale(
                MediaType.VIDEO, 1920, -1, 0, PORTRAIT, true));
    }

    @Test
    public void cropGateAppliesToVideoOnly() {
        assertNull(MediaCompatibility.incompatibleVideoUpscale(
                MediaType.IMAGE, 1920, 1080, 0, PORTRAIT, true));
        assertNull(MediaCompatibility.incompatibleVideoUpscale(
                MediaType.WEB, 1920, 1080, 0, PORTRAIT, true));
        assertNull(MediaCompatibility.incompatibleVideoUpscale(
                null, 1920, 1080, 0, PORTRAIT, true));
    }

    @Test
    public void cropGateAddsCoverageTheThroughputGateMisses() {
        // The reason this gate is not just another number on the same axis: a 1920x1080 clip with a
        // 90-degree matrix turned to display 1080x1920 on a LANDSCAPE panel is the same 1.78x fill,
        // while its macroblock rate (244 555/s) sits comfortably inside the throughput budget. Only
        // the crop gate sees it.
        assertNull(MediaCompatibility.incompatibleVideoThroughput(
                MediaType.VIDEO, 1920, 1080, 29970));
        assertCropRejected(1920, 1080, 90, LANDSCAPE, true);
    }

    @Test
    public void panelShapeCarriesOrientationAndRejectsNothingMeasurable() {
        // Orientation is load-bearing: normalising to long-side/short-side would call a landscape clip
        // native on a portrait panel, which is the exact case that must go to MediaPlayer.
        assertEquals(PORTRAIT, MediaCompatibility.PanelShape.of(1080, 1920));
        assertFalse(PORTRAIT.equals(LANDSCAPE));
        assertFalse(LANDSCAPE.equals(PORTRAIT));
        assertEquals("1080x1920", PORTRAIT.toString());
        // A side that is not a measurement is refused rather than carried, so callers see one answer.
        assertNull(MediaCompatibility.PanelShape.of(0, 1920));
        assertNull(MediaCompatibility.PanelShape.of(1080, -1));
    }

    private static boolean differs(int width, int height, int rotationDeg,
                                   MediaCompatibility.PanelShape panel) {
        return MediaCompatibility.videoAspectDiffersFromPanel(width, height, rotationDeg, panel);
    }

    private static void assertCropAccepted(int width, int height, int rotationDeg,
                                           MediaCompatibility.PanelShape panel, boolean twoEngines) {
        assertNull(width + "x" + height + " at " + rotationDeg + " degrees on a " + panel
                        + " panel needs no crop",
                MediaCompatibility.incompatibleVideoUpscale(
                        MediaType.VIDEO, width, height, rotationDeg, panel, twoEngines));
    }

    private static void assertCropRejected(int width, int height, int rotationDeg,
                                           MediaCompatibility.PanelShape panel, boolean twoEngines) {
        assertTrue("expected " + width + "x" + height + " at " + rotationDeg + " degrees on a "
                        + panel + " panel (two engines: " + twoEngines + ") to need a crop",
                MediaCompatibility.incompatibleVideoUpscale(
                        MediaType.VIDEO, width, height, rotationDeg, panel, twoEngines) != null);
    }

    private static void assertBitrateAccepted(long bitRateBps) {
        assertNull(bitRateBps + " bps should fit the budget",
                MediaCompatibility.incompatibleVideoBitrate(MediaType.VIDEO, bitRateBps));
    }

    private static void assertBitrateRejected(long bitRateBps) {
        assertTrue("expected " + bitRateBps + " bps to exceed the budget",
                MediaCompatibility.incompatibleVideoBitrate(MediaType.VIDEO, bitRateBps) != null);
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
