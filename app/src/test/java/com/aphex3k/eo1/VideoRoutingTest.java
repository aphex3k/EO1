package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which engine a clip is <em>suitable</em> for, pinned directly.
 *
 * <p>This file exists because the rule had never been tested. {@code routingAllowsTsPlayer} sits
 * inside an {@code Activity} behind a header probe, so the suite could only reach the predicate it
 * delegates to — and that meant the engine assignment for most of a frame's library was asserted
 * nowhere, so a change moving nearly every clip from one engine to the other left all 854 tests green.
 * The rule is therefore extracted into {@link VideoRoutingRule}, which is pure, and asserted here.
 *
 * <p>The fixtures are the shapes an EO2 actually gets sent: a square uploaded through the web page is
 * what started this, and the padded 1080p row is the one that would silently strand a whole library on
 * the slower compositor if the tolerance were tuned wrong.
 */
public class VideoRoutingTest {

    /** The EO2 portrait viewport as that frame logged it: {@code view=1080x1920}. */
    private static final MediaCompatibility.PanelShape PORTRAIT =
            MediaCompatibility.PanelShape.of(1080, 1920);

    /** The same panel unrotated, as {@code docs/EO2-CAPABILITIES.md} measured it. */
    private static final MediaCompatibility.PanelShape LANDSCAPE =
            MediaCompatibility.PanelShape.of(1920, 1080);

    /** Stand-in for {@code MainActivity.MAX_ENGINE_ATTEMPTS}; the rule takes it as an argument. */
    private static final int MAX_ATTEMPTS = 2;

    @Test
    public void nativeAspectKeepsThePreferredEngine() {
        assertTrue(VideoRoutingRule.allowsTsPlayer(0, 1080, 1920, PORTRAIT));
        assertTrue(VideoRoutingRule.allowsTsPlayer(0, 1920, 1080, LANDSCAPE));
        // 720p is native-shaped, so it is not a resolution test dressed up as an aspect one.
        assertTrue(VideoRoutingRule.allowsTsPlayer(0, 1280, 720, LANDSCAPE));
    }

    @Test
    public void macroblockPaddedVideoIsStillNative() {
        // The single highest-value row here. H.264 pads a 1080-line file to 1088, so if this reads as
        // a mismatch every ordinary 1080p encode on a landscape-hung frame leaves TsPlayer, and the
        // frame gets slower for a reason nobody can see in the file.
        assertTrue(VideoRoutingRule.allowsTsPlayer(0, 1920, 1088, LANDSCAPE));
        assertTrue(VideoRoutingRule.allowsTsPlayer(0, 1088, 1920, PORTRAIT));
    }

    @Test
    public void squareVideoGoesToMediaPlayer() {
        // The reported bug. A square has no long axis, so the old test for an aspect being "opposite"
        // the panel could never fire on one, and it was composited by an engine whose scaling is fixed
        // — which reaches the wall letterboxed. MediaPlayer crops it to fill instead.
        assertFalse(VideoRoutingRule.allowsTsPlayer(0, 640, 640, PORTRAIT));
        assertFalse(VideoRoutingRule.allowsTsPlayer(0, 1080, 1080, PORTRAIT));
        assertFalse(VideoRoutingRule.allowsTsPlayer(0, 1080, 1080, LANDSCAPE));
    }

    @Test
    public void sameOrientationWithTheWrongRatioGoesToMediaPlayer() {
        // The case "opposite" could not express at all: a 3:4 portrait clip on a 9:16 portrait panel is
        // not opposite anything, and fixed scaling cannot fill it either.
        assertFalse(VideoRoutingRule.allowsTsPlayer(0, 1080, 1440, PORTRAIT));
        assertFalse(VideoRoutingRule.allowsTsPlayer(0, 1920, 800, LANDSCAPE));
        assertFalse(VideoRoutingRule.allowsTsPlayer(0, 1280, 720, PORTRAIT));
    }

    @Test
    public void oppositeAspectStillGoesToMediaPlayer() {
        // The behavior that predates this rule must survive it, because "opposite" is simply the
        // largest case of "differs".
        assertFalse(VideoRoutingRule.allowsTsPlayer(0, 1920, 1080, PORTRAIT));
        assertFalse(VideoRoutingRule.allowsTsPlayer(0, 1080, 1920, LANDSCAPE));
    }

    @Test
    public void turnedVideoAlwaysGoesToMediaPlayer() {
        // Non-identity rotation is its own term, and it outranks the shape: the .so ignores
        // display-matrix metadata, so a clip that needs turning needs the player that will turn it
        // even when the turn would have left it exactly the panel's own shape.
        assertFalse(VideoRoutingRule.allowsTsPlayer(90, 1920, 1080, PORTRAIT));
        assertFalse(VideoRoutingRule.allowsTsPlayer(270, 1920, 1080, PORTRAIT));
        assertFalse(VideoRoutingRule.allowsTsPlayer(180, 1080, 1920, PORTRAIT));
    }

    @Test
    public void anUnmeasurablePanelKeepsTheDefaultEngine() {
        // The conservative direction, and the one worth stating: the alternative this rule can reach
        // is the slower compositor, so a frame that cannot measure its own wall must not hand every
        // clip to it on a guess.
        assertTrue(VideoRoutingRule.allowsTsPlayer(0, 640, 640, null));
        assertTrue(VideoRoutingRule.allowsTsPlayer(0, 1920, 1080, null));
        // Unknown dimensions likewise.
        assertTrue(VideoRoutingRule.allowsTsPlayer(0, 0, 0, PORTRAIT));
        // An unreadable matrix is judged from the coded dimensions rather than excused — the asymmetry
        // docs/TSPLAYER.md records, and the reason an .mkv can be MediaPlayer material for the engine
        // while the source gate refuses to decide about it at all.
        assertFalse(VideoRoutingRule.allowsTsPlayer(-1, 1920, 1080, PORTRAIT));
        assertTrue(VideoRoutingRule.allowsTsPlayer(-1, 1080, 1920, PORTRAIT));
    }

    @Test
    public void theRuleSaysNothingAboutWhichEngineThisFrameHas() {
        // Routing answers "what can draw this", and selectVideoPlayer answers "what does this board
        // own" by ANDing the two. Keeping them apart is what lets a log line say TsPlayer on an EO1
        // without that being a false statement about the clip — see VideoEngineReport's javadoc.
        assertEquals("MediaPlayer", VideoEngineReport.engineWord(false, false));
        assertEquals("MediaPlayer", VideoEngineReport.engineWord(true, false));
        assertEquals("MediaPlayer", VideoEngineReport.engineWord(true, true));
        assertEquals("TsPlayer", VideoEngineReport.engineWord(false, true));
    }

    @Test
    public void theSecondEngineRungOnlyOffersAnEngineNotYetTried() {
        // The bug this predicate replaces: the rung asked whether the FRAME had two engines, while the
        // ladder re-derives the engine from the file on every entry. So a clip routing had already
        // given to the platform player was replayed on the player that just failed it, burning a whole
        // attempt before descending. That is the common case once every non-native aspect lands here.
        assertFalse(VideoRoutingRule.offersSecondEngineAttempt(
                true, false, 0, MAX_ATTEMPTS));
        // An EO1 has one engine and does not enter the rung at all, whatever the clip looks like.
        assertFalse(VideoRoutingRule.offersSecondEngineAttempt(
                false, true, 0, MAX_ATTEMPTS));
        assertFalse(VideoRoutingRule.offersSecondEngineAttempt(
                false, false, 0, MAX_ATTEMPTS));
        // A clip routing put on TsPlayer does get the second engine after it fails there.
        assertTrue(VideoRoutingRule.offersSecondEngineAttempt(
                true, true, 0, MAX_ATTEMPTS));
        // And a transient native failure gets one retry, which is why this is a count and not a flag.
        assertTrue(VideoRoutingRule.offersSecondEngineAttempt(
                true, true, 1, MAX_ATTEMPTS));
        assertFalse(VideoRoutingRule.offersSecondEngineAttempt(
                true, true, 2, MAX_ATTEMPTS));
        // Budget 1 is the "one attempt per engine" policy; the rule must honour it rather than hardcode
        // two, so the ceiling stays a single named constant in MainActivity.
        assertFalse(VideoRoutingRule.offersSecondEngineAttempt(
                true, true, 1, 1));
    }

    @Test
    public void orientationWordIsDerivedFromTheMeasuredShape() {
        // One account of how the frame is hung. /state's frameOrientation is now read off the same
        // pixels the rule uses, so the two can never disagree about which way the wall is turned.
        assertEquals("portrait", VideoRoutingRule.orientationWord(PORTRAIT));
        assertEquals("landscape", VideoRoutingRule.orientationWord(LANDSCAPE));
        assertEquals("square", VideoRoutingRule.orientationWord(
                MediaCompatibility.PanelShape.of(1000, 1000)));
        assertEquals("undefined", VideoRoutingRule.orientationWord(null));
    }
}
