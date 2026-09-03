package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.aphex3k.immichApi.ImmichType;

import org.junit.Test;

public class CacheFileNameTest {

    private static final String UUID = "abc123";

    @Test
    public void cacheFileNameUsesOriginalExtension() {
        assertEquals("abc123.jpg", MediaManager.cacheFileName(UUID, "photo.jpg", null, ImmichType.IMAGE, false));
        assertEquals("abc123.mp4", MediaManager.cacheFileName(UUID, "video.MP4", null, ImmichType.VIDEO, false));
    }

    @Test
    public void cacheFileNameUsesOriginalPathWhenNameHasNoExtension() {
        assertEquals("abc123.mov", MediaManager.cacheFileName(UUID, "clip", "path/to/clip.mov", ImmichType.VIDEO, false));
    }

    @Test
    public void cacheFileNameFallsBackToTypeDefault() {
        assertEquals("abc123.jpg", MediaManager.cacheFileName(UUID, "IMG_1234", null, ImmichType.IMAGE, false));
        assertEquals("abc123.mp4", MediaManager.cacheFileName(UUID, "clip", null, ImmichType.VIDEO, false));
    }

    @Test
    public void cacheFileNameUsesFallbackEndpointExtension() {
        assertEquals("abc123.mp4", MediaManager.cacheFileName(UUID, "video.mov", null, ImmichType.VIDEO, true));
        assertEquals("abc123.jpg", MediaManager.cacheFileName(UUID, "photo.heic", null, ImmichType.IMAGE, true));
    }

    @Test
    public void transcodeCacheFileNameUsesEo1Suffix() {
        assertEquals("abc123_eo1.mp4", VideoTranscodeManager.transcodeCacheFileName(UUID));
    }

    @Test
    public void imageConvertCacheFileNameUsesEo1JpgSuffix() {
        assertEquals("abc123_eo1.jpg", ImageConvertManager.convertCacheFileName(UUID));
    }

    @Test
    public void extensionFromFileNameRejectsInvalidValues() {
        assertNull(MediaManager.extensionFromFileName("file."));
        assertNull(MediaManager.extensionFromFileName("file.toolongext1"));
        assertNull(MediaManager.extensionFromFileName("file.tx$t"));
        assertNull(MediaManager.extensionFromFileName(""));
        assertNull(MediaManager.extensionFromFileName(null));
    }
}
