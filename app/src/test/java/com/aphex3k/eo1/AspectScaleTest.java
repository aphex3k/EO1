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
    public void rotationAwareScaleTest() {
        float noRotation = TextureVideoView.rotationAwareAspectScale(1080, 1920, 1920, 1080, 0);
        assertEquals(1.7777778, noRotation, 0.0001);

        float rotatedPortrait = TextureVideoView.rotationAwareAspectScale(1080, 1920, 1920, 1080, 90);
        assertEquals(1.0, rotatedPortrait, 0.0001);

        float rotatedLandscape = TextureVideoView.rotationAwareAspectScale(1080, 1920, 1080, 1920, 270);
        assertEquals(1.7777778, rotatedLandscape, 0.0001);
    }

    @Test
    public void cropScaleFactorsTest() {
        // Landscape video in a portrait view: fill height, crop the sides, no distortion,
        // and no uniform-scale over-zoom (the old setScale(s, s) bug).
        float[] landscapeInPortrait = TextureVideoView.cropScaleFactors(1080, 1920, 1920, 1080, 0);
        assertEquals(3.1604938f, landscapeInPortrait[0], 0.0001);
        assertEquals(1.0f, landscapeInPortrait[1], 0.0001);

        // Same aspect as the view: the factors stay 1.0 (no extra zoom beyond the view stretch).
        float[] sameAspect = TextureVideoView.cropScaleFactors(1080, 1920, 720, 1280, 0);
        assertEquals(1.0f, sameAspect[0], 0.0001);
        assertEquals(1.0f, sameAspect[1], 0.0001);

        // 90-degree rotated 1920x1080 content in a 1080x1920 view: net uniform 1.0 after
        // the postRotate, exact fit.
        float[] rotated = TextureVideoView.cropScaleFactors(1080, 1920, 1920, 1080, 90);
        assertEquals(1.7777778f, rotated[0], 0.0001);
        assertEquals(0.5625f, rotated[1], 0.0001);

        // Oversized 4K landscape source downscales to fill the portrait view (crop sides).
        float[] downscale = TextureVideoView.cropScaleFactors(1080, 1920, 4096, 2160, 0);
        assertEquals(3.3711934f, downscale[0], 0.0001);
        assertEquals(1.0f, downscale[1], 0.0001);
    }
}
