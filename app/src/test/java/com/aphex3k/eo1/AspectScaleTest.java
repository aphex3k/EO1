package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void aspectFillNeverLetterboxesOnEitherPanelOrientation() {
        // The product rule this pins, stated as the arithmetic that expresses it. A TextureView first
        // stretches whatever buffer it holds into the view bounds, and the transform is applied on top
        // of that — so a factor of 1.0 on an axis means the content exactly reaches both edges, and
        // anything BELOW 1.0 would pull the content in from the edges, which is a bar. There is no
        // other way to read these numbers: "no axis below 1.0, with at least one exactly 1.0" is
        // precisely "fills the viewport, crops exactly the one axis that overflows, never bars".
        //
        // It holds today because aspectScale takes Math.max, and a fit choice would take Math.min.
        // That is why this test is worth having: the rule is now load-bearing, because routing sends
        // every clip whose aspect is not the panel's here, and a clip that arrived with bars would have
        // nowhere left to go — TsPlayer, the only alternative, is the engine that bars things.
        int[][] portraitViewportClips = {
                {1920, 1080},   // opposite orientation: the case that has always come here
                {1080, 1080},   // square: why the rule was broadened
                {640, 640},
                {1080, 1440},   // same orientation, wrong ratio
                {1920, 1088},   // macroblock-padded 1080p
                {1280, 720},
                {854, 480},
        };
        for (int[] clip : portraitViewportClips) {
            assertCoversWithoutBars(1080, 1920, clip[0], clip[1]);
        }
        // The mirror, so the property cannot be an accident of a portrait wall.
        for (int[] clip : new int[][] {{1080, 1920}, {1080, 1080}, {1920, 800}, {720, 1280}}) {
            assertCoversWithoutBars(1920, 1080, clip[0], clip[1]);
        }
    }

    private static void assertCoversWithoutBars(int viewWidth, int viewHeight,
                                                int clipWidth, int clipHeight) {
        float[] factors = TextureVideoView.cropScaleFactors(
                viewWidth, viewHeight, clipWidth, clipHeight);
        String what = clipWidth + "x" + clipHeight + " in " + viewWidth + "x" + viewHeight;
        assertTrue(what + " would leave a bar on the horizontal edges", factors[0] >= 1.0f - 1e-4);
        assertTrue(what + " would leave a bar on the vertical edges", factors[1] >= 1.0f - 1e-4);
        assertTrue(what + " over-crops both axes; one axis must land exactly on the edges",
                Math.min(factors[0], factors[1]) <= 1.0f + 1e-4);
    }
}
