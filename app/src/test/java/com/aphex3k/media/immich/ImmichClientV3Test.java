package com.aphex3k.media.immich;

import com.aphex3k.eo1.AuthenticationFailedException;
import com.aphex3k.eo1.ImmichApiTagException;
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
import static org.junit.Assert.assertNull;
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
        // The store is process-wide, exactly as in production; without this a session from one
        // test's host could satisfy another test's client.
        ImmichSessionStore.clearForTests();
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

    @Test
    public void listTaggedAssetIdsReturnsIdsWhenTagExists() throws Exception {
        server.enqueue(json(200,
                "[{\"id\":\"tag-1\",\"name\":\"EO1_INCOMPATIBLE\",\"type\":\"user\",\"userId\":\"user-1\"}]"));
        server.enqueue(json(200,
                "{\"assets\":{\"count\":2,\"items\":[{\"id\":\"a-1\"},{\"id\":\"a-2\"}]}}"));

        ImmichClientV3 client = new ImmichClientV3(server.url("/").toString(), "user-1", "secret", null);
        java.util.List<String> ids = client.listTaggedAssetIds("EO1_INCOMPATIBLE");
        assertEquals(2, ids.size());
        assertEquals("a-1", ids.get(0));
        assertEquals("a-2", ids.get(1));

        assertEquals("/api/tags", server.takeRequest().getPath());
        RecordedRequest search = server.takeRequest();
        assertEquals("POST", search.getMethod());
        assertEquals("/api/search/metadata", search.getPath());
        // The search must be filtered by the resolved tag id, not by tag name.
        assertTrue(search.getBody().readUtf8().contains("\"tagIds\":[\"tag-1\"]"));
    }

    @Test
    public void listTaggedAssetIdsIsEmptyWithoutASearchCallWhenTagAbsent() throws Exception {
        server.enqueue(json(200,
                "[{\"id\":\"tag-2\",\"name\":\"other\",\"type\":\"user\",\"userId\":\"user-1\"}]"));

        ImmichClientV3 client = new ImmichClientV3(server.url("/").toString(), "user-1", "secret", null);
        java.util.List<String> ids = client.listTaggedAssetIds("EO1_INCOMPATIBLE");
        assertTrue("no such tag must yield an empty list", ids.isEmpty());
        // Only the tag lookup ran — no metadata search when the tag does not exist.
        assertEquals(1, server.getRequestCount());
    }

    /**
     * The tag's whole purpose is to be visible in the Immich UI. A tag the server refused used to
     * return quietly, which made "nothing is tagged" indistinguishable from "nothing failed".
     */
    @Test
    public void tagAssetIncompatibleReportsARefusedTagCreation() throws Exception {
        server.enqueue(json(200, "[]"));
        server.enqueue(json(403,
                "{\"message\":\"no permission\",\"error\":\"Forbidden\",\"statusCode\":403}"));

        ImmichClientV3 client =
                new ImmichClientV3(server.url("/").toString(), "user-1", "secret", null);
        try {
            client.tagAssetIncompatible("a-1", "EO1_INCOMPATIBLE");
            fail("a refused tag creation must not look like success");
        } catch (ImmichApiTagException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HTTP 403"));
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("EO1_INCOMPATIBLE"));
        }
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void tagAssetIncompatibleReportsAnUnreadableTagList() throws Exception {
        server.enqueue(json(500, "{\"message\":\"explode\"}"));

        ImmichClientV3 client =
                new ImmichClientV3(server.url("/").toString(), "user-1", "secret", null);
        try {
            client.tagAssetIncompatible("a-1", "EO1_INCOMPATIBLE");
            fail("a tag list that could not be read must not be treated as an empty one");
        } catch (ImmichApiTagException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("list tags"));
        }
        assertEquals("no create may be attempted on an unreadable list", 1, server.getRequestCount());
    }

    @Test
    public void tagAssetIncompatibleCreatesTheTagThenAppliesIt() throws Exception {
        server.enqueue(json(200, "[]"));
        server.enqueue(json(201, "{\"id\":\"tag-9\",\"name\":\"EO1_INCOMPATIBLE\"}"));
        server.enqueue(json(200, "[{\"assetId\":\"a-1\",\"tagId\":\"tag-9\"}]"));

        ImmichClientV3 client =
                new ImmichClientV3(server.url("/").toString(), "user-1", "secret", null);
        client.tagAssetIncompatible("a-1", "EO1_INCOMPATIBLE");

        RecordedRequest list = server.takeRequest();
        assertEquals("GET", list.getMethod());
        assertEquals("/api/tags", list.getPath());
        RecordedRequest create = server.takeRequest();
        assertEquals("POST", create.getMethod());
        assertEquals("/api/tags", create.getPath());
        assertTrue(create.getBody().readUtf8().contains("\"name\":\"EO1_INCOMPATIBLE\""));
        RecordedRequest apply = server.takeRequest();
        assertEquals("PUT", apply.getMethod());
        assertEquals("/api/tags/tag-9/assets", apply.getPath());
        assertTrue(apply.getBody().readUtf8().contains("\"ids\":[\"a-1\"]"));
    }

    /** An existing tag must be reused from the list, and a failed asset update must be reported. */
    @Test
    public void tagAssetIncompatibleReusesAnExistingTagAndReportsAFailedApply() throws Exception {
        server.enqueue(json(200, "[{\"id\":\"tag-9\",\"name\":\"EO1_INCOMPATIBLE\"}]"));
        server.enqueue(json(404, "{\"message\":\"Asset not found\",\"statusCode\":404}"));

        ImmichClientV3 client =
                new ImmichClientV3(server.url("/").toString(), "user-1", "secret", null);
        try {
            client.tagAssetIncompatible("a-1", "EO1_INCOMPATIBLE");
            fail("a failed tag-apply must surface");
        } catch (ImmichApiTagException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HTTP 404"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("a-1"));
        }
        assertEquals("the listed tag must not be re-created", 2, server.getRequestCount());
    }

    /**
     * What authentication actually costs now: one credentials exchange per session, and a bearer
     * token on everything after it. The password must appear in exactly one request body.
     */
    @Test
    public void credentialsBuyOneLoginAndEveryLaterRequestCarriesTheToken() throws Exception {
        server.enqueue(json(201, "{\"userId\":\"user-1\",\"accessToken\":\"at\"}"));
        // A list without a match: one request per call, so the header assertions below line up
        // with the requests actually made.
        server.enqueue(json(200, "[{\"id\":\"tag-2\",\"name\":\"other\"}]"));
        server.enqueue(json(200, "[{\"id\":\"tag-2\",\"name\":\"other\"}]"));

        ImmichClientV3 client =
                new ImmichClientV3(server.url("/").toString(), "user-1", "secret", null);
        client.login();
        client.listTaggedAssetIds("EO1_INCOMPATIBLE");
        client.listTaggedAssetIds("EO1_INCOMPATIBLE");

        RecordedRequest login = server.takeRequest();
        assertEquals("/api/auth/login", login.getPath());
        assertTrue("the password is spent here and nowhere else",
                login.getBody().readUtf8().contains("\"password\":\"secret\""));
        assertNull("a login must not carry a session it is about to replace",
                login.getHeader("Authorization"));

        RecordedRequest first = server.takeRequest();
        assertEquals("Bearer at", first.getHeader("Authorization"));
        assertNull("cookie replay is gone; one credential, not two", first.getHeader("Cookie"));
        assertEquals("Bearer at", server.takeRequest().getHeader("Authorization"));
    }

    /** A second client for the same account — what a pool rebuild builds — must not log in again. */
    @Test
    public void aRebuiltClientReusesTheSessionInsteadOfLoggingInAgain() throws Exception {
        String host = server.url("/").toString();
        server.enqueue(json(201, "{\"userId\":\"user-1\",\"accessToken\":\"at\"}"));
        server.enqueue(json(200, "[]"));

        new ImmichClientV3(host, "user-1", "secret", null).login();
        ImmichClientV3 rebuilt = new ImmichClientV3(host, "user-1", "secret", null);
        rebuilt.listTaggedAssetIds("EO1_INCOMPATIBLE");

        assertTrue("the rebuilt client must still be authenticated", rebuilt.isLoggedIn());
        assertEquals(2, server.getRequestCount());
        assertEquals("/api/auth/login", server.takeRequest().getPath());
        assertEquals("Bearer at", server.takeRequest().getHeader("Authorization"));
    }

    /**
     * The version probe runs before any login, so a token left over from an earlier session must
     * stay off it: a spent one would 401 the probe and raise a version warning that has nothing to
     * do with the server's version.
     */
    @Test
    public void theVersionProbeGoesOutWithoutAToken() throws Exception {
        String host = server.url("/").toString();
        server.enqueue(json(201, "{\"userId\":\"user-1\",\"accessToken\":\"at\"}"));
        server.enqueue(json(200, "{\"major\":3,\"minor\":2,\"patch\":2}"));

        new ImmichClientV3(host, "user-1", "secret", null).login();
        new ImmichClientV3(host, "user-1", "secret", null).probeServerVersion();

        server.takeRequest();
        assertNull(server.takeRequest().getHeader("Authorization"));
    }

    /**
     * A download whose token aged out mid-flight: the retry has to be a <em>fresh</em> call. Handing
     * the same Retrofit call to {@code execute()} twice throws "Already Executed", and an expired
     * token makes that path ordinary rather than theoretical.
     */
    @Test
    public void aStreamRejectedMidFlightIsRetriedWithANewToken() throws Exception {
        String host = server.url("/").toString();
        server.enqueue(json(201, "{\"userId\":\"user-1\",\"accessToken\":\"old\"}"));
        server.enqueue(json(401, "{\"message\":\"Unauthorized\",\"statusCode\":401}"));
        server.enqueue(json(201, "{\"userId\":\"user-1\",\"accessToken\":\"fresh\"}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "image/jpeg").setBody("bytes"));

        ImmichClientV3 client = new ImmichClientV3(host, "user-1", "secret", null);
        client.login();
        java.io.InputStream in = client.originalStream("a-1");
        assertNotNull(in);
        in.close();

        assertEquals(4, server.getRequestCount());
        server.takeRequest();
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"));
        server.takeRequest();
        assertEquals("the retry must carry the token the re-login minted",
                "Bearer fresh", server.takeRequest().getHeader("Authorization"));
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
