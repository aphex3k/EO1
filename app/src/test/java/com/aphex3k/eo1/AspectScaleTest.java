package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;

import com.dd.crop.TextureVideoView;

import org.junit.Test;

public class AspectScaleTest {

    private float scale = 0.0f;

    @Test
    public void ScaleTest() {

        assertEquals(scale, 0.0f, 0.0);

        scale = TextureVideoView.aspectScale(1080,1920,1080,1920);

        assertEquals(1.0, scale, 0.0);

        scale = TextureVideoView.aspectScale(1080,1920,720,1280);

        assertEquals(1.5, scale, 0.0);

        scale = TextureVideoView.aspectScale(1080,1920,480,640);

        assertEquals(3.0, scale, 0.0);

        scale = TextureVideoView.aspectScale(1080,1920,1920,1080);

        assertEquals(1.7777778, scale, 0.0001);

        scale = TextureVideoView.aspectScale(1080,1920,4096,2160);

        assertEquals(0.8888889, scale, 0.0001);
    }

    @Test
    public void cropScaleFactorsTest() {
        // Landscape video in a portrait view: fill height, crop the sides, no distortion,
        // and no uniform-scale over-zoom (the old setScale(s, s) bug).
        float[] landscapeInPortrait = TextureVideoView.cropScaleFactors(1080, 1920, 1920, 1080);
        assertEquals(3.1604938f, landscapeInPortrait[0], 0.0001);
        assertEquals(1.0f, landscapeInPortrait[1], 0.0001);

        // Same aspect as the view: the factors stay 1.0 (no extra zoom beyond the view stretch).
        float[] sameAspect = TextureVideoView.cropScaleFactors(1080, 1920, 720, 1280);
        assertEquals(1.0f, sameAspect[0], 0.0001);
        assertEquals(1.0f, sameAspect[1], 0.0001);

        // Oversized 4K landscape source downscales to fill the portrait view (crop sides).
        float[] downscale = TextureVideoView.cropScaleFactors(1080, 1920, 4096, 2160);
        assertEquals(3.3711934f, downscale[0], 0.0001);
        assertEquals(1.0f, downscale[1], 0.0001);
    }

    @Test
    public void rotatedVideoAlreadyMatchesTheViewAndMustNotBeScaled() {
        // The case measured on an EO2 playing a 1920x1080 clip with a -90 degree display matrix:
        // the decoder rotates before this view sees a pixel, so onVideoSizeChanged reports the
        // buffer as 1080x1920 into a 1080x1920 view. Transposing those axes a second time because
        // the angle is 90/270 produced (1.7778, 1.7778) — a native-resolution video composited at
        // 1.78x scale, 3.2x the pixels — and read on screen as video playing below realtime.
        float[] alreadyAligned = TextureVideoView.cropScaleFactors(1080, 1920, 1080, 1920);
        assertEquals(1.0f, alreadyAligned[0], 0.0001);
        assertEquals(1.0f, alreadyAligned[1], 0.0001);

        assertEquals(1.0f, TextureVideoView.aspectScale(1080, 1920, 1080, 1920), 0.0001);
    }
}
