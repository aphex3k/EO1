package com.aphex3k.eo1;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the on-screen debug board is allowed to say, and about whom.
 *
 * <p>The board is one map every diagnostic writer in the app puts into, rendered twice — to the panel and
 * to {@code /state}'s {@code debug} block. It used to have no lifetime at all: no {@code remove()} or
 * {@code clear()} existed anywhere, so every key ever written stayed printed forever, and a video replaced
 * by a photograph left {@code displayVideo: /last.mp4} and {@code video: end pos=…} on the wall beside the
 * photograph's line. That is the report an operator reads as "it is still playing that clip".
 *
 * <p><b>The rule is attribution, not eviction.</b> An interim revision swept the map at each asset handoff,
 * and that turned out to be the wrong mechanism twice over. A sweep is an <em>ordering</em> rule, so it
 * depends on when a write happens relative to a handoff — and the writes do not cooperate: an acquisition
 * verdict ({@code videoBudget}, {@code Incompatible}) can land <em>before</em> the asset it names is
 * displayed, while a backend failure from {@code tagAssetAsIncompatible} can land <em>after</em> that asset
 * has left, and a sweep can only be correct for one of those. It also deleted the sentence explaining a
 * failure when the recovery that followed it happened to restate the same asset. So each row now carries
 * the asset it is about, and {@link #visible} renders a row only while that asset is what the panel holds.
 * Nothing is destroyed, so nothing can be destroyed too early or too late, and a late write about a
 * departed asset is simply not shown — while remaining in {@code files/eo1-app.log}, which is an
 * append-only transcript and is never filtered.
 *
 * <p>Two consequences: the map's keys are the fixed set of writer names, so it cannot grow without bound
 * and needs no eviction at all; and a writer that does not declare a subject is attributed implicitly to
 * whatever the panel holds when it writes — right for the display paths, wrong for anything running ahead
 * of or behind a display, which is why the media pipeline's verdict writers declare the asset key
 * explicitly.
 */
public final class DebugBoard {

    /**
     * Keys that describe the frame rather than an asset, so they render whatever is on the panel.
     *
     * <p>{@code "connection status"} is not a typo to be replaced by a resource reference: it is the value
     * of {@code R.string.connection_status_key}, and this class deliberately holds no {@code android.*} so
     * the rule stays testable on a JVM host. The two must be changed together.
     *
     * <p>A list of names reaches every writer whose key is a literal, which is all of them but one:
     * {@code BrightnessManager} reports a light sensor's accuracy under {@code sensor.getName()}, so no
     * literal could name it and it declares {@link DebugInformation#ofFrame} instead. Adding a frame-scoped
     * row with a computed key without that declaration is how a diagnostic silently stops outliving the
     * asset that happened to be on the wall when it was written.
     */
    public static final Set<String> FRAME_SCOPED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            // Which build is running, and who signed it.
            "version",
            // Per-device identity and its configuration problems. "device name" is the value of
            // SettingsManager.DEBUG_KEY_DEVICE_NAME, spelled out for the same reason as above.
            "device name",
            "Failed to delete outdated configuration file",
            // The panel and its schedule — none of which cares what is displayed on it.
            "screenHold", "screenOffWakeLock", "quietHours", "quietHoursSchedule",
            "startQuietHours", "endQuietHours",
            "Min Brightness", "Reported Light Value", "Calculated Brightness Value",
            // Who moved the backlight, and whether it stuck. Declared, because it is the answer to "it
            // dimmed by itself" — and left undeclared it would be attributed to whatever photo happened to
            // be up and vanish on the next rotation, which is the row this frame most needs to keep.
            "brightness step",
            // Network, broker, the frame's own watchdog, and the last physical key it handled. Input
            // state has nothing to do with an asset, and losing this row made a working frame look dead.
            "connection status", "mqtt", "watchdog", "vendorUpdater", "key",
            // Self-update, and a backend's compatibility verdict about its service (not about a clip).
            "Update", "Update Available", "compatibility",
            // The last thing that went wrong anywhere in the process, and the most recent reason an asset
            // did not make it onto the panel — which has to survive the recovery it explains. See
            // MainActivity.noteDisplayFailure for why this one row is deliberately not attributed.
            "Last Exception", "displayFailure")));

    /**
     * One board row: what it says, and which asset it is about.
     *
     * <p>{@code assetKey == null} means "about the frame", and renders always. Otherwise the row renders
     * only while that asset is what the panel holds.
     */
    public static final class Row {
        public final String value;
        public final String assetKey;
        /**
         * Whether this row describes a <em>video display</em> of that asset rather than the asset itself.
         *
         * <p>Asset identity alone is not enough for a measurement. A clip this device cannot draw is held as
         * render-silent and shown as its server-side still preview — same asset key, and now an image on the
         * wall — so a {@code videoBudget} row carrying coded size, frame rate and bitrate, or a {@code video}
         * row carrying a position, would go on describing a video over a photograph. That is the complaint
         * this whole change set opened with, arriving through the one door that a per-asset filter cannot
         * see: the asset did not change, only what kind of thing is being displayed of it.
         */
        public final boolean videoScoped;

        public Row(String value, String assetKey) {
            this(value, assetKey, false);
        }

        public Row(String value, String assetKey, boolean videoScoped) {
            this.value = value == null ? "" : value;
            this.assetKey = assetKey;
            this.videoScoped = videoScoped;
        }

        /** A row about the frame itself. */
        public static Row ofFrame(String value) {
            return new Row(value, null, false);
        }

        /** A row that is about this asset <em>as a video</em>, and retires when it stops being one. */
        public static Row ofVideo(String value, String assetKey) {
            return new Row(value, assetKey, true);
        }
    }

    private DebugBoard() {
    }

    /**
     * Keys whose rows describe the asset <em>as a video</em>, so they retire when the same asset is shown
     * some other way — a render-silent clip displayed as its still preview, most of all.
     *
     * <p>Declared here rather than passed by each writer because the answer is a property of the row, not of
     * the call site: eleven places write {@code video} and two write {@code videoBudget}, and a rule that
     * lives in eleven places is the rule that gets missed at the twelfth.
     */
    public static final Set<String> VIDEO_SCOPED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "video", "videoBudget")));
    // `displayVideo` is deliberately absent: after the display paths stopped writing a file name under it,
    // its only remaining use is "render-silent still <id>", which explains a <em>photograph</em> and must
    // stay up with it.

    /**
     * Whether a row about {@code key} is scoped to a video display of the asset.
     */
    public static boolean videoScoped(String key, boolean declared) {
        return declared || VIDEO_SCOPED.contains(key);
    }

    /**
     * The subject a write about {@code key} should get when the writer did not say.
     *
     * <p>Frame keys are unattributed; anything else is attributed to the asset the panel holds at the
     * moment of writing, which is the writer's best available claim about what it was describing. A writer
     * that can run ahead of a display (the media pipeline's verdicts) or behind one (a backend call that
     * finishes after a rotation) must declare its subject instead of relying on this.
     */
    public static String implicitSubject(String key, String currentAssetKey) {
        if (FRAME_SCOPED.contains(key)) {
            return null;
        }
        return currentAssetKey == null || currentAssetKey.isEmpty() ? "" : currentAssetKey;
    }

    /**
     * The subject a row gets, from what its writer declared and the board's rule for what it did not.
     *
     * <p>Extracted from the single write path in {@code MainActivity} because that path is an {@code Activity}
     * behind a live panel, and the whole question — which rows survive a rotation — is answerable on a JVM
     * host. Two declarations win over the rule, in order: a writer that named its asset, and a writer that
     * said the row is about the frame (which is how a <em>computed</em> key — a sensor's own name — escapes
     * a list of literal names). A writer that said neither is asked of the panel.
     */
    public static String subject(DebugInformation info, String currentAssetKey) {
        if (info.isFrameScoped()) {
            return null;
        }
        if (info.getAssetKey() != null) {
            return info.getAssetKey();
        }
        return implicitSubject(info.getKey(), currentAssetKey);
    }

    /**
     * The rows that may be printed right now, in a stable order, with the panel's own facts added.
     *
     * <p>Both renderers go through here, which is the point: the panel description and the routing sentence
     * are <em>derived</em> from {@code panel} rather than written into the map, so they cannot outlive the
     * display they describe and cannot disagree between the wall and {@code /state}. It is also what retires
     * a clip's measurements when the panel stops showing video — the image record that replaced it carries
     * no routing line, so there is nothing to render, and the failure sentence explaining the switch (an
     * {@code asset}-attributed row for the same asset) stays.
     *
     * @param rows         the live board, unmodified
     * @param panel        what the panel provably holds, or null before the first display
     */
    public static Map<String, String> visible(Map<String, Row> rows, PanelDisplay panel) {
        String showing = panel == null ? "" : panel.assetKey;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Row> entry : rows.entrySet()) {
            Row row = entry.getValue();
            if (row == null) {
                continue;
            }
            if (row.assetKey == null) {
                out.put(entry.getKey(), row.value);
            } else if (row.videoScoped) {
                // Both the asset and the kind of display have to match. A still preview of the very clip
                // these numbers were measured from is the same asset — and no longer a video.
                boolean videoUp = panel != null && panel.showsVideo()
                        && row.assetKey.equals(showing);
                if (videoUp) {
                    out.put(entry.getKey(), row.value);
                }
            } else if (row.assetKey.equals(showing)) {
                out.put(entry.getKey(), row.value);
            }
        }
        // Reserved keys, always the record's own words. No writer may use either name: a second opinion
        // about the screen is what this class was rewritten to make impossible.
        out.put(KEY_ON_SCREEN, panel == null ? "nothing yet" : panel.describe());
        String routing = panel == null ? "" : panel.routingSentence();
        if (routing.isEmpty()) {
            out.remove(KEY_ROUTING);
        } else {
            out.put(KEY_ROUTING, routing);
        }
        return out;
    }

    /** The panel's own line. Written by nobody, derived from {@link PanelDisplay#describe()} by everyone. */
    public static final String KEY_ON_SCREEN = "on screen";

    /** The routing sentence. Derived from {@link PanelDisplay#routingFacts}, so a photo cannot carry one. */
    public static final String KEY_ROUTING = "videoRouting";
}
