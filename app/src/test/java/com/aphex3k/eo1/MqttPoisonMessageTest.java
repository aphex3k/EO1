package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aphex3k.eo1.mqtt.MqttManager;
import com.aphex3k.eo1.mqtt.Payload;
import com.aphex3k.eo1.mqtt.PlaybackListener;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.Test;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The frame's MQTT session died because a payload it could not read threw out of {@code messageArrived}, and
 * Paho answers any throw from that callback by closing the connection — not by reporting it. The order that
 * produced the broker log the operator was reading was: null-check at the wrong line, NPE,
 * {@code ClientComms.shutdownConnection()}, "closed its connection" with no DISCONNECT on the wire.
 *
 * <p>These cases are the contract that failure violated, stated as the methods Paho itself calls. Every one of
 * them asserts that <em>nothing escapes</em>. A JVM test can reach them directly: constructing a
 * {@code MqttClient} opens no socket, so the callbacks can be invoked exactly as {@code CommsCallback} would.
 */
public class MqttPoisonMessageTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Records what the manager decided, and never throws of its own accord. */
    private static class Recorder implements PlaybackListener {
        final List<String> started = new ArrayList<String>();
        final List<String> stopped = new ArrayList<String>();
        /** The last payload handed over, for the cases that assert on what actually mapped. */
        Payload last;

        @Override public void onPlaybackStarted(Payload payload) {
            last = payload;
            started.add(payload == null ? "null" : payload.transportState);
        }

        @Override public void onPlaybackStopped(Payload payload) {
            stopped.add(payload == null ? "null" : payload.transportState);
        }
    }

    /** A listener that throws, which is what the real one does on a PLAYING with no track. */
    private static class Exploder implements PlaybackListener {
        @Override public void onPlaybackStarted(Payload payload) {
            throw new NullPointerException("currentTrack.duration");
        }

        @Override public void onPlaybackStopped(Payload payload) {
            throw new IllegalStateException("stopped with nothing playing");
        }
    }

    private static MqttManager manager(PlaybackListener listener,
                                        final List<String> reports) throws Exception {
        return new MqttManager("tcp://127.0.0.1:1883", "eo1-test0000000000", listener, null,
                new MqttManager.Reporter() {
                    @Override public void mqttEvent(String message, boolean severe) {
                        reports.add(message);
                    }
                });
    }

    private static void deliver(MqttManager m, String body) throws Exception {
        m.messageArrived("sonos/livingroom", new MqttMessage(body.getBytes(UTF8)));
    }

    @Test
    public void emptyPayloadDoesNotKillTheSession() throws Exception {
        // Gson returns null for an empty body rather than throwing. That is what a retained state-clear
        // arrives as, and the old code reached for payloadObj.transportState two lines before noticing.
        List<String> reports = new ArrayList<String>();
        Recorder r = new Recorder();
        deliver(manager(r, reports), "");
        assertTrue("nothing should have been delivered to the banner", r.started.isEmpty());
        assertTrue(r.stopped.isEmpty());
        // And nothing should have been <em>reported</em> either. The surrounding catch(Throwable) would keep a
        // reintroduced deref from disconnecting anything, so without this assertion the case above passes
        // whether or not the null test still precedes it — an empty payload is a normal non-event, not an
        // error, and a body that logs one every time the coordinator clears a retained state is a lie in
        // /logs.
        assertTrue("an empty payload must not be reported as a failure: " + reports, reports.isEmpty());
    }

    @Test
    public void literalNullJsonDoesNotKillTheSession() throws Exception {
        List<String> reports = new ArrayList<String>();
        deliver(manager(new Recorder(), reports), "null");
    }

    @Test
    public void malformedJsonDoesNotKillTheSession() throws Exception {
        List<String> reports = new ArrayList<String>();
        MqttManager m = manager(new Recorder(), reports);
        deliver(m, "{ this is not json");
        deliver(m, "[]");
        deliver(m, "{\"transportState\": }");
        assertTrue("an unreadable payload is reported, not swallowed", reports.size() >= 1);
    }

    @Test
    public void aPayloadWithNoTrackIsReportedAndTheSessionSurvives() throws Exception {
        List<String> reports = new ArrayList<String>();
        // The real listener would throw here on currentTrack.duration. That throw must stay inside
        // messageArrived, and it must be visible: a banner that silently never draws is its own bug report.
        MqttManager m = manager(new Exploder(), reports);
        deliver(m, "{\"transportState\":\"PLAYING\"}");
        assertTrue("a dropped banner must be logged", reports.size() >= 1);
    }

    @Test
    public void aThrowingListenerNeverEscapesMessageArrived() throws Exception {
        List<String> reports = new ArrayList<String>();
        MqttManager m = manager(new Exploder(), reports);
        try {
            deliver(m, "{\"transportState\":\"PLAYING\",\"currentTrack\":{\"title\":\"x\"}}");
            deliver(m, "{\"transportState\":\"STOPPED\"}");
        } catch (Throwable t) {
            fail("anything thrown from messageArrived closes the broker connection; got " + t);
        }
    }

    @Test
    public void everyPoisonShapeLeavesTheCallbacksThrowFree() throws Exception {
        List<String> reports = new ArrayList<String>();
        MqttManager m = manager(new Exploder(), reports);
        String[] bodies = {"", " ", "null", "{}", "not json at all", "{\"transportState\":null}",
                "[1,2,3]", "{\"transportState\":\"PLAYING\"}"};
        for (String body : bodies) {
            try {
                deliver(m, body);
            } catch (Throwable t) {
                fail("payload " + body + " escalated to a disconnect: " + t);
            }
        }
    }

    /**
     * The shape a live frame reported on 2026-11-03: `sonos/connected` carrying a bare number under the
     * `sonos/+` wildcard. The session correctly survived it, but the read failure was logged as a severe
     * event — one line in files/eo1-app.log per publish, which spends the durable record on a topic that
     * was never going to hold a coordinator state payload. Silence here is the requirement; the timestamp
     * still moving is what proves the subscription is alive, which is the failure this field exists to catch.
     */
    @Test
    public void aNonObjectBodyIsNotAFailureAndIsNotLogged() throws Exception {
        List<String> reports = new ArrayList<String>();
        Recorder r = new Recorder();
        MqttManager m = manager(r, reports);
        deliver(m, "1");
        deliver(m, "0");
        assertTrue("a liveness flag is not a read failure: " + reports, reports.isEmpty());
        assertTrue(r.started.isEmpty() && r.stopped.isEmpty());
        assertTrue("the delivery must still be counted as hearing something",
                !m.describe().contains("heard=never"));
    }

    /**
     * The reason the shape test scans rather than calls {@code String.trim()}: a padded state document has to
     * still draw a banner. trim() would have handled that but allocates a copy, and on a heap this size an
     * OutOfMemoryError raised outside the guards would make Paho close the connection over whitespace.
     */
    @Test
    public void aPaddedStatePayloadStillDrawsABanner() throws Exception {
        List<String> reports = new ArrayList<String>();
        Recorder r = new Recorder();
        MqttManager m = manager(r, reports);
        // The fixture carries a key that actually resolves. TTrack.Reader folds case, so either
        // spelling maps — which matters, because this package's annotations pinned UPNPClass and
        // ItemID while the coordinator writes UpnpClass and ItemId, and the older lowercase "title"
        // fixture here resolved to nothing at all: the case was asserting a banner drew while
        // passing on one that had no text to draw.
        deliver(m, "  {\"transportState\":\"PLAYING\",\"currentTrack\":{\"Title\":\"x\"}}\t\n");
        assertEquals("padding must not cost the banner", 1, r.started.size());
        assertTrue(reports.isEmpty());
    }

    /**
     * The keys this app is actually served, quoted by the frame's own durable log on 2026-11-14
     * (v2.0.1.20): the coordinator writes MixedCase — UpnpClass, ItemId, ParentId, TrackUri,
     * ProtocolInfo — where TTrack pinned UPNPClass, ItemID, ParentID, TrackURI. Gson matches
     * names exactly, so every one of those fields stayed null on debug builds as well as on
     * release ones, and the only symptom was a banner with no text on it.
     */
    @Test
    public void theKeysTheCoordinatorActuallySendsMapIntoTheTrack() throws Exception {
        List<String> reports = new ArrayList<String>();
        Recorder r = new Recorder();
        deliver(manager(r, reports), "{\"transportState\":\"PLAYING\",\"groupName\":\"Kitchen\","
                + "\"currentTrack\":{"
                + "\"UpnpClass\":\"object.item.audioItem.musicTrack\","
                + "\"ItemId\":\"S100_A00001\",\"ParentId\":\"S100\","
                + "\"TrackUri\":\"x-rincon-playcontainer://RINCON_000E0FFFFF#S100_A00001\","
                + "\"ProtocolInfo\":\"http-get:*:audio/mpeg:*\"}}");
        assertEquals("the banner still gets its state", 1, r.started.size());
        assertNotNull("a payload that reached the listener", r.last);
        assertEquals("object.item.audioItem.musicTrack", r.last.currentTrack.upnpClass);
        assertEquals("S100_A00001", r.last.currentTrack.itemID);
        assertEquals("S100", r.last.currentTrack.parentID);
        assertEquals("http-get:*:audio/mpeg:*", r.last.currentTrack.protocolInfo);
        assertTrue("a document this app can read must not be reported: " + reports, reports.isEmpty());
    }

    /** Case folding is tolerance, not one spelling replacing another. */
    @Test
    public void theUppercaseSpellingStillMapsToo() throws Exception {
        List<String> reports = new ArrayList<String>();
        Recorder r = new Recorder();
        deliver(manager(r, reports), "{\"transportState\":\"PLAYING\",\"currentTrack\":"
                + "{\"UPNPClass\":\"object.item.audioItem.musicTrack\",\"ItemID\":\"X1\","
                + "\"Title\":\"A Title\",\"Duration\":\"0:03:45\"}}");
        assertNotNull(r.last);
        assertEquals("X1", r.last.currentTrack.itemID);
        assertEquals("A Title", r.last.currentTrack.title);
        assertEquals("0:03:45", r.last.currentTrack.duration);
        assertTrue("a readable track is not a fault: " + reports, reports.isEmpty());
    }

    /**
     * The diagnostic, and why it exists: a state that names a track and yields nothing this app can read is
     * invisible from a wall — a banner with no text looks exactly like a house with no music, which is how
     * both the R8 merge and the casing mismatch stayed unnoticed. So the line names the keys the document
     * used: the one fact that tells "nothing is playing" apart from "the mapping and the wire have drifted".
     */
    @Test
    public void aTrackThisAppCannotReadNamesTheKeysTheCoordinatorUsed() throws Exception {
        List<String> reports = new ArrayList<String>();
        MqttManager m = manager(new Recorder(), reports);
        deliver(m, "{\"transportState\":\"PLAYING\",\"groupName\":\"Kitchen\","
                + "\"currentTrack\":{\"MediaName\":\"a title nobody published\",\"Ordinal\":3}}");
        assertEquals("one line for a track nothing could be read from: " + reports, 1, reports.size());
        String line = reports.get(0);
        assertTrue("the line must say what it saw: " + line, line.contains("keys in currentTrack:"));
        assertTrue("and name the keys: " + line, line.contains("MediaName") && line.contains("Ordinal"));
        // Names, never values: this line lands in files/eo1-app.log, which the LAN page serves, and a
        // house's listening history has no business being in it.
        assertFalse("values must not be quoted: " + line, line.contains("nobody published"));
    }

    @Test
    public void aTrackThatResolvesToTextStaysSilent() throws Exception {
        List<String> reports = new ArrayList<String>();
        Recorder r = new Recorder();
        MqttManager m = manager(r, reports);
        deliver(m, "{\"transportState\":\"PLAYING\",\"currentTrack\":{\"Title\":\"Real\",\"Artist\":\"Someone\"}}");
        assertEquals("the banner still gets its state", 1, r.started.size());
        assertTrue("a healthy document must not be reported at all: " + reports, reports.isEmpty());
    }

    @Test
    public void whitespaceAloneIsNotAStateDocumentAndIsNotLogged() throws Exception {
        List<String> reports = new ArrayList<String>();
        MqttManager m = manager(new Recorder(), reports);
        deliver(m, "   ");
        deliver(m, "\n\t ");
        assertTrue("blank is nothing gone wrong: " + reports, reports.isEmpty());
    }

    @Test
    public void connectCompleteNeverThrowsEvenWhenTheSubscribeFails() throws Exception {
        List<String> reports = new ArrayList<String>();
        MqttManager m = manager(new Recorder(), reports);
        // No connect() happened, so the subscribe inside connectComplete must fail. It is caught, reported,
        // and above all does not propagate: connectComplete is dispatched in the same catch(Throwable) region
        // as messageArrived, so a throw from here is a disconnect just the same.
        try {
            m.connectComplete(true, "tcp://127.0.0.1:1883");
            m.connectComplete(false, "tcp://127.0.0.1:1883");
        } catch (Throwable t) {
            fail("connectComplete escaped to Paho's dispatch region: " + t);
        }
    }

    @Test
    public void connectionLostIsReportedAndDoesNotThrow() throws Exception {
        List<String> reports = new ArrayList<String>();
        MqttManager m = manager(new Recorder(), reports);
        m.connectionLost(new java.io.IOException("broker went away"));
        assertEquals("the loss must reach the durable record", 1, reports.size());
        assertTrue(reports.get(0).contains("reconnect"));
    }

    @Test
    public void groupPlayingNowMatchesSomething() throws Exception {
        List<String> reports = new ArrayList<String>();
        Recorder r = new Recorder();
        // GROUP_PLAYING was the literal "PLAYING", so PLAY_STATES held one distinct value and a group
        // coordinator never drew a banner.
        deliver(manager(r, reports), "{\"transportState\":\"GROUP_PLAYING\"}");
        assertEquals(1, r.started.size());
        assertEquals("GROUP_PLAYING", r.started.get(0));
    }

    @Test
    public void stopStatesReachTheStoppedCallback() throws Exception {
        List<String> reports = new ArrayList<String>();
        Recorder r = new Recorder();
        MqttManager m = manager(r, reports);
        deliver(m, "{\"transportState\":\"STOPPED\"}");
        deliver(m, "{\"transportState\":\"GROUP_PAUSED\"}");
        assertEquals(2, r.stopped.size());
        assertTrue(r.started.isEmpty());
    }

    @Test
    public void theTopicIsTheOneTheFeatureReads() {
        assertEquals("sonos/+", MqttManager.TOPIC);
    }
}
