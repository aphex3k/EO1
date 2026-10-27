package com.aphex3k.media.flickr;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What this backend knows it is holding: which Flickr photos it has bytes for, when each was last
 * shown, and which ones an owner asked it to stop holding.
 *
 * <p>It exists because Flickr's API terms make three promises on this device that the shared cache
 * cannot keep. LRU eviction is capacity-driven, not age-driven, so a photo nobody displays again is
 * never removed by it — the terms ask for storage only "for reasonable periods", which needs an age
 * rule of the backend's own. Absence is the privacy signal: a photo that turned private stops
 * appearing in {@code people.getPublicPhotos}, and the bytes on this device must follow. And a
 * removal request has to survive the next rotation — which is the part a delete cannot do: if the
 * catalog still lists the photo, the next tick downloads it again, so the request needs a durable
 * exclusion, not just freed space.
 *
 * <h2>Why the ledger is persisted and what that buys</h2>
 *
 * An in-memory id set would satisfy the privacy rule inside a process and forget it at restart,
 * leaving the privatised photo's bytes on disk with nothing left referencing them. The file is what
 * closes that gap. It is written once per pool generation (and after a forget), never per rotation
 * tick: on a 30-day retention window, losing one generation's worth of "last shown" times costs a
 * single early re-download and saves writing a few hundred kilobytes to flash every five minutes.
 *
 * <h2>What a wrong clock can and cannot do here</h2>
 *
 * The frame keeps no time sync, so ages are computed against a wall clock that can be badly wrong.
 * Every failure direction is a needless re-download, never a privacy miss: {@code nowMs} behind
 * {@code lastUsedMs} makes an age negative, which is "not expired", and the next clock correction
 * expires it; {@code nowMs} ahead expires early, which fetches the photo again. The two obligations
 * that must not slip — privatised eviction and the removal flag — are driven by catalog state and by
 * an operator's action, not by the clock, so no clock error can soften either.
 */
final class FlickrRetention {

    /** How long an asset may sit unshown before its bytes are dropped. A terms choice, not a capacity one. */
    static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    /**
     * Bound on tracked entries. Roughly what this device's cache can hold at Flickr's ~140 KB per
     * 1024px still, so a ledger that hits the cap has stopped being able to account for files rather
     * than run out of room to describe them — which is reported, never assumed away.
     */
    static final int MAX_ENTRIES = 6000;

    private static final String TAG = "EO1";

    private static final class Entry {
        long lastUsedMs;
        boolean forgotten;
    }

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean dirty;
    private int droppedForCap;

    /**
     * @param file where this backend's ledger lives, or {@code null} for no durable state — a test
     *        convenience that must never be the production answer, because without a file a removal
     *        flag is forgotten at the next restart and the photo comes back.
     */
    FlickrRetention(@Nullable File file) {
        this.file = file;
    }

    /** Reads the ledger, tolerating a missing or damaged file: the catalog is authoritative anyway. */
    void load() {
        droppedForCap = 0;
        entries.clear();
        dirty = false;
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            JsonElement root = new JsonParser().parse(new FileReader(file));
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject map = root.getAsJsonObject().getAsJsonObject("entries");
            if (map == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> row : map.entrySet()) {
                if (!row.getValue().isJsonObject()) {
                    continue;
                }
                Entry entry = new Entry();
                JsonObject value = row.getValue().getAsJsonObject();
                JsonElement used = value.get("u");
                if (used != null && used.isJsonPrimitive()) {
                    try {
                        entry.lastUsedMs = used.getAsLong();
                    } catch (NumberFormatException ignored) {
                        // A row we cannot read is a row we cannot honour; leave it un-used, which
                        // expires it on the next sweep rather than trusting a corrupt timestamp.
                    }
                }
                JsonElement forgotten = value.get("f");
                entry.forgotten = forgotten != null && forgotten.isJsonPrimitive()
                        && forgotten.getAsBoolean();
                entries.put(row.getKey(), entry);
            }
        } catch (Exception e) {
            Log.w(TAG, "Flickr retention ledger unreadable at " + file.getName() + ": " + e);
        }
    }

    /**
     * Registers an id this backend has just been shown by the listing, without claiming any bytes for
     * it. Two facts have to be kept apart: whether this device holds a photo's bytes (what the idle
     * window governs) and whether this frame has ever <em>seen</em> the photo in a stream (what a
     * privacy eviction and a removal request have to reach). Tracking only displayed photos would make
     * "forget everything from this backend" miss every photo that had not come up yet — and the next
     * catalog build would happily re-list and re-download them.
     *
     * <p>An id registered here and never displayed keeps {@code lastUsedMs} at zero, which
     * {@link #expired} skips: it has no bytes to age out, and a listing timestamp must never become the
     * idle clock, or a photo in active rotation would never expire and the window would be meaningless.
     */
    void noteListed(String assetId) {
        if (!entries.containsKey(assetId)) {
            Entry entry = new Entry();
            entries.put(assetId, entry);
            dirty = true;
        }
    }

    /** Called when the catalog build finished, so the ledger can be persisted for the next process. */
    void save() {
        if (file == null || !dirty) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "Flickr retention: cannot create " + parent.getAbsolutePath());
            return;
        }
        JsonObject map = new JsonObject();
        for (Map.Entry<String, Entry> row : entries.entrySet()) {
            JsonObject value = new JsonObject();
            value.addProperty("u", row.getValue().lastUsedMs);
            if (row.getValue().forgotten) {
                value.addProperty("f", true);
            }
            map.add(row.getKey(), value);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("entries", map);
        try {
            FileWriter writer = new FileWriter(file);
            try {
                writer.write(root.toString());
            } finally {
                writer.close();
            }
            dirty = false;
        } catch (Exception e) {
            Log.w(TAG, "Flickr retention ledger could not be written: " + e);
        }
    }

    /** True when an owner asked for this asset to be forgotten; such an id never re-enters the pool. */
    boolean isForgotten(String assetId) {
        Entry entry = entries.get(assetId);
        return entry != null && entry.forgotten;
    }

    /**
     * Records that this asset's bytes are held, or renews the idle window if they already are.
     *
     * <p>The caller must only report time for an asset it has just seen in a successful, untruncated
     * catalog. Renewal is what keeps a photo in active rotation from churning its own cache, and the
     * one place it must not apply is a photo that has quietly gone private — restarting that clock
     * would keep the bytes past the point where the terms say to drop them.
     */
    void noteHeld(String assetId, long nowMs) {
        Entry existing = entries.get(assetId);
        if (existing != null && existing.forgotten) {
            return;
        }
        if (existing == null) {
            existing = new Entry();
            entries.put(assetId, existing);
        }
        existing.lastUsedMs = nowMs;
        dirty = true;
    }

    /**
     * Ids this ledger still accounts for that a completed catalog no longer lists — deleted, or made
     * private. Their bytes go, and so do their entries.
     *
     * @param complete false when the build failed or hit the record ceiling, because then absence proves
     *        nothing about the tail and the honest answer is to drop nothing.
     */
    List<String> vanishedFrom(Set<String> listedIds, boolean complete) {
        List<String> gone = new ArrayList<>();
        if (!complete) {
            return gone;
        }
        for (Iterator<String> it = entries.keySet().iterator(); it.hasNext(); ) {
            String id = it.next();
            Entry entry = entries.get(id);
            if (entry != null && entry.forgotten) {
                continue;
            }
            if (!listedIds.contains(id)) {
                gone.add(id);
                it.remove();
                dirty = true;
            }
        }
        return gone;
    }

    /** Ids whose idle window has run out. Their entries stay: the next display renews them. */
    List<String> expired(long nowMs) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> row : entries.entrySet()) {
            Entry entry = row.getValue();
            if (entry.forgotten || entry.lastUsedMs <= 0) {
                continue;
            }
            if (nowMs - entry.lastUsedMs > RETENTION_MS) {
                out.add(row.getKey());
            }
        }
        return out;
    }

    /**
     * Stops accounting for an asset whose bytes were just dropped, so a later display starts a fresh
     * window rather than inheriting an expired one.
     */
    void forgetBytes(String assetId) {
        Entry entry = entries.get(assetId);
        if (entry != null) {
            entry.lastUsedMs = 0;
            dirty = true;
        }
    }

    /**
     * The removal request: mark the asset so it leaves the pool and never returns, and keep the entry
     * even past the ledger cap. A forgotten row that aged out of the file would silently re-admit the
     * photo on the next catalog build, which is the clause this exists to satisfy.
     */
    void markForgotten(String assetId) {
        Entry entry = entries.get(assetId);
        if (entry == null) {
            entry = new Entry();
            entries.put(assetId, entry);
        }
        entry.forgotten = true;
        dirty = true;
    }

    /** Every id this ledger knows, including forgotten ones — what "forget everything" must clear. */
    Set<String> knownIds() {
        return new HashSet<>(entries.keySet());
    }

    /**
     * Trims to the cap after a catalog build, oldest-used first, never touching a forgotten row.
     *
     * @return how many entries had to be let go; the caller reports it, because an unaccounted-for
     *         cached file must never be quietly assumed deleted.
     */
    int trimToCap() {
        int overflow = entries.size() - MAX_ENTRIES;
        if (overflow <= 0) {
            return 0;
        }
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        while (overflow > 0) {
            oldestKey = null;
            oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> row : entries.entrySet()) {
                if (row.getValue().forgotten) {
                    continue;
                }
                if (row.getValue().lastUsedMs <= oldest) {
                    oldest = row.getValue().lastUsedMs;
                    oldestKey = row.getKey();
                }
            }
            if (oldestKey == null) {
                break;
            }
            entries.remove(oldestKey);
            dirty = true;
            droppedForCap++;
            overflow--;
        }
        return droppedForCap;
    }

    /** How many rows the cap has forced out over this process. */
    int capDrops() {
        return droppedForCap;
    }

    /** True when there is no file to write to, which is a state the backend must not hide. */
    boolean isDurable() {
        return file != null;
    }
}
