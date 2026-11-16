package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The arithmetic behind "the same banner, the same size, on every board".
 *
 * <p>The claim under test is not that one frame's text is a particular size; it is that the size does not
 * depend on what the frame's ROM says about its panel. That is the defect this class exists for: an EO1 and
 * an EO2 render the same {@code activity_main.xml} at different sizes, because {@code sp} costs
 * {@code density × fontScale} pixels and those two boards answer differently. Nothing here can measure a
 * frame — the numbers are inputs — but every way the mechanism can be silently wrong is answerable on a JVM
 * host, including the one that would have hidden the bug: a rule that looks right at one density and drifts
 * at another.
 */
public class FrameTypographyTest {

    /** An EO2 at its measured density, with the multiplier at 1.0, must be left exactly as it renders. */
    @Test
    public void theReferenceDensityIsLeftAloneAtMultiplierOne() {
        assertEquals(1.0f, FrameTypography.fontScaleFor(FrameTypography.DEFAULT_PERCENT, 240), 1e-6f);
        assertEquals(1.5f, FrameTypography.pxPerSp(FrameTypography.DEFAULT_PERCENT), 1e-6f);
    }

    /**
     * The whole point. A frame at a different density has its font scale adjusted so that one {@code sp}
     * still costs the same pixels — which is what makes an EO1 match an EO2 whatever either ROM claims.
     */
    @Test
    public void pxPerSpAgreesAcrossDensities() {
        for (int densityDpi : new int[]{120, 160, 213, 240, 320, 480}) {
            float fontScale = FrameTypography.fontScaleFor(FrameTypography.DEFAULT_PERCENT, densityDpi);
            float pxPerSpOnThisDevice = (densityDpi / 160f) * fontScale;
            assertEquals("density " + densityDpi + " must land on the reference",
                    FrameTypography.pxPerSp(FrameTypography.DEFAULT_PERCENT), pxPerSpOnThisDevice, 1e-4f);
        }
    }

    /** A frame at half the reference density gets double the font scale — the EO1 case, numerically. */
    @Test
    public void aLowerDensityFrameScalesUp() {
        assertEquals(1.5f, FrameTypography.fontScaleFor(FrameTypography.DEFAULT_PERCENT, 160), 1e-6f);
        // And the banner really is the same pixels on both boards.
        assertEquals(FrameTypography.sizePx(34f, FrameTypography.DEFAULT_PERCENT),
                Math.round(34f * (160 / 160f) * FrameTypography.fontScaleFor(100, 160)));
        assertEquals(51, FrameTypography.sizePx(34f, FrameTypography.DEFAULT_PERCENT));
    }

    /** The multiplier rides on top of the density correction rather than replacing it. */
    @Test
    public void multiplierComposesWithDensityCorrection() {
        assertEquals(2.25f, FrameTypography.fontScaleFor(150, 160), 1e-6f);
        assertEquals(1.5f * 1.5f, FrameTypography.pxPerSp(150), 1e-6f);
    }

    /** Sizes come out as whole pixels, so two boards cannot differ by a fraction of one. */
    @Test
    public void sizesAreWholePixels() {
        int px = FrameTypography.sizePx(20f, 33);
        assertEquals(px, FrameTypography.sizePx(20f, 33));
        assertTrue(px > 0);
        assertEquals(30, FrameTypography.sizePx(20f, 100));
    }

    /**
     * No invented density. A platform that will not say what its density is must not be handed a font scale
     * computed from a guess — the caller is expected to leave the configuration alone (see
     * {@link FrameFontScale#apply}), and this is the only place that answers "unusable".
     */
    @Test
    public void anUnreportedDensityIsRefusedNotGuessed() {
        assertEquals(-1f, FrameTypography.fontScaleFor(100, 0), 0f);
        assertEquals(-1f, FrameTypography.fontScaleFor(100, -1), 0f);
    }

    @Test
    public void boundsAreExclusiveOfEverythingOutside() {
        assertTrue(FrameTypography.isAllowed(FrameTypography.MIN_PERCENT));
        assertTrue(FrameTypography.isAllowed(FrameTypography.MAX_PERCENT));
        assertFalse(FrameTypography.isAllowed(FrameTypography.MIN_PERCENT - 1));
        assertFalse(FrameTypography.isAllowed(FrameTypography.MAX_PERCENT + 1));
        assertFalse(FrameTypography.isAllowed(0));
        assertFalse("the sentinel must never pass as a scale", FrameTypography.isAllowed(FrameTypography.INVALID));
    }

    @Test
    public void multiplierFormIsAccepted() {
        assertEquals(100, FrameTypography.parsePercent("1"));
        assertEquals(100, FrameTypography.parsePercent(" 1.0 "));
        assertEquals(125, FrameTypography.parsePercent("1.25"));
        assertEquals(50, FrameTypography.parsePercent(".5"));
        assertEquals(125, FrameTypography.parsePercent("1.25x"));
        assertEquals(125, FrameTypography.parsePercent("1.25 *"));
        assertEquals(25, FrameTypography.parsePercent("0.25"));
        assertEquals(400, FrameTypography.parsePercent("4"));
    }

    @Test
    public void percentageFormIsAccepted() {
        assertEquals(125, FrameTypography.parsePercent("125%"));
        assertEquals(100, FrameTypography.parsePercent("100 %"));
        assertEquals(400, FrameTypography.parsePercent("400%"));
    }

    /**
     * A bare {@code 150} is refused rather than read as a percentage. This is the one spelling where the two
     * readings are indistinguishable at the keyboard, and the wrong reading is off by a factor of 100 — text
     * that fills the panel, from an operator who believes they typed something ordinary.
     */
    @Test
    public void aBareNumberIsAMultiplierEvenWhenItLooksLikeAPercentage() {
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("150"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("15"));
    }

    @Test
    public void nonsenseIsRefused() {
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent(null));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent(""));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("   "));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("big"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("%"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("x"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("0"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("-1"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("1e400"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("NaN"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("Infinity"));
        assertEquals("out of range is refused, not clamped", FrameTypography.INVALID,
                FrameTypography.parsePercent("5"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("0.2"));
    }

    /** The line an operator reads on the board and in {@code /state}: asked-for and cost, together. */
    @Test
    public void describeNamesBothTheMultiplierAndWhatItCosts() {
        String s = FrameTypography.describe(125);
        assertTrue(s, s.contains("125%"));
        assertTrue(s, s.contains("1.875"));
    }
}
