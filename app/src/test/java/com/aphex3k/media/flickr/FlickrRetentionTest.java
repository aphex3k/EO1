package com.aphex3k.media.flickr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.eo1.MediaManagerInterface;
import com.aphex3k.media.MediaAsset;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The obligations Flickr's API terms place on this backend, and on this backend only: an idle window for
 * cached bytes, eviction of a photo that has gone private, and a removal request that holds past the
 * next rotation.
 *
 * <p>The one that is easy to get wrong is the last. A delete is not a removal — if the listing still
 * describes the photo, the next tick downloads it again, and every plausible-looking test still passes.
 * So the assertion that matters here is that a forgotten photo stays out of the pool <em>while the
 * server keeps offering it</em>.
 */
public class FlickrRetentionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private MockWebServer rest;
    private MockWebServer cdn;

    /** Records what the backend asked the manager to free, by raw id. */
    private final List<String> dropped = new ArrayList<>();

    private long now = 1_700_000_000_000L;

    private final MediaManagerInterface sink = new MediaManagerInterface() {
        @Override
        public void removeFromCache(File file) {
            throw new AssertionError("a backend must never delete by path");
        }

        @Override
        public int dropCachedAsset(String backendId, String rawId) {
            dropped.add(rawId);
            return 1;
        }

        @Override
        public int forgetAssets(String backendId, String assetKey) {
            throw new AssertionError("not called from here");
        }

        @Override
        public void showNextImage(Activity activity) {
        }

        @Override
        public void tagAssetAsIncompatible(String assetKey) {
        }

        @Override
        public void displayThumbnailAsset(Activity activity, String assetKey, boolean isVideo) {
        }

        @Override
        public void webDisplayFailed(Activity activity, String assetKey) {
        }
    };

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

    private FlickrMediaBackend backend() throws Exception {
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_FLICKR;
        entry.id = "flickr-1";
        entry.apiKey = "0123456789abcdef0123456789abcdef";
        entry.source = "mhenke";
        FlickrMediaBackend backend = new FlickrMediaBackend(entry, rest.url("/").toString(),
                cdn.url("/").toString(), tmp.newFolder("state"), sink);
        backend.setTimeSource(new FlickrMediaBackend.TimeSource() {
            @Override
            public long nowMs() {
                return now;
            }
        });
        return backend;
    }

    private void enqueueUser() {
        rest.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"user\":{\"nsid\":\"12345678@N00\"},\"stat\":\"ok\"}"));
    }

    private void enqueueStream(String... photoIds) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < photoIds.length; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"id\":\"").append(photoIds[i])
                    .append("\",\"secret\":\"sec").append(photoIds[i])
                    .append("\",\"server\":\"6553\",\"title\":\"t\",\"media\":\"photo\"}");
        }
        rest.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"photos\":{\"page\":\"1\",\"pages\":\"1\",\"perpage\":\"100\",\"total\":"
                        + photoIds.length + "},\"photo\":[" + items + "],\"stat\":\"ok\"}"));
    }

    /** A photo that left the public listing was deleted or made private; its bytes must follow. */
    @Test
    public void aPhotoThatVanishedFromTheListingLosesItsBytes() throws Exception {
        FlickrMediaBackend backend = backend();
        enqueueUser();
        enqueueStream("111", "222");
        backend.initialize();
        List<MediaAsset> first = backend.fetchCatalog();
        assertEquals(2, first.size());
        String vanishedId = first.get(1).id;

        // One generation later: the second photo is gone from the stream.
        enqueueStream("111");
        List<MediaAsset> second = backend.fetchCatalog();

        assertEquals(1, second.size());
        assertEquals("the vanished photo's bytes were freed", 1, dropped.size());
        assertEquals(vanishedId, dropped.get(0));
        assertFalse("the surviving photo was left alone", dropped.contains(first.get(0).id));
    }

    /**
     * Absence only proves something when the build actually finished. Half a library, or a failed
     * listing, must not be read as "the rest went private" — that would evict a healthy stream.
     */
    @Test
    public void aFailedOrPartialListingEvictsNothing() throws Exception {
        FlickrRetention retention = new FlickrRetention(null);
        retention.noteHeld("a", 1L);
        retention.noteHeld("b", 1L);

        Set<String> partial = new HashSet<>();
        partial.add("a");
        assertEquals("an incomplete build proves nothing about the tail",
                0, retention.vanishedFrom(partial, false).size());
        assertEquals("a completed build does", 1, retention.vanishedFrom(partial, true).size());
    }

    /** Idle past the window loses the bytes; being displayed again renews the window instead. */
    @Test
    public void theRetentionWindowIsIdleTimeAndRestartsWithUse() throws Exception {
        FlickrMediaBackend backend = backend();
        enqueueUser();
        enqueueStream("111");
        backend.initialize();
        List<MediaAsset> pool = backend.fetchCatalog();
        String id = pool.get(0).id;

        cdn.enqueue(json("bytes"));
        assertNotNull(backend.resolveOriginal(pool.get(0)));

        // 29 days of idleness: nothing.
        now += 29L * 24 * 60 * 60 * 1000;
        enqueueStream("111");
        backend.fetchCatalog();
        assertEquals("a photo still in rotation is not stale", 0, dropped.size());

        // Past 30 idle days, its bytes go and the window restarts.
        now += 2L * 24 * 60 * 60 * 1000;
        enqueueStream("111");
        backend.fetchCatalog();
        assertEquals(1, dropped.size());
        assertEquals(id, dropped.get(0));

        // Displaying it again renews rather than inherits the expired clock.
        dropped.clear();
        cdn.enqueue(json("bytes"));
        drain(backend.resolveOriginal(pool.get(0)));
        now += 29L * 24 * 60 * 60 * 1000;
        enqueueStream("111");
        backend.fetchCatalog();
        assertEquals("the renewal was real: 29 idle days, not 60", 0, dropped.size());
    }

    /**
     * The removal request, honoured rather than attempted: the server keeps listing the photo, and the
     * frame must still stop showing it — and stop re-downloading it — for as long as this install
     * exists.
     */
    @Test
    public void aForgottenPhotoStaysForgottenEvenWhileTheStreamListsIt() throws Exception {
        FlickrMediaBackend backend = backend();
        enqueueUser();
        enqueueStream("111", "222");
        backend.initialize();
        List<MediaAsset> pool = backend.fetchCatalog();
        String target = pool.get(1).id;

        assertEquals(1, backend.forget(target));
        assertEquals("its bytes were freed", 1, dropped.size());

        // The very next listing still offers it. Flickr has no idea anyone asked.
        enqueueStream("111", "222");
        List<MediaAsset> after = backend.fetchCatalog();
        assertEquals("it is not in the pool", 1, after.size());
        assertEquals(pool.get(0).id, after.get(0).id);
        assertNull("and its source will not resolve even if asked",
                backend.resolveOriginal(pool.get(1)));
        assertTrue(backend.describeVersion(), backend.describeVersion().contains("withheld on request"));
    }

    /** A forget has to survive a restart, or the 24-hour clause fails on the next boot. */
    @Test
    public void forgetfulnessOutlivesTheProcess() throws Exception {
        File stateRoot = tmp.newFolder("state2");
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_FLICKR;
        entry.id = "flickr-2";
        entry.apiKey = "k";
        entry.source = "mhenke";

        FlickrMediaBackend first = new FlickrMediaBackend(entry, rest.url("/").toString(),
                cdn.url("/").toString(), stateRoot, sink);
        first.setTimeSource(clock());
        enqueueUser();
        enqueueStream("333");
        first.initialize();
        String id = first.fetchCatalog().get(0).id;
        first.forget(id);

        // A fresh backend, as a pool rebuild or a restart would build it.
        FlickrMediaBackend second = new FlickrMediaBackend(entry, rest.url("/").toString(),
                cdn.url("/").toString(), stateRoot, sink);
        second.setTimeSource(clock());
        dropped.clear();
        enqueueUser();
        enqueueStream("333");
        second.initialize();
        assertEquals("the ledger, not memory, is what remembers", 0, second.fetchCatalog().size());
    }

    /** "Forget everything from this backend" clears the whole stream, not one photo. */
    @Test
    public void forgettingABackendClearsEveryAssetItKnows() throws Exception {
        FlickrMediaBackend backend = backend();
        enqueueUser();
        enqueueStream("111", "222", "333");
        backend.initialize();
        backend.fetchCatalog();
        dropped.clear();

        assertEquals(3, backend.forget(null));
        assertEquals(3, dropped.size());
    }

    /** A backend with no state directory must say so, not look healthy while retaining nothing. */
    @Test
    public void anInMemoryOnlyBackendReportsIt() throws Exception {
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_FLICKR;
        entry.id = "flickr-3";
        entry.apiKey = "k";
        entry.source = "mhenke";
        FlickrMediaBackend backend = new FlickrMediaBackend(entry, rest.url("/").toString(),
                cdn.url("/").toString(), null, sink);
        enqueueUser();
        enqueueStream("111");
        backend.initialize();
        backend.fetchCatalog();
        assertTrue(backend.describeVersion(),
                backend.describeVersion().contains("in-process only"));
    }

    /**
     * A backend id is configuration text, only trimmed by {@code normalize}. A ledger named from it
     * verbatim turns {@code ../../configuration} into a write outside the state directory — into the
     * frame's own settings file, once the parent directories exist.
     */
    @Test
    public void aHostileBackendIdCannotEscapeTheLedgerDirectory() throws Exception {
        File root = tmp.newFolder("root");
        File stateDir = new File(root, "backends");
        File ledger = FlickrMediaBackend.ledgerFile(stateDir, "../../configuration");
        assertTrue(ledger.getCanonicalPath(),
                ledger.getCanonicalPath().startsWith(new File(stateDir, "flickr")
                        .getCanonicalPath()));
        assertFalse("the id must not survive as a path", ledger.getName().contains(".."));
    }

    /** Comment 6's hole: the display-error path reaches the degraded rung directly, after the delete. */
    @Test
    public void theDegradedRungHonoursTheRemovalMark() throws Exception {
        FlickrMediaBackend backend = backend();
        enqueueUser();
        enqueueStream("111", "222");
        backend.initialize();
        List<MediaAsset> pool = backend.fetchCatalog();
        String removed = pool.get(1).id;
        backend.forget(removed);
        dropped.clear();
        cdn.enqueue(json("bytes"));

        assertNull("a removed photo must not be re-fetched by the fallback rung",
                backend.resolveThumbnailFallback(pool.get(1)));
        assertEquals(0, cdn.getRequestCount());
        assertEquals("and its bytes stay freed", 0, dropped.size());
    }

    /** An interrupted save must not be able to leave a half-written ledger in place of a whole one. */
    @Test
    public void aSaveReplacesTheLedgerAtomically() throws Exception {
        File stateRoot = tmp.newFolder("atomic");
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_FLICKR;
        entry.id = "flickr-9";
        entry.apiKey = "k";
        entry.source = "mhenke";
        FlickrMediaBackend backend = new FlickrMediaBackend(entry, rest.url("/").toString(),
                cdn.url("/").toString(), stateRoot, sink);
        backend.setTimeSource(clock());
        enqueueUser();
        enqueueStream("111");
        backend.initialize();
        backend.fetchCatalog();

        File dir = new File(stateRoot, "flickr");
        File[] files = dir.listFiles();
        assertNotNull(files);
        assertEquals("one ledger, no leftovers", 1, files.length);
        assertFalse(files[0].getName().endsWith(".tmp"));
    }

    /**
     * The cap exists to bound the file, not to lose track of bytes. Dropping a row that owns a cached
     * photo would hand that photo to nobody: neither the privacy sweep nor "forget everything" could
     * reach it again.
     */
    @Test
    public void capTrimmingKeepsTheRowsThatOwnBytes() throws Exception {
        FlickrRetention retention = new FlickrRetention(null);
        for (int i = 0; i < 3; i++) {
            retention.noteHeld("held" + i, 1000L + i);
        }
        for (int i = 0; i < 9000; i++) {
            retention.noteListed("listed" + i);
        }
        int overflow = retention.trimToCap();
        assertEquals("only never-held rows are droppable", 0, overflow);
        for (int i = 0; i < 3; i++) {
            assertTrue(retention.knownIds().contains("held" + i));
        }
        assertTrue("the ledger is back inside its bound",
                retention.knownIds().size() <= 6000);
    }

    /** Every photo a stream ever listed, not merely the ones that happened to come up. */
    @Test
    public void forgettingAStreamCoversPhotosNeverDisplayed() throws Exception {
        FlickrMediaBackend backend = backend();
        enqueueUser();
        enqueueStream("111", "222", "333", "444");
        backend.initialize();
        backend.fetchCatalog();
        dropped.clear();

        assertEquals("no photo had to be shown first", 4, backend.forget(null));
        assertEquals(4, dropped.size());
    }

    private FlickrMediaBackend.TimeSource clock() {
        return new FlickrMediaBackend.TimeSource() {
            @Override
            public long nowMs() {
                return now;
            }
        };
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setBody(body);
    }

    private static void drain(com.aphex3k.media.MediaSource source) throws Exception {
        java.io.InputStream in = source.opener.open();
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
