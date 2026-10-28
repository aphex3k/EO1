package com.aphex3k.media.flickr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.eo1.MediaDownloadFailedException;
import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * {@link FlickrMediaBackend} over the real Retrofit/OkHttp stack, against two mock origins: one
 * answering REST calls and one serving photo bytes. Two servers because a Flickr entry has no host
 * field to redirect and the {@code server} component of a photo URL is constrained by the cache-name
 * pattern, so neither can carry a mock address — which is what {@code restOrigin} and {@code cdnOrigin}
 * exist for.
 *
 * <p>Most of what this suite pins is the traffic shape. The report behind issue #94 says only three or
 * four frames could run at once and connections failed with a Flickr error: a backend that probed per
 * asset or listed per tick would reproduce that symptom on a key of its own, and would look healthy in
 * every other test.
 */
public class FlickrMediaBackendTest {

    private MockWebServer rest;
    private MockWebServer cdn;

    @Before
    public void startServers() throws IOException {
        rest = new MockWebServer();
        rest.start();
        cdn = new MockWebServer();
        cdn.start();
    }

    @After
    public void stopServers() throws IOException {
        rest.shutdown();
        cdn.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private FlickrMediaBackend backend(String source) {
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_FLICKR;
        entry.id = "flickr-1";
        entry.apiKey = "0123456789abcdef0123456789abcdef";
        entry.source = source;
        return new FlickrMediaBackend(entry, rest.url("/").toString(), cdn.url("/").toString());
    }

    private static String photoJson(String id, String secret, String server, String media) {
        return "{\"id\":\"" + id + "\",\"secret\":\"" + secret + "\",\"server\":\"" + server
                + "\",\"title\":\"t" + id + "\",\"media\":\"" + media + "\",\"width_l\":\"1024\","
                + "\"height_l\":\"768\"}";
    }

    private void enqueueLookupUser() {
        rest.enqueue(json(200, "{\"user\":{\"id\":\"12345678@N00\"},\"stat\":\"ok\"}"));
    }

    private void enqueueStreamPage(String... photos) {
        rest.enqueue(json(200, "{\"photos\":{\"page\":\"1\",\"pages\":\"1\",\"perpage\":\"100\","
                + "\"total\":\"" + photos.length + "\"},\"photo\":[" + join(photos) + "],"
                + "\"stat\":\"ok\"}"));
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** One resolve, one page: the whole catalog build is two requests, and no per-tick call at all. */
    @Test
    public void buildingTheCatalogCostsOneRequestPerPage() throws Exception {
        FlickrMediaBackend backend = backend("https://www.flickr.com/photos/mhenke/");
        enqueueLookupUser();
        enqueueStreamPage(photoJson("11111111111", "aaaaaaaaaa", "6553", "photo"),
                photoJson("22222222222", "bbbbbbbbbb", "6553", "photo"));

        backend.initialize();
        List<MediaAsset> pool = backend.fetchCatalog();

        assertEquals(2, pool.size());
        assertEquals("resolve + one page, nothing else", 2, rest.getRequestCount());
        assertEquals("photo bytes are never fetched to build a pool", 0, cdn.getRequestCount());
    }

    /**
     * {@code per_page} is capped well under the 500 the API allows: Flickr's terms prohibit displaying
     * more than 30 of a user's photos per page, and paging metadata at 500 reads against the letter of
     * that even though the frame shows one photo at a time.
     */
    @Test
    public void pagingStaysInsideTheTermsCeiling() throws Exception {
        FlickrMediaBackend backend = backend("mhenke");
        rest.enqueue(json(200, "{\"user\":{\"nsid\":\"12345678@N00\"},\"stat\":\"ok\"}"));
        enqueueStreamPage(photoJson("11111111111", "aaaaaaaaaa", "6553", "photo"));

        backend.initialize();
        backend.fetchCatalog();

        RecordedRequest listing = rest.takeRequest();
        // The first call resolved the username; the second is the listing.
        assertTrue(listing.getPath().contains("method=flickr.people.findByUsername"));
        listing = rest.takeRequest();
        assertTrue(listing.getPath().contains("method=flickr.people.getPublicPhotos"));
        assertTrue("per_page must never exceed the terms-driven ceiling",
                listing.getPath().contains("per_page=" + FlickrClient.PER_PAGE));
        assertTrue(FlickrClient.PER_PAGE <= 100);
        // Flickr documents no header form for the application key, so it is in the query string of every
        // call — the reason RedactingLogger must rewrite the request line, not just headers.
        assertTrue(listing.getPath().contains("api_key="));
    }

    /**
     * A wrong key is the most common way this backend is misconfigured, and Flickr distinguishes a
     * malformed one ("Key has invalid format") from an unknown one ("Key not found"). Both strings have
     * to reach the operator, because "the frame shows nothing from Flickr" is otherwise undiagnosable.
     */
    @Test
    public void aRejectedKeyCarriesFlickrsOwnWords() throws Exception {
        FlickrMediaBackend backend = backend("https://www.flickr.com/photos/mhenke/");
        rest.enqueue(json(200, "{\"stat\":\"fail\",\"code\":100,"
                + "\"message\":\"Invalid API Key (Key has invalid format)\"}"));
        try {
            backend.initialize();
            fail("a call the server refused must not look like a successful initialize");
        } catch (FlickrApiException e) {
            assertEquals(100, e.code);
            assertTrue(e.getMessage(), e.getMessage().contains("Invalid API Key"));
        }
    }

    /**
     * A tier the account cannot receive answers {@code 410 Gone} with a 462-byte error page. Those bytes
     * must never reach the integrity layer as an image: caching an error page under a photo's name is
     * how a healthy library gets flagged. Cause-less is deliberate — the pipeline reads a cause as
     * "nobody's asset, the wire is down" and propagates it.
     */
    @Test
    public void aGoneTierFailsWithoutHandingOverAnErrorPage() throws Exception {
        FlickrMediaBackend backend = backend("mhenke");
        rest.enqueue(json(200, "{\"user\":{\"nsid\":\"12345678@N00\"},\"stat\":\"ok\"}"));
        enqueueStreamPage(photoJson("11111111111", "aaaaaaaaaa", "6553", "photo"));
        backend.initialize();
        List<MediaAsset> pool = backend.fetchCatalog();
        cdn.enqueue(new MockResponse().setResponseCode(410).setBody("<HTML><HEAD>410 Gone</HEAD>"));

        MediaSource source = backend.resolveOriginal(pool.get(0));
        assertNotNull(source);
        try {
            source.opener.open();
            fail("a 410 must not be delivered as image bytes");
        } catch (MediaDownloadFailedException e) {
            assertTrue("the message names the status: " + e.getMessage(),
                    e.getMessage().contains("410"));
            assertNull("and carries no cause, so the ladder descends once instead of propagating",
                    e.getCause());
        }
    }

    /**
     * A dead network is nobody's asset, so it keeps its cause: {@code MediaManager} falls through to the
     * degraded rung only for a cause-less failure, and a frame with no connection must not spend a
     * thousand requests proving it.
     */
    @Test
    public void aWireFailureKeepsItsCause() throws Exception {
        FlickrMediaBackend backend = backend("mhenke");
        rest.enqueue(json(200, "{\"user\":{\"nsid\":\"12345678@N00\"},\"stat\":\"ok\"}"));
        enqueueStreamPage(photoJson("11111111111", "aaaaaaaaaa", "6553", "photo"));
        backend.initialize();
        List<MediaAsset> pool = backend.fetchCatalog();
        cdn.shutdown();

        try {
            backend.resolveOriginal(pool.get(0)).opener.open();
            fail("an unreachable host must surface as the IOException it is");
        } catch (IOException expected) {
            // Exactly what TrustCensus.gateNote needs to see.
            assertNotNull(expected);
        }
    }

    /**
     * The bug class {@link FlickrUrls} exists to prevent, tested through the backend rather than the
     * helper: the acquisition path passes a real asset to the degraded rung and the display-error path
     * passes one rebuilt from an opaque key with every other field empty. If the two answers differ, the
     * pipeline caches two artifacts under one {@code <id>_fb.jpg} name and has no checksum to notice.
     */
    @Test
    public void theDegradedRungIsTheSameUrlFromBothCallSites() throws Exception {
        FlickrMediaBackend backend = backend("mhenke");
        rest.enqueue(json(200, "{\"user\":{\"nsid\":\"12345678@N00\"},\"stat\":\"ok\"}"));
        enqueueStreamPage(photoJson("11111111111", "aaaaaaaaaa", "6553", "photo"));
        backend.initialize();
        MediaAsset real = backend.fetchCatalog().get(0);

        // What MediaManager.assetFromKey builds on the display-error path: id and backend id, nothing else.
        MediaAsset stub = new MediaAsset(real.id, "flickr-1", MediaType.IMAGE, -1, null, null, null,
                null, null, 0, 0);

        cdn.enqueue(json(200, "bytes"));
        drain(backend.resolveOriginal(real));
        String originalPath = cdn.takeRequest().getPath();

        cdn.enqueue(json(200, "bytes"));
        drain(backend.resolveThumbnailFallback(real));
        String fromRealAsset = cdn.takeRequest().getPath();

        cdn.enqueue(json(200, "bytes"));
        drain(backend.resolveThumbnailFallback(stub));
        String fromStubAsset = cdn.takeRequest().getPath();

        assertEquals("one asset must have one degraded URL", fromRealAsset, fromStubAsset);
        assertNotEquals("and the rung must not be the original again under another name",
                originalPath, fromStubAsset);
    }

    /** Flickr documents no downloadable video file, so a clip's poster still is what can be honest. */
    @Test
    public void videosJoinThePoolAsStillsAndSaySo() throws Exception {
        FlickrMediaBackend backend = backend("mhenke");
        rest.enqueue(json(200, "{\"user\":{\"nsid\":\"12345678@N00\"},\"stat\":\"ok\"}"));
        enqueueStreamPage(photoJson("11111111111", "aaaaaaaaaa", "6553", "photo"),
                photoJson("33333333333", "cccccccccc", "6553", "video"));
        backend.initialize();
        List<MediaAsset> pool = backend.fetchCatalog();

        assertEquals(2, pool.size());
        for (MediaAsset asset : pool) {
            assertEquals("a video URL is a JPEG, and must be named .jpg not .mp4",
                    MediaType.IMAGE, asset.type);
            assertTrue(asset.originalFileName.endsWith(".jpg"));
            assertNull("Flickr reports no byte size, and null is now safe", asset.sizeBytes);
            assertNull("and no checksum either", asset.checksum);
            assertNull("a remote asset never carries a local path", asset.localPath);
        }
        assertTrue(backend.describeVersion(), backend.describeVersion().contains("as stills"));
    }

    /**
     * A listing entry with no server cannot yield a URL from its id alone, which both rungs require. It
     * is skipped, and the skip is counted — a pool that is quietly shorter than the library is the
     * complaint this feature exists to answer.
     */
    @Test
    public void anUnbuildableListingEntryIsCountedNotSilentlyDropped() throws Exception {
        FlickrMediaBackend backend = backend("mhenke");
        rest.enqueue(json(200, "{\"user\":{\"nsid\":\"12345678@N00\"},\"stat\":\"ok\"}"));
        enqueueStreamPage(photoJson("11111111111", "aaaaaaaaaa", "6553", "photo"),
                photoJson("44444444444", "dddddddddd", "", "photo"));
        backend.initialize();
        List<MediaAsset> pool = backend.fetchCatalog();

        assertEquals(1, pool.size());
        assertTrue(backend.describeVersion(), backend.describeVersion().contains("unlistable"));
    }

    /** An album URL goes to the album's contents, and needs the owner's nsid to ask for them. */
    @Test
    public void anAlbumUrlPagesTheAlbumNotTheStream() throws Exception {
        FlickrMediaBackend backend =
                backend("https://www.flickr.com/photos/12345678@N00/albums/72177720303387681");
        enqueueStreamPage(photoJson("55555555555", "eeeeeeeeee", "6553", "photo"));
        rest.enqueue(json(200, "{\"photoset\":{\"id\":\"72177720303387681\",\"page\":\"1\","
                + "\"pages\":\"1\",\"total\":\"1\"},\"photo\":["
                + photoJson("55555555555", "eeeeeeeeee", "6553", "photo") + "],\"stat\":\"ok\"}"));

        backend.initialize();
        List<MediaAsset> pool = backend.fetchCatalog();

        assertEquals(1, pool.size());
        assertEquals("no lookup needed: the URL already carried an nsid", 1, rest.getRequestCount());
        assertTrue(rest.takeRequest().getPath().contains("method=flickr.photosets.getPhotos"));
    }

    /**
     * What is true before any request is made belongs in {@code compatibilityWarning}, because that is
     * the only place it can be said on the generation that discovers the configuration — and a warning
     * that first appears on the <em>next</em> rebuild is about 100 days away on a large stream.
     */
    @Test
    public void theKeyOnlyLimitsAreWarnedAboutBeforeTheFirstFetch() throws Exception {
        FlickrMediaBackend backend = backend("mhenke");
        assertNull("nothing to report before initialize", backend.compatibilityWarning());
        rest.enqueue(json(200, "{\"user\":{\"nsid\":\"12345678@N00\"},\"stat\":\"ok\"}"));
        backend.initialize();
        String warning = backend.compatibilityWarning();
        assertNotNull(warning);
        assertTrue(warning, warning.contains("Safe"));
        assertTrue(warning, warning.contains("1024"));
    }

    private static void drain(MediaSource source) throws Exception {
        InputStream in = source.opener.open();
        try {
            in.close();
        } catch (IOException ignored) {
            // A closed mock response is not the point of the test.
        }
    }

}
