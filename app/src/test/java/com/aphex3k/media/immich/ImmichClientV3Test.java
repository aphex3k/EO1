package com.aphex3k.media.immich;

import com.aphex3k.eo1.AuthenticationFailedException;
import com.vdurmont.semver4j.Semver;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives {@link ImmichClientV3} through the real pinned Retrofit/OkHttp stack against
 * MockWebServer.
 *
 * <p>Regression: after {@code execute()}, {@code response.raw().close()} always threw
 * {@code IllegalStateException} ("Cannot read raw response body of a converted body")
 * under Retrofit 2.6 — its {@code parseResponse} drains or closes the raw body and then
 * swaps in a {@code NoContentResponseBody} whose {@code close()} throws — which silently
 * broke login and version probing on device.
 */
public class ImmichClientV3Test {

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

    @Test
    public void loginSucceedsAndMarksLoggedIn() throws Exception {
        server.enqueue(json(201,
                "{\"userId\":\"user-1\",\"accessToken\":\"at\","
                        + "\"userEmail\":\"u@x.io\",\"status\":\"success\"}"));

        ImmichClientV3 client = new ImmichClientV3(server.url("/").toString(), "user-1", "secret", null);
        client.login();
        assertTrue("successful login must mark the client logged in", client.isLoggedIn());

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/auth/login", request.getPath());
        assertTrue(request.getBody().readUtf8().contains("\"password\":\"secret\""));
    }

    @Test
    public void loginRejectsBadCredentials() throws Exception {
        server.enqueue(json(401, "{\"status\":\"error\",\"message\":\"bad credentials\"}"));

        ImmichClientV3 client = new ImmichClientV3(server.url("/").toString(), "user-1", "bad", null);
        try {
            client.login();
            fail("expected AuthenticationFailedException");
        } catch (AuthenticationFailedException expected) {
            // A 401 must surface as the app's auth failure, not a raw ISE
        }
    }

    @Test
    public void probeServerVersionReturnsSemver() throws Exception {
        server.enqueue(json(200, "{\"major\":3,\"minor\":2,\"patch\":2}"));

        ImmichClientV3 client = new ImmichClientV3(server.url("/").toString(), "user-1", "secret", null);
        Semver version = client.probeServerVersion();
        assertNotNull("probe must not be nulled out by a close failure", version);
        assertEquals(new Semver("3.2.2"), version);
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
