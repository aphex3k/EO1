package com.aphex3k.eo1;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Revolving media cache: keep downloaded originals on disk, reserve a safety margin,
 * and evict owned media files when space is needed.
 */
public class MediaCacheManager {

    public static final long SAFETY_MARGIN_BYTES = 128L * 1024L * 1024L;

    /**
     * UUID-style Immich asset id followed by an extension, or {@code _eo1.mp4}/{@code _eo1.jpg} suffix.
     * Matches {@code abc-123.mp4}, {@code abc-123.jpg}, {@code abc-123_eo1.mp4}, {@code abc-123_eo1.jpg}.
     */
    private static final Pattern MEDIA_CACHE_NAME = Pattern.compile(
            "^[0-9a-fA-F-]{8,}(\\.[a-zA-Z0-9]{1,10}|_eo1\\.(mp4|jpg))$");

    public interface SpaceProvider {
        long usableSpace(File cacheDir);
    }

    private final SpaceProvider spaceProvider;
    private final Random random;

    public MediaCacheManager() {
        this(File::getUsableSpace, new Random());
    }

    public MediaCacheManager(SpaceProvider spaceProvider) {
        this(spaceProvider, new Random());
    }

    public MediaCacheManager(SpaceProvider spaceProvider, Random random) {
        this.spaceProvider = spaceProvider;
        this.random = random;
    }

    public long usableSpace(File cacheDir) {
        if (cacheDir == null) {
            return 0L;
        }
        return spaceProvider.usableSpace(cacheDir);
    }

    /**
     * Ensures {@code usableSpace >= bytesNeeded + SAFETY_MARGIN_BYTES} by evicting
     * owned media files. Returns false if space cannot be freed.
     */
    public boolean ensureSpace(File cacheDir, long bytesNeeded, Set<String> protectedPaths) {
        if (cacheDir == null || bytesNeeded < 0) {
            return false;
        }
        long required = bytesNeeded + SAFETY_MARGIN_BYTES;
        Set<String> protectedCopy = protectedPaths != null
                ? new HashSet<>(protectedPaths)
                : Collections.<String>emptySet();

        while (usableSpace(cacheDir) < required) {
            File victim = pickEvictionVictim(listEvictableMedia(cacheDir, protectedCopy));
            if (victim == null) {
                return false;
            }
            if (!victim.delete()) {
                // Avoid infinite loop on undeletable files
                protectedCopy.add(victim.getAbsolutePath());
                continue;
            }
            MediaIntegrity.deleteSidecar(victim);
        }
        return true;
    }

    public List<File> listEvictableMedia(File cacheDir, Set<String> protectedPaths) {
        List<File> result = new ArrayList<>();
        if (cacheDir == null || !cacheDir.isDirectory()) {
            return result;
        }
        File[] children = cacheDir.listFiles();
        if (children == null) {
            return result;
        }
        Set<String> protectedCopy = protectedPaths != null ? protectedPaths : Collections.<String>emptySet();
        for (File child : children) {
            if (!child.isFile()) {
                continue;
            }
            if (protectedCopy.contains(child.getAbsolutePath())) {
                continue;
            }
            if (!isOwnedMediaCacheFile(child)) {
                continue;
            }
            result.add(child);
        }
        return result;
    }

    /**
     * Pick a random evictable candidate.
     */
    public File pickEvictionVictim(List<File> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    public static boolean isOwnedMediaCacheFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        return MEDIA_CACHE_NAME.matcher(file.getName()).matches();
    }

    /**
     * Total free space that must remain after writing {@code bytesNeeded}.
     */
    public static long requiredFreeBytes(long bytesNeeded) {
        return bytesNeeded + SAFETY_MARGIN_BYTES;
    }
}
