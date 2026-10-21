package com.aphex3k.eo1;

import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaBackend;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A clip that rendered nothing at every rung of the ladder is a fact about this device, not a verdict
 * on the user's file — so that verdict is remembered in memory and never reaches the backend's tag.
 * The alternative is a frame quietly writing {@code EO1_INCOMPATIBLE} into a real Immich library for
 * videos that a regenerated encode, or a decoder that gets the profile right, would show fine.
 *
 * <p>What the display half of this policy does — substitute the still preview for the clip — needs a
 * live Activity and so is not reachable from here. What is checked is that the verdict is taken once,
 * keyed the way the pipeline keys assets, that nothing in taking it can tag, and that the tag itself
 * refuses an asset already held render-silent (the substituted still re-enters the ladder's terminal
 * rung, which tags on its own).
 */
public class MediaManagerRenderSilentTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Counts the one server-side write this verdict must never make. */
    private static class CountingBackend implements MediaBackend {
        int tags;

        @Override
        public String getId() {
            return "immich-1";
        }

        @Override
        public String getType() {
            return "immich";
        }

        @Override
        public List<MediaAsset> fetchCatalog() {
            return Collections.emptyList();
        }

        @Override
        public MediaSource resolveOriginal(MediaAsset asset) {
            return null;
        }

        @Override
        public void markIncompatible(MediaAsset asset) {
            tags++;
        }
    }

    private static class NoopListener implements MediaManagerListener {
        @Override
        public void handleException(Exception e) {
        }

        @Override
        public void debugInformationProvided(DebugInformation debugInformation) {
        }

        @Override
        public void displayPicture(File finalTempFile, String assetId) {
        }

        @Override
        public void displayVideo(File finalTempFile, String assetId) {
        }

        @Override
        public void displayWebsite(String url, String assetId) {
        }
    }

    private static MediaAsset video(String id, String backendId) {
        return new MediaAsset(id, backendId, MediaType.VIDEO, 16245, null, "IMG_5353.MOV",
                null, 31617645L, null, 1920, 1080);
    }

    private final MediaManager.UiPoster directPoster = new MediaManager.UiPoster() {
        @Override
        public void post(Runnable action) {
            action.run();
        }
    };

    private MediaManager manager(CountingBackend backend) throws Exception {
        MediaManager manager = new MediaManager(new NoopListener(),
                Collections.<MediaBackend>singletonList(backend), new Configuration(),
                new Random(1L), new MediaCacheManager(dir -> Long.MAX_VALUE));
        // Tags are written through backendById, which only rebuildPool fills. Without one tick here,
        // every "it did not tag" assertion below would pass because the backend was never reachable —
        // which is a passing test that proves nothing.
        manager.showNextImageLocked(tmp.newFolder("cache"), tmp.newFolder("uploads"),
                directPoster, false);
        return manager;
    }

    @Test
    public void verdictIsTakenOnceAndNeverTags() throws Exception {
        CountingBackend backend = new CountingBackend();
        MediaManager manager = manager(backend);
        String key = video("37454989", "immich-1").key();

        assertFalse("unseen asset is not render-silent", manager.isRenderSilent(key));
        assertTrue("first note reports a new verdict", manager.noteRenderSilent(key));
        assertTrue("and it sticks", manager.isRenderSilent(key));
        assertFalse("a second note is not a new verdict", manager.noteRenderSilent(key));

        assertEquals("skipping renders nothing must never be a server-side tag", 0, backend.tags);
    }

    @Test
    public void verdictIsPerAssetKeyNotPerBackend() throws Exception {
        CountingBackend backend = new CountingBackend();
        MediaManager manager = manager(backend);
        String noted = video("a1", "immich-1").key();
        String neighbour = video("a2", "immich-1").key();
        String otherBackend = video("a1", "immich-2").key();

        manager.noteRenderSilent(noted);

        assertTrue(manager.isRenderSilent(noted));
        assertFalse("a sibling asset is not implicated by its neighbour",
                manager.isRenderSilent(neighbour));
        assertFalse("nor is the same raw id on a different backend",
                manager.isRenderSilent(otherBackend));
    }

    /**
     * The hole this exists to close: a render-silent clip is shown as its still preview, and that still
     * goes through the ladder's terminal rung — which tags. So the verdict can come back as a write to
     * the user's library through the door the policy did not lock.
     */
    @Test
    public void renderSilentAssetCannotBeTaggedByALaterImageFailure() throws Exception {
        CountingBackend backend = new CountingBackend();
        MediaManager manager = manager(backend);
        String key = video("37454989", "immich-1").key();

        manager.noteRenderSilent(key);
        manager.tagAssetAsIncompatible(key);

        assertEquals("a render-silent verdict must never reach the server, by any route",
                0, backend.tags);
    }

    /** Guards against "fixed" by making the gate a no-op: an ordinary asset must still get tagged. */
    @Test
    public void anOrdinaryAssetIsStillTagged() throws Exception {
        CountingBackend backend = new CountingBackend();
        MediaManager manager = manager(backend);

        manager.tagAssetAsIncompatible(video("deadbeef", "immich-1").key());

        long deadline = System.currentTimeMillis() + 2000L;
        while (backend.tags == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue("tagging itself still works — the gate above must not be the reason nothing happened",
                backend.tags > 0);
    }

    @Test
    public void nullKeyIsNeitherSilentNorRecorded() throws Exception {
        CountingBackend backend = new CountingBackend();
        MediaManager manager = manager(backend);

        assertFalse("a display that came from no pool asset cannot hold a verdict",
                manager.isRenderSilent(null));
        assertFalse(manager.noteRenderSilent(null));
        assertEquals(0, backend.tags);
    }
}
