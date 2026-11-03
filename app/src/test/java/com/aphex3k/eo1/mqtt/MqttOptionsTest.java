package com.aphex3k.eo1.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.Test;

/**
 * Lives in the {@code mqtt} package rather than beside {@code MqttNoOpLoggerTest} for one reason: the thing
 * under test is package-private, and reaching it through a public seam invented for the test would widen the
 * production surface to serve the check.
 */
public class MqttOptionsTest {

    @Test
    public void optionsAskPahoForTheReconnectAndNameEveryDefault() {
        MqttConnectOptions o = MqttManager.buildOptions("eos1", "secret");
        // The bug this pins: nothing used to call this, so Paho's default — false — meant a dropped link was
        // never retried for the life of the process, because the only caller of connect() sits on a branch a
        // resume cannot reach.
        assertTrue("automaticReconnect is the whole reason a drop is survivable",
                o.isAutomaticReconnect());
        assertTrue(o.isCleanSession());
        // Stated rather than inherited: the broker log's k60 was Paho's default, and a default nobody chose is
        // how a keepalive interval ends up changing on its own when the dependency is bumped.
        assertEquals(60, o.getKeepAliveInterval());
        assertEquals(10, o.getConnectionTimeout());
        assertEquals(60_000, o.getMaxReconnectDelay());
    }

    @Test
    public void absentCredentialsAreAbsentNotDefaulted() {
        // AGENTS.md: never ship a credential of the app's own, for any service. An unset one must go on the
        // wire unset so the broker refuses it, rather than every frame authenticating against a key in the APK.
        MqttConnectOptions o = MqttManager.buildOptions(null, null);
        assertNull(o.getUserName());
        assertNull(o.getPassword());
        MqttConnectOptions blank = MqttManager.buildOptions("", "");
        assertNull(blank.getUserName());
        assertNull(blank.getPassword());
    }

    @Test
    public void aPresentPasswordIsStillSent() {
        // The other half of the rule above: "never default" must not have become "never send".
        MqttConnectOptions o = MqttManager.buildOptions("eos1", "hunter2");
        assertEquals("eos1", o.getUserName());
        assertEquals("hunter2", new String(o.getPassword()));
    }

    @Test
    public void aConnectThatArrivesByAnotherRouteIsNotAnError() {
        // Paho says "already connected" to a settings save and "connect in progress" to a core-loop re-arm
        // that lands mid-backoff. Both must be silent: a frame whose broker is merely down would otherwise
        // put one line into /logs per retry, forever.
        assertTrue(MqttManager.isAlreadyOnItsWay(MqttException.REASON_CODE_CLIENT_CONNECTED));
        assertTrue(MqttManager.isAlreadyOnItsWay(MqttException.REASON_CODE_CLIENT_DISCONNECTING));
        assertTrue(MqttManager.isAlreadyOnItsWay(MqttException.REASON_CODE_CONNECT_IN_PROGRESS));
        // A refusal is a refusal, and a downed broker is not "on its way".
        assertFalse(MqttManager.isAlreadyOnItsWay(
                MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION));
        assertFalse(MqttManager.isAlreadyOnItsWay(
                MqttException.REASON_CODE_INVALID_CLIENT_ID));
    }

    /**
     * The hazard, stated against Paho rather than against this app: {@code MqttClient} waits on
     * {@code getTimeToWait()} for every synchronous call, and its constructor stores {@code -1}.
     * {@code setConnectionTimeout} does not bound that wait, so a client built without an explicit
     * {@code setTimeToWait} blocks forever on a peer that accepts the socket and never answers. This pins the
     * default so the fix cannot be described away, and pins the value this app sets. It cannot read the field
     * on the app's own client without a test-only seam, so that half is on the hardware checklist.
     */
    @Test
    public void pahoDefaultsToAnInfiniteWaitAndThisAppDoesNot() throws Exception {
        org.eclipse.paho.client.mqttv3.MqttClient raw =
                new org.eclipse.paho.client.mqttv3.MqttClient("tcp://127.0.0.1:1883", "eo1-defaultprobe01",
                        new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
        assertEquals("if Paho ever changes this default, the reasoning below it must be revisited",
                -1L, raw.getTimeToWait());
        assertTrue(MqttManager.PROTOCOL_WAIT_MS > 0L);
        assertTrue(MqttManager.PROTOCOL_WAIT_MS >= MqttManager.CONNECT_TIMEOUT_S * 1000L);
        raw.close();   // the constructor builds a ClientComms, and this suite should not accumulate them
    }

    @Test
    public void subscriptionIsQosZero() {
        // QoS 1 asked the broker to hold a message until this client acknowledged it, and the acknowledgment
        // is only sent once messageArrived returns — so one poison payload became a standing redelivery.
        assertEquals(0, MqttManager.SUBSCRIBE_QOS);
    }
}
