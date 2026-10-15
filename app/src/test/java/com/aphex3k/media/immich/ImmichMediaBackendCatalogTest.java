package com.aphex3k.media.immich;

import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.media.MediaAsset;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

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
 * Retrofit/OkHttp stack: the pool is what the account can see, and building it touches nothing
 * but the catalog endpoints — no tag read, so an asset the frame flagged last week is tried
 * again on the next rebuild.
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

    /** Version probe, login, owned + shared albums, and the timeline search — nothing else. */
    private static final int CATALOG_REQUESTS = 5;

    private void enqueueCatalog(String... items) {
        server.enqueue(json(200, "{\"major\":3,\"minor\":2,\"patch\":2}"));
        server.enqueue(json(201,
                "{\"userId\":\"user-1\",\"accessToken\":\"at\","
                        + "\"userEmail\":\"u@x.io\",\"status\":\"success\"}"));
        server.enqueue(json(200, "[]"));
        server.enqueue(json(200, "[]"));
        StringBuilder itemsJson = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                itemsJson.append(',');
            }
            itemsJson.append(items[i]);
        }
        server.enqueue(json(200, "{\"assets\":{\"count\":" + items.length
                + ",\"items\":[" + itemsJson + "]}}"));
    }

    private static boolean poolContains(List<MediaAsset> pool, String id) {
        for (MediaAsset asset : pool) {
            if (id.equals(asset.id)) {
                return true;
            }
        }
        return false;
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
    public void fetchCatalogKeepsEverythingTheDecoderMightReject() throws Exception {
        enqueueCatalog(
                "{\"id\":\"plain\",\"type\":\"IMAGE\",\"originalFileName\":\"a.jpg\","
                        + "\"exifInfo\":{\"fileSizeInByte\":10}}",
                "{\"id\":\"heif\",\"type\":\"IMAGE\",\"originalFileName\":\"b.heic\","
                        + "\"exifInfo\":{\"fileSizeInByte\":10}}",
                "{\"id\":\"giant\",\"type\":\"IMAGE\",\"originalFileName\":\"c.tiff\","
                        + "\"exifInfo\":{\"fileSizeInByte\":5368709120}}",
                "{\"id\":\"noexif\",\"type\":\"VIDEO\",\"originalFileName\":\"d.hevc\"}",
                "{\"id\":\"sound\",\"type\":\"AUDIO\",\"originalFileName\":\"e.mp3\"}");

        List<MediaAsset> catalog = initializedBackend().fetchCatalog();

        // Only the non-image/video stays out: every codec question is answered by displaying.
        assertEquals(4, catalog.size());
        assertTrue(poolContains(catalog, "plain"));
        assertTrue(poolContains(catalog, "heif"));
        assertTrue(poolContains(catalog, "giant"));
        assertTrue(poolContains(catalog, "noexif"));
        assertFalse(poolContains(catalog, "sound"));
    }

    @Test
    public void fetchCatalogBuildsThePoolWithoutReadingTags() throws Exception {
        enqueueCatalog("{\"id\":\"plain\",\"type\":\"IMAGE\",\"exifInfo\":{\"fileSizeInByte\":10}}");

        initializedBackend().fetchCatalog();

        // A tag read would be a sixth request — and would retire an asset the operator has since
        // given a regenerated thumbnail, with nothing left to tell them it came back.
        assertEquals(CATALOG_REQUESTS, server.getRequestCount());
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
