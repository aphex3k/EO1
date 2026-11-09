package com.aphex3k.eo1;

/**
 * What one pass of the video routing rule concluded, returned rather than stored.
 *
 * <p>{@code MainActivity.routingAllowsTsPlayer} used to answer a boolean and, on the side, write six
 * fields — rotation, coded size, codec, stream stats, and the panel it judged them against — which
 * {@code /state} then reported unconditionally. Those fields were written only when a video was
 * displayed and cleared by nobody, so they described the last clip a frame had played no matter what
 * was on the panel now. Returning the measurement instead makes the caller put it where it can only be
 * read while the clip it came from is the clip on screen: inside a {@link PanelDisplay.ClipFacts}.
 *
 * <p>{@link #allowTsPlayer} stays separate from {@link #engineWord} because they are not the same
 * claim — the first says what these bytes are suitable for, the second says what this frame will
 * actually composite them with, and on a build that compiled the native engine out they disagree.
 * {@code MainActivity} ANDs the first with {@code preferTsPlayer}; {@code VideoEngineReport} folds
 * both into the word.
 */
public final class VideoRoutingDecision {

    /** Whether {@code VideoRoutingRule} would hand these bytes to the native player. */
    public final boolean allowTsPlayer;
    /** The engine this frame will composite them with, as one word. */
    public final String engineWord;
    /** What the header probe (and, failing that, the catalog) says about the file. */
    public final PanelDisplay.ClipFacts clip;
    /**
     * The routing sentence, in the wording the debug board has always carried it in — codec, rotation,
     * coded size, the panel it was judged against, and the engine it came out as.
     */
    public final String routingFacts;

    public VideoRoutingDecision(boolean allowTsPlayer, String engineWord,
                               PanelDisplay.ClipFacts clip, String routingFacts) {
        this.allowTsPlayer = allowTsPlayer;
        this.engineWord = engineWord;
        this.clip = clip == null ? PanelDisplay.ClipFacts.UNKNOWN : clip;
        this.routingFacts = routingFacts == null ? "" : routingFacts;
    }
}
