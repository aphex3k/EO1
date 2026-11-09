package com.aphex3k.eo1;

import com.aphex3k.media.MediaType;

/**
 * What the panel is holding at this instant, as one immutable value.
 *
 * <p>This is the only answer in the app to "what is on the wall", and it exists because there was
 * none. {@code /state}'s video block and the on-screen debug board both reported per-clip facts
 * (codec, coded size, frame rate, bitrate, rotation) out of fields that only a video display ever
 * wrote and nothing ever cleared — so a frame showing a photograph for a week went on describing the
 * last clip it had played, and the panel's own debug text printed the dead clip's lines next to the
 * live photograph's. Both surfaces were answering a question nobody had posed: neither could ask
 * whether a video was on screen at all.
 *
 * <p>The invariant that makes that unstatable is {@link #clip}: a {@link ClipFacts} reference exists
 * if and only if {@link #type} is {@link MediaType#VIDEO}. Reporting a clip property therefore
 * requires reading it off a value that cannot exist while a photograph or a web page is up, so the
 * stale-data bug is a type error rather than a line someone forgot to write.
 *
 * <p>Deliberately absent: the panel's own shape and orientation. Those are properties of the wall,
 * published by the layout listener whether or not any clip has ever played, and folding them in here
 * would make a frame that cannot measure its viewport indistinguishable from one that has not yet
 * needed to — which is the one signal {@code panelShape: "unmeasured"} carries.
 *
 * <p>Holds key <em>strings</em> rather than a {@code MediaAsset} or any {@code android.*} reference,
 * so the rule "describe only what the panel holds" is testable on a JVM host. Written on the UI
 * thread, read from the web server's worker — which is why the holder in {@code MainActivity} is
 * {@code volatile} and this object is never mutated after construction.
 */
public final class PanelDisplay {

    /**
     * Everything the routing decision learned about the bytes of one clip.
     *
     * <p>One value rather than six fields for the same reason {@code PanelDisplay} is one value: a
     * reader cannot see half of one asset's numbers and half of another's. {@code -1} in any numeric
     * field means the header probe could not answer, which is a different claim from zero.
     */
    public static final class ClipFacts {
        /** A record for a clip whose header was never read: every field unknown, none of them zero. */
        public static final ClipFacts UNKNOWN =
                new ClipFacts(-1, -1, -1, null, -1, -1L, -1L, -1L, -1L);

        /** Display-matrix rotation in degrees, or -1 when the header had no matrix. */
        public final int rotationDeg;
        /** Coded width and height from the sample description, falling back to the catalog's. */
        public final int codedWidth;
        public final int codedHeight;
        /** Fourcc-derived codec name, or null when unreadable. */
        public final String codec;
        /** From {@code VideoHeaderProbe.StreamStats}; -1 where that reported -1. */
        public final int fpsMilli;
        public final long avgFrameBytes;
        public final long peakFrameBytes;
        public final long bitRateBps;
        /** The clip's real length from its own {@code mdhd}, not the player's shorter answer. */
        public final long fileDurationMs;

        public ClipFacts(int rotationDeg, int codedWidth, int codedHeight, String codec,
                         int fpsMilli, long avgFrameBytes, long peakFrameBytes, long bitRateBps,
                         long fileDurationMs) {
            this.rotationDeg = rotationDeg;
            this.codedWidth = codedWidth;
            this.codedHeight = codedHeight;
            this.codec = codec;
            this.fpsMilli = fpsMilli;
            this.avgFrameBytes = avgFrameBytes;
            this.peakFrameBytes = peakFrameBytes;
            this.bitRateBps = bitRateBps;
            this.fileDurationMs = fileDurationMs;
        }
    }

    /**
     * {@code null} means the panel is showing nothing — the state after the operator asks the frame to
     * forget the asset that was up. It is a distinct value rather than an {@link #image} with empty
     * fields because "a photograph whose file we cannot name" and "no photograph at all" are different
     * answers, and collapsing them would let a cleared panel claim an image.
     */
    public final MediaType type;
    /** Rotation pool key of the asset on the panel; "" when the panel holds something unkeyed. */
    public final String assetKey;
    /** File path or page URL, as the display path proved it; "" when nothing is claimed. */
    public final String pathOrUrl;
    /** {@code VideoEngineReport} word for the engine compositing this clip; "" unless VIDEO. */
    public final String engine;
    /**
     * {@code DisplayStage} name for this attempt; "" unless VIDEO.
     *
     * <p>Kept beside the path because an original and the server's degraded encode of one clip are the
     * same size, duration and codec: the path and the rung are the only things on {@code /state} that
     * tell them apart, and therefore the only thing that says whether the compatibility ladder actually
     * descended.
     */
    public final String stage;
    /** Non-null exactly when {@link #type} is VIDEO. The invariant this class exists to hold. */
    public final ClipFacts clip;
    /**
     * The routing sentence for these bytes, carried here rather than written straight to the board.
     *
     * <p>Not a convenience: {@code routingAllowsTsPlayer} runs <em>before</em> the handoff that adopts
     * this asset, so a line it published itself was swept away by that handoff — the routing verdict
     * for the clip on screen missing from the board, on the one asset whose whole diagnosis is the
     * routing. Holding it in the value means the sweep happens first and the line arrives with the
     * asset it describes, in the order the facts were learned.
     */
    public final String routingFacts;
    /** This attempt's creation number — <em>not</em> a count of times the panel changed. */
    public final long generation;
    /** When this display was <em>decided on</em>. When it reached the wall is the panel's business. */
    public final long sinceMs;

    /**
     * Package-private rather than private so the invariant below is a tested claim and not a sentence in
     * a javadoc block. The factories are the API; this is the gate they all pass through.
     */
    PanelDisplay(MediaType type, String assetKey, String pathOrUrl, String engine,
                 String stage, ClipFacts clip, String routingFacts,
                 long generation, long sinceMs) {
        if ((type == MediaType.VIDEO) != (clip != null)) {
            throw new IllegalArgumentException("clip belongs to a video and only to a video");
        }
        this.type = type;
        this.assetKey = assetKey == null ? "" : assetKey;
        this.pathOrUrl = pathOrUrl == null ? "" : pathOrUrl;
        this.engine = engine == null ? "" : engine;
        this.stage = stage == null ? "" : stage;
        this.clip = clip;
        this.routingFacts = routingFacts == null ? "" : routingFacts;
        this.generation = generation;
        this.sinceMs = sinceMs;
    }

    /**
     * There is no factory for "nothing <em>yet</em>", and that is deliberate: before the first display of
     * the session the holder in {@code MainActivity} is {@code null}, because a record is only ever built
     * from a display that actually happened. {@link #nothing} is the other case — something was on the
     * panel and has been taken away.
     */
    public static PanelDisplay image(String assetKey, String path, long generation, long sinceMs) {
        return new PanelDisplay(MediaType.IMAGE, assetKey, path, "", "", null, "",
                generation, sinceMs);
    }

    /**
     * The panel is holding nothing: the surface was cleared, as it is when the operator asks the frame to
     * forget the asset that was up and there is no other asset to show in its place.
     *
     * <p>{@link #image} with empty fields would be a false statement of the same shape this class exists
     * to prevent — a report claiming a photograph on a wall that shows nothing.
     */
    public static PanelDisplay nothing(long generation, long sinceMs) {
        return new PanelDisplay(null, "", "", "", "", null, "", generation, sinceMs);
    }

    public static PanelDisplay web(String assetKey, String url, long generation, long sinceMs) {
        return new PanelDisplay(MediaType.WEB, assetKey, url, "", "", null, "",
                generation, sinceMs);
    }

    /**
     * The panel holds these bytes, on this engine, at this rung.
     *
     * @param engine {@code VideoEngineReport}'s word for the controller that was handed the file — not
     *               for what routing would have preferred, which is a different claim on a frame whose
     *               native library never loaded
     * @param clip   what the header probe read, or {@link ClipFacts#UNKNOWN} for bytes that reached the
     *               panel without a routing pass; never a previous clip's measurement, because -1 says
     *               "not known" and a stale number does not
     */
    public static PanelDisplay video(String assetKey, String path, String engine, String stage,
                                     ClipFacts clip, String routingFacts,
                                     long generation, long sinceMs) {
        return new PanelDisplay(MediaType.VIDEO, assetKey, path, engine, stage,
                clip == null ? ClipFacts.UNKNOWN : clip, routingFacts, generation, sinceMs);
    }

    public boolean showsVideo() {
        return type == MediaType.VIDEO;
    }

    /**
     * Whether {@code record} may still take the panel: it is the display in flight, or it is what is
     * provably up.
     *
     * <p>Separated from the view work it guards because the answer decides whether a clip reaches the
     * screen at all, and the field it reads lives in an {@code Activity} no JVM test can construct. A
     * reveal refused for a record that is neither leaves the surface it was meant for covered by whatever
     * was up before it — which is why an in-flight attempt may be re-pointed by a newer display, but must
     * never simply be cleared to improve what a report says. The attempt is an identity, not a report.
     */
    public static boolean mayBeRevealed(PanelDisplay record, PanelDisplay attempt, PanelDisplay proven) {
        return record != null && (record == attempt || record == proven);
    }

    /**
     * The record that already holds measurements of <em>these</em> bytes, or null when nothing does.
     *
     * <p>A caller that re-displays one file on the other engine runs no fresh routing pass, so it needs the
     * numbers learned the first time — and needs them from whichever field still holds them. Which field
     * that is cannot be predicted: handing the controller to a new engine clears the owner before the
     * replacement looks anything up, and an asset whose still preview is pending leaves the panel naming
     * the clip underneath it while the attempt names the preview. So every live record is asked, and the
     * answer is constrained by what makes the numbers true rather than by where they were found: the same
     * asset, and the same rung of the ladder, because an original and the server's transcode of it share an
     * asset key and describe different bytes.
     *
     * @param stage the {@link #stage} being attempted; a record from another rung is a different file
     */
    public static PanelDisplay carryFrom(String assetId, String stage, PanelDisplay... candidates) {
        if (assetId == null || assetId.isEmpty()) {
            return null;
        }
        for (PanelDisplay candidate : candidates) {
            if (candidate != null && candidate.showsVideo() && assetId.equals(candidate.assetKey)
                    && (stage == null || stage.equals(candidate.stage))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The routing sentence as an operator reads it: what was measured, then the compositor that
     * <em>decision</em> produced — appended, never stored.
     *
     * <p>The compositor is a property of the record, not of the measurement, because the two can part
     * company: a clip routed to TsPlayer and then handed to the platform player after a stalled attempt
     * carries the original routing facts, and a sentence that had baked {@code -> TsPlayer} into them
     * contradicted the {@code engine} field beside it, on the board and in {@code /state}'s debug block.
     */
    public String routingSentence() {
        if (routingFacts.isEmpty()) {
            return "";
        }
        return engine.isEmpty() ? routingFacts : routingFacts + " -> " + engine;
    }

    /**
     * Instances of this class are never copied, and that is load-bearing.
     *
     * <p>An earlier revision minted a new record at adoption so that {@code media.screen.generation} would
     * move forward with the wall rather than with the decisions that preceded it. The cost was worse than
     * the bug it fixed: every "is this still the display I belong to?" test in {@code MainActivity} and in
     * the {@code /state} probe compares <em>references</em>, and re-stamping made a record stop being equal
     * to its own adopted copy — so an ownership check could report a live display as stale, and a surface
     * could be uncovered on the strength of an attempt that had already been superseded.
     *
     * <p>Counting promotions therefore lives in {@code MainActivity}, where it belongs. A record identifies
     * a display <em>attempt</em> and carries the sequence number it was created with; how many times the
     * panel has actually changed hands, and when, are facts about the panel.
     */

    /** The one debug-board line that can never disagree with the panel, because this writes it. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (type == null) {
            return "nothing on the panel";
        }
        switch (type) {
            case VIDEO:
                text.append("video");
                break;
            case WEB:
                text.append("web");
                break;
            default:
                text.append("image");
                break;
        }
        // "(unknown)" rather than nothing at all: a handoff that reveals the photo still held by the
        // image view knows the panel shows a photograph but not which one, and an empty field would
        // read as "nothing on the panel" — a different and worse claim.
        text.append(' ').append(pathOrUrl.isEmpty() ? "(unknown)" : pathOrUrl);
        if (showsVideo() && (!engine.isEmpty() || !stage.isEmpty())) {
            text.append(" (").append(engine);
            if (!stage.isEmpty()) {
                text.append(engine.isEmpty() ? "" : ", ").append(stage);
            }
            text.append(')');
        }
        return text.toString();
    }
}
