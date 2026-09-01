package com.aphex3k.eo1.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImageProbeResultTest {

    @Test
    public void heicNeedsConvert() {
        ImageProbeResult heic = ImageProbeResult.success("hevc", "heif", 4032, 3024);
        assertTrue(heic.isKnownIncompatible());
        assertTrue(heic.needsConvert());
    }

    @Test
    public void oversizedJpegNeedsConvert() {
        ImageProbeResult jpeg = ImageProbeResult.success("mjpeg", "image2", 4000, 3000);
        assertFalse(jpeg.isKnownIncompatible());
        assertTrue(jpeg.exceedsMaxLongestAxis());
        assertTrue(jpeg.needsConvert());
    }

    @Test
    public void inBoundsJpegDoesNotNeedConvert() {
        ImageProbeResult jpeg = ImageProbeResult.success("mjpeg", "image2", 1920, 1080);
        assertFalse(jpeg.needsConvert());
    }

    @Test
    public void exactlyMaxAxisDoesNotNeedConvert() {
        ImageProbeResult jpeg = ImageProbeResult.success("mjpeg", "jpeg", 1920, 1080);
        assertEquals(1920, jpeg.getLongestAxis());
        assertFalse(jpeg.exceedsMaxLongestAxis());
        assertFalse(jpeg.needsConvert());
    }
}
