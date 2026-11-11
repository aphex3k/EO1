package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;

import org.junit.Test;

/**
 * What the {@code /state} video block says about a clip, and what it says when there is no clip.
 *
 * <p>The block used to answer the second question with the first one's data: {@code rotation},
 * {@code width}, {@code height}, {@code codec}, {@code fpsMilli}, {@code bitRateBps} and the rest came
 * out of fields that only a video display wrote and nobody cleared, so a frame showing a photograph for
 * a week described the last clip it had played. {@code video.active} reported which engine the frame
 * prefers, which is not the question anybody reading the block was asking.
 *
 * <p>These tests are the reason {@link VideoStateJson} is a class and not lines in an
 * {@code Activity} — the same reason {@code VideoRoutingRule} and {@code ConfigStateJson} were extracted
 * before it. The routing change that moved most of an EO2's library from one engine to the other left
 * the whole suite green while it sat behind a live player.
 */
public class VideoStateJsonTest {

    private static final long GEN = 3L;
    private static final long WHEN = 1_700_000_000_000L;

    /**
     * A probe that fails loudly if the builder asks it about player state.
     *
     * <p>That is the point of the fixture, not a shortcut: asking a player for its position while the
     * panel holds a photograph meant calling into a released player on the web server's thread, at
     * whatever moment the UI thread was tearing it down. A test that tolerates the call cannot catch the
     * call.
     */
    private static final class NeverAskMeAboutThePlayer implements VideoStateJson.PlayerProbe {

        @Override
        public boolean stale() {
            throw new AssertionError("asked whether a panel holding no clip is stale");
        }

        @Override
        public boolean painted() {
            throw new AssertionError("asked a player whether it painted while no clip is on screen");
        }

        @Override
        public long lastFrameAtMs() {
            throw new AssertionError("asked a released player for its last composited frame");
        }

        @Override
        public long gapSnapshot() {
            throw new AssertionError("asked a released player for its frame spacing");
        }

        @Override
        public int durationMs() {
            throw new AssertionError("asked a released player for its duration");
        }

        @Override
        public int positionMs() {
            throw new AssertionError("asked a released player for its position");
        }

        @Override
        public VideoStateJson.NativeCounters nativeCounters() {
            throw new AssertionError("asked TsPlayer's counters for a non-video panel");
        }

        @Override
        public VideoStateJson.ManualCounters manualCounters() {
            throw new AssertionError("asked the platform player's counters for a non-video panel");
        }

        @Override
        public boolean renderSilent(String assetKey) {
            throw new AssertionError("asked whether a photograph renders silently");
        }
    }

    private static VideoStateJson.Frame frame() {
        VideoStateJson.Frame frame = new VideoStateJson.Frame();
        frame.engine = VideoEngineReport.MEDIA_PLAYER;
        frame.preferTsPlayer = true;
        frame.tsSupported = true;
        frame.tsLibraryLoaded = true;
        frame.tsSilicon = "meson8";
        frame.modelProfile = "eo2";
        frame.tsModelAllows = true;
        frame.tsGateAxis = "silicon:allowed";
        frame.buildFlag = true;
        frame.panelShape = "1080x1920";
        frame.frameOrientation = "portrait";
        frame.tsFallbacks = 1;
        frame.playbackRateX100 = 100;
        frame.playbackRateMinX100 = 95;
        frame.playbackRateMaxX100 = 104;
        frame.playbackRateSamples = 12;
        frame.debugBuild = true;
        return frame;
    }

    private static PanelDisplay videoOnScreen() {
        return videoOnScreen(VideoEngineReport.MEDIA_PLAYER);
    }

    private static PanelDisplay videoOnScreen(String engine) {
        return PanelDisplay.video("asset-1", "/data/x/clip.mp4", engine,
                "ORIGINAL",
                new PanelDisplay.ClipFacts(90, 1920, 1080, "hvc1", 29970, 100_000L, 400_000L,
                        15_000_000L, 23_621L),
                "codec=hvc1 rotation=90", GEN, WHEN);
    }

    /** The complaint, stated as an assertion: no clip field may appear while a photograph is up. */
    @Test
    public void anImageOnThePanelEmitsNotOneClipField() {
        JsonObject video = VideoStateJson.build(
                PanelDisplay.image("asset-2", "/data/x/photo.jpg", GEN, WHEN),
                frame(), new NeverAskMeAboutThePlayer());
        for (String field : VideoStateJson.CLIP_FIELDS) {
            assertFalse("an image is on the panel, yet the block reports " + field,
                    video.has(field));
        }
    }

    @Test
    public void aWebPageOnThePanelEmitsNotOneClipField() {
        JsonObject video = VideoStateJson.build(
                PanelDisplay.web("web-1", "http://lan/frame", GEN, WHEN),
                frame(), new NeverAskMeAboutThePlayer());
        for (String field : VideoStateJson.CLIP_FIELDS) {
            assertFalse("a page is on the panel, yet the block reports " + field,
                    video.has(field));
        }
    }

    @Test
    public void aPanelThatHasNotDisplayedAnythingYetEmitsNotOneClipField() {
        JsonObject video = VideoStateJson.build(null, frame(), new NeverAskMeAboutThePlayer());
        assertFalse(video.has("path"));
        assertFalse(video.has("codec"));
        assertEquals("none", video.get("showing").getAsString());
    }

    /** The question the block could not previously be made to answer at all. */
    @Test
    public void theBlockSaysWhetherAClipIsUp() {
        JsonObject image = VideoStateJson.build(
                PanelDisplay.image("asset-2", "/x.jpg", GEN, WHEN), frame(),
                new NeverAskMeAboutThePlayer());
        assertFalse(image.get("onScreen").getAsBoolean());
        assertEquals("image", image.get("showing").getAsString());

        JsonObject video = VideoStateJson.build(videoOnScreen(), frame(), new AnsweringProbe());
        assertTrue(video.get("onScreen").getAsBoolean());
        assertEquals("video", video.get("showing").getAsString());
    }

    /**
     * {@code engine} is what {@code active} used to hold. The rename is the fix's visible half: a frame
     * reading {@code active: MediaPlayer} could not be distinguished from one displaying a photograph.
     */
    @Test
    public void theEngineFieldNamesTheCompositorAndFollowsThePanel() {
        JsonObject video = VideoStateJson.build(videoOnScreen(), frame(), new AnsweringProbe());
        assertEquals(VideoEngineReport.MEDIA_PLAYER, video.get("engine").getAsString());

        JsonObject photo = VideoStateJson.build(
                PanelDisplay.image("k", "/x.jpg", GEN, WHEN), frame(), new NeverAskMeAboutThePlayer());
        assertEquals("the frame still has a default engine, and says so",
                VideoEngineReport.MEDIA_PLAYER, photo.get("engine").getAsString());
    }

    @Test
    public void aClipOnThePanelReportsWhatWasMeasuredAboutIt() {
        JsonObject video = VideoStateJson.build(videoOnScreen(), frame(), new AnsweringProbe());
        assertEquals("/data/x/clip.mp4", video.get("path").getAsString());
        assertEquals("ORIGINAL", video.get("stage").getAsString());
        assertEquals(90, video.get("rotation").getAsInt());
        assertEquals(1920, video.get("width").getAsInt());
        assertEquals(1080, video.get("height").getAsInt());
        assertEquals("hvc1", video.get("codec").getAsString());
        assertEquals(29970, video.get("fpsMilli").getAsInt());
        assertEquals(15_000_000L, video.get("bitRateBps").getAsLong());
        assertEquals(23_621L, video.get("fileDurationMs").getAsLong());
    }

    /**
     * A frame facts stay lit whatever the panel holds — above all {@code panelShape}, the one field that
     * separates "the aspect rule is working" from "the aspect rule is dark and kept every clip on its
     * default engine", which look identical from every other field in the document. Gating it on a video
     * being up would hide precisely the case it reports.
     */
    @Test
    public void theFramesOwnFactsAreReportedWhateverIsOnScreen() {
        JsonObject photo = VideoStateJson.build(
                PanelDisplay.image("k", "/x.jpg", GEN, WHEN), frame(), new NeverAskMeAboutThePlayer());
        assertEquals("1080x1920", photo.get("panelShape").getAsString());
        assertEquals("portrait", photo.get("frameOrientation").getAsString());
        assertTrue(photo.has("tsGate"));
        assertTrue(photo.get("tsGate").getAsJsonObject().has("decidedBy"));
        assertTrue(photo.get("tsSupported").getAsBoolean());
        assertTrue(photo.get("tsAvailable").getAsBoolean());
        assertTrue(photo.get("paintSignal").getAsBoolean());
        // Debug facts about the frame itself. The rate windows are NOT here — see
        // playbackRateIsNotReportedAsAFrameCapability.
        assertEquals(1, photo.get("tsFallbacks").getAsInt());
        assertFalse(photo.has("playbackRateX100"));
    }

    /**
     * A rate window is a measurement of one player over one attempt, not a capability of the machine.
     *
     * <p>A clip whose timeline advances while its photograph stays visible produces real samples, and
     * printing them beside {@code showing: image} would quote a video's playback rate as if it described the
     * frame. The cumulative {@code tsFallbacks} counter beside them is genuinely a frame fact — engine
     * handovers this process has made, in total — and stays.
     */
    @Test
    public void playbackRateIsNotReportedAsAFrameCapability() {
        JsonObject photo = VideoStateJson.build(
                PanelDisplay.image("k", "/x.jpg", GEN, WHEN), frame(), new NeverAskMeAboutThePlayer());
        assertFalse("a rate window from some player is printed over a photograph",
                photo.has("playbackRateX100"));

        JsonObject video = VideoStateJson.build(videoOnScreen(), frame(), new AnsweringProbe());
        assertEquals(100, video.get("playbackRateX100").getAsInt());
    }

    /**
     * Ownership has to be re-checked <em>after</em> the live reads, not only before them.
     *
     * <p>This probe goes stale on the last read, modelling the UI thread handing the player to the next
     * asset between two of them. Every live field must then be dropped together; a block that published
     * {@code positionMs} before noticing cannot take it back, which is why the reads are collected first.
     */
    @Test
    public void aHandoffPartwayThroughTheLiveReadsDiscardsAllOfThem() {
        AnsweringProbe probe = new AnsweringProbe() {
            @Override
            public int positionMs() {
                stale = true;
                return 4_200;
            }
        };
        JsonObject video = VideoStateJson.build(videoOnScreen(), frame(), probe);
        assertFalse("position was read before the handoff and published after it",
                video.has("positionMs"));
        assertFalse(video.has("durationMs"));
        assertFalse(video.has("painted"));
        assertFalse(video.has("loopPerf"));
        assertTrue("the record's own measurement is unaffected by a later handoff", video.has("codec"));
    }

    /** An unmeasured panel has to stay visible, and it is visible without a clip being up. */
    @Test
    public void anUnmeasuredPanelStillSaysSo() {
        VideoStateJson.Frame frame = frame();
        frame.panelShape = null;
        JsonObject photo = VideoStateJson.build(
                PanelDisplay.image("k", "/x.jpg", GEN, WHEN), frame, new NeverAskMeAboutThePlayer());
        assertEquals("unmeasured", photo.get("panelShape").getAsString());
    }

    @Test
    public void tsIsNotReportedUsableWhenTheSiliconHasNoPlayerForIt() {
        VideoStateJson.Frame frame = frame();
        frame.tsSupported = false;
        frame.tsLibraryLoaded = false;
        JsonObject photo = VideoStateJson.build(
                PanelDisplay.image("k", "/x.jpg", GEN, WHEN), frame, new NeverAskMeAboutThePlayer());
        assertFalse(photo.get("tsSupported").getAsBoolean());
        assertFalse("the .so loading is not the same claim as the board having one",
                photo.get("tsAvailable").getAsBoolean());
    }

    @Test
    public void aReleaseBuildStillAnswersWhatIsOnThePanel() {
        // The block exists because a release frame showing a black panel has to be able to say whether
        // a pixel ever reached it. Debug-only must never mean the whole answer.
        VideoStateJson.Frame frame = frame();
        frame.debugBuild = false;
        JsonObject photo = VideoStateJson.build(
                PanelDisplay.image("k", "/x.jpg", GEN, WHEN), frame, new NeverAskMeAboutThePlayer());
        assertFalse(photo.get("onScreen").getAsBoolean());
        assertEquals("image", photo.get("showing").getAsString());
        assertFalse(photo.has("playbackRateX100"));
        assertFalse(photo.has("loopPerf"));

        JsonObject video = VideoStateJson.build(videoOnScreen(), frame, new AnsweringProbe());
        assertTrue(video.has("codec"));
        assertFalse("TsPlayer-internal counters stay debug-only", video.has("errors"));
        assertFalse(video.has("loopPerf"));
    }

    /** The other half of the same rule: for a clip the panel holds, the measurement is reported. */
    @Test
    public void everyClipMeasurementIsEmittedForAClip() {
        JsonObject video = VideoStateJson.build(videoOnScreen(), frame(), new AnsweringProbe());
        for (String field : new String[]{"path", "stage", "rotation", "width", "height", "codec",
                "fpsMilli", "avgFrameBytes", "peakFrameBytes", "bitRateBps", "fileDurationMs",
                "macroblocksPerSecond", "renderSilent", "painted", "lastFrameAgeMs",
                "frameGapMs", "frameGapMaxMs", "durationMs", "positionMs"}) {
            assertTrue("a clip is on the panel, yet the block omits " + field, video.has(field));
        }
    }

    /**
     * The engine-specific cluster is the exception, and it is the earlier bug: `errors`,
     * `playerCreated` and friends describe the native view, so a clip the platform player composites
     * must not carry them.
     */
    @Test
    public void theNativeClusterIsAbsentForAPlatformCompositedClip() {
        JsonObject video = VideoStateJson.build(videoOnScreen(), frame(), new AnsweringProbe());
        for (String field : new String[]{"errors", "playerCreated", "surfaceReady",
                "recreatingForLoop"}) {
            assertFalse("a hidden view must not be reported as this clip's state: " + field,
                    video.has(field));
        }
    }

    @Test
    public void theLoopPerfObjectNamesTheEngineCompositing() {
        JsonObject video = VideoStateJson.build(videoOnScreen(), frame(), new AnsweringProbe());
        JsonObject loopPerf = video.getAsJsonObject("loopPerf");
        assertEquals(VideoEngineReport.MEDIA_PLAYER, loopPerf.get("engine").getAsString());
        // Named `manual*` on purpose: it counts restarts this app issues, which is not what the native
        // player's loop-cycle timing ever measured (that key is gone — its producer was deleted by
        // ee409ef). A platform clip that loops perfectly reads 0 / -1 here forever, and that is the design.
        assertEquals(0L, loopPerf.get("manualLoopRestarts").getAsLong());
        assertEquals(-1L, loopPerf.get("manualLoopCycleMs").getAsLong());
    }

    @Test
    public void aNativeClipReportsTheNativeCountersAndNoManualOnes() {
        // Which cluster is emitted follows the record's engine, not a fresh read of which controller is
        // live — so the fixture changes the display, not the probe.
        JsonObject video = VideoStateJson.build(
                videoOnScreen(VideoEngineReport.TS_PLAYER), frame(), new AnsweringProbe() {
                    @Override
                    public VideoStateJson.NativeCounters nativeCounters() {
                        VideoStateJson.NativeCounters counters = new VideoStateJson.NativeCounters();
                        counters.errors = 4;
                        counters.playerCreated = true;
                        counters.startMs = 8_000L;
                        return counters;
                    }
                });
        assertEquals(4, video.get("errors").getAsInt());
        assertEquals(VideoEngineReport.TS_PLAYER, video.get("engine").getAsString());
        JsonObject loopPerf = video.getAsJsonObject("loopPerf");
        assertEquals(VideoEngineReport.TS_PLAYER, loopPerf.get("engine").getAsString());
        assertEquals(8_000L, loopPerf.get("startMs").getAsLong());
        assertFalse(loopPerf.has("manualLoopRestarts"));
    }

    /** The engine in the report is the one the display carried, whatever the frame's default is. */
    @Test
    public void theReportedEngineIsTheDisplayNotTheFramesDefault() {
        VideoStateJson.Frame frame = frame();
        frame.engine = VideoEngineReport.TS_PLAYER;
        JsonObject video = VideoStateJson.build(videoOnScreen(), frame, new AnsweringProbe());
        assertEquals("routing handed a non-native clip to the platform player, so say that",
                VideoEngineReport.MEDIA_PLAYER, video.get("engine").getAsString());
    }

    /** Paint evidence only for a clip, and only where the engine can answer for it. */
    @Test
    public void paintEvidenceIsNotClaimedForAFrameShowingAPhotograph() {
        try {
            VideoStateJson.build(PanelDisplay.image("k", "/x.jpg", GEN, WHEN), frame(),
                    new NeverAskMeAboutThePlayer());
        } catch (AssertionError e) {
            fail("the block consulted the player while an image was on the panel: " + e.getMessage());
        }
        JsonObject video = VideoStateJson.build(videoOnScreen(), frame(), new AnsweringProbe());
        assertTrue(video.has("painted"));
        assertTrue(video.has("lastFrameAgeMs"));
        assertTrue(video.has("durationMs"));
        assertTrue(video.has("positionMs"));
    }

    /**
     * The one-read discipline the comments call load-bearing, as an assertion rather than a sentence.
     *
     * <p>{@code videoPlayer} is volatile and reassigned by the engine switch from both the UI thread and
     * the player's event thread, so a builder that asked twice could print one controller's
     * {@code paintSignal} beside another controller's frame stamp — the halves of one document describing
     * two engines, which is the failure the hoisted local exists to prevent.
     */
    /**
     * The capability claim and the engine named beside it cannot come from two different moments.
     *
     * <p>{@code videoPlayer} is volatile and an engine switch reassigns it while a web worker is halfway
     * through a document, so a builder that asks the controller for its paint capability after reading the
     * engine can print {@code engine: TsPlayer} beside {@code paintSignal: true} — and a capability claim,
     * unlike a measurement, is not something a later staleness check can retract. The no-clip path used to
     * do exactly that, and its own test fixture had to exempt that one call from its throwing discipline
     * because the answer was legitimately about the controller. It no longer asks, so a probe that throws on
     * <em>every</em> player read is now a valid fixture for a panel with no clip on it.
     */
    @Test
    public void paintSignalAndEngineCannotNameDifferentEngines() {
        VideoStateJson.Frame ts = frame();
        ts.engine = VideoEngineReport.TS_PLAYER;
        JsonObject photo = VideoStateJson.build(PanelDisplay.image("k", "/x.jpg", GEN, WHEN), ts,
                new NeverAskMeAboutThePlayer());
        assertEquals(VideoEngineReport.TS_PLAYER, photo.get("engine").getAsString());
        assertFalse("a photograph cannot borrow a paint signal the reported engine does not have",
                photo.get("paintSignal").getAsBoolean());

        JsonObject blank = VideoStateJson.build(PanelDisplay.nothing(GEN, WHEN), frame(),
                new NeverAskMeAboutThePlayer());
        assertEquals(VideoEngineReport.MEDIA_PLAYER, blank.get("engine").getAsString());
        assertTrue(blank.get("paintSignal").getAsBoolean());
    }

    /**
     * A capability claim has to agree with the engine it is reported beside.
     *
     * <p>TsPlayer's native library offers no per-frame proof, so {@code engine: TsPlayer} alongside
     * {@code paintSignal: true} is a contradiction inside one document — and unlike the live telemetry, a
     * later ownership check cannot retract it, because it was never a measurement of the clip. The probe
     * here answers "yes, there is a paint signal", which is what the <em>current</em> controller would say
     * after a handover to the platform player, to prove the answer is taken from the record instead.
     */
    @Test
    public void paintSignalFollowsTheReportedEngineNotTheLiveController() {
        VideoStateJson.Frame frame = frame();
        JsonObject ts = VideoStateJson.build(
                videoOnScreen(VideoEngineReport.TS_PLAYER), frame, new AnsweringProbe());
        assertFalse("a TsPlayer clip cannot prove a composited frame", ts.get("paintSignal").getAsBoolean());
        assertEquals(VideoEngineReport.TS_PLAYER, ts.get("engine").getAsString());

        JsonObject media = VideoStateJson.build(
                videoOnScreen(VideoEngineReport.MEDIA_PLAYER), frame, new AnsweringProbe());
        assertTrue(media.get("paintSignal").getAsBoolean());
    }

    /**
     * The document is assembled on a web worker while the UI thread keeps rotating. The snapshot's own
     * measurements cannot go stale and stay; everything read live from the player is suppressed rather than
     * paired with a clip that is no longer the one on the panel — and rather than read from a controller the
     * UI thread may have just released.
     */
    @Test
    public void aRotationMidDocumentKeepsTheSnapshotAndDropsTheLiveReads() {
        AnsweringProbe probe = new AnsweringProbe();
        probe.stale = true;
        JsonObject video = VideoStateJson.build(videoOnScreen(), frame(), probe);
        assertTrue("the record's own measurement is not affected by a handoff", video.has("codec"));
        assertTrue(video.has("bitRateBps"));
        assertFalse(video.has("durationMs"));
        assertFalse(video.has("positionMs"));
        assertFalse(video.has("painted"));
        assertFalse(video.has("lastFrameAgeMs"));
        assertFalse(video.has("loopPerf"));
        assertFalse(video.has("errors"));
    }

    /** A probe that answers, for the cases where a clip really is up. */
    private static class AnsweringProbe implements VideoStateJson.PlayerProbe {
        /** Flip it to model a rotation landing mid-document. */
        boolean stale;

        @Override
        public boolean stale() {
            return stale;
        }

        @Override
        public boolean painted() {
            return true;
        }

        @Override
        public long lastFrameAtMs() {
            return WHEN;
        }

        @Override
        public long gapSnapshot() {
            // One word carrying the pair the two getters used to return separately: latest 33, worst 413.
            return FrameGapWindow.pack(33, 413);
        }

        @Override
        public int durationMs() {
            return 23_000;
        }

        @Override
        public int positionMs() {
            return 4_200;
        }

        /**
         * Answered, not abstained. Returning null here would make the engine guard untestable: the
         * MediaPlayer-clip assertions would pass because the fixture declined to answer rather than
         * because {@code VideoStateJson} refused to ask, which is the difference between a test and a
         * tautology on the exact regression AGENTS.md documents verbatim.
         */
        @Override
        public VideoStateJson.NativeCounters nativeCounters() {
            VideoStateJson.NativeCounters counters = new VideoStateJson.NativeCounters();
            counters.errors = 3;
            counters.playerCreated = true;
            counters.surfaceReady = true;
            counters.recreatingForLoop = true;
            counters.startMs = 8_000L;
            return counters;
        }

        @Override
        public VideoStateJson.ManualCounters manualCounters() {
            return new VideoStateJson.ManualCounters();
        }

        @Override
        public boolean renderSilent(String assetKey) {
            return false;
        }
    }
}
