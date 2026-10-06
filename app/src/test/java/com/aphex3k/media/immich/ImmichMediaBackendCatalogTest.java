package com.aphex3k.media.immich;

import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.media.MediaAsset;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ImmichMediaBackend#fetchCatalog()} end-to-end over the real pinned
 * Retrofit/OkHttp stack: {@code EO1_INCOMPATIBLE}-tagged assets stay out of the pool, and a
 * failing tag query degrades to the unfiltered catalog instead of losing the pool.
 */
public class ImmichMediaBackendCatalogTest {

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

    /** Version probe, login, owned + shared albums, and the timeline search. */
    private void enqueueCatalog() {
        server.enqueue(json(200, "{\"major\":3,\"minor\":2,\"patch\":2}"));
        server.enqueue(json(201,
                "{\"userId\":\"user-1\",\"accessToken\":\"at\","
                        + "\"userEmail\":\"u@x.io\",\"status\":\"success\"}"));
        server.enqueue(json(200, "[]"));
        server.enqueue(json(200, "[]"));
        server.enqueue(json(200,
                "{\"assets\":{\"count\":3,\"items\":["
                        + "{\"id\":\"plain\",\"type\":\"IMAGE\",\"exifInfo\":{\"fileSizeInByte\":10}},"
                        + "{\"id\":\"doomed\",\"type\":\"IMAGE\",\"exifInfo\":{\"fileSizeInByte\":10}},"
                        + "{\"id\":\"plain2\",\"type\":\"IMAGE\",\"exifInfo\":{\"fileSizeInByte\":10}}"
                        + "]}}"));
    }

    private ImmichMediaBackend initializedBackend() throws Exception {
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_IMMICH;
        entry.id = "immich-1";
        entry.host = server.url("/").toString();
        entry.userid = "user-1";
        entry.password = "secret";
        ImmichMediaBackend backend = new ImmichMediaBackend(entry, null);
        backend.initialize();
        return backend;
    }

    @Test
    public void fetchCatalogExcludesAssetsCarryingTheIncompatibleTag() throws Exception {
        enqueueCatalog();
        // The tag lookup finds the tag; the filtered search returns exactly the tagged asset.
        server.enqueue(json(200,
                "[{\"id\":\"tag-1\",\"name\":\"EO1_INCOMPATIBLE\",\"type\":\"user\",\"userId\":\"user-1\"}]"));
        server.enqueue(json(200,
                "{\"assets\":{\"count\":1,\"items\":[{\"id\":\"doomed\"}]}}"));

        List<MediaAsset> catalog = initializedBackend().fetchCatalog();

        assertEquals(2, catalog.size());
        for (MediaAsset asset : catalog) {
            assertFalse("tagged asset must not rotate", "doomed".equals(asset.id));
        }
    }

    @Test
    public void fetchCatalogSurvivesAFailingTagQueryUnfiltered() throws Exception {
        enqueueCatalog();
        // The tag lookup dies on the wire; the whole catalog must still come back.
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        List<MediaAsset> catalog = initializedBackend().fetchCatalog();

        assertEquals(3, catalog.size());
        boolean foundDoomed = false;
        for (MediaAsset asset : catalog) {
            if ("doomed".equals(asset.id)) {
                foundDoomed = true;
            }
        }
        assertTrue("a failing tag query must degrade to the unfiltered catalog", foundDoomed);
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
