package com.aphex3k.eo1.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * What the now-playing banner is obliged to do with a coordinator state.
 *
 * <p>These cases exist because three photographs of two real frames showed three different wrong
 * walls — a title cut off with its room name missing from a box far wider than its text, a box holding
 * nothing but "Kitchen Speaker + 3", and a box holding nothing at all — and because none of the
 * decisions behind them was testable where it lived. The mapping used to be six local variables and a
 * {@code setText} inside an anonymous listener in {@code MainActivity}, so {@link
 * MqttPoisonMessageTest}, which is a good file and covers its own contract, could only ever ask
 * "did a callback fire". It never asked what the callback draws, and the answer was a dark rectangle
 * on a wall.
 *
 * <p>Read the three groups in order. The first is the one that ends the empty box: a state with nothing
 * to say must not produce a card, and must not erase a card that has something to say. The second is
 * the fallback chain, which is a ranking rather than a lookup and is asserted as one. The third is
 * identity and dwell, the two facts that decide when a banner changes and when it goes away — the
 * half of "it does not update" that no amount of layout work would have touched.
 */
public class NowPlayingTest {

    private static Payload state(String transportState) {
        Payload payload = new Payload();
        payload.transportState = transportState;
        payload.coordinatorUuid = "RINCON_COORDINATOR";
        return payload;
    }

    private static TTrack track(String title, String artist, String duration) {
        TTrack track = new TTrack();
        track.title = title;
        track.artist = artist;
        track.duration = duration;
        track.itemID = "Q:0/1";
        return track;
    }

    @Test
    public void aTrackWithTextDrawsAllThreeRowsAndSaysNothingIsMissing() {
        Payload payload = state(MqttManager.PLAYING);
        payload.currentTrack = track("The First Cut Is the Deepest", "Rod Stewart", "0:03:45");
        payload.groupName = "Kitchen Speaker + 3";

        NowPlaying.Decision decision = NowPlaying.decide(payload, null);
        assertEquals(NowPlaying.Action.SHOW, decision.action());
        NowPlaying card = decision.card();
        assertEquals("The First Cut Is the Deepest", card.title());
        assertEquals("Rod Stewart", card.artist());
        assertEquals("Kitchen Speaker + 3", card.room());
        assertEquals("", decision.missingRows());
        assertEquals(225_000L, card.dwellMs());
    }

    /**
     * The empty box, killed at the source. A {@code currentTrack} of {@code {}} is a legitimate state —
     * {@code MqttPoisonMessageTest.anEmptyTrackObjectIsNotReported} pins that — and it used to reach
     * three {@code setText(null)} calls and a {@code VISIBLE}.
     */
    @Test
    public void aTrackCarryingNoTextDrawsNoCard() {
        Payload payload = state(MqttManager.PLAYING);
        payload.currentTrack = new TTrack();
        payload.groupName = null;
        payload.name = null;

        NowPlaying.Decision decision = NowPlaying.decide(payload, null);
        assertEquals(NowPlaying.Action.KEEP, decision.action());
        assertNull("a KEEP must not carry rows to draw", decision.card());
        assertEquals("", decision.missingRows());
    }

    /**
     * The state behind the second photograph: a track with no title and no artist, where the old path
     * erased a correct banner and redrew the room name alone. Drawing nothing here is not the same as
     * drawing what the last track was — the frame is not obliged to guess.
     */
    @Test
    public void anUninformativeStateDoesNotEraseTheCardAlreadyUp() {
        Payload quiet = state(MqttManager.PLAYING);
        quiet.currentTrack = new TTrack();

        assertEquals(NowPlaying.Action.KEEP, NowPlaying.decide(quiet, "RINCON_COORDINATOR//").action());
    }

    /**
     * The other half of the same rule, and the one that keeps the first from being a licence to stick.
     * A state that names a <em>different</em> track and says nothing readable about it does contradict
     * the card on the wall, so the wall has to come down rather than go on naming a song that has
     * stopped.
     */
    @Test
    public void aDifferentTrackWithNoTextTakesTheWrongCardDown() {
        Payload changed = state(MqttManager.PLAYING);
        changed.currentTrack = new TTrack();
        changed.currentTrack.itemID = "Q:0/9";
        changed.currentTrack.duration = "0:04:00";

        NowPlaying.Decision decision = NowPlaying.decide(changed, "RINCON_COORDINATOR/Q:0/1/0:03:45");
        assertEquals(NowPlaying.Action.CLEAR, decision.action());
        assertNull(decision.card());
    }

    @Test
    public void aStateThatNamesNoTrackAtAllChangesNothing() {
        Payload payload = state(MqttManager.PLAYING);
        payload.currentTrack = null;
        assertEquals(NowPlaying.Action.KEEP, NowPlaying.decide(payload, "anything").action());
        assertEquals(NowPlaying.Action.KEEP, NowPlaying.decide(null, "anything").action());
    }

    /** Line-in has no title. It does have a scheme, and a scheme is a fact about the source. */
    @Test
    public void aLineInSourceIsNamedByWhatItIs() {
        Payload payload = state(MqttManager.PLAYING);
        payload.currentTrack = new TTrack();
        payload.currentTrack.trackURI = "line-in:///";
        payload.groupName = "Kitchen Speaker + 3";

        NowPlaying.Decision decision = NowPlaying.decide(payload, null);
        assertEquals(NowPlaying.Action.SHOW, decision.action());
        assertEquals("Line-In", decision.card().title());
        assertEquals("artist", decision.missingRows());
    }

    @Test
    public void aRadioStreamIsNamedByWhatItIs() {
        Payload payload = state(MqttManager.PLAYING);
        payload.currentTrack = new TTrack();
        payload.currentTrack.trackURI = "x-rincon-mp3radio://streams.example.com/station.mp3";

        assertEquals("Radio", NowPlaying.decide(payload, null).card().title());
        assertEquals("Radio", NowPlaying.sourceLabel("RAXIS://example/station"));
        assertEquals("Line-In", NowPlaying.sourceLabel("x-rincon-stream:RINCON_000SECOND"));
        assertNull("a queue item is not a source", NowPlaying.sourceLabel("x-file-cues://store/1"));
        assertNull(NowPlaying.sourceLabel(null));
    }

    /**
     * The ranking, not the lookup. {@code enqueuedMetadata} is what the queue is holding <em>next</em> on
     * the bridges that fill it in, so naming a line-in with it would put a specific wrong song title on
     * the wall where "Line-In" is true. The vague-but-true label has to come first, and a test that only
     * checks that the fallback exists would pass either way round.
     */
    @Test
    public void aSourceLabelOutranksTheQueuedTrackThatIsNotPlayingYet() {
        Payload payload = state(MqttManager.PLAYING);
        payload.currentTrack = new TTrack();
        payload.currentTrack.trackURI = "x-rincon-stream:RINCON_000SECOND";
        EnqueuedMetadata queued = new EnqueuedMetadata();
        queued.title = "A Song From Later In The Queue";
        payload.enqueuedMetadata = queued;

        assertEquals("Line-In", NowPlaying.decide(payload, null).card().title());
    }

    @Test
    public void aTrackWithNoSourceFallsBackToTheQueuedTitle() {
        Payload payload = state(MqttManager.PLAYING);
        payload.currentTrack = new TTrack();
        payload.currentTrack.trackURI = "x-file-cues://store/0$2$0";
        EnqueuedMetadata queued = new EnqueuedMetadata();
        queued.title = "First As Well";
        payload.enqueuedMetadata = queued;

        assertEquals("First As Well", NowPlaying.decide(payload, null).card().title());
    }

    /**
     * A player that is not in a group reports its own room name and no {@code groupName} at all. With no
     * track title either, the name would otherwise be drawn as the title <em>and</em> as the room — one
     * card saying "Kitchen" twice, which is what the third photograph's box would have become with text
     * in it.
     */
    @Test
    public void aSinglePlayerDoesNotSayItsOwnNameTwice() {
        Payload payload = state(MqttManager.PLAYING);
        payload.currentTrack = new TTrack();
        payload.name = "Kitchen";
        payload.groupName = null;

        NowPlaying.Decision decision = NowPlaying.decide(payload, null);
        assertEquals("Kitchen", decision.card().title());
        assertEquals("the name is already the title row", "", decision.card().room());
        assertEquals("artist, room", decision.missingRows());
    }

    @Test
    public void aGroupNameStillGetsItsOwnRowBesideAPlayerName() {
        Payload payload = state(MqttManager.PLAYING);
        payload.currentTrack = track("Real Title", "Real Artist", "0:03:00");
        payload.groupName = "Kitchen Speaker + 3";
        payload.name = "Kitchen";

        NowPlaying card = NowPlaying.decide(payload, null).card();
        assertEquals("Kitchen Speaker + 3", card.room());
        assertEquals("Real Title", card.title());
    }

    /**
     * Every spelling this app can meet, plus the ones it must not crash on. The bound matters as much as
     * the parse: a stream reporting {@code 99:59:59} would otherwise schedule a fade 100 hours out, and a
     * frame whose broker had died would wear one station's name for four days.
     */
    @Test
    public void everyLengthSpellingYieldsAPositiveBoundedDwell() {
        assertEquals("H:MM:SS", 225_000L, NowPlaying.dwellMs("0:03:45"));
        assertEquals("and with a padded hour", 225_000L, NowPlaying.dwellMs("00:03:45"));
        assertEquals("MM:SS, the example the old comment gave while its regex rejected it",
                225_000L, NowPlaying.dwellMs("3:45"));
        assertEquals("a bare count of seconds", 225_000L, NowPlaying.dwellMs("225"));
        assertEquals("ISO 8601", 225_000L, NowPlaying.dwellMs("PT3M45S"));
        assertEquals("and whatever case it arrives in", 225_000L, NowPlaying.dwellMs("pt3m45s"));

        // The ceiling, on any track long enough to reach it — a podcast, or a stream.
        assertEquals(NowPlaying.MAX_DWELL_MS, NowPlaying.dwellMs("99:59:59"));
        assertEquals(NowPlaying.MAX_DWELL_MS, NowPlaying.dwellMs("1:02:00"));

        // And everything that is not a length at all lands on the default, never on zero: the value this
        // replaces produced 0 from "0:00:00", the caller's `if (durationMs > 0)` then declined to schedule
        // a fade, and the banner could only ever be dismissed by a stop whose text happened to match.
        assertEquals("UPnP's answer for a live stream", NowPlaying.DEFAULT_DWELL_MS,
                NowPlaying.dwellMs("0:00:00"));
        assertEquals(NowPlaying.DEFAULT_DWELL_MS, NowPlaying.dwellMs("?:??:??"));
        assertEquals(NowPlaying.DEFAULT_DWELL_MS, NowPlaying.dwellMs(""));
        assertEquals(NowPlaying.DEFAULT_DWELL_MS, NowPlaying.dwellMs(null));
        assertEquals(NowPlaying.DEFAULT_DWELL_MS, NowPlaying.dwellMs("nonsense"));
        assertEquals("more digits than parseLong survives", NowPlaying.DEFAULT_DWELL_MS,
                NowPlaying.dwellMs("99999999999999999999"));
    }

    /**
     * Identity carries no display text, and that is the whole design. A coordinator republishing the same
     * state must look unchanged (so the caller does not re-arm a timer that then never expires), and two
     * tracks that share title, artist and length must look different — the {@code
     * groupName_title_artist_duration} key this replaces got both of those backwards.
     */
    @Test
    public void identityIgnoresTextAndNoticesTheTrack() {
        Payload first = state(MqttManager.PLAYING);
        first.currentTrack = track("Same Title", "Same Artist", "0:04:00");
        String id = NowPlaying.identityOf(first);

        // The same queue item, now with its title filled in.
        Payload richer = state(MqttManager.PLAYING);
        richer.currentTrack = track("Same Title", "Rod Stewart", "0:04:00");
        assertEquals("metadata arriving late is not a new announcement", id, NowPlaying.identityOf(richer));

        // A different item that happens to carry identical text.
        Payload neighbour = state(MqttManager.PLAYING);
        neighbour.currentTrack = track("Same Title", "Same Artist", "0:04:00");
        neighbour.currentTrack.itemID = "Q:0/2";
        assertNotNull(id);
        assertNotEquals(id, NowPlaying.identityOf(neighbour));
    }

    @Test
    public void identityIsUnknownRatherThanGuessedWhenNothingStructuralIsThere() {
        Payload payload = state(MqttManager.PLAYING);
        payload.coordinatorUuid = null;
        payload.uuid = null;
        payload.currentTrack = new TTrack();
        payload.currentTrack.title = "Title Only";
        assertNull("a title is not a way to recognise a track later", NowPlaying.identityOf(payload));

        // And an unknown identity is answered by re-arming, which is the conservative direction: a frame
        // that re-arms keeps a live banner, a frame that concludes "unchanged" from no evidence hides one
        // forever.
        payload.currentTrack.duration = "0:03:00";
        assertEquals(180_000L, NowPlaying.decide(payload, "old").card().dwellMs());
        assertNotNull(NowPlaying.identityOf(payload));
    }

    /**
     * The invariant the constructor enforces, checked from inside the package because the factory is
     * package-private for exactly this. A {@code SHOW} that carries an empty card, or a {@code CLEAR}
     * that carries one, are the two ways the empty box could come back without any call site looking
     * like it had changed.
     */
    @Test
    public void aDecisionCannotContradictItself() {
        try {
            NowPlaying.Decision.show(null);
            fail("a SHOW with no card is constructible again");
        } catch (IllegalArgumentException expected) {
            assertEquals("a card with nothing in it is not a decision", expected.getMessage());
        }
        assertEquals(NowPlaying.Action.CLEAR, NowPlaying.Decision.clear().action());
        assertEquals(NowPlaying.Action.KEEP, NowPlaying.Decision.keep().action());
        assertNull(NowPlaying.Decision.clear().card());
    }

    /**
     * A card whose only content is the room name is legitimate — a grouped player that is playing
     * something with no metadata at all, where the third row is the only truth available. Asserted
     * because the rule "at least one non-empty row" is what stands between this feature and the
     * photographed empty box, and it must not quietly become "at least a title".
     */
    @Test
    public void aRoomNameAloneIsStillSomethingWorthSaying() {
        Payload payload = state(MqttManager.PLAYING);
        payload.currentTrack = new TTrack();
        payload.groupName = "Kitchen Speaker + 3";

        NowPlaying.Decision decision = NowPlaying.decide(payload, null);
        assertEquals(NowPlaying.Action.SHOW, decision.action());
        assertEquals("", decision.card().title());
        assertEquals("title, artist", decision.missingRows());
    }
}
