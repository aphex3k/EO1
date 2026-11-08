package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the on-screen debug board may print, and about whom.
 *
 * <p>The board used to have no lifetime at all — no {@code remove()} or {@code clear()} existed anywhere in
 * the repository — so every key ever written stayed printed forever, and a video replaced by a photograph
 * left the clip's lines on the wall beside the photograph's. An interim revision swept the map at each
 * handoff; these tests are why that was replaced with attribution. A sweep is an ordering rule, and these
 * writes do not cooperate with ordering: an acquisition verdict lands <em>before</em> the asset it names is
 * displayed, and a backend failure from {@code tagAssetAsIncompatible} lands <em>after</em> that asset has
 * left. One sweep cannot be right for both, so the row carries its subject and the panel decides
 * visibility.
 */
public class DebugBoardTest {

    private static final String GONE = "a11ce000-dead-beef-0000-000000000001";
    private static final String HERE = "b0b00000-live-beef-0000-000000000002";

    private static Map<String, DebugBoard.Row> rows(Object... keyValueSubjectTriples) {
        Map<String, DebugBoard.Row> board = new LinkedHashMap<>();
        for (int i = 0; i < keyValueSubjectTriples.length; i += 3) {
            String key = (String) keyValueSubjectTriples[i];
            String value = (String) keyValueSubjectTriples[i + 1];
            String subject = (String) keyValueSubjectTriples[i + 2];
            board.put(key, new DebugBoard.Row(value, subject));
        }
        return board;
    }

    private static PanelDisplay photo(String key) {
        return PanelDisplay.image(key, "/x.jpg", 1L, 1L);
    }

    // ---------------------------------------------------------------- the frame's own lines

    @Test
    public void aFrameLineRendersWhateverIsOnThePanel() {
        Map<String, String> visible = DebugBoard.visible(
                rows("version", "2.0.1.4", null, "mqtt", "connected", null,
                        "screenHold", "held", null, "key", "C at 19:32", null),
                photo(HERE));
        assertEquals("2.0.1.4", visible.get("version"));
        assertEquals("connected", visible.get("mqtt"));
        assertEquals("held", visible.get("screenHold"));
        assertEquals("C at 19:32", visible.get("key"));
    }

    /**
     * A declared survivor has to be a name the app actually writes. {@code "device name"} duplicates
     * {@code SettingsManager.DEBUG_KEY_DEVICE_NAME} as a literal because this class holds no Android; a
     * rename on either side silently changes what the board prints, and this is what catches it.
     */
    @Test
    public void aDeclaredSurvivorIsPinnedToTheNameItsWriterUses() {
        assertTrue("SettingsManager renamed its debug key without updating FRAME_SCOPED",
                DebugBoard.FRAME_SCOPED.contains(SettingsManager.DEBUG_KEY_DEVICE_NAME));
    }

    /**
     * Asset identity is not the whole question: a clip this device cannot draw is displayed as its still
     * preview, which is the <em>same asset</em> and an image. A measurement row that only knew the asset key
     * would go on describing coded size, frame rate and bitrate over that photograph.
     */
    @Test
    public void aVideoMeasurementRetiresWhenTheSameAssetIsShownAsAPreview() {
        String clip = "3f2a9c1e-silent-clip-0000-000000000000";
        Map<String, DebugBoard.Row> board = new HashMap<>();
        board.put("videoBudget", new DebugBoard.Row(clip + " - over the bitrate ceiling", clip, true));
        board.put("video", DebugBoard.Row.ofVideo("end pos=23621", clip));
        // The verdict that put the still on the wall in the first place: about the asset, and explaining an
        // image, so it is not video-scoped.
        board.put("displayVideo", new DebugBoard.Row("render-silent still " + clip, clip));

        // The clip itself, on the wall.
        Map<String, String> asVideo = DebugBoard.visible(board,
                PanelDisplay.video(clip, "/x.mp4", "MediaPlayer", "ORIGINAL",
                        PanelDisplay.ClipFacts.UNKNOWN, "", 1L, 1L));
        assertTrue(asVideo.containsKey("video"));
        assertTrue("the routing verdict for a clip still on the wall should be visible",
                asVideo.containsKey("videoBudget"));

        // The same asset, now as its still preview: every video measurement retires.
        Map<String, String> asPhoto = DebugBoard.visible(board, photo(clip));
        assertFalse("a video's numbers are printed over a photograph", asPhoto.containsKey("video"));
        assertFalse("a bitrate is printed over a photograph", asPhoto.containsKey("videoBudget"));
        // The verdict that <em>caused</em> the preview is about the asset and explains an image: it stays.
        assertTrue(asPhoto.containsKey("displayVideo"));
    }

    @Test
    public void theVideoScopedSetNeverOverlapsTheFrameSet() {
        for (String key : DebugBoard.VIDEO_SCOPED) {
            assertFalse(key + " cannot be both a frame fact and a video measurement",
                    DebugBoard.FRAME_SCOPED.contains(key));
        }
        assertTrue(DebugBoard.videoScoped("video", false));
        assertFalse(DebugBoard.videoScoped("Incompatible", false));
        assertTrue(DebugBoard.videoScoped("Incompatible", true));
    }

    @Test
    public void theFrameSetNeverContainsAnAssetLine() {
        for (String assetLine : new String[]{"video", "displayVideo", "displayPictures",
                "displayWebsite", "web", "Show", "videoBudget", "Thumbnail", "Incompatible",
                DebugBoard.KEY_ON_SCREEN, DebugBoard.KEY_ROUTING}) {
            assertFalse(assetLine + " describes the asset, not the frame",
                    DebugBoard.FRAME_SCOPED.contains(assetLine));
        }
    }

    // ---------------------------------------------------------------- attribution

    @Test
    public void aLineAboutTheAssetOnThePanelRenders() {
        Map<String, String> visible = DebugBoard.visible(
                rows("video", "prepared duration=23621", HERE), photo(HERE));
        assertEquals("prepared duration=23621", visible.get("video"));
    }

    /**
     * The hole a sweep could not close. {@code tagAssetAsIncompatible} posts from its own thread when the
     * backend finally answers, which can be several rotations after the asset left; nothing in the display
     * path knows the write is coming, so no ordering rule could have removed it again.
     */
    @Test
    public void aLateVerdictAboutADepartedAssetIsNotPrintedOverWhatIsUpNow() {
        Map<String, DebugBoard.Row> board = rows("Incompatible", "could not flag " + GONE
                + " in 'immich-1': timeout", GONE);
        board.put("version", DebugBoard.Row.ofFrame("2.0.1.4"));

        Map<String, String> visible = DebugBoard.visible(board, photo(HERE));

        assertFalse("a departed asset's failure is printed as if it were current",
                visible.containsKey("Incompatible"));
        assertEquals("2.0.1.4", visible.get("version"));
    }

    /** The mirror case, which is why filtering happens at render rather than at write. */
    @Test
    public void aVerdictRecordedBeforeItsAssetArrivesAppearsWhenThatAssetReachesThePanel() {
        String incoming = "c0ming00-0000-0000-0000-000000000003";
        Map<String, DebugBoard.Row> board = rows("videoBudget", incoming + " - over the bitrate ceiling",
                incoming);

        // Written while the previous asset is still what the panel holds.
        assertFalse(DebugBoard.visible(board, photo(HERE)).containsKey("videoBudget"));
        // ...and visible the moment the asset it describes is what is up, with no write in between.
        assertEquals(incoming + " - over the bitrate ceiling",
                DebugBoard.visible(board, photo(incoming)).get("videoBudget"));
    }

    @Test
    public void aWriterThatDidNotNameItsAssetIsAttributedToThePanel() {
        assertNull(DebugBoard.implicitSubject("version", HERE));
        assertEquals(HERE, DebugBoard.implicitSubject("video", HERE));
        assertEquals("", DebugBoard.implicitSubject("video", ""));
    }

    // ---------------------------------------------------------------- derived rows

    @Test
    public void thePanelDescriptionIsDerivedRatherThanWritten() {
        Map<String, DebugBoard.Row> board = new HashMap<>();
        board.put(DebugBoard.KEY_ON_SCREEN, new DebugBoard.Row("video /old.mp4", null));

        Map<String, String> visible = DebugBoard.visible(board, photo(HERE));

        assertEquals("image " + "/x.jpg", visible.get(DebugBoard.KEY_ON_SCREEN));
    }

    @Test
    public void anEmptyPanelSaysNothingYetRatherThanAnOldClaim() {
        Map<String, DebugBoard.Row> board = new HashMap<>();
        board.put(DebugBoard.KEY_ON_SCREEN, new DebugBoard.Row("video /old.mp4", null));
        assertEquals("nothing yet", DebugBoard.visible(board, null).get(DebugBoard.KEY_ON_SCREEN));
    }

    /**
     * A clip covered by a photograph of the <em>same</em> asset is not an asset change, so attribution
     * alone would keep the routing sentence on the board — and it carries codec, dimensions, frame rate and
     * bitrate, which is exactly what must not be stated over an image. It is therefore derived from the
     * record, which an image record cannot populate.
     */
    @Test
    public void aClipsRoutingMeasurementLeavesWhenThePanelStopsShowingVideo() {
        PanelDisplay clip = PanelDisplay.video(HERE, "/x.mp4", "TsPlayer", "ORIGINAL",
                new PanelDisplay.ClipFacts(0, 1920, 1080, "avc1", 29970, 1L, 1L, 15_000_000L, 23_621L),
                "codec=avc1 rotation=0 dims=1920x1080", 1L, 1L);
        assertEquals("codec=avc1 rotation=0 dims=1920x1080 -> TsPlayer",
                DebugBoard.visible(new HashMap<String, DebugBoard.Row>(), clip).get(DebugBoard.KEY_ROUTING));

        // The same asset, now covered by its still preview after the clip failed to paint.
        Map<String, String> overPhoto = DebugBoard.visible(
                new HashMap<String, DebugBoard.Row>(), photo(HERE));
        assertFalse("a photograph is on the wall and the routing line is still reporting a clip",
                overPhoto.containsKey(DebugBoard.KEY_ROUTING));
    }

    @Test
    public void aStaleRoutingRowIsSupersededByTheDerivedOne() {
        Map<String, DebugBoard.Row> board = rows(DebugBoard.KEY_ROUTING, "codec=hevc STALE", HERE);
        PanelDisplay clip = PanelDisplay.video(HERE, "/x.mp4", "MediaPlayer", "FALLBACK",
                PanelDisplay.ClipFacts.UNKNOWN, "codec=avc1 fresh", 1L, 1L);
        // Rendered from the record, including the compositor it now carries — so a clip handed over from
        // TsPlayer keeps its measurements and stops claiming the engine that gave up on it.
        assertEquals("codec=avc1 fresh -> MediaPlayer",
                DebugBoard.visible(board, clip).get(DebugBoard.KEY_ROUTING));
    }

    /**
     * The one asset-naming row that must <em>not</em> be attributed.
     *
     * <p>A failure explanation is a fact about the machine, and it is needed precisely after recovery has
     * uncovered a photograph of some other asset — at which point a row attributed to the failed clip is
     * filtered out as "not about the panel", so every recovery would swallow its own diagnosis. Attributing
     * it to nothing instead would print a stale failure forever, so it is frame-scoped with the asset named
     * inside the text: visible whenever it is the latest answer, and honest about what it refers to.
     */
    @Test
    public void theReasonAnAssetMissedThePanelSurvivesTheRecoveryItExplains() {
        Map<String, DebugBoard.Row> board = rows(
                "displayFailure", "clip-9: no frame within 30s stage=FALLBACK on clip-9.mp4", null,
                "video", "end pos=23621", "clip-9");

        Map<String, String> visible = DebugBoard.visible(board, photo("photo-3"));

        assertTrue("the recovery hid the sentence explaining itself",
                visible.containsKey("displayFailure"));
        assertFalse("the failed clip's own state line must still go", visible.containsKey("video"));
    }

    @Test
    public void anEmptyBoardRendersOnlyThePanelLine() {
        Map<String, String> visible = DebugBoard.visible(
                new HashMap<String, DebugBoard.Row>(), photo(HERE));
        assertEquals(1, visible.size());
        assertTrue(visible.containsKey(DebugBoard.KEY_ON_SCREEN));
    }
}
