package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aphex3k.eo1.mqtt.MqttManager;
import com.aphex3k.eo1.mqtt.Payload;
import com.aphex3k.eo1.mqtt.PlaybackListener;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * What "the panel owns the connection" has to mean in Paho's terms, and the finding that made it non-obvious:
 * with {@code automaticReconnect} on, a client whose link dropped is in the DISCONNECTED state while it waits
 * out its backoff timer — and {@code ClientComms.disconnect()} is guarded on exactly that state, so it throws
 * 32101 <em>without cancelling the timer</em>. A quiet-hours handover built on {@code disconnect()} therefore
 * leaves the frame dialling the broker in the dark.
 *
 * <p>{@code MqttManager.stop()} answers that by force-closing instead, because {@code close()} accepts a
 * DISCONNECTED client and CLOSED is terminal. These cases pin the observable half of that contract; the socket
 * half needs a broker, which is on the hardware checklist.
 */
public class MqttSuspendTest {

    private static class Silent implements PlaybackListener {
        @Override public void onPlaybackStarted(Payload payload) { }

        @Override public void onPlaybackStopped(Payload payload) { }
    }

    /** The manager under test plus everything it reported, so "said nothing" is assertable. */
    private static class Harness {
        final List<String> messages = new ArrayList<String>();
        final List<String> severe = new ArrayList<String>();
        final MqttManager manager;

        Harness() throws Exception {
            manager = new MqttManager("tcp://127.0.0.1:1883", "eo1-suspendtest01", new Silent(), null,
                    new MqttManager.Reporter() {
                        @Override public void mqttEvent(String message, boolean isSevere) {
                            messages.add(message);
                            if (isSevere) {
                                severe.add(message);
                            }
                        }
                    });
        }
    }

    @Test
    public void stoppingBeforeAnyConnectIsQuietAndIdempotent() throws Exception {
        Harness h = new Harness();
        h.manager.stop();
        h.manager.stop();
        assertTrue("a deliberate stand-down is not an error: " + h.severe, h.severe.isEmpty());
        assertTrue(h.manager.isSuspended());
        assertFalse(h.manager.isConnected());
    }

    @Test
    public void suspendedIsReportedRatherThanLeftToLookLikeAFailure() throws Exception {
        Harness h = new Harness();
        assertFalse(h.manager.isSuspended());
        h.manager.stop();
        assertFalse("a held link must not read as a failure", h.manager.describe().startsWith("down"));
        assertTrue(h.manager.describe().startsWith("held"));
        assertTrue(h.manager.stateJson().get("suspended").getAsBoolean());
    }

    /**
     * The regression this whole class exists for. A reconnect completing after the panel went off must not
     * subscribe: had it done so, the not-connected client it is standing down would have thrown and reported
     * "connected but sonos/+ was refused" as severe. Nothing being reported here is the proof the ladder was
     * cut rather than followed.
     */
    @Test
    public void aReconnectCompletingAfterThePanelWentOffSubscribesNothing() throws Exception {
        Harness h = new Harness();
        h.manager.stop();
        h.manager.connectComplete(true, "tcp://127.0.0.1:1883");
        assertTrue("a suspended client must not try to subscribe: " + h.messages, h.messages.isEmpty());
        assertTrue(h.manager.isSuspended());
    }

    @Test
    public void aConnectionLostThatWasOursIsNotReportedAsALoss() throws Exception {
        Harness h = new Harness();
        h.manager.stop();
        h.manager.connectionLost(new java.io.IOException("socket closed by stop()"));
        assertTrue("standing down must not read as a dropped link: " + h.messages, h.messages.isEmpty());
    }

    /**
     * A stopped manager is replaced, never revived. That is what closes the stale-callback race: if
     * {@code connect()} could resurrect a closed Paho client, the object a manager holds would change while an
     * old client's callback was still in flight, and that callback's teardown would land on its successor.
     */
    @Test
    public void aStoppedManagerRefusesToConnectRatherThanRevivingItself() throws Exception {
        Harness h = new Harness();
        h.manager.stop();
        try {
            h.manager.connect("eos1", "secret");
            org.junit.Assert.fail("a stopped manager must not attempt a session");
        } catch (org.eclipse.paho.client.mqttv3.MqttException e) {
            assertEquals(org.eclipse.paho.client.mqttv3.MqttException.REASON_CODE_CLIENT_CLOSED,
                    e.getReasonCode());
        }
        assertTrue(h.manager.isSuspended());
    }

    @Test
    public void aConnectionLostThatWasNotOursStillReachesTheLog() throws Exception {
        Harness h = new Harness();
        h.manager.connectionLost(new java.io.IOException("broker went away"));
        assertEquals(1, h.messages.size());
        assertFalse("stop() must not leave the manager permanently muted", h.manager.isSuspended());
    }
}
