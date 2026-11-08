package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.aphex3k.media.MediaType;

import org.junit.Test;

/**
 * The invariant {@code /state} and the debug board both depend on: a clip measurement exists if and
 * only if the panel holds a clip.
 *
 * <p>Nothing here needs an {@code Activity}, which is the point of the type existing as a separate
 * value — the alternative was asserting this on a frame, by reading a report.
 */
public class PanelDisplayTest {

    private static final long GEN = 7L;
    private static final long WHEN = 1_700_000_000_000L;

    private static PanelDisplay.ClipFacts clip() {
        return new PanelDisplay.ClipFacts(90, 1920, 1080, "avc1", 29970, 100_000L, 400_000L,
                15_000_000L, 23_621L);
    }

    @Test
    public void anImageRecordCarriesNoClipFactsAtAll() {
        assertNull(PanelDisplay.image("k", "/x.jpg", GEN, WHEN).clip);
        assertFalse(PanelDisplay.image("k", "/x.jpg", GEN, WHEN).showsVideo());
    }

    @Test
    public void aWebRecordCarriesNoClipFactsAtAll() {
        PanelDisplay web = PanelDisplay.web("web-1", "http://lan/page", GEN, WHEN);
        assertNull(web.clip);
        assertEquals("", web.engine);
        assertEquals(MediaType.WEB, web.type);
    }

    @Test
    public void aVideoRecordAlwaysHasClipFacts() {
        PanelDisplay video = PanelDisplay.video("k", "/x.mp4", "TsPlayer", "ORIGINAL",
                clip(), "codec=avc1", GEN, WHEN);
        assertNotNull(video.clip);
        assertEquals(90, video.clip.rotationDeg);
        assertEquals(1_920, video.clip.codedWidth);
        assertEquals("avc1", video.clip.codec);
        assertEquals(23_621L, video.clip.fileDurationMs);
    }

    /**
     * A clip that reached the panel without a routing pass is reported as unknown, never as absent:
     * absence is what made a reader fall back to whatever field was lying around.
     */
    @Test
    public void aVideoWithNoMeasurementReportsUnknownRatherThanNothing() {
        PanelDisplay video = PanelDisplay.video("k", "/x.mp4", "MediaPlayer", "FALLBACK",
                null, "", GEN, WHEN);
        assertNotNull(video.clip);
        assertSame(PanelDisplay.ClipFacts.UNKNOWN, video.clip);
        assertEquals(-1, video.clip.rotationDeg);
        assertEquals(-1L, video.clip.bitRateBps);
    }

    @Test
    public void zeroIsNotUsedForUnknown() {
        // A 0 fps or 0 byte read would be a claim about the file. -1 is the claim "not known", and the
        // two have to survive a round trip through the record.
        PanelDisplay video = PanelDisplay.video("k", "/x.mp4", "MediaPlayer", "ORIGINAL",
                PanelDisplay.ClipFacts.UNKNOWN, "", GEN, WHEN);
        assertEquals(-1, video.clip.fpsMilli);
        assertEquals(-1L, video.clip.peakFrameBytes);
    }

    @Test
    public void theEngineAndRungAreKeptApartFromTheBytes() {
        PanelDisplay video = PanelDisplay.video("k", "/x.mp4", "MediaPlayer", "ORIGINAL_REPLAY",
                clip(), "codec=avc1", GEN, WHEN);
        assertEquals("MediaPlayer", video.engine);
        assertEquals("ORIGINAL_REPLAY", video.stage);
    }

    @Test
    public void describeNamesTheTypeAndTheThingOnThePanel() {
        assertEquals("image /x.jpg",
                PanelDisplay.image("k", "/x.jpg", GEN, WHEN).describe());
        assertEquals("web http://lan/page",
                PanelDisplay.web("web-1", "http://lan/page", GEN, WHEN).describe());
        assertEquals("video /x.mp4 (TsPlayer, ORIGINAL)",
                PanelDisplay.video("k", "/x.mp4", "TsPlayer", "ORIGINAL",
                        clip(), "", GEN, WHEN).describe());
    }

    /** A handoff that reveals the photo still in the image view knows the type but not which photo. */
    @Test
    public void describeSaysUnknownRatherThanNothingWhenTheFileIsNotIdentified() {
        String line = PanelDisplay.image("", "", GEN, WHEN).describe();
        assertEquals("image (unknown)", line);
        assertFalse("must not read as an empty panel", line.contains("nothing"));
    }

    @Test
    public void aVideoRecordWithoutEngineStillNamesTheClip() {
        String line = PanelDisplay.video("k", "/x.mp4", "", "ORIGINAL", clip(), "", GEN, WHEN)
                .describe();
        assertEquals("video /x.mp4 (ORIGINAL)", line);
    }

    @Test
    public void nullStringsBecomeEmptyRatherThanNullFields() {
        PanelDisplay image = PanelDisplay.image(null, null, GEN, WHEN);
        assertEquals("", image.assetKey);
        assertEquals("", image.pathOrUrl);
    }

    /**
     * An emptied panel is a different claim from a photograph whose file cannot be named, and conflating
     * them is how a frame that was asked to forget its only asset goes on reporting an image.
     */
    @Test
    public void nothingOnThePanelIsNotAnImageWithNoFile() {
        PanelDisplay blank = PanelDisplay.nothing(GEN, WHEN);
        assertNull(blank.type);
        assertFalse(blank.showsVideo());
        assertEquals("nothing on the panel", blank.describe());
        assertNotEquals(PanelDisplay.image("", "", GEN, WHEN).describe(), blank.describe());
    }

    @Test
    public void theInvariantIsEnforcedRatherThanDocumented() {
        // Both directions: a video with no facts and a photograph with facts are the two shapes the
        // reporting bug took, and neither may be constructible.
        try {
            new PanelDisplay(MediaType.VIDEO, "k", "/x.mp4", "TsPlayer", "ORIGINAL",
                    null, "", GEN, WHEN);
            fail("a video record without clip facts must not be constructible");
        } catch (IllegalArgumentException expected) {
            assertEquals("clip belongs to a video and only to a video", expected.getMessage());
        }
        try {
            new PanelDisplay(MediaType.IMAGE, "k", "/x.jpg", "", "", clip(), "", GEN, WHEN);
            fail("an image record carrying clip facts must not be constructible");
        } catch (IllegalArgumentException expected) {
            assertEquals("clip belongs to a video and only to a video", expected.getMessage());
        }
    }
}
