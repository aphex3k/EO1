package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which lines on the on-screen debug board outlive the asset they describe.
 *
 * <p>The board used to have no lifetime at all — no {@code remove()} or {@code clear()} existed anywhere
 * in the repository — so every key ever written stayed printed forever, and a video replaced by a
 * photograph left the clip's lines on the wall beside the photograph's. That is the report an operator
 * reads as "it is still playing that clip", on the one surface they can see from across the room.
 *
 * <p>The two rules under test are the whole policy: {@link DebugBoard#sweepRequired} says <em>when</em>
 * the board is swept (only when a different asset is adopted), and {@link DebugBoard#onAssetHandoff}
 * says <em>what</em> survives it (the frame's own lines, plus any line naming the asset arriving). Both
 * are pure functions of a map and two strings, which is the reason the rule lives in a class at all —
 * while they were inline in an {@code Activity}, no JVM test could tell "sweeps when it should" from
 * "never sweeps", and a never-sweeping variant is indistinguishable from the original bug.
 */
public class DebugBoardTest {

    private static Map<String, String> board(String... keys) {
        Map<String, String> board = new LinkedHashMap<>();
        for (String key : keys) {
            board.put(key, "value-of-" + key);
        }
        return board;
    }

    private static PanelDisplay image(String key) {
        return PanelDisplay.image(key, "/x.jpg", 1L, 1L);
    }

    // ---------------------------------------------------------------- when it sweeps

    @Test
    public void aDifferentAssetArrivingSweepsTheBoard() {
        assertTrue(DebugBoard.sweepRequired(image("asset-1"), image("asset-2")));
    }

    /**
     * The case that made this a function rather than a line of code: covering a failed clip with a
     * photograph does not change which asset the panel is about, and sweeping anyway deleted the
     * "video: no frame within 30s" sentence on the statement after it was written.
     */
    @Test
    public void restatingTheSameAssetDoesNotSweepTheBoard() {
        assertFalse(DebugBoard.sweepRequired(
                PanelDisplay.video("asset-1", "/x.mp4", "TsPlayer", "FALLBACK",
                        PanelDisplay.ClipFacts.UNKNOWN, "", 1L, 1L),
                image("asset-1")));
    }

    @Test
    public void aMidAttemptEngineHandoverIsNotAnAssetChange() {
        PanelDisplay before = PanelDisplay.video("asset-1", "/x.mp4", "TsPlayer", "ORIGINAL",
                PanelDisplay.ClipFacts.UNKNOWN, "", 1L, 1L);
        PanelDisplay after = PanelDisplay.video("asset-1", "/x.mp4", "MediaPlayer", "ORIGINAL",
                PanelDisplay.ClipFacts.UNKNOWN, "", 2L, 1L);
        assertFalse(DebugBoard.sweepRequired(before, after));
    }

    @Test
    public void theFirstDisplayOfASessionHasNothingToSweep() {
        assertFalse(DebugBoard.sweepRequired(null, image("asset-1")));
    }

    /** Removing the asset that was up is a handoff away from <em>something</em>, not to something. */
    @Test
    public void aClearedPanelSweepsTheAssetItRemoved() {
        assertTrue(DebugBoard.sweepRequired(image("asset-1"), PanelDisplay.nothing(2L, 2L)));
    }

    // ---------------------------------------------------------------- what survives it

    @Test
    public void anAssetHandoffDropsTheLinesThatDescribeTheDepartedAsset() {
        Map<String, String> board = board(
                DebugBoard.KEY_ON_SCREEN, "video", "displayVideo", "videoRouting", "web",
                "Incompatible");
        board.put("video", "end pos=23621 duration=23000");
        board.put("displayVideo", "/data/x/last.mp4");
        board.put(DebugBoard.KEY_ON_SCREEN, "video /data/x/last.mp4 (TsPlayer, ORIGINAL)");

        DebugBoard.onAssetHandoff(board, "a-brand-new-photo");

        assertTrue("the board held nothing but departed-asset lines and survived: " + board.keySet(),
                board.isEmpty());
    }

    /** The bug as reported, stated as an assertion. */
    @Test
    public void aVideoDoesNotOutliveItsDisplayOnTheWall() {
        Map<String, String> board = new HashMap<>();
        board.put(DebugBoard.KEY_ON_SCREEN, "video /last.mp4 (TsPlayer, ORIGINAL)");
        board.put("video", "end pos=23621 duration=23000");
        board.put("displayVideo", "/data/x/last.mp4");

        DebugBoard.onAssetHandoff(board, "photo-2");

        assertFalse("the dead clip is still being reported", board.containsKey("displayVideo"));
        assertFalse("the dead clip's state line is still being reported", board.containsKey("video"));
        assertFalse("the previous asset's on-screen sentence is still being reported",
                board.containsKey(DebugBoard.KEY_ON_SCREEN));
    }

    @Test
    public void aFrameLineSurvivesWhateverIsDisplayed() {
        Map<String, String> board = board("version", SettingsManager.DEBUG_KEY_DEVICE_NAME,
                "connection status", "mqtt", "watchdog", "screenHold", "screenOffWakeLock",
                "quietHours", "quietHoursSchedule", "startQuietHours", "endQuietHours",
                "Min Brightness", "Reported Light Value", "Calculated Brightness Value",
                "vendorUpdater", "compatibility", "Update", "Update Available", "Last Exception",
                "key", "Failed to delete outdated configuration file");
        board.put("video", "prepared duration=23621 player=MediaPlayer");

        DebugBoard.onAssetHandoff(board, "photo-2");

        assertFalse(board.containsKey("video"));
        assertEquals(DebugBoard.FRAME_SCOPED.size(), board.size());
        assertTrue(board.keySet().containsAll(DebugBoard.FRAME_SCOPED));
    }

    /**
     * A declared survivor has to be a name the app actually writes.
     *
     * <p>{@code "device name"} and {@code "connection status"} are duplicated string literals between
     * this set and their writers, and a rename on either side silently stops the row surviving a
     * handoff. Pinning the one that is a compile-time constant costs nothing; the other comes from a
     * string resource, which no JVM test can read, and is left to its comment.
     */
    @Test
    public void aDeclaredSurvivorIsPinnedToTheNameItsWriterUses() {
        assertTrue("SettingsManager renamed its debug key without updating FRAME_SCOPED",
                DebugBoard.FRAME_SCOPED.contains(SettingsManager.DEBUG_KEY_DEVICE_NAME));
    }

    /** The inversion of the default is the whole design; this is the test that pins it. */
    @Test
    public void aKeyNobodyDeclaredIsSwept() {
        Map<String, String> board = new HashMap<>();
        board.put("someBrandNewAssetLineWrittenTomorrow", "a claim about the clip");
        board.put("video", "a claim about the clip");

        DebugBoard.onAssetHandoff(board, "photo-2");

        assertTrue("an undeclared key must not outlive the asset it describes", board.isEmpty());
    }

    /**
     * Acquisition verdicts are written about the asset that is <em>then</em> displayed, so they land
     * while the previous asset is still the one recorded. Sweeping by subject rather than by arrival is
     * what keeps {@code videoBudget}'s explanation of a routed clip on the board for as long as that
     * clip is what is on the wall — the same problem {@code videoRouting} solved by travelling inside the
     * record, and this is the half of it that cannot.
     */
    @Test
    public void aLineNamingTheArrivingAssetSurvivesItsOwnHandoff() {
        String arriving = "3f2a9c1e-7d4b-4a11-9a2f-55c0ffee1234";
        Map<String, String> board = new HashMap<>();
        board.put("videoBudget", arriving + " - coded 3840x2160 above the panel's throughput");
        board.put("Incompatible", "skipped " + arriving + " (no backend)");
        board.put("Thumbnail", "Failed to get thumbnail for asset: " + arriving);
        board.put("Show", "Asset not in the rotation pool: " + arriving);
        board.put("video", "end pos=23621 duration=23000");
        board.put(DebugBoard.KEY_ON_SCREEN, "video /old.mp4 (TsPlayer, ORIGINAL)");

        DebugBoard.onAssetHandoff(board, arriving);

        assertTrue(board.containsKey("videoBudget"));
        assertTrue(board.containsKey("Incompatible"));
        assertTrue(board.containsKey("Thumbnail"));
        assertTrue(board.containsKey("Show"));
        assertFalse("a finished clip's state line names no asset and must go",
                board.containsKey("video"));
        assertFalse(board.containsKey(DebugBoard.KEY_ON_SCREEN));
    }

    /** An unnamed incoming asset keeps no asset lines: there is nothing to match a line against. */
    @Test
    public void anUnidentifiableArrivingAssetKeepsOnlyFrameLines() {
        Map<String, String> board = new HashMap<>();
        board.put("videoBudget", "3f2a9c1e - too demanding");
        board.put("version", "2.0.1.4");

        DebugBoard.onAssetHandoff(board, "");

        assertEquals(1, board.size());
        assertEquals("2.0.1.4", board.get("version"));
    }

    /**
     * The accepted cost of a default that is a set complement, written down rather than discovered.
     *
     * <p>{@code BrightnessManager.sensorAccuracyChanged} keys its line by {@code Sensor.getName()}, so no
     * literal can declare it frame-scoped and the sweep takes it. It is a hardware inventory row, it is
     * still in {@code files/eo1-app.log}, and the alternative — treating an unknown key as persistent — is
     * the exact failure this class removes. This asserts the trade rather than hiding it.
     */
    @Test
    public void aHardwareInventoryLineNamedByTheSensorIsSweptAndThatIsDeliberate() {
        Map<String, String> board = board("Ambient Light Sensor");
        DebugBoard.onAssetHandoff(board, "photo-2");
        assertTrue(board.isEmpty());
    }

    /**
     * The third key a default-sweep would have taken by mistake. The last physical key the app handled is
     * state about the frame, not about an asset, so it is declared — a row that quietly vanished on every
     * rotation would have read as "the buttons stopped working", which is a diagnosis this repo has
     * already chased twice.
     */
    @Test
    public void theLastKeyPressedSurvivesAnAssetHandoff() {
        Map<String, String> board = board("key");
        board.put("key", "C at 2026-11-08 19:32:01");
        DebugBoard.onAssetHandoff(board, "photo-2");
        assertEquals(1, board.size());
        assertTrue(DebugBoard.FRAME_SCOPED.contains("key"));
    }

    @Test
    public void anEmptyBoardSurvivesAHandoff() {
        Map<String, String> board = new HashMap<>();
        DebugBoard.onAssetHandoff(board, "photo-2");
        assertTrue(board.isEmpty());
    }

    @Test
    public void theFrameSetNeverContainsAnAssetLine() {
        // A key in both places is the bug wearing the fix's clothes: it would be declared swept and kept
        // anyway.
        for (String assetLine : new String[]{"video", "videoRouting", "displayVideo",
                "displayPictures", "displayWebsite", "web", "Show", "videoBudget", "Thumbnail",
                "Incompatible", DebugBoard.KEY_ON_SCREEN}) {
            assertFalse(assetLine + " describes the asset, not the frame",
                    DebugBoard.FRAME_SCOPED.contains(assetLine));
        }
    }
}
