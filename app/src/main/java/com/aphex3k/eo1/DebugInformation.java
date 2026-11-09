package com.aphex3k.eo1;

import androidx.annotation.Keep;

/**
 * One diagnostic line, and — optionally — the asset it is about.
 *
 * <p>The subject exists because the board's lifetime cannot be inferred from when a write happens. A
 * media-pipeline verdict can be posted <em>before</em> the asset it names is displayed, and a backend
 * failure from {@code tagAssetAsIncompatible} can arrive <em>after</em> that asset has left the panel; any
 * rule based on ordering is correct for one and wrong for the other. A writer that knows its asset says
 * so, and the row is then shown only while that asset is what the frame is holding.
 *
 * <p>{@code null} means the writer did not say, and {@link DebugBoard#implicitSubject} decides — which is
 * right for the display paths and only a guess everywhere else.
 *
 * <p>A row about the <em>frame</em> normally needs no declaration either: {@link DebugBoard#FRAME_SCOPED}
 * carries those key names. {@link #ofFrame} is for the case a name list cannot reach — a writer whose key
 * is computed rather than literal, like a light sensor's accuracy reported under the sensor's own name.
 * Left to the name list it would be treated as an unclaimed key, attributed to whatever asset the panel
 * happened to hold, and disappear from both the overlay and {@code /state}'s {@code debug} block on the
 * next rotation.
 */
@Keep
public class DebugInformation {
    private final String key;
    private final String value;
    private final String assetKey;
    private final boolean videoScoped;
    private final boolean frameScoped;

    /**
     * A line about this asset <em>as a video</em> — a coded size, a bitrate, a playback position.
     *
     * <p>Asset identity is not enough for those. A clip held as render-silent is displayed as its still
     * preview, which is the same asset and an image, and a row that only knew the asset would keep describing
     * a video over that photograph.
     */
    public static DebugInformation ofVideo(String key, String value, String assetKey) {
        return new DebugInformation(key, value, assetKey, true, false);
    }

    /**
     * A line about the frame, stated by the writer rather than by a key the board has heard of.
     *
     * <p>Use it when the key is computed, so {@link DebugBoard#FRAME_SCOPED} cannot name it. Do not use it
     * to overrule the subject a writer already knows: an asset-scoped row that never goes away is the exact
     * stale line this board was rewritten to make impossible.
     */
    public static DebugInformation ofFrame(String key, String value) {
        return new DebugInformation(key, value, null, false, true);
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    /** The asset this line describes, or {@code null} when it describes the frame or did not say. */
    public String getAssetKey() {
        return assetKey;
    }

    /** Whether this line describes the asset as a video display. */
    public boolean isVideoScoped() {
        return videoScoped;
    }

    /** Whether the writer stated that this line is about the frame, whatever its key reads. */
    public boolean isFrameScoped() {
        return frameScoped;
    }

    DebugInformation(String key, String value) {
        this(key, value, null, false, false);
    }

    /**
     * @param assetKey the asset the line is about; {@code null} to leave the subject to the board's rule
     */
    DebugInformation(String key, String value, String assetKey) {
        this(key, value, assetKey, false, false);
    }

    DebugInformation(String key, String value, String assetKey, boolean videoScoped,
                     boolean frameScoped) {
        this.key = key;
        this.value = value;
        this.assetKey = assetKey;
        this.videoScoped = videoScoped;
        this.frameScoped = frameScoped;
        if (videoScoped && frameScoped) {
            throw new IllegalArgumentException("a row is about a video display or about the frame");
        }
    }
}
