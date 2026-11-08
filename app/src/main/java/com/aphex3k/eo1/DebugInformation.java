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

    DebugInformation(String key, String value) {
        this(key, value, null);
    }

    /**
     * @param assetKey the asset the line is about; {@code null} to leave the subject to the board's rule
     */
    DebugInformation(String key, String value, String assetKey) {
        this.key = key;
        this.value = value;
        this.assetKey = assetKey;
    }
}
