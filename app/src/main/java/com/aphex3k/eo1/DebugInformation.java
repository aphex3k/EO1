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
 */
@Keep
public class DebugInformation {
    private final String key;
    private final String value;
    private final String assetKey;
    private final boolean videoScoped;

    /**
     * A line about this asset <em>as a video</em> — a coded size, a bitrate, a playback position.
     *
     * <p>Asset identity is not enough for those. A clip held as render-silent is displayed as its still
     * preview, which is the same asset and an image, and a row that only knew the asset would keep describing
     * a video over that photograph.
     */
    public static DebugInformation ofVideo(String key, String value, String assetKey) {
        return new DebugInformation(key, value, assetKey, true);
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

    DebugInformation(String key, String value) {
        this(key, value, null, false);
    }

    /**
     * @param assetKey the asset the line is about; {@code null} to leave the subject to the board's rule
     */
    DebugInformation(String key, String value, String assetKey) {
        this(key, value, assetKey, false);
    }

    DebugInformation(String key, String value, String assetKey, boolean videoScoped) {
        this.key = key;
        this.value = value;
        this.assetKey = assetKey;
        this.videoScoped = videoScoped;
    }
}
