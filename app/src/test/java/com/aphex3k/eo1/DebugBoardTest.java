package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the on-screen debug board keeps when a different asset comes onto the panel.
 *
 * <p>The board used to have no lifetime at all — no {@code remove()} or {@code clear()} existed anywhere
 * in the repository — so every key ever written stayed printed forever and a video replaced by a
 * photograph left the clip's lines on the wall beside the photograph's. That is the report an operator
 * reads as "it is still playing that clip", on the one surface they can see from across the room.
 *
 * <p>The tests below are written against a <em>future</em> key on purpose. The rule is asset-scoped by
 * default with a declared set of frame-scoped survivors, so the interesting question is not whether the
 * keys we thought of are swept — it is whether a key nobody thought of is.
 */
public class DebugBoardTest {

    private static Map<String, String> board(String... keys) {
        Map<String, String> board = new LinkedHashMap<>();
        for (String key : keys) {
            board.put(key, "value-of-" + key);
        }
        return board;
    }

    @Test
    public void anAssetHandoffDropsTheLinesThatDescribeTheAsset() {
        Map<String, String> board = board(
                DebugBoard.KEY_ON_SCREEN, "video", "displayVideo", "displayPictures",
                "displayWebsite", "web", "videoRouting", "Show", "videoBudget", "Thumbnail",
                "Incompatible");
        DebugBoard.onAssetHandoff(board);
        assertTrue("the board held nothing but asset lines and survived", board.isEmpty());
    }

    /**
     * The bug as reported: a clip's lines surviving onto a photograph. Named for the symptom so a
     * regression reads as the complaint rather than as a failing set-membership assertion.
     */
    @Test
    public void aVideoDoesNotOutliveItsDisplayOnTheWall() {
        Map<String, String> board = board("on screen", "videoRouting", "video", "displayVideo");
        board.put(DebugBoard.KEY_ON_SCREEN, "video /last.mp4 (TsPlayer, ORIGINAL)");
        board.put("video", "end pos=23621 duration=23000");
        board.put("displayVideo", "/data/x/last.mp4");

        DebugBoard.onAssetHandoff(board);

        assertFalse("the dead clip is still being reported", board.containsKey("displayVideo"));
        assertFalse("the dead clip's state line is still being reported", board.containsKey("video"));
        assertFalse(board.containsKey("videoRouting"));
        // The caller rewrites this immediately from the new record; leaving the previous asset's
        // sentence standing for even one render is the same claim about the wrong asset.
        assertFalse(board.containsKey(DebugBoard.KEY_ON_SCREEN));
    }

    @Test
    public void aFrameLineSurvivesWhateverIsDisplayed() {
        Map<String, String> board = board("version", "device name", "connection status", "mqtt",
                "watchdog", "screenHold", "screenOffWakeLock", "quietHours", "quietHoursSchedule",
                "startQuietHours", "endQuietHours", "Min Brightness", "Reported Light Value",
                "Calculated Brightness Value", "vendorUpdater", "compatibility", "Update",
                "Update Available", "Last Exception",
                "Failed to delete outdated configuration file");
        board.put("video", "prepared duration=23621 player=MediaPlayer");

        DebugBoard.onAssetHandoff(board);

        assertFalse("the clip's state line must not survive onto a photograph",
                board.containsKey("video"));
        for (String key : board.keySet()) {
            assertTrue(key + " must be declared frame-scoped", DebugBoard.FRAME_SCOPED.contains(key));
        }
        assertEquals(20, board.size());
    }

    /** The inversion of the default is the whole design; this is the test that pins it. */
    @Test
    public void aKeyNobodyDeclaredIsSwept() {
        Map<String, String> board = new HashMap<>();
        board.put("someBrandNewAssetLineWrittenTomorrow", "a claim about the clip");
        board.put("video", "a claim about the clip");

        DebugBoard.onAssetHandoff(board);

        assertTrue("an undeclared key must not outlive the asset it describes", board.isEmpty());
    }

    /**
     * The accepted cost of that default, written down rather than discovered.
     *
     * <p>{@code BrightnessManager.sensorAccuracyChanged} keys its line by {@code Sensor.getName()}, so
     * no literal can declare it frame-scoped and the sweep takes it. It is a hardware inventory row, it
     * is still in {@code files/eo1-app.log}, and the alternative — treating an unknown key as persistent
     * — is the exact failure this class removes. This test asserts the trade rather than hiding it.
     */
    @Test
    public void aHardwareInventoryLineNamedByTheSensorIsSweptAndThatIsDeliberate() {
        Map<String, String> board = board("Ambient Light Sensor");
        DebugBoard.onAssetHandoff(board);
        assertTrue(board.isEmpty());
    }

    @Test
    public void anEmptyBoardSurvivesAHandoff() {
        Map<String, String> board = new HashMap<>();
        DebugBoard.onAssetHandoff(board);
        assertTrue(board.isEmpty());
    }

    @Test
    public void theFrameSetNeverContainsAnAssetLine() {
        // A key in both places is the bug wearing the fix's clothes: it would be declared swept and
        // kept anyway. None of these may appear in FRAME_SCOPED.
        for (String assetLine : new String[]{"video", "videoRouting", "videoPlayer", "displayVideo",
                "displayPictures", "displayWebsite", "web", "Show", "videoBudget", "Thumbnail",
                "Incompatible", DebugBoard.KEY_ON_SCREEN}) {
            assertFalse(assetLine + " describes the asset, not the frame",
                    DebugBoard.FRAME_SCOPED.contains(assetLine));
        }
    }
}
