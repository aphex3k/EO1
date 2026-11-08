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
            // Network, broker, the frame's own watchdog, and the last physical key it handled. Input
            // state has nothing to do with an asset, and losing this row made a working frame look dead.
            "connection status", "mqtt", "watchdog", "vendorUpdater", "key",
            // Self-update, and a backend's compatibility verdict about its service (not about a clip).
            "Update", "Update Available", "compatibility",
            // The last thing that went wrong anywhere in the process.
            "Last Exception")));

    /**
     * One board row: what it says, and which asset it is about.
     *
     * <p>{@code assetKey == null} means "about the frame", and renders always. Otherwise the row renders
     * only while that asset is what the panel holds.
     */
    public static final class Row {
        public final String value;
        public final String assetKey;

        public Row(String value, String assetKey) {
            this.value = value == null ? "" : value;
            this.assetKey = assetKey;
        }

        /** A row about the frame itself. */
        public static Row ofFrame(String value) {
            return new Row(value, null);
        }
    }

    private DebugBoard() {
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
            if (row.assetKey == null || row.assetKey.equals(showing)) {
                out.put(entry.getKey(), row.value);
            }
        }
        // Reserved keys, always the record's own words. No writer may use either name: a second opinion
        // about the screen is what this class was rewritten to make impossible.
        out.put(KEY_ON_SCREEN, panel == null ? "nothing yet" : panel.describe());
        if (panel != null && !panel.routingLine.isEmpty()) {
            out.put(KEY_ROUTING, panel.routingLine);
        } else {
            out.remove(KEY_ROUTING);
        }
        return out;
    }

    /** The panel's own line. Written by nobody, derived from {@link PanelDisplay#describe()} by everyone. */
    public static final String KEY_ON_SCREEN = "on screen";

    /** The routing sentence. Derived from {@link PanelDisplay#routingLine}, so a photo cannot carry one. */
    public static final String KEY_ROUTING = "videoRouting";
}
