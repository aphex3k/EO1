package com.aphex3k.media.local;

import androidx.annotation.Nullable;

import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaBackend;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Backend for media uploaded to this device through the LAN web server
 * ({@code filesDir/uploaded}).
 *
 * <p>{@link #fetchCatalog()} re-scans the directory on every call so web uploads
 * appear on the next rotation tick without a restart. Deterministic ids
 * ({@link #localAssetIdFor}) keep cache file names stable across rescans.
 */
public class LocalMediaBackend implements MediaBackend {

    /** Recognised video extensions for uploaded files (lowercase, no dot). */
    private static final Set<String> VIDEO_EXTENSIONS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("mp4", "mov", "mkv", "avi", "ts", "m4v", "webm", "3gp", "mpg", "mpeg", "hevc", "h265")));
    /** Recognised image extensions for uploaded files (lowercase, no dot). */
    private static final Set<String> IMAGE_EXTENSIONS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng")));

    private final String id;
    private final File uploadDir;

    public LocalMediaBackend(String id, File uploadDir) {
        this.id = id;
        this.uploadDir = uploadDir;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return ConfigurationBackendEntry.TYPE_LOCAL;
    }

    @Override
    public List<MediaAsset> fetchCatalog() {
        List<MediaAsset> assets = new ArrayList<>();
        File[] files = uploadDir.listFiles();
        if (files == null) {
            return assets;
        }
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            MediaType type = mediaTypeForExtension(extensionOf(f.getName()));
            if (type == null) {
                continue;
            }
            assets.add(new MediaAsset(
                    localAssetIdFor(f.getName()), id, type, -1, null,
                    f.getName(), null, f.length(), f.getAbsolutePath()));
        }
        return assets;
    }

    @Override
    public MediaSource resolveOriginal(MediaAsset asset) {
        if (asset == null || asset.localPath == null) {
            return null;
        }
        File f = new File(asset.localPath);
        return f.isFile() ? MediaSource.localFile(f) : null;
    }

    /** Deterministic UUID derived from the file name; stable across rescans. */
    public static String localAssetIdFor(String filename) {
        return UUID.nameUUIDFromBytes(filename.getBytes(Charset.forName("UTF-8"))).toString();
    }

    /** Maps an extension (with or without leading dot) to a media type, or null when unrecognized. */
    @Nullable
    public static MediaType mediaTypeForExtension(String extWithDot) {
        String e = extWithDot.startsWith(".") ? extWithDot.substring(1) : extWithDot;
        if (VIDEO_EXTENSIONS.contains(e)) {
            return MediaType.VIDEO;
        }
        if (IMAGE_EXTENSIONS.contains(e)) {
            return MediaType.IMAGE;
        }
        return null;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase();
    }
}
