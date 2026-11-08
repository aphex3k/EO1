package com.aphex3k.eo1;

import com.google.gson.JsonObject;

/**
 * The {@code /state} {@code video} block, built from what the panel holds.
 *
 * <p>Extracted from {@code MainActivity} for the reason every other reporting rule in this package was
 * extracted: while it sat inside an {@code Activity} behind a live player and a view tree, no test could
 * ask the one question that matters here: what does this block say about a clip while a photograph is on
 * the screen? It answered wrongly twice — once by reporting a hidden TsPlayer view for a clip the
 * platform player was compositing, and once by reporting the last clip's codec, coded size, frame rate
 * and bitrate over whatever the frame had moved on to. Both were found by reading the source, not by a
 * failing test, which is how a fix that looks finished stays wrong.
 *
 * <p>The rule the whole class enforces: a field that describes the clip being displayed is emitted only
 * when {@link PanelDisplay#showsVideo()} and only from {@link PanelDisplay#clip}. Fields that describe
 * the <em>frame</em> — which engine it prefers, how the TsPlayer gate decided, the panel's own shape —
 * are emitted whatever is on screen, because they are true whatever is on screen. Among the latter is
 * {@code panelShape}: with an unmeasured panel the aspect predicate returns false for every clip, so the
 * frame looks exactly as it did before the rule existed, and this field is the only way to tell a working
 * rule from a dark one. Gating it on a video being up would hide precisely the case it reports.
 *
 * <p>Two consequences of that rule are load-bearing beyond tidiness. {@code durationMs} and
 * {@code positionMs} are <em>absent</em>, not zero or -1, when nothing is playing: producing them meant
 * calling into the player on the web server's thread while the UI thread could be releasing that same
 * player. And {@code painted} is likewise absent, so a frame whose last clip composited and then left the
 * panel cannot report paint evidence for a photograph.
 */
public final class VideoStateJson {

    /**
     * Facts about the frame rather than the asset: what it is capable of and how it chose an engine.
     *
     * <p>Public mutable fields because this is a transcription of state for one render of one report,
     * filled at the call site. {@code panelShape} stays a string here so the builder needs no
     * {@code MediaCompatibility} types and no Android at all.
     */
    public static final class Frame {
        public String engine = "";
        public boolean preferTsPlayer;
        public boolean tsSupported;
        public boolean tsLibraryLoaded;
        public String tsLoadError;
        public String tsSilicon = VideoSilicon.UNKNOWN;
        public String modelProfile = "";
        public boolean tsModelAllows;
        public String tsGateAxis = "";
        public boolean buildFlag;
        public String panelShape;
        public String frameOrientation;
        public int tsFallbacks;
        public int playbackRateX100 = -1;
        public int playbackRateMinX100 = -1;
        public int playbackRateMaxX100 = -1;
        public int playbackRateSamples;
        public boolean debugBuild;
    }

    /**
     * Live reads off whatever player is compositing.
     *
     * <p>The builder calls these <em>only</em> for a video record, which is the point of putting them
     * behind an interface rather than passing values in: a value passed in has to be read before the
     * builder knows whether it is allowed to exist, and reading it is the part that is unsafe when the
     * panel holds a photograph. A test asserts the refusal by supplying a probe that throws.
     */
    public interface PlayerProbe {
        /** Whether the compositing engine can report a composited frame at all. */
        boolean paintSignal();

        boolean painted();

        long lastFrameAtMs();

        int durationMs();

        int positionMs();

        /** The native player's own counters, or null when it is not compositing. */
        NativeCounters nativeCounters();

        /** Restart counts this app issues on the platform player, or null when it is not compositing. */
        ManualCounters manualCounters();

        /** Whether this asset key is filed under "rendered nothing on this device". */
        boolean renderSilent(String assetKey);
    }

    /** The native player's per-loop counters. Every value is the engine's own measurement. */
    public static final class NativeCounters {
        public int generation;
        public int errors;
        public boolean playerCreated;
        public boolean surfaceReady;
        public boolean recreatingForLoop;
        public long restartLatencyMs;
        public long createPlayerMs;
        public long setSurfaceMs;
        public long startMs;
        public long restartToPlayingMs;
        public long endToPlayingMs;
        public long loopCycleMs;
        public long posAtTriggerMs;
        public String restartMethod = "";
    }

    /**
     * Restarts <em>this app</em> issued on the platform player.
     *
     * <p>Not the same measurement as {@link NativeCounters}, and deliberately not given the same key:
     * with {@code setLooping(true)} the platform player loops inside itself and raises no callback, so a
     * clip looping perfectly reads {@code 0} / {@code -1} here forever. That is the design, and reading
     * it as a stall sends an operator chasing a frame that is fine.
     */
    public static final class ManualCounters {
        public long manualLoopRestarts;
        public long manualLoopCycleMs = -1;
    }

    private VideoStateJson() {
    }

    /**
     * @param shown what the panel holds, or null before the first display of the session
     */
    public static JsonObject build(PanelDisplay shown, Frame frame, PlayerProbe probe) {
        JsonObject video = new JsonObject();
        boolean onScreen = shown != null && shown.showsVideo();

        // Answers the question the block used to be unable to ask. `engine` is what the earlier field
        // was actually reporting under the name `active`, which is why nothing here could tell "a clip
        // is playing on MediaPlayer" from "a photograph has been up for an hour".
        video.addProperty("onScreen", onScreen);
        video.addProperty("showing", shown == null ? "none" : mediaTypeName(shown.type));
        video.addProperty("engine", onScreen ? shown.engine : frame.engine);
        video.addProperty("preferTsPlayer", frame.preferTsPlayer);
        // "usable on this frame", not "the .so happened to load": the platform test is written first, so
        // a board whose silicon has no vendor player never reaches the class and never triggers the
        // dlopen it would fail. `tsAvailable: false` without `tsSupported: false` implies the opposite.
        video.addProperty("tsSupported", frame.tsSupported);
        video.addProperty("tsAvailable", frame.tsSupported && frame.tsLibraryLoaded);
        // Every input to the gate, so the decision can be rebuilt from the document instead of inferred
        // from its outcome: a frame that says only "MediaPlayer" cannot be told apart from one that was
        // never going to say anything else, and that difference is the whole report.
        JsonObject tsGate = new JsonObject();
        tsGate.addProperty("silicon", frame.tsSilicon);
        tsGate.addProperty("modelProfile", frame.modelProfile);
        tsGate.addProperty("modelAllows", frame.tsModelAllows);
        tsGate.addProperty("decidedBy", frame.tsGateAxis);
        tsGate.addProperty("buildFlag", frame.buildFlag);
        tsGate.addProperty("libraryLoaded", frame.tsLibraryLoaded);
        video.add("tsGate", tsGate);
        if (frame.tsLoadError != null) {
            video.addProperty("tsLoadError", frame.tsLoadError);
        }
        // Asked once. Whether the engine can report a composited frame is a property of the controller
        // class rather than of playback, so it is a frame fact — and it is also the guard on the two clip
        // fields that need it, which must not be answered by a second, possibly different read.
        boolean paintSignal = probe.paintSignal();
        video.addProperty("paintSignal", paintSignal);
        if (frame.frameOrientation != null) {
            video.addProperty("frameOrientation", frame.frameOrientation);
        }
        // The number the aspect rule decided against, whatever is on the panel. An "unmeasured" here is
        // the only thing that separates a working rule from a dark one.
        video.addProperty("panelShape", frame.panelShape == null ? "unmeasured" : frame.panelShape);

        if (!onScreen) {
            // Nothing below this line may be emitted, because every field beneath it is a measurement of
            // a clip. This early return is the fix; the guard used to be six fields nobody cleared.
            addDebugFrameFields(video, frame);
            return video;
        }

        PanelDisplay.ClipFacts clip = shown.clip;
        video.addProperty("path", shown.pathOrUrl);
        video.addProperty("stage", shown.stage);
        video.addProperty("rotation", clip.rotationDeg);
        video.addProperty("width", clip.codedWidth);
        video.addProperty("height", clip.codedHeight);
        if (clip.codec != null) {
            video.addProperty("codec", clip.codec);
        }
        video.addProperty("fpsMilli", clip.fpsMilli);
        video.addProperty("avgFrameBytes", clip.avgFrameBytes);
        video.addProperty("peakFrameBytes", clip.peakFrameBytes);
        video.addProperty("bitRateBps", clip.bitRateBps);
        video.addProperty("fileDurationMs", clip.fileDurationMs);
        video.addProperty("macroblocksPerSecond", MediaCompatibility.videoMacroblocksPerSecond(
                clip.codedWidth, clip.codedHeight, clip.fpsMilli));
        video.addProperty("renderSilent", probe.renderSilent(shown.assetKey));

        // "painted" is only a claim where the engine can answer it. TsPlayer's .so offers no per-frame
        // signal, so reporting true there would be a tautology on the one field this exists to answer.
        // The answer is the one read at the top of the block, not a second call: asking twice means the
        // two halves of one document can describe two different controllers.
        if (paintSignal) {
            video.addProperty("painted", probe.painted());
            long lastFrameAtMs = probe.lastFrameAtMs();
            video.addProperty("lastFrameAgeMs", lastFrameAtMs > 0L
                    ? (System.currentTimeMillis() - lastFrameAtMs) : -1L);
        }
        video.addProperty("durationMs", probe.durationMs());
        video.addProperty("positionMs", probe.positionMs());

        addDebugFrameFields(video, frame);

        if (frame.debugBuild) {
            // Everything below belongs to the engine compositing this clip. These were once read off the
            // TsPlayer view unconditionally, so for a platform-player clip `generation`,
            // `playerCreated`, `surfaceReady` and the whole loop-perf object described a hidden view
            // playing nothing.
            // From the record rather than a fresh read of which controller is live: the engine that was
            // handed these bytes is part of the display, and a second opinion about it can disagree with
            // the `engine` field five lines above.
            boolean tsActive = VideoEngineReport.TS_PLAYER.equals(shown.engine);
            NativeCounters counters = tsActive ? probe.nativeCounters() : null;
            if (counters != null) {
                video.addProperty("generation", counters.generation);
                video.addProperty("errors", counters.errors);
                video.addProperty("playerCreated", counters.playerCreated);
                video.addProperty("surfaceReady", counters.surfaceReady);
                video.addProperty("recreatingForLoop", counters.recreatingForLoop);
                JsonObject loopPerf = new JsonObject();
                loopPerf.addProperty("engine", VideoEngineReport.TS_PLAYER);
                loopPerf.addProperty("restartLatencyMs", counters.restartLatencyMs);
                loopPerf.addProperty("createPlayerMs", counters.createPlayerMs);
                loopPerf.addProperty("setSurfaceMs", counters.setSurfaceMs);
                loopPerf.addProperty("startMs", counters.startMs);
                loopPerf.addProperty("restartToPlayingMs", counters.restartToPlayingMs);
                loopPerf.addProperty("endToPlayingMs", counters.endToPlayingMs);
                loopPerf.addProperty("loopCycleMs", counters.loopCycleMs);
                loopPerf.addProperty("posAtTriggerMs", counters.posAtTriggerMs);
                loopPerf.addProperty("restartMethod", counters.restartMethod);
                video.add("loopPerf", loopPerf);
            } else {
                ManualCounters manual = probe.manualCounters();
                JsonObject loopPerf = new JsonObject();
                loopPerf.addProperty("engine", VideoEngineReport.MEDIA_PLAYER);
                if (manual != null) {
                    loopPerf.addProperty("manualLoopRestarts", manual.manualLoopRestarts);
                    loopPerf.addProperty("manualLoopCycleMs", manual.manualLoopCycleMs);
                }
                video.add("loopPerf", loopPerf);
            }
        }
        return video;
    }

    /** Debug-only facts about the frame itself, which are true whatever the panel holds. */
    private static void addDebugFrameFields(JsonObject video, Frame frame) {
        if (!frame.debugBuild) {
            return;
        }
        video.addProperty("tsFallbacks", frame.tsFallbacks);
        video.addProperty("playbackRateX100", frame.playbackRateX100);
        video.addProperty("playbackRateMinX100", frame.playbackRateMinX100);
        video.addProperty("playbackRateMaxX100", frame.playbackRateMaxX100);
        video.addProperty("playbackRateSamples", frame.playbackRateSamples);
    }

    /** The word {@code /state} has always used for an asset type, kept where both surfaces can share it. */
    static String mediaTypeName(com.aphex3k.media.MediaType type) {
        if (type == null) {
            return "none";
        }
        switch (type) {
            case VIDEO:
                return "video";
            case WEB:
                return "web";
            default:
                return "image";
        }
    }

    /** The clip-scoped field names, so a test can assert that not one of them survives a handoff. */
    static final String[] CLIP_FIELDS = {
            "path", "stage", "rotation", "width", "height", "codec", "fpsMilli", "avgFrameBytes",
            "peakFrameBytes", "bitRateBps", "fileDurationMs", "macroblocksPerSecond", "renderSilent",
            "painted", "lastFrameAgeMs", "durationMs", "positionMs", "generation", "errors",
            "playerCreated", "surfaceReady", "recreatingForLoop", "loopPerf"};
}
