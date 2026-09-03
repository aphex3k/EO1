package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ConnectionManagerTest {

    private static final class RecordingListener implements ConnectionManagerListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void connected() {
            events.add("connected");
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
        }
    }

    private static final class TestableConnectionManager extends ConnectionManager {
        private boolean networkAvailable;

        TestableConnectionManager(boolean initiallyAvailable) {
            super(null, null);
            this.networkAvailable = initiallyAvailable;
        }

        void setNetworkAvailable(boolean networkAvailable) {
            this.networkAvailable = networkAvailable;
        }

        @Override
        protected boolean isNetworkAvailable() {
            return networkAvailable;
        }
    }

    private RecordingListener listener;
    private TestableConnectionManager manager;

    @Before
    public void setUp() {
        listener = new RecordingListener();
    }

    @Test
    public void registerListener_notifiesConnectedImmediatelyWhenOnline() {
        manager = new TestableConnectionManager(true);

        manager.registerListener(listener);

        assertEquals(1, listener.events.size());
        assertEquals("connected", listener.events.get(0));
        assertTrue(manager.isNetworkAvailable());
    }

    @Test
    public void registerListener_notifiesDisconnectedImmediatelyWhenOffline() {
        manager = new TestableConnectionManager(false);

        manager.registerListener(listener);

        assertEquals(1, listener.events.size());
        assertEquals("disconnected", listener.events.get(0));
        assertFalse(manager.isNetworkAvailable());
    }

    @Test
    public void evaluateConnectionStatus_notifiesOnTransitionToConnected() {
        manager = new TestableConnectionManager(false);
        manager.registerListener(listener);
        listener.events.clear();

        manager.setNetworkAvailable(true);
        manager.evaluateConnectionStatus();

        assertEquals(1, listener.events.size());
        assertEquals("connected", listener.events.get(0));
    }

    @Test
    public void evaluateConnectionStatus_notifiesOnTransitionToDisconnected() {
        manager = new TestableConnectionManager(true);
        manager.registerListener(listener);
        listener.events.clear();

        manager.setNetworkAvailable(false);
        manager.evaluateConnectionStatus();

        assertEquals(1, listener.events.size());
        assertEquals("disconnected", listener.events.get(0));
    }

    @Test
    public void evaluateConnectionStatus_doesNotNotifyWhenUnchanged() {
        manager = new TestableConnectionManager(true);
        manager.registerListener(listener);
        listener.events.clear();

        manager.evaluateConnectionStatus();
        manager.evaluateConnectionStatus();

        assertTrue(listener.events.isEmpty());
    }

    @Test
    public void pollInterval_isTwoSeconds() {
        assertEquals(2_000L, ConnectionManager.POLL_INTERVAL_MS);
    }
}
