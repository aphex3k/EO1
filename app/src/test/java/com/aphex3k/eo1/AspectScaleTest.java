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
}
