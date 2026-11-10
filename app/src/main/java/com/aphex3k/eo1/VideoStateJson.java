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
        /**
         * Whether the panel stopped being this document's subject while it was being built.
         *
         * <p>{@code /state} is assembled on a web worker while the UI thread keeps rotating, so the record
         * is a snapshot and the player is not. Without this, a handoff landing between the two can print the
         * departed clip's path and engine beside the new player's position — or read a player the UI thread
         * has just released. A builder that finds itself stale reports <em>nothing</em> from the player: an
         * absent field is a fact, a mixed one is a lie.
         */
        boolean stale();

        boolean painted();

        long lastFrameAtMs();

        /**
         * Spacing of the two most recent composited frames and the worst since this source was set, in the
         * one word they were published in — {@link FrameGapWindow#latestOf(long)} and
         * {@link FrameGapWindow#maxOf(long)} decode it.
         *
         * <p>One method rather than two getters on purpose: the pair has to come from a single load, because
         * a surface reset between two loads changes nothing this builder can see.
         */
        long gapSnapshot();

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
        // Whether a composited frame can be proven at all is a property of the <em>engine</em>, not of
        // playback — so it is derived from the same engine string this document printed one line above, on
        // both paths: from the clip's own record when there is one, from the frame fact when there is not.
        // Asking the live controller instead — which the no-clip branch used to do, with the same
        // justification the clip branch had already abandoned — reads one fact twice, and `videoPlayer` is
        // reassigned by an engine switch while a web worker is halfway through a document. The result was
        // `engine: TsPlayer` beside `paintSignal: true`, a capability claim for a compositor this very
        // document says is not compositing; and unlike a measurement, a capability cannot be retracted by a
        // later staleness check, because it is not about a clip at all.
        boolean paintSignal = !VideoEngineReport.TS_PLAYER.equals(onScreen ? shown.engine : frame.engine);
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

        // Everything from here down is read live, so it is emitted only while the snapshot still names the
        // panel. The measurements above came from the record and cannot go stale; these come from a player
        // the UI thread may have handed to another asset — or released — since the record was taken.
        boolean live = !probe.stale();
        if (!live) {
            addDebugFrameFields(video, frame);
            return video;
        }
        // Read into locals first, then re-validate ownership before any of it is published. Checking once
        // up front is not enough: these are calls into a player the UI thread can hand to the next asset
        // between two of them, so the last read could describe a different display than the first — and a
        // block that has already written `positionMs` cannot take it back. Discarding the whole set on a
        // changed owner is the only honest outcome, and it is why nothing live is added to `video` until
        // this point returns.
        boolean painted = false;
        long lastFrameAgeMs = -1L;
        int lastFrameGapMs = FrameGapWindow.NO_GAP;
        int maxFrameGapMs = FrameGapWindow.NO_GAP;
        if (paintSignal) {
            painted = probe.painted();
            long lastFrameAtMs = probe.lastFrameAtMs();
            lastFrameAgeMs = lastFrameAtMs > 0L ? (System.currentTimeMillis() - lastFrameAtMs) : -1L;
            // One load, two decodes. Two getter calls would be two samples of a value that a surface reset
            // can clear between them, and nothing downstream could tell: the reset changes neither the panel
            // record nor which controller is compositing, so the ownership re-check below accepts the pair.
            long gaps = probe.gapSnapshot();
            lastFrameGapMs = FrameGapWindow.latestOf(gaps);
            maxFrameGapMs = FrameGapWindow.maxOf(gaps);
        }
        int durationMs = probe.durationMs();
        int positionMs = probe.positionMs();
        // Which engine's counters to take is answered from the record, the same answer that produced the
        // `engine` field above — not from a fresh look at which controller is live, which is a second
        // opinion about a display this block has already committed to describing.
        boolean tsActive = VideoEngineReport.TS_PLAYER.equals(shown.engine);
        NativeCounters nativeCounters = frame.debugBuild && tsActive ? probe.nativeCounters() : null;
        ManualCounters manualCounters = frame.debugBuild && !tsActive ? probe.manualCounters() : null;

        if (probe.stale()) {
            // Something reached the wall while this document was being assembled. The record's own
            // measurements above stand — they came from the snapshot — but every number read live is
            // dropped together rather than partly published, because a half-collected set is exactly the
            // pairing of one clip's path with another player's position that this block exists to prevent.
            addDebugFrameFields(video, frame);
            return video;
        }

        if (paintSignal) {
            video.addProperty("painted", painted);
            video.addProperty("lastFrameAgeMs", lastFrameAgeMs);
            // Spacing, which the age is not: the age says when the last buffer arrived, these say how far
            // apart they came. Deliberately NOT behind frame.debugBuild, because the hold they exist to
            // catch happens on shipped builds. Read FrameGapWindow before trusting a small value — the
            // counter sees buffers reaching a draw pass, not changes of picture, so a wide value is
            // evidence of a stall while a narrow one is not evidence that the image changed. -1 is "no
            // record": TsPlayer has none, and neither does a clip under two frames.
            video.addProperty("frameGapMs", lastFrameGapMs);
            video.addProperty("frameGapMaxMs", maxFrameGapMs);
        }
        video.addProperty("durationMs", durationMs);
        video.addProperty("positionMs", positionMs);
        // Measured from this player's timeline over this attempt, and reset per attempt: a clip whose
        // timeline advances while its photograph is still visible produces real rate samples, and printing
        // them beside `showing: image` would quote a video's rate as the frame's. `tsFallbacks` beside them
        // is genuinely a frame fact — a cumulative counter of engine handovers this process has made.
        if (frame.debugBuild) {
            video.addProperty("playbackRateX100", frame.playbackRateX100);
            video.addProperty("playbackRateMinX100", frame.playbackRateMinX100);
            video.addProperty("playbackRateMaxX100", frame.playbackRateMaxX100);
            video.addProperty("playbackRateSamples", frame.playbackRateSamples);
        }

        addDebugFrameFields(video, frame);

        // These belong to the engine compositing this clip, and were once read off the TsPlayer view
        // unconditionally — so for a platform-player clip `generation`, `playerCreated`, `surfaceReady` and
        // the whole loop-perf object described a hidden view playing nothing. They are taken above, before
        // the ownership revalidation, and never re-read here: a second pass of live calls after that check
        // would put the reads it is meant to guard back outside it.
        if (frame.debugBuild) {
            NativeCounters counters = nativeCounters;
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
                JsonObject loopPerf = new JsonObject();
                loopPerf.addProperty("engine", VideoEngineReport.MEDIA_PLAYER);
                if (manualCounters != null) {
                    loopPerf.addProperty("manualLoopRestarts", manualCounters.manualLoopRestarts);
                    loopPerf.addProperty("manualLoopCycleMs", manualCounters.manualLoopCycleMs);
                }
                video.add("loopPerf", loopPerf);
            }
        }
        return video;
    }

    /**
     * Debug-only facts about the frame itself, true whatever the panel holds.
     *
     * <p>{@code playbackRate*} deliberately is not here. Those are derived from one player's position over
     * one attempt and reset when the attempt resets, which makes them a measurement of a display rather
     * than a capability of the machine — emitted from here they would appear beside {@code showing: image}
     * for a clip whose timeline advanced while its photograph stayed visible. {@code tsFallbacks} does
     * belong: it counts engine handovers this process has made, in total.
     */
    private static void addDebugFrameFields(JsonObject video, Frame frame) {
        if (!frame.debugBuild) {
            return;
        }
        video.addProperty("tsFallbacks", frame.tsFallbacks);
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
            "painted", "lastFrameAgeMs", "frameGapMs", "frameGapMaxMs", "durationMs", "positionMs",
            "generation", "errors",
            "playerCreated", "surfaceReady", "recreatingForLoop", "loopPerf"};
}
