package com.aphex3k.eo1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which axis decides the video engine, and what happens when neither can.
 *
 * <p>The inputs are real strings, not shapes: {@code amlogic} / {@code stvm8b} / {@code meson8} are
 * read out of the EO2's prop dump in this repository, and the EO1's i.MX6Dual is the case that has no
 * matching marker at all — its identification comes from a die photograph, not from a running frame's
 * properties, which is why nothing here claims to recognise it.
 */
public class VideoSiliconTest {

    @Test
    public void theEo2sOwnPropertiesAreRecognisedByEveryAxis() {
        assertEquals(VideoSilicon.AMLOGIC, VideoSilicon.classify("amlogic", "stvm8b", "meson8"));
        assertEquals("one marker is enough; they are not a vote",
                VideoSilicon.AMLOGIC, VideoSilicon.classify("amlogic", null, null));
        assertEquals(VideoSilicon.AMLOGIC, VideoSilicon.classify(null, "stvm8b", null));
        assertEquals(VideoSilicon.AMLOGIC, VideoSilicon.classify(null, null, "meson8"));
    }

    /** The EO1, and every board nobody has measured: no Amlogic marker, so nothing is asserted. */
    @Test
    public void absenceOfAnAmlogicMarkerIsUnknownRatherThanNotAmlogic() {
        assertEquals(VideoSilicon.UNKNOWN, VideoSilicon.classify("mx6dl", "nitrogen6x", "imx6"));
        assertEquals(VideoSilicon.UNKNOWN, VideoSilicon.classify(null, null, null));
    }

    @Test
    public void amlogicSiliconPermitsWhateverTheModelSays() {
        assertTrue(VideoSilicon.allowsTsPlayer(VideoSilicon.AMLOGIC, false));
        assertTrue(VideoSilicon.allowsTsPlayer(VideoSilicon.AMLOGIC, true));
    }

    @Test
    public void anUnresolvedBoardDefersToTheModelProfile() {
        assertFalse("an EO1 stays without it",
                VideoSilicon.allowsTsPlayer(VideoSilicon.UNKNOWN, false));
        assertTrue("a box we have never seen keeps it",
                VideoSilicon.allowsTsPlayer(VideoSilicon.UNKNOWN, true));
    }

    /**
     * Real EO2 hardware whose {@code Build.MODEL} says nothing about being an EO2 — "stvm8b" /
     * "Quad-Core Enjoy TV Box", from an EO2's own recovery log. It is kept on purpose by <em>both</em>
     * axes: the model match fails so it gets the generic profile, and the board string is Amlogic.
     * That is defence in depth rather than one mechanism doing the work, so the assertion says which
     * axis actually decided — if someone later simplifies either one, this line is where the loss of
     * redundancy becomes visible instead of quietly changing the answer for this box.
     */
    @Test
    public void renamedEo2HardwareIsKeptByBothAxesAndTheSiliconSpeaksFirst() {
        String silicon = VideoSilicon.classify("amlogic", "stvm8b", null);
        boolean modelAllows = new GenericCapabilities().supportsTsPlayer();
        assertTrue(VideoSilicon.allowsTsPlayer(silicon, modelAllows));
        assertEquals("the silicon axis is the one that answered",
                VideoSilicon.AMLOGIC, VideoSilicon.decidingAxis(silicon, modelAllows));
        // Either axis alone is sufficient here — stated so it cannot be mistaken for the other.
        assertTrue("the model axis alone would also permit it",
                VideoSilicon.allowsTsPlayer(VideoSilicon.UNKNOWN, modelAllows));
    }

    @Test
    public void theDecidingAxisIsAlwaysReportable() {
        assertEquals(VideoSilicon.AMLOGIC,
                VideoSilicon.decidingAxis(VideoSilicon.AMLOGIC, false));
        assertEquals("model:allowed", VideoSilicon.decidingAxis(VideoSilicon.UNKNOWN, true));
        assertEquals("model:denied", VideoSilicon.decidingAxis(VideoSilicon.UNKNOWN, false));
    }

    /** Must not throw on a host with no such file — /state is built on the web server's thread. */
    @Test
    public void readingCpuInfoIsNeverAFailure() {
        String line = VideoSilicon.cpuModelLine();
        assertTrue("null or a short line, never an exception", line == null || line.length() <= 120);
    }
}
