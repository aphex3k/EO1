package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaBackend;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;
import com.aphex3k.media.local.LocalMediaBackend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives {@link MediaManager}'s merged-pool rotation over fake in-memory backends:
 * deterministic shuffle, per-backend failure isolation, local-asset playback, cache
 * reuse, and stale-pool invalidation. No HTTP is involved.
 */
public class MediaManagerCyclingTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final MediaManager.UiPoster directPoster = new MediaManager.UiPoster() {
        @Override
        public void post(Runnable action) {
            action.run();
        }
    };

    /** Records what the pipeline hands to the UI. */
    private static class FakeListener implements MediaManagerListener {
        final List<Exception> exceptions = new ArrayList<>();
        final List<String> displayedKeys = new ArrayList<>();
        final List<File> displayedFiles = new ArrayList<>();

        @Override
        public void handleException(Exception e) {
            exceptions.add(e);
        }

        @Override
        public void debugInformationProvided(DebugInformation debugInformation) {
        }

        @Override
        public void displayPicture(File file, String assetKey) {
            displayedFiles.add(file);
            displayedKeys.add(assetKey);
        }

        @Override
        public void displayVideo(File file, String assetKey) {
            displayedFiles.add(file);
            displayedKeys.add(assetKey);
        }
    }

    /** In-memory backend: canned assets, countable fetch/resolve/open calls, fault injection. */
    private static class FakeBackend implements MediaBackend {
        final String id;
        final String type;
        final List<MediaAsset> assets = new ArrayList<>();
        final Set<String> nullResolve = new HashSet<>();
        final Set<String> throwingOpeners = new HashSet<>();
        final AtomicInteger fetchCount = new AtomicInteger();
        final AtomicInteger openerCalls = new AtomicInteger();
        volatile Exception fetchError;
        /** When set, the remote source reports a checksum the served bytes never match. */
        volatile boolean corruptChecksum;

        FakeBackend(String id, String type, MediaAsset... assets) {
            this.id = id;
            this.type = type;
            this.assets.addAll(Arrays.asList(assets));
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public List<MediaAsset> fetchCatalog() throws Exception {
            fetchCount.incrementAndGet();
            if (fetchError != null) {
                throw fetchError;
            }
            return assets;
        }

        @Override
        public MediaSource resolveOriginal(MediaAsset asset) {
            String localPath = asset.localPath;
            if (localPath != null) {
                File f = new File(localPath);
                return f.isFile() ? MediaSource.localFile(f) : null;
            }
            if (nullResolve.contains(asset.id)) {
                return null;
            }
            if (throwingOpeners.contains(asset.id)) {
                final String assetId = asset.id;
                return MediaSource.remoteOriginal(new MediaSource.Opener() {
                    @Override
                    public InputStream open() throws Exception {
                        openerCalls.incrementAndGet();
                        throw new IOException("open failed for " + assetId);
                    }
                }, "bad-checksum", 10L);
            }
            final byte[] content = contentFor(asset.id);
            final String checksum = corruptChecksum
                    ? sha1Base64("some-other-bytes".getBytes(StandardCharsets.UTF_8))
                    : sha1Base64(content);
            return MediaSource.remoteOriginal(new MediaSource.Opener() {
                @Override
                public InputStream open() throws Exception {
                    openerCalls.incrementAndGet();
                    return new ByteArrayInputStream(content);
                }
            }, checksum, (long) content.length);
        }

        byte[] contentFor(String assetId) {
            return ("fake-content-" + assetId).getBytes(StandardCharsets.UTF_8);
        }

        private static String sha1Base64(byte[] bytes) {
            try {
                return MediaIntegrity.toBase64(MessageDigest.getInstance("SHA-1").digest(bytes));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static MediaAsset remoteAsset(String id, String backendId) {
        return new MediaAsset(id, backendId, MediaType.IMAGE, -1, null,
                id + ".jpg", null, null, null);
    }

    private static FakeBackend backendA(int count) {
        MediaAsset[] assets = new MediaAsset[count];
        for (int i = 0; i < count; i++) {
            assets[i] = remoteAsset("a" + i, "fakeA");
        }
        return new FakeBackend("fakeA", "immich", assets);
    }

    private static FakeBackend backendB(int count) {
        MediaAsset[] assets = new MediaAsset[count];
        for (int i = 0; i < count; i++) {
            assets[i] = remoteAsset("b" + i, "fakeB");
        }
        return new FakeBackend("fakeB", "immich", assets);
    }

    private MediaManager manager(List<MediaBackend> backends, FakeListener listener, long seed) {
        return new MediaManager(listener, backends, new Configuration(), new Random(seed),
                new MediaCacheManager(dir -> Long.MAX_VALUE));
    }

    @Test
    public void mergedPoolShufflesDeterministically() throws Exception {
        FakeListener l1 = new FakeListener();
        FakeListener l2 = new FakeListener();
        MediaManager m1 = manager(Arrays.asList(backendA(3), backendB(2)), l1, 42L);
        MediaManager m2 = manager(Arrays.asList(backendA(3), backendB(2)), l2, 42L);

        m1.showNextImageLocked(tmp.newFolder("cache1"), tmp.newFolder("up1"), directPoster, false);
        m2.showNextImageLocked(tmp.newFolder("cache2"), tmp.newFolder("up2"), directPoster, false);

        assertEquals(1, l1.displayedKeys.size());
        assertEquals(l1.displayedKeys.get(0), l2.displayedKeys.get(0));
        // 5 merged assets, one shown: 4 remain.
        assertEquals(4, m1.rotationListSize());
    }

    @Test
    public void rotationCyclesAcrossBackendsAndRefetchesOnExhaustion() throws Exception {
        FakeBackend a = backendA(3);
        FakeBackend b = backendB(2);
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a, b), listener, 7L);
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
            seen.add(listener.displayedKeys.get(i));
        }
        assertEquals(5, seen.size());
        assertEquals(0, mm.rotationListSize());
        assertEquals(1, a.fetchCount.get());
        assertEquals(1, b.fetchCount.get());

        // Sixth tick: pool exhausted -> refetch every backend, then display the next asset.
        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(2, a.fetchCount.get());
        assertEquals(2, b.fetchCount.get());
        assertEquals(6, listener.displayedKeys.size());
        assertEquals(4, mm.rotationListSize());
    }

    @Test
    public void failedBackendIsSkippedAndOthersRotate() throws Exception {
        FakeBackend a = backendA(3);
        a.fetchError = new IOException("backend A down");
        FakeBackend b = backendB(2);
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a, b), listener, 7L);

        mm.showNextImageLocked(tmp.newFolder("cache"), tmp.newFolder("uploads"), directPoster, false);

        assertEquals(1, listener.displayedKeys.size());
        assertTrue(listener.displayedKeys.get(0).startsWith("fakeB:"));
        assertEquals(1, mm.rotationListSize());
        int failures = 0;
        for (Exception e : listener.exceptions) {
            if (e instanceof BackendUnavailableException) {
                failures++;
                assertEquals("backend A down", ((BackendUnavailableException) e).getCause().getMessage());
            }
        }
        assertEquals(1, failures);
    }

    @Test
    public void allBackendsFailingYieldsNoMediaFound() throws Exception {
        FakeBackend a = backendA(2);
        a.fetchError = new Exception("down a");
        FakeBackend b = backendB(2);
        b.fetchError = new Exception("down b");
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a, b), listener, 7L);

        mm.showNextImageLocked(tmp.newFolder("cache"), tmp.newFolder("uploads"), directPoster, false);

        assertTrue(listener.displayedKeys.isEmpty());
        assertEquals(3, listener.exceptions.size());
        assertTrue(listener.exceptions.get(0) instanceof BackendUnavailableException);
        assertTrue(listener.exceptions.get(1) instanceof BackendUnavailableException);
        assertTrue(listener.exceptions.get(2) instanceof NoMediaFoundException);
    }

    @Test
    public void failedAssetsAreSkippedAndRotationContinues() throws Exception {
        FakeBackend a = backendA(3);
        a.nullResolve.add("a0");
        a.throwingOpeners.add("a1");
        FakeBackend b = backendB(1);
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a, b), listener, 1L);
        mm.prefetchEnabled = false; // keep this test's exact opener counts hermetic
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        int ticks = 0;
        while (listener.displayedKeys.size() < 2 && ticks < 4) {
            mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
            ticks++;
        }

        assertEquals(2, listener.displayedKeys.size());
        Set<String> shown = new HashSet<>(listener.displayedKeys);
        assertTrue(shown.containsAll(Arrays.asList("fakeA:a2", "fakeB:b0")));
        // Exactly one remote-download failure is surfaced (a1); the null-resolve skip is silent.
        int downloadFailures = 0;
        for (Exception e : listener.exceptions) {
            if (e instanceof MediaDownloadFailedException) {
                downloadFailures++;
            }
        }
        assertEquals(1, downloadFailures);
        // a1's opener failed once (no retry after a hard open failure); a2 opened once.
        assertEquals(2, a.openerCalls.get());
    }

    @Test
    public void localPeerPlaysFromUploadsDirWithoutDownloading() throws Exception {
        File uploads = tmp.newFolder("uploads");
        byte[] localContent = "local-upload-bytes".getBytes(StandardCharsets.UTF_8);
        File localFile = new File(uploads, "upload.jpg");
        Files.write(localFile.toPath(), localContent);
        String localAssetId = LocalMediaBackend.localAssetIdFor("upload.jpg");
        MediaAsset localAsset = new MediaAsset(localAssetId, "fakeL", MediaType.IMAGE, -1, null,
                "upload.jpg", null, (long) localContent.length, localFile.getAbsolutePath());
        FakeBackend local = new FakeBackend("fakeL", "local", localAsset);
        FakeBackend remote = backendB(2);
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(local, remote), listener, 3L);
        mm.prefetchEnabled = false; // keep this test's exact opener counts hermetic
        File cacheDir = tmp.newFolder("cache");

        int ticks = 0;
        while (listener.displayedKeys.size() < 3 && ticks < 3) {
            mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
            ticks++;
        }

        Set<String> shown = new HashSet<>(listener.displayedKeys);
        assertTrue(shown.containsAll(Arrays.asList("fakeL:" + localAssetId, "fakeB:b0", "fakeB:b1")));
        assertEquals(3, shown.size());
        // The local asset plays from the uploads dir itself; nothing was cached for it.
        File localShown = null;
        for (int i = 0; i < listener.displayedKeys.size(); i++) {
            if (listener.displayedKeys.get(i).equals("fakeL:" + localAssetId)) {
                localShown = listener.displayedFiles.get(i);
            }
        }
        assertEquals(localFile.getAbsolutePath(), localShown.getAbsolutePath());
        assertEquals(0, local.openerCalls.get());
        assertEquals(2, remote.openerCalls.get());
        for (File f : cacheDir.listFiles()) {
            assertFalse(f.getName().contains(localAssetId));
        }
    }

    @Test
    public void backendChangeInvalidatesPoolButUnrelatedChangeDoesNot() throws Exception {
        Configuration config = new Configuration();
        ConfigurationBackendEntry e = new ConfigurationBackendEntry();
        e.type = ConfigurationBackendEntry.TYPE_IMMICH;
        e.id = "immich-1";
        e.host = "https://one.example/";
        e.userid = "u";
        e.password = "p";
        e.apiVersion = ConfigurationBackendEntry.API_VERSION_AUTO;
        config.backends = new ArrayList<>();
        config.backends.add(e);

        FakeBackend a = backendA(3);
        FakeListener listener = new FakeListener();
        MediaManager mm = new MediaManager(listener, Arrays.asList(a), config, new Random(7L),
                new MediaCacheManager(dir -> Long.MAX_VALUE));
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(1, a.fetchCount.get());
        assertEquals(2, mm.rotationListSize());

        // No config change: no refetch.
        mm.invalidatePoolIfStale();
        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(1, a.fetchCount.get());

        // Backend entry changed: next tick refetches.
        e.host = "https://two.example/";
        mm.invalidatePoolIfStale();
        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(2, a.fetchCount.get());

        // Unrelated setting: no refetch.
        config.interval = 30;
        mm.invalidatePoolIfStale();
        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(2, a.fetchCount.get());
    }

    @Test
    public void remoteDownloadWritesCacheAndReusesIt() throws Exception {
        FakeBackend a = backendA(1);
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a), listener, 7L);
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(1, listener.displayedKeys.size());
        assertEquals("fakeA:a0", listener.displayedKeys.get(0));
        assertEquals(1, a.openerCalls.get());

        File cached = new File(cacheDir, "a0.jpg");
        assertTrue(cached.isFile());
        byte[] served = a.contentFor("a0");
        assertEquals(new String(served, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(cached.toPath()), StandardCharsets.UTF_8));
        File sidecar = new File(cacheDir, "a0.jpg.sha1");
        assertTrue(sidecar.isFile());
        String expectedHex = MediaIntegrity.toHex(MessageDigest.getInstance("SHA-1").digest(served));
        assertEquals(expectedHex, new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8).trim());

        // Next tick: pool exhausted -> refetch -> same asset served from cache, opener not re-invoked.
        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(2, listener.displayedKeys.size());
        assertEquals(1, a.openerCalls.get());
        assertEquals(2, a.fetchCount.get());
    }

    /** Minimal ISO 14496-12 ftyp box with major brand {@code heic} (16 bytes). */
    private static byte[] heicFtypBytes() {
        byte[] b = new byte[16];
        b[3] = 16; // big-endian box size 0x00000010
        putFourCc(b, 4, "ftyp");
        putFourCc(b, 8, "heic");
        return b;
    }

    private static void putFourCc(byte[] b, int off, String fourCc) {
        System.arraycopy(fourCc.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, b, off, 4);
    }

    @Test
    public void incompatibleExtensionIsSkippedBeforeDownload() throws Exception {
        String heicId = "01234567-89ab-cdef-0123-456789abcdef";
        MediaAsset heic = new MediaAsset(heicId, "fakeA", MediaType.IMAGE, -1, null,
                heicId + ".heic", null, null, null);
        FakeBackend a = new FakeBackend("fakeA", "immich", heic, remoteAsset("a0", "fakeA"));
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a), listener, 7L);
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        int ticks = 0;
        while (listener.displayedKeys.isEmpty() && ticks < 2) {
            mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
            ticks++;
        }

        assertEquals(1, listener.displayedKeys.size());
        assertEquals("fakeA:a0", listener.displayedKeys.get(0));
        // The .heic asset was never opened (no wasted download); the skip is silent.
        assertEquals(1, a.openerCalls.get());
        assertTrue(listener.exceptions.isEmpty());
        assertFalse(new File(cacheDir, heicId + ".heic").exists());
    }

    @Test
    public void byteVerifiedIncompatibleFileIsDiscardedAndRemembered() throws Exception {
        String heicId = "01234567-89ab-cdef-0123-456789abcdef";
        MediaAsset heic = new MediaAsset(heicId, "fakeA", MediaType.IMAGE, -1, null,
                heicId + ".jpg", null, null, null); // HEIF content behind a .jpg name
        final AtomicInteger heicOpens = new AtomicInteger();
        FakeBackend a = new FakeBackend("fakeA", "immich", heic) {
            @Override
            byte[] contentFor(String assetId) {
                heicOpens.incrementAndGet();
                return heicFtypBytes();
            }
        };
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a), listener, 7L);
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);

        // Downloaded once, byte-checked and discarded; the exhaustion refetch already sees
        // the remembered key, so no second download.
        assertEquals(1, heicOpens.get());
        assertEquals(2, a.fetchCount.get());
        assertTrue(listener.displayedKeys.isEmpty());
        int noMedia = 0;
        for (Exception e : listener.exceptions) {
            if (e instanceof NoMediaFoundException) {
                noMedia++;
            }
        }
        assertEquals(1, noMedia);
        assertFalse(new File(cacheDir, heicId + ".jpg").exists());

        // Next tick: pool rebuilt again, still no re-download.
        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(1, heicOpens.get());
        assertEquals(3, a.fetchCount.get());
    }

    @Test
    public void corruptedDownloadFailsIntegrityAndSurfacesNoMediaFound() throws Exception {
        // UUID-shaped id so the cache file matches the owned-name regex and failed
        // downloads are actually deleted between attempts.
        String a0Id = "01234567-89ab-cdef-0123-456789abcdef";
        FakeBackend a = new FakeBackend("fakeA", "immich", remoteAsset(a0Id, "fakeA"));
        // The source reports a checksum the served bytes never match.
        a.corruptChecksum = true;
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a), listener, 7L);
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);

        // Two attempts in the first pass, two after the exhaustion refetch.
        assertEquals(4, a.openerCalls.get());
        int mdFailures = 0;
        int noMedia = 0;
        for (Exception e : listener.exceptions) {
            if (e instanceof MediaDownloadFailedException) {
                mdFailures++;
            }
            if (e instanceof NoMediaFoundException) {
                noMedia++;
            }
        }
        assertEquals(2, mdFailures);
        assertEquals(1, noMedia);
        assertTrue(listener.displayedKeys.isEmpty());
        assertTrue(cacheDir.listFiles().length == 0);
    }

    /** Polls for up to {@code timeoutMs} milliseconds until {@code file} exists on disk. */
    private static boolean awaitFile(File file, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile()) {
                return true;
            }
            Thread.sleep(20);
        }
        return file.isFile();
    }

    @Test
    public void nextAssetIsPrefetchedIntoCache() throws Exception {
        // Seed 7 leaves the 2-element pool unshuffled: [a0, a1].
        FakeBackend a = backendA(2);
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a), listener, 7L);
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(1, listener.displayedKeys.size());
        assertEquals("fakeA:a0", listener.displayedKeys.get(0));

        // Prefetch stages the next record (a1) while a0 is on screen: the sidecar is written
        // only after a fully verified download, so awaiting it means the prefetch finished.
        File sidecar = new File(cacheDir, "a1.jpg.sha1");
        assertTrue("prefetch should stage the next asset's cache file + sidecar",
                awaitFile(sidecar, 5000));
        int opensAfterPrefetch = a.openerCalls.get();

        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(2, listener.displayedKeys.size());
        assertEquals("fakeA:a1", listener.displayedKeys.get(1));
        // a1 was served from the prefetched cache: tick 2 adds no further open.
        assertEquals(opensAfterPrefetch, a.openerCalls.get());
    }

    @Test
    public void noPrefetchWhenPoolExhausted() throws Exception {
        // Single-asset pool: after the tick the cursor is at the pool end, so the
        // prefetch guard must not spawn a thread (or read past the end).
        FakeBackend a = backendA(1);
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a), listener, 7L);
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(1, listener.displayedKeys.size());
        Thread.sleep(300);

        assertEquals(1, a.openerCalls.get());
        assertEquals(1, a.fetchCount.get());
        assertTrue(listener.exceptions.isEmpty());
    }

    @Test
    public void prefetchDisabledPerformsNoBackgroundDownloads() throws Exception {
        // Seed 7 leaves the 2-element pool unshuffled: [a0, a1].
        FakeBackend a = backendA(2);
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a), listener, 7L);
        mm.prefetchEnabled = false;
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(1, listener.displayedKeys.size());
        assertEquals("fakeA:a0", listener.displayedKeys.get(0));
        Thread.sleep(300);

        assertEquals(1, a.openerCalls.get());
        File[] cached = cacheDir.listFiles();
        assertEquals(2, cached.length); // only a0.jpg + a0.jpg.sha1
        for (File f : cached) {
            assertTrue(f.getName().startsWith("a0"));
        }
        assertFalse(new File(cacheDir, "a1.jpg").isFile());
    }

    @Test
    public void failedPrefetchPostsNothing() throws Exception {
        // Seed 7 leaves the 2-element pool unshuffled: [a0, a1]; a1's opener always fails,
        // so the background prefetch of a1 must fail silently.
        FakeBackend a = backendA(2);
        a.throwingOpeners.add("a1");
        FakeListener listener = new FakeListener();
        MediaManager mm = manager(Arrays.asList(a), listener, 7L);
        File cacheDir = tmp.newFolder("cache");
        File uploads = tmp.newFolder("uploads");

        mm.showNextImageLocked(cacheDir, uploads, directPoster, false);
        assertEquals(1, listener.displayedKeys.size());
        assertEquals("fakeA:a0", listener.displayedKeys.get(0));

        // Wait for the failed prefetch attempt: a0's main-path open plus a1's failed open.
        long deadline = System.currentTimeMillis() + 5000;
        while (a.openerCalls.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("prefetch should have attempted a1", a.openerCalls.get() >= 2);

        // A failed prefetch must not surface through the listener and leaves no cache file.
        assertTrue(listener.exceptions.isEmpty());
        assertEquals(1, listener.displayedKeys.size());
        assertFalse(new File(cacheDir, "a1.jpg").isFile());
    }
}
