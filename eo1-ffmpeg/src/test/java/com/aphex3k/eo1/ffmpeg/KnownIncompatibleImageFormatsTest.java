package com.aphex3k.eo1.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KnownIncompatibleImageFormatsTest {

    @Test
    public void detectsHeicFormatTokens() {
        assertTrue(KnownIncompatibleImageFormats.isKnownIncompatible("heic"));
        assertTrue(KnownIncompatibleImageFormats.isKnownIncompatible("heif"));
        assertTrue(KnownIncompatibleImageFormats.isKnownIncompatible("mov,heif,mp4"));
        assertTrue(KnownIncompatibleImageFormats.isKnownIncompatible("mif1"));
        assertFalse(KnownIncompatibleImageFormats.isKnownIncompatible("jpeg"));
        assertFalse(KnownIncompatibleImageFormats.isKnownIncompatible("mjpeg"));
        assertFalse(KnownIncompatibleImageFormats.isKnownIncompatible(null));
    }

    @Test
    public void detectsHeicExtensions() {
        assertTrue(KnownIncompatibleImageFormats.isHeicExtension("photo.HEIC"));
        assertTrue(KnownIncompatibleImageFormats.isHeicExtension("a.heif"));
        assertFalse(KnownIncompatibleImageFormats.isHeicExtension("photo.jpg"));
        assertFalse(KnownIncompatibleImageFormats.isHeicExtension(null));
    }
}
