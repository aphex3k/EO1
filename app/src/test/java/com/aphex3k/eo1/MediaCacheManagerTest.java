package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class MediaCacheManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private AtomicLong usableSpace;
    private MediaCacheManager manager;
    private File cacheDir;

    @Before
    public void setUp() throws IOException {
        cacheDir = tempFolder.getRoot();
        usableSpace = new AtomicLong(MediaCacheManager.SAFETY_MARGIN_BYTES);
        manager = new MediaCacheManager(dir -> usableSpace.get(), new Random(42));
    }

    @Test
    public void isOwnedMediaCacheFileMatchesUuidAssets() throws IOException {
        File original = new File(cacheDir, "178a4ea6-6675-49c7-be59-674b84987622.mp4");
        File converted = new File(cacheDir, "178a4ea6-6675-49c7-be59-674b84987622_eo1.mp4");
        File image = new File(cacheDir, "abc12345-1111-2222-3333-444444444444.jpg");
        File imageConverted = new File(cacheDir, "abc12345-1111-2222-3333-444444444444_eo1.jpg");
        assertTrue(original.createNewFile());
        assertTrue(converted.createNewFile());
        assertTrue(image.createNewFile());
        assertTrue(imageConverted.createNewFile());

        assertTrue(MediaCacheManager.isOwnedMediaCacheFile(original));
        assertTrue(MediaCacheManager.isOwnedMediaCacheFile(converted));
        assertTrue(MediaCacheManager.isOwnedMediaCacheFile(image));
        assertTrue(MediaCacheManager.isOwnedMediaCacheFile(imageConverted));
    }

    @Test
    public void listEvictableMediaSkipsDirectoriesAndProtectedPaths() throws IOException {
        File original = writeFile("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.mp4", "orig");
        File converted = writeFile("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee_eo1.mp4", "tc");
        File glideDir = tempFolder.newFolder("image_manager_disk_cache");
        assertTrue(glideDir.isDirectory());

        Set<String> protectedPaths = new HashSet<>();
        protectedPaths.add(converted.getAbsolutePath());

        List<File> evictable = manager.listEvictableMedia(cacheDir, protectedPaths);

        assertEquals(1, evictable.size());
        assertEquals(original, evictable.get(0));
    }

    @Test
    public void pickEvictionVictimReturnsSoleCandidate() throws IOException {
        File only = writeFile("aaaaaaaa-bbbb-cccc-dddd-ffffffffffff.mp4", "x");
        assertEquals(only, manager.pickEvictionVictim(Collections.singletonList(only)));
    }

    @Test
    public void ensureSpaceNoOpWhenAlreadyEnough() throws IOException {
        File original = writeFile("aaaaaaaa-bbbb-cccc-dddd-111111111111.mp4", "keep");
        usableSpace.set(MediaCacheManager.SAFETY_MARGIN_BYTES + 1000);

        assertTrue(manager.ensureSpace(cacheDir, 500, Collections.<String>emptySet()));
        assertTrue(original.exists());
    }

    @Test
    public void ensureSpaceEvictsUntilEnough() throws IOException {
        File original = writeFile("bbbbbbbb-bbbb-cccc-dddd-222222222222.mp4", "xxxx");
        File converted = writeFile("cccccccc-bbbb-cccc-dddd-333333333333_eo1.mp4", "yyyy");
        usableSpace.set(MediaCacheManager.SAFETY_MARGIN_BYTES);

        // 2 bytes beyond margin must be freed; simulate freed space on each delete.
        MediaCacheManager countingManager = new MediaCacheManager(dir -> usableSpace.get(), new Random(7)) {
            @Override
            public boolean ensureSpace(File dir, long bytesNeeded, Set<String> protectedPaths) {
                long required = bytesNeeded + SAFETY_MARGIN_BYTES;
                Set<String> protectedCopy = protectedPaths != null
                        ? new HashSet<>(protectedPaths)
                        : Collections.<String>emptySet();
                while (usableSpace(dir) < required) {
                    File victim = pickEvictionVictim(listEvictableMedia(dir, protectedCopy));
                    if (victim == null) {
                        return false;
                    }
                    long freed = victim.length();
                    if (!victim.delete()) {
                        protectedCopy.add(victim.getAbsolutePath());
                        continue;
                    }
                    usableSpace.addAndGet(freed);
                }
                return true;
            }
        };

        assertTrue(countingManager.ensureSpace(cacheDir, 2, Collections.<String>emptySet()));
        // Exactly one of the two candidates is evicted to cover the 2-byte shortfall; pick order
        // is now random, so assert the remaining count, not which file.
        assertEquals(1, cacheDir.listFiles().length);
    }

    @Test
    public void ensureSpaceNeverDeletesProtectedPath() throws IOException {
        File playing = writeFile("dddddddd-bbbb-cccc-dddd-444444444444.mp4", "play");
        File other = writeFile("eeeeeeee-bbbb-cccc-dddd-555555555555.mp4", "other");
        usableSpace.set(MediaCacheManager.SAFETY_MARGIN_BYTES);

        MediaCacheManager countingManager = new MediaCacheManager(dir -> usableSpace.get(), new Random(3)) {
            @Override
            public boolean ensureSpace(File dir, long bytesNeeded, Set<String> protectedPaths) {
                long required = bytesNeeded + SAFETY_MARGIN_BYTES;
                Set<String> protectedCopy = protectedPaths != null
                        ? new HashSet<>(protectedPaths)
                        : Collections.<String>emptySet();
                while (usableSpace(dir) < required) {
                    File victim = pickEvictionVictim(listEvictableMedia(dir, protectedCopy));
                    if (victim == null) {
                        return false;
                    }
                    long freed = victim.length();
                    if (!victim.delete()) {
                        protectedCopy.add(victim.getAbsolutePath());
                        continue;
                    }
                    usableSpace.addAndGet(freed);
                }
                return true;
            }
        };

        Set<String> protectedPaths = Collections.singleton(playing.getAbsolutePath());
        assertTrue(countingManager.ensureSpace(cacheDir, 2, protectedPaths));
        assertTrue(playing.exists());
        assertFalse(other.exists());
    }

    @Test
    public void ensureSpaceReturnsFalseWhenNothingToEvict() {
        usableSpace.set(0);
        assertFalse(manager.ensureSpace(cacheDir, 1, Collections.<String>emptySet()));
    }

    @Test
    public void pickEvictionVictimNullOnEmpty() {
        assertNull(manager.pickEvictionVictim(Collections.<File>emptyList()));
        assertNull(manager.pickEvictionVictim(null));
    }

    @Test
    public void requiredFreeBytesIncludesMargin() {
        assertEquals(MediaCacheManager.SAFETY_MARGIN_BYTES + 100,
                MediaCacheManager.requiredFreeBytes(100));
        assertNotNull(manager);
    }

    private File writeFile(String name, String contents) throws IOException {
        File file = new File(cacheDir, name);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(contents);
        }
        return file;
    }
}
