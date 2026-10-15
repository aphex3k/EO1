package com.aphex3k.eo1;

import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaBackend;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A backend that starts degraded must reach the user exactly once — an empty pool makes
 * {@link MediaManager#showNextImageLocked} rebuild on every tick, and a warning that repeats
 * with it stops being a warning.
 */
public class MediaManagerWarningTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final MediaManager.UiPoster directPoster = new MediaManager.UiPoster() {
        @Override
        public void post(Runnable action) {
            action.run();
        }
    };

    private static class RecordingListener implements MediaManagerListener {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void handleException(Exception e) {
        }

        @Override
        public void debugInformationProvided(DebugInformation debugInformation) {
        }

        @Override
        public void displayPicture(File file, String assetKey) {
        }

        @Override
        public void displayVideo(File file, String assetKey) {
        }

        @Override
        public void displayWebsite(String url, String assetKey) {
        }

        @Override
        public void warnUser(String message) {
            warnings.add(message);
        }
    }

    /** Backend that comes up degraded and reports a fixed number of assets. */
    private static class DegradedBackend implements MediaBackend {
        private final List<MediaAsset> assets;
        private String warning = "Immich 4.1.0 is outside the supported range; best effort.";

        DegradedBackend(int assetCount) {
            List<MediaAsset> out = new ArrayList<>();
            for (int i = 0; i < assetCount; i++) {
                out.add(new MediaAsset("x" + i, "degraded", MediaType.IMAGE, -1, null,
                        "x" + i + ".jpg", null, null, null, 0, 0));
            }
            this.assets = out;
        }

        @Override
        public String getId() {
            return "degraded";
        }

        @Override
        public String getType() {
            return "immich";
        }

        @Override
        public List<MediaAsset> fetchCatalog() {
            return assets;
        }

        @Override
        public MediaSource resolveOriginal(MediaAsset asset) {
            return null;
        }

        @Override
        public String compatibilityWarning() {
            return warning;
        }
    }

    private MediaManager manager(MediaBackend backend, RecordingListener listener) {
        return new MediaManager(listener, Collections.singletonList(backend), new Configuration(),
                new Random(1L), new MediaCacheManager(dir -> Long.MAX_VALUE));
    }

    @Test
    public void degradedBackendWarnsOnceAcrossRebuilds() throws Exception {
        RecordingListener listener = new RecordingListener();
        DegradedBackend backend = new DegradedBackend(3);
        MediaManager manager = manager(backend, listener);
        File cache = tmp.newFolder("cache1");
        File uploads = tmp.newFolder("uploads1");

        for (int i = 0; i < 5; i++) {
            manager.showNextImageLocked(cache, uploads, directPoster, false);
        }

        assertEquals("one warning, not one per tick", 1, listener.warnings.size());
        assertTrue(listener.warnings.get(0).contains("outside the supported range"));
    }

    @Test
    public void warningRefiresWhenTheBackendStartsClean() throws Exception {
        RecordingListener listener = new RecordingListener();
        DegradedBackend backend = new DegradedBackend(3);
        MediaManager manager = manager(backend, listener);
        File cache = tmp.newFolder("cache2");
        File uploads = tmp.newFolder("uploads2");

        manager.showNextImageLocked(cache, uploads, directPoster, false);
        assertEquals(1, listener.warnings.size());

        // The caveat changes (server upgraded, or the version probe starting to fail), so the
        // user should hear about the new state rather than have the old warning mute it forever.
        backend.warning = "Could not determine the Immich server version; assuming 3.x.";
        manager.showNextImageLocked(cache, uploads, directPoster, false);

        assertEquals(2, listener.warnings.size());
    }

    @Test
    public void healthyBackendNeverWarns() throws Exception {
        RecordingListener listener = new RecordingListener();
        MediaBackend healthy = new DegradedBackend(2) {
            @Override
            public String compatibilityWarning() {
                return null;
            }
        };
        MediaManager manager = manager(healthy, listener);

        manager.showNextImageLocked(tmp.newFolder("cache3"), tmp.newFolder("uploads3"),
                directPoster, false);

        assertEquals(Collections.emptyList(), listener.warnings);
    }
}
