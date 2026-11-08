package com.aphex3k.eo1;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The lifetime rule for the on-screen debug board.
 *
 * <p>The board is one {@code Map<String,String>} that every diagnostic writer in the app puts into and
 * that {@code MainActivity} re-renders in full — to the panel, and to {@code /state}'s {@code debug}
 * block. It had no lifetime at all: no {@code remove()} or {@code clear()} existed anywhere, so every
 * key ever written stayed printed forever, last-write-wins. A video replaced by a photograph therefore
 * left {@code displayVideo: /last.mp4} and {@code video: end pos=… duration=…} on the wall next to the
 * photograph's own line, which is the report an operator reads as "it is still playing that clip".
 *
 * <p>The rule that replaces that is per-<em>asset</em>, not per-key-and-call-site: when a different
 * asset comes onto the panel, every line that is not known to describe the frame rather than the asset
 * is dropped, and whatever the new asset earns is written after. Call it from
 * {@code MainActivity.panelNowShowing} only — one sweep per handoff, on the UI thread, under the map's
 * own monitor. Scattering a {@code remove()} beside each handoff is the shape that already shipped once
 * for {@code activeVideoPath} and did not fix this class of bug, because it asks ~58 writers to
 * remember a fact about a screen none of them can see.
 *
 * <p><b>Asset-scoped is the default, and that inversion is the load-bearing choice.</b> The two errors
 * are not symmetric. A frame line missing from {@link #FRAME_SCOPED} loses one row until the next event
 * that writes it — and most of them are re-seeded on every {@code onResume} or on every brightness
 * apply, so it self-heals. An asset line wrongly treated as persistent prints a false statement about
 * the panel for the rest of the process. Choose the failure that heals.
 *
 * <p>One consequence of a default that is a set complement: a writer whose key name is computed rather
 * than literal cannot be declared here. Two exist. The quiet-hours pair is passed through a local
 * {@code key} variable but has exactly two values, both listed below. The other is
 * {@code BrightnessManager.sensorAccuracyChanged}, which keys a line by
 * {@code Sensor.getName()} — a hardware inventory row that is therefore swept and not rewritten until
 * the next accuracy-change event, which may be never within a session. That is accepted, not missed:
 * the board's job is saying what is true <em>now</em>, the sensor inventory belongs to
 * {@code /state}'s {@code device} and {@code capabilities} blocks, and the line is still in
 * {@code files/eo1-app.log} — the sweep reaches the rendered board and never the durable log, which is
 * an append-only transcript by design.
 */
public final class DebugBoard {

    /**
     * The board's one line that names what is on the panel, written only by
     * {@code MainActivity.panelNowShowing} from {@link PanelDisplay#describe()}.
     *
     * <p>Reserved: no other writer may use it, because any second writer is a second opinion about the
     * screen and the whole point is that there is exactly one.
     */
    public static final String KEY_ON_SCREEN = "on screen";

    /**
     * Keys that survive an asset handoff, because they describe the frame rather than what it shows.
     *
     * <p>{@code "connection status"} is not a typo to be replaced by a resource reference: it is the
     * value of {@code R.string.connection_status_key}, and this class deliberately holds no
     * {@code android.*} so the rule stays testable on a JVM host. The two must be changed together.
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
            // Network, broker and the frame's own watchdog.
            "connection status", "mqtt", "watchdog", "vendorUpdater",
            // The last physical key this app handled. Input state, not asset state — and the row that
            // answers "did the frame hear me?" would otherwise disappear on the next rotation, which is a
            // diagnosis this repo has already chased twice.
            "key",
            // Self-update, and a backend's compatibility verdict about its service (not about a clip).
            "Update", "Update Available", "compatibility",
            // The last thing that went wrong anywhere in the process.
            "Last Exception")));

    private DebugBoard() {
    }

    /**
     * Whether adopting {@code next} in place of {@code previous} is an asset handoff — the only event
     * that may sweep the board.
     *
     * <p>Keyed on the asset, not on the call, because several paths restate the panel without changing
     * what is on it: a clip that failed and is now covered by a photo, and a page that failed to render,
     * both via {@code revealPhotoView}, and a mid-attempt engine handover. Treating those as handoffs
     * deleted the line explaining the failure on the statement after it was written — the one sentence
     * that says why the panel in front of an operator is blank.
     *
     * <p>Public, and separate from the sweep for one reason: this predicate is the entire policy, and
     * while it lived inline in an {@code Activity} no test could tell "sweeps when it should" from
     * "never sweeps" — and a permanently-false condition here reproduces the original bug exactly.
     */
    public static boolean sweepRequired(PanelDisplay previous, PanelDisplay next) {
        if (previous == null) {
            // Nothing was on the board's account of the panel, so there is no departed asset to unmention.
            return false;
        }
        return !previous.assetKey.equals(next == null ? "" : next.assetKey);
    }

    /**
     * Drops every line that describes a departed asset, keeping the frame's own and any line naming the
     * asset that is arriving.
     *
     * <p>The arriving-key test is not a convenience. Several acquisition verdicts — {@code videoBudget},
     * {@code Show}, {@code Thumbnail}, {@code Incompatible} — are written on the acquisition thread
     * <em>about</em> the asset that is then displayed, so they land while the previous asset is still the
     * one recorded, and a plain sweep would delete each of them at the moment its subject reached the
     * panel. Those lines carry the asset key in their value, which is what makes "is this line about the
     * thing on screen" answerable without asking fifty-eight writers to declare a lifetime. A line naming
     * no asset — a finished clip's {@code video: end pos=…} — is still dropped, which is the point.
     *
     * <p>{@code "on screen"} always goes: the caller rewrites it immediately from the new
     * {@link PanelDisplay}, and leaving the previous asset's sentence standing for even one render of the
     * gap is the bug this class exists to close.
     *
     * @param board the live debug board, mutated in place; the caller holds its monitor
     * @param arrivingKey the key of the asset now on the panel, or "" when it cannot be named
     */
    public static void onAssetHandoff(Map<String, String> board, String arrivingKey) {
        String key = arrivingKey == null ? "" : arrivingKey;
        Iterator<Map.Entry<String, String>> entries = board.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, String> entry = entries.next();
            if (FRAME_SCOPED.contains(entry.getKey())) {
                continue;
            }
            if (KEY_ON_SCREEN.equals(entry.getKey())) {
                entries.remove();
            } else if (key.isEmpty() || entry.getValue() == null
                    || !entry.getValue().contains(key)) {
                entries.remove();
            }
        }
    }
}
