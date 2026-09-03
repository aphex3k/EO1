package com.aphex3k.eo1;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class MediaTypeHelperTest {

    @Test
    public void isGifFileDetectsGifExtension() {
        assertTrue(MediaTypeHelper.isGifFile(new File("/cache/abc123.gif")));
        assertTrue(MediaTypeHelper.isGifFile(new File("/cache/abc123.GIF")));
    }

    @Test
    public void isGifFileRejectsNonGifExtensions() {
        assertFalse(MediaTypeHelper.isGifFile(new File("/cache/abc123.jpg")));
        assertFalse(MediaTypeHelper.isGifFile(new File("/cache/abc123.mp4")));
        assertFalse(MediaTypeHelper.isGifFile(new File("/cache/abc123.heic")));
    }
}
