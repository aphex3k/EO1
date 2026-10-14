package com.aphex3k.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;

/**
 * How a single {@link MediaAsset}'s bytes are obtained.
 *
 * <p>{@link Kind#LOCAL_FILE} sources carry the on-disk file directly (no download, no
 * integrity check); remote sources carry a one-shot stream opener plus the integrity
 * reference the download gate uses. The opener must return a fresh, open stream each
 * call — the pipeline closes it.
 *
 * <p>{@link Kind#REMOTE_URL} carries no bytes: the asset is a web page, and the display side
 * loads {@link #url} itself. The pipeline never caches or integrity-checks such a source.
 */
public final class MediaSource {

    public enum Kind {
        LOCAL_FILE,
        REMOTE_ORIGINAL,
        REMOTE_THUMBNAIL_FALLBACK,
        REMOTE_VIDEO_PLAYBACK_FALLBACK,
        /**
         * The asset is a web page: no bytes to fetch here, the display side loads
         * {@link MediaSource#url} in a browser view. Never cached.
         */
        REMOTE_URL
    }

    /**
     * Opens a fresh stream of the asset bytes. The caller closes it. Declares
     * {@code Exception} so backend-specific checked exceptions (e.g.
     * {@code MediaDownloadFailedException}) propagate to the pipeline intact.
     */
    public interface Opener {
        InputStream open() throws Exception;
    }

    public final Kind kind;
    /** Set for {@link Kind#LOCAL_FILE} only. */
    @Nullable public final File localFile;
    /** Set for remote kinds only. */
    @Nullable public final Opener opener;
    /** Integrity reference for the download gate, or null (fallback streams always re-fetch). */
    @Nullable public final String expectedChecksum;
    /** Expected byte size of the original, or null when unknown. */
    @Nullable public final Long expectedBytes;
    /** Page address for {@link Kind#REMOTE_URL} only; null for every other kind. */
    @Nullable public final String url;

    public MediaSource(Kind kind, @Nullable File localFile, @Nullable Opener opener,
                       @Nullable String expectedChecksum, @Nullable Long expectedBytes,
                       @Nullable String url) {
        this.kind = kind;
        this.localFile = localFile;
        this.opener = opener;
        this.expectedChecksum = expectedChecksum;
        this.expectedBytes = expectedBytes;
        this.url = url;
    }

    public static MediaSource localFile(@NonNull File file) {
        return new MediaSource(Kind.LOCAL_FILE, file, null, null, null, null);
    }

    public static MediaSource remoteOriginal(@NonNull Opener opener,
                                             @Nullable String expectedChecksum,
                                             @Nullable Long expectedBytes) {
        return new MediaSource(Kind.REMOTE_ORIGINAL, null, opener, expectedChecksum, expectedBytes,
                null);
    }

    public static MediaSource remoteThumbnail(@NonNull Opener opener) {
        return new MediaSource(Kind.REMOTE_THUMBNAIL_FALLBACK, null, opener, null, null, null);
    }

    public static MediaSource remoteVideoPlayback(@NonNull Opener opener) {
        return new MediaSource(Kind.REMOTE_VIDEO_PLAYBACK_FALLBACK, null, opener, null, null, null);
    }

    public static MediaSource remoteUrl(@NonNull String url) {
        return new MediaSource(Kind.REMOTE_URL, null, null, null, null, url);
    }

    public boolean isLocal() {
        return kind == Kind.LOCAL_FILE;
    }

    public boolean isFallback() {
        return kind == Kind.REMOTE_THUMBNAIL_FALLBACK || kind == Kind.REMOTE_VIDEO_PLAYBACK_FALLBACK;
    }
}
