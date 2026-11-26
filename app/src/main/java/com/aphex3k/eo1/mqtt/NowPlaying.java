package com.aphex3k.eo1.mqtt;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the frame's now-playing banner should say about a coordinator state, and for how long.
 *
 * <p>This exists because the drawing path had no rule in it. Until now the only test between a
 * {@code sonos/+} document and the wall was {@code payload.currentTrack == null}: three
 * {@code setText} calls, a {@code VISIBLE}, and an alpha of 1. Every field a coordinator left out
 * therefore <em>erased</em> the correct banner rather than leaving it alone — a state that names a
 * track without naming its text (line-in, radio, the queue mid-change, or {@code "currentTrack":{}},
 * which {@code MqttPoisonMessageTest.anEmptyTrackObjectIsNotReported} establishes is legitimate) drew
 * a box holding the group name alone, or a box holding nothing at all. Both were photographed on real
 * frames. An empty banner is not a benign outcome: a dark rounded rectangle with no text in it is the
 * one thing on this panel that reads as a broken frame rather than as a quiet house.
 *
 * <p>So the decision lives here, and the caller only applies it. Three rules follow, and each is a
 * thing an edit to {@code MainActivity} can silently undo:
 *
 * <ul>
 *   <li><b>Nothing to say is not a card.</b> {@link Action#SHOW} exists only for a card with at least
 *       one non-empty row, enforced in the constructor — an empty box is unrepresentable rather than
 *       discouraged.</li>
 *   <li><b>An uninformative state changes nothing.</b> A document that names no track makes no claim
 *       about a track, so it must not clear a card that a document which <em>did</em> name one put up
 *       ({@link Action#KEEP}). The reverse is also held: a state that names a <em>different</em> track
 *       and carries no readable text does clear, because the card on the wall is then about something
 *       that has stopped. Neither of those can be expressed by the {@code null} this method used to
 *       return, which is why it answers an action.</li>
 *   <li><b>Identity is not text.</b> {@link #identity()} is built from the coordinator's uuid and the
 *       queue item's id, never from a title — see its javadoc for why the concatenated
 *       {@code title_artist_duration} key it replaces could neither recognise a re-publish nor tell two
 *       tracks apart.</li>
 * </ul>
 *
 * <p>Free of {@code android.*} by design, for the same reason {@code VideoRoutingRule},
 * {@code VideoLoopSeamRule} and {@code BrightnessReportRule} are: the caller was an anonymous listener
 * inside an {@code Activity} behind a live broker, and as long as the rule lived there, <b>no test
 * covered the state-to-banner mapping, the duration spelling, the identity or the group name at
 * all</b> — {@code MqttPoisonMessageTest} stopped at "did a callback fire". {@code Payload} and
 * {@code TTrack} are already android-free, so {@code NowPlayingTest} can build a coordinator document
 * field by field. That this is the third copy of the argument in this repository is the point: every
 * time, the fix was to move the decision out, not to test around it.
 */
public final class NowPlaying {

    /**
     * What a state obliges the wall to do.
     *
     * <p>{@code KEEP} is not a failure or an unimplemented case — it is the answer to "this document
     * says nothing that bears on the card", and it is the difference between a banner that survives a
     * metadata-less publish and one that blanks on it.
     */
    public enum Action { SHOW, CLEAR, KEEP }

    /** How long a card stays up when the state says nothing usable about its length. */
    public static final long DEFAULT_DWELL_MS = 120L * 1000L;

    /** Ceiling on any one dwell — see {@link #dwellMs(String)}. */
    public static final long MAX_DWELL_MS = 30L * 60L * 1000L;

    /** The card's rows, in the order the banner stacks them, for {@link Decision#missingRows()}. */
    private static final String ROW_TITLE = "title";
    private static final String ROW_ARTIST = "artist";
    private static final String ROW_ROOM = "room";

    /** {@code PT1H2M3S} and every partial form of it. ISO 8601 duration, as some bridges write it. */
    private static final Pattern ISO_DURATION =
            Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?",
                    Pattern.CASE_INSENSITIVE);

    /** A bare count of seconds. Bounded to seven digits so {@code parseLong} cannot throw on a typo. */
    private static final Pattern PLAIN_SECONDS = Pattern.compile("\\d{1,7}");

    /**
     * A decision about the wall: what to do, and with what words if it is to draw.
     *
     * <p>One type so that the pairing cannot be got wrong from either side. A {@code SHOW} without a
     * card, a card with every row empty, and a {@code CLEAR} carrying text are all unconstructable,
     * which is what makes the state in the second paragraph of this file's javadoc impossible to
     * re-create at the call site.
     */
    public static final class Decision {

        private final Action action;
        @Nullable
        private final NowPlaying card;

        private Decision(Action action, @Nullable NowPlaying card) {
            if (action == Action.SHOW && (card == null || card.isEmpty())) {
                throw new IllegalArgumentException("a card with nothing in it is not a decision");
            }
            if (action != Action.SHOW && card != null) {
                throw new IllegalArgumentException("only SHOW carries a card");
            }
            this.action = action;
            this.card = card;
        }

        static Decision show(NowPlaying card) {
            return new Decision(Action.SHOW, card);
        }

        static Decision keep() {
            return new Decision(Action.KEEP, null);
        }

        static Decision clear() {
            return new Decision(Action.CLEAR, null);
        }

        public Action action() {
            return action;
        }

        /** Non-null for {@link Action#SHOW}, null for everything else. */
        @Nullable
        public NowPlaying card() {
            return card;
        }

        /** The rows this decision would leave blank, for {@link #missingRows()}. */
        public String missingRows() {
            return card == null ? "" : card.missingRows();
        }
    }

    private final String title;
    private final String artist;
    private final String room;
    @Nullable
    private final String identity;
    private final long dwellMs;

    private NowPlaying(String title, String artist, String room,
                       @Nullable String identity, long dwellMs) {
        this.title = title;
        this.artist = artist;
        this.room = room;
        this.identity = identity;
        this.dwellMs = dwellMs;
    }

    /** Row 1. Never empty: a card whose only claim is a room name is the room's row, not this one. */
    public String title() {
        return title;
    }

    /** Row 2. May be {@code ""} — the caller hides an empty row rather than leaving a gap for it. */
    public String artist() {
        return artist;
    }

    /** Row 3, the group or player name. May be {@code ""} for a coordinator that reports neither. */
    public String room() {
        return room;
    }

    /**
     * What makes two announcements the same announcement, or {@code null} when nothing structural
     * distinguishes them.
     *
     * <p>Deliberately carries no display text. The key this replaces was
     * {@code groupName + "_" + title + "_" + artist + "_" + duration}, and that shape was wrong in both
     * directions. A coordinator republishing the same state — which Sonos bridges do continuously, not
     * only on a change — produced the same key, and the caller used equality to decide whether to
     * <em>fade</em> rather than whether to <em>re-arm</em>, so a banner sat up indefinitely on a frame
     * that had merely been told the truth twice. And two different tracks sharing title, artist and
     * length (a live album's two halves, one track on two albums) produced the same key, so a real
     * change looked like a re-publish. Neither half of that is fixable with text.
     *
     * <p>{@code null} is an answer, not a gap: it means this document carries nothing this app can use
     * to recognise it again, and the caller treats it as "changed" — re-arming the timer every time. A
     * frame that re-arms is a frame whose banner never hides while the broker is alive, which is the
     * correct reading of a coordinator that keeps saying it is playing, and is strictly better than a
     * frame that concludes nothing has changed from an absence of evidence.
     */
    @Nullable
    public String identity() {
        return identity;
    }

    /** Milliseconds until this card should fade. Always positive, always ≤ {@link #MAX_DWELL_MS}. */
    public long dwellMs() {
        return dwellMs;
    }

    /**
     * {@code ""} when all three rows carry text; otherwise the names of the rows that arrived empty, as
     * {@code "artist"} or {@code "artist, room"}.
     *
     * <p>Values are never quoted, for the reason {@code MqttManager.appendKeyNames} gives: this string
     * reaches a log the web page serves, and a house's listening history has no business in it. This
     * reports a <em>partial</em> card, which is the case {@code MqttManager.reportBlankBanner} cannot
     * see — that one fires only when the app reads nothing at all out of a track, and so stays silent
     * on exactly the state the group-name-only photograph shows.
     */
    public String missingRows() {
        StringBuilder missing = new StringBuilder();
        if (title.isEmpty()) {
            missing.append(ROW_TITLE);
        }
        if (artist.isEmpty()) {
            append(missing, ROW_ARTIST);
        }
        if (room.isEmpty()) {
            append(missing, ROW_ROOM);
        }
        return missing.toString();
    }

    private static void append(StringBuilder into, String name) {
        if (into.length() > 0) {
            into.append(", ");
        }
        into.append(name);
    }

    private boolean isEmpty() {
        return title.isEmpty() && artist.isEmpty() && room.isEmpty();
    }

    /**
     * The whole mapping, for a state {@code MqttManager} has already routed to
     * {@code onPlaybackStarted}. It does not re-read {@code transportState}: the state table belongs to
     * {@code MqttManager}, and a second copy of it is a second thing to keep in step.
     *
     * @param shownIdentity the {@link #identity()} of the card currently on the wall, or {@code null}
     *                      when nothing is. Passed in rather than remembered here so this stays a
     *                      function of its arguments, like {@code BrightnessReportRule.shouldWrite} and
     *                      its last-written buckets.
     */
    public static Decision decide(@Nullable Payload state, @Nullable String shownIdentity) {
        if (state == null || state.currentTrack == null) {
            // No track named at all is a state that makes no claim about a track, and a coordinator
            // that omits currentTrack on some of its publishes is not reporting silence.
            return Decision.keep();
        }

        String title = text(state.currentTrack.title);
        if (title.isEmpty()) {
            // The source label ranks above the queue entry's title on purpose. A line-in or a radio
            // stream has no title, and enqueuedMetadata on those bridges names what the queue is
            // holding next — presenting the queue's next item as the thing playing is a specific lie,
            // and "Radio" is a vague truth. Order them the other way and this app would confidently
            // name the wrong song.
            String source = sourceLabel(state.currentTrack.trackURI);
            if (source != null) {
                title = source;
            } else if (state.enqueuedMetadata != null) {
                title = text(state.enqueuedMetadata.title);
            }
            if (title.isEmpty()) {
                title = text(state.name);
            }
        }

        // No fallback for the artist, deliberately: every candidate (album, the player's name, the
        // room) is a different fact, and a card that mislabels an album as an artist is worse than one
        // that leaves the row out.
        String artist = text(state.currentTrack.artist);

        String room = text(state.groupName);
        if (room.isEmpty()) {
            room = text(state.name);
        }
        if (room.equals(title)) {
            // A player that is not in a group reports its own room name and no groupName at all, which
            // would draw "Kitchen" twice on one card. The title row already says it.
            room = "";
        }

        String identity = identityOf(state);

        if (title.isEmpty() && artist.isEmpty() && room.isEmpty()) {
            // Nothing readable, so nothing to draw. What is already up depends on whether this
            // document contradicts it: a nameless track that is the one already on the wall has not
            // changed, and a different one has.
            if (identity == null || shownIdentity == null
                    || identity.equals(shownIdentity)) {
                return Decision.keep();
            }
            return Decision.clear();
        }

        return Decision.show(new NowPlaying(title, artist, room, identity,
                dwellMs(state.currentTrack.duration)));
    }

    /**
     * The dwell this state asks for: always positive, always bounded, never "no timer at all".
     *
     * <p>The spelling it accepts has never been confirmed against a capture of this bridge — AGENTS.md
     * records that no document observed here contained a {@code Duration} at all, and the code this
     * replaces matched {@code \d{1,2}:\d{2}:\d{2}} while its own comment gave {@code "03:45"}, an
     * example that does not match it. So the fallback and the ceiling carry the design and the parse is
     * a tolerance, not a claim. {@code H:MM:SS}, {@code MM:SS}, a bare count of seconds and ISO
     * {@code PT#M#S} are accepted; anything else is the default.
     *
     * <p>{@code "0:00:00"} matters more than it looks. It is what a coordinator reports for a stream and
     * often for a track it has just been asked about, and the old path computed 0 from it, failed
     * {@code if (durationMs > 0)}, and scheduled <em>no</em> fade — a banner that could then only be
     * dismissed by a stop whose text happened to match. Here a zero is the default, like any other
     * unreadable length.
     *
     * <p>The ceiling exists because a radio stream whose {@code Duration} is {@code 99:59:59} would
     * otherwise schedule a fade 100 hours out, and a frame whose broker had died would wear one
     * station's name for four days. Anything past half an hour is a stream or a podcast, which the
     * coordinator re-publishes as it goes, so the ceiling is invisible while the link is alive.
     */
    static long dwellMs(@Nullable String duration) {
        long seconds = -1L;
        if (duration != null) {
            String value = duration.trim();
            String[] parts = value.split(":");
            if (parts.length == 3) {
                seconds = clockSeconds(parts[0], parts[1], parts[2]);
            } else if (parts.length == 2) {
                seconds = clockSeconds("0", parts[0], parts[1]);
            } else if (PLAIN_SECONDS.matcher(value).matches()) {
                seconds = Long.parseLong(value);
            } else {
                Matcher iso = ISO_DURATION.matcher(value);
                if (iso.matches()) {
                    seconds = clockSeconds(iso.group(1), iso.group(2), iso.group(3));
                }
            }
        }
        if (seconds <= 0L) {
            return DEFAULT_DWELL_MS;
        }
        long millis = seconds * 1000L;
        return millis > MAX_DWELL_MS ? MAX_DWELL_MS : millis;
    }

    /**
     * {@code h*3600 + m*60 + s}, with a blank or absent group meaning zero, and {@code -1} for any
     * group that is not one to three digits.
     *
     * <p>The {@code -1} path is the point: {@code "?:??:??"} is what UPnP sends for a live stream, and a
     * coordinator that says "of unknown length" must land on the default rather than on a
     * {@code NumberFormatException} on the UI thread.
     */
    private static long clockSeconds(String hours, @Nullable String minutes, @Nullable String seconds) {
        long h = component(hours);
        long m = component(minutes);
        int dot = seconds == null ? -1 : seconds.indexOf('.');
        long s = component(dot < 0 ? seconds : seconds.substring(0, dot));
        if (h < 0 || m < 0 || s < 0) {
            return -1L;
        }
        return h * 3600L + m * 60L + s;
    }

    private static long component(@Nullable String value) {
        if (value == null) {
            return 0L;
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0) {
            return 0L;
        }
        if (trimmed.length() > 3 || !allDigits(trimmed)) {
            return -1L;
        }
        return Long.parseLong(trimmed);
    }

    /** Digits tested by character, not by {@code matches()}: no {@code Pattern}, no allocation. */
    private static boolean allDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Coordinator uuid + queue item id (+ duration when there is no item id), or {@code null}.
     *
     * <p>The item id is preferred over any text because it changes when the track changes and does not
     * change when the coordinator fills in metadata a moment later. The coordinator uuid leads the key
     * because a household whose grouping changed mid-track is a different announcement even when the
     * bytes playing are the same ones.
     */
    @Nullable
    static String identityOf(@Nullable Payload state) {
        if (state == null || state.currentTrack == null) {
            return null;
        }
        String coordinator = text(state.coordinatorUuid);
        if (coordinator.isEmpty()) {
            coordinator = text(state.uuid);
        }
        String item = text(state.currentTrack.itemID);
        if (item.isEmpty() && state.enqueuedMetadata != null) {
            item = text(state.enqueuedMetadata.itemID);
        }
        String duration = text(state.currentTrack.duration);
        if (coordinator.isEmpty() && item.isEmpty() && duration.isEmpty()) {
            return null;
        }
        return coordinator + "/" + item + "/" + duration;
    }

    /**
     * What kind of source a {@code trackURI} describes, as a word a person would put on a wall, or
     * {@code null} for a URI that names no source this app can recognise.
     *
     * <p>Only the scheme is read, never what follows it: that is a station id, a stream handle or a
     * house's file path, and none of it belongs on a frame that shows photographs of a family.
     */
    @Nullable
    static String sourceLabel(@Nullable String trackUri) {
        if (trackUri == null) {
            return null;
        }
        String scheme = trackUri.toLowerCase(Locale.US);
        if (scheme.startsWith("line-in") || scheme.contains("x-rincon-stream")) {
            return "Line-In";
        }
        if (scheme.contains("mp3radio") || scheme.contains("x-sonosapi-stream")
                || scheme.contains("x-sonos-htastream") || scheme.startsWith("raxis://")) {
            return "Radio";
        }
        return null;
    }

    /** Null and JSON null both mean "no text"; the rows the caller writes are never null. */
    private static String text(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
