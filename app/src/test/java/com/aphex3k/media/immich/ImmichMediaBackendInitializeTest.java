package com.aphex3k.media.immich;

import com.aphex3k.eo1.BackendUnavailableException;
import com.aphex3k.eo1.ConfigurationBackendEntry;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ImmichMediaBackend#initialize()}'s version-resolution branches: a server inside the
 * band is silent, a server above it is served best effort with a compatibility warning, and a
 * server predating every band stays a hard refusal.
 */
public class ImmichMediaBackendInitializeTest {

    private MockWebServer server;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void stopServer() throws IOException {
        server.shutdown();
    }

    private ImmichMediaBackend backend(String apiVersion) {
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_IMMICH;
        entry.id = "immich-1";
        entry.host = server.url("/").toString();
        entry.userid = "user-1";
        entry.password = "secret";
        entry.apiVersion = apiVersion;
        return new ImmichMediaBackend(entry, null);
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static MockResponse login() {
        return json(201, "{\"userId\":\"user-1\",\"accessToken\":\"at\"}");
    }

    @Test
    public void inBandServerInitializesQuietly() throws Exception {
        server.enqueue(json(200, "{\"major\":3,\"minor\":3,\"patch\":1}"));
        server.enqueue(login());

        ImmichMediaBackend backend = backend(ConfigurationBackendEntry.API_VERSION_AUTO);
        backend.initialize();

        assertNull("3.3.1 is in band; nothing to warn about", backend.compatibilityWarning());
    }

    @Test
    public void serverNewerThanEveryBandWarnsButStillStarts() throws Exception {
        server.enqueue(json(200, "{\"major\":4,\"minor\":1,\"patch\":0}"));
        server.enqueue(login());

        ImmichMediaBackend backend = backend(ConfigurationBackendEntry.API_VERSION_AUTO);
        backend.initialize();

        String warning = backend.compatibilityWarning();
        assertNotNull("a 4.x server must warn rather than be refused", warning);
        assertTrue(warning, warning.contains("4.1.0"));
        assertTrue(warning, warning.contains("ImmichClientV3"));
    }

    @Test
    public void pinnedVersionAboveTheBandWarns() throws Exception {
        // A pin skips the probe entirely, so login is the first request on the wire.
        server.enqueue(login());

        ImmichMediaBackend backend = backend("4.0.0");
        backend.initialize();

        assertNotNull(backend.compatibilityWarning());
    }

    @Test
    public void serverOlderThanEveryBandIsRefused() throws Exception {
        server.enqueue(json(200, "{\"major\":2,\"minor\":3,\"patch\":0}"));

        ImmichMediaBackend backend = backend(ConfigurationBackendEntry.API_VERSION_AUTO);
        try {
            backend.initialize();
            fail("a pre-3.0 server must not be served best effort");
        } catch (BackendUnavailableException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("2.3.0"));
        }
        assertNull(backend.compatibilityWarning());
    }

    @Test
    public void failedProbeWarnsButStillStarts() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(login());

        ImmichMediaBackend backend = backend(ConfigurationBackendEntry.API_VERSION_AUTO);
        backend.initialize();

        assertNotNull("an unknown server version is a degraded run, not a silent one",
                backend.compatibilityWarning());
    }
}
