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

    /** The divisor is {@code DisplayMetrics.density}, so a dpi has to be converted like the platform does. */
    private static float fontScaleAtDpi(int percent, int densityDpi) {
        return FrameTypography.fontScaleFor(percent, densityDpi / 160f);
    }

    /** An EO2 at its measured density, with the multiplier at 1.0, must be left exactly as it renders. */
    @Test
    public void theReferenceDensityIsLeftAloneAtMultiplierOne() {
        assertEquals(1.0f, fontScaleAtDpi(FrameTypography.DEFAULT_PERCENT, 240), 1e-6f);
        // Exactly, not approximately: this value is printed to operators and quoted in the commit that
        // measured the reference frame, so a rounding tail here would be a tail in every report.
        assertEquals(1.5f, FrameTypography.pxPerSp(FrameTypography.DEFAULT_PERCENT), 0f);
    }

    /**
     * The whole point. A frame at a different density has its font scale adjusted so that one {@code sp}
     * still costs the same pixels — which is what makes an EO1 match an EO2 whatever either ROM claims.
     */
    @Test
    public void pxPerSpAgreesAcrossDensities() {
        for (int densityDpi : new int[]{120, 160, 213, 240, 320, 480}) {
            float pxPerSpOnThisDevice = (densityDpi / 160f) * fontScaleAtDpi(
                    FrameTypography.DEFAULT_PERCENT, densityDpi);
            assertEquals("density " + densityDpi + " must land on the reference",
                    FrameTypography.pxPerSp(FrameTypography.DEFAULT_PERCENT), pxPerSpOnThisDevice, 1e-4f);
        }
    }

    /** A frame at two thirds of the reference density gets 1.5x the font scale — the EO1 case, numerically. */
    @Test
    public void aLowerDensityFrameScalesUp() {
        assertEquals(1.5f, fontScaleAtDpi(FrameTypography.DEFAULT_PERCENT, 160), 1e-6f);
        // And the banner really is the same pixels on both boards: 34sp resolves to this on a 160dpi frame
        // and to the same on a 240dpi one, which is the sentence the whole class exists to make true.
        assertEquals(51, FrameTypography.sizePx(34f, FrameTypography.DEFAULT_PERCENT));
        assertEquals(51, Math.round(34f * 1.0f * fontScaleAtDpi(100, 160)));
        assertEquals(51, Math.round(34f * 1.5f * fontScaleAtDpi(100, 240)));
    }

    /** The multiplier rides on top of the density correction rather than replacing it. */
    @Test
    public void multiplierComposesWithDensityCorrection() {
        assertEquals(2.25f, fontScaleAtDpi(150, 160), 1e-6f);
        assertEquals(2.25f, FrameTypography.pxPerSp(150), 0f);
    }

    /**
     * Sizes come out as whole pixels, so two boards cannot differ by a fraction of one. Asserted against
     * literals rather than against another call to the same function — a test that compares a pure function
     * to itself cannot fail, which is the trap this file's own history warns about.
     */
    @Test
    public void sizesAreWholePixels() {
        // 20sp at x1.0 on the reference density: 30 px exactly.
        assertEquals(30, FrameTypography.sizePx(20f, 100));
        // 20sp at x0.33 → 0.495 px/sp → 9.9 px, rounded rather than truncated or left fractional.
        assertEquals(10, FrameTypography.sizePx(20f, 33));
        // 24sp at x1.1 → 1.65 px/sp → 39.6 px.
        assertEquals(40, FrameTypography.sizePx(24f, 110));
        // A recovered design size that carries float noise from the division still lands on the same pixel.
        assertEquals(FrameTypography.sizePx(34f, 100),
                FrameTypography.sizePx((51f / 1.5f) + 0.000001f, 100));
    }

    /**
     * The number printed to operators is the number the rule produced, not a float's rounding tail. The
     * first version did two float operations, which rendered 38 of the 376 legal percents as
     * {@code 1.6500001}-style artifacts on the debug board, in {@code /state} and in the page's prose —
     * including for {@code 1.1}, the most natural thing to type after {@code 1.0}.
     */
    @Test
    public void theReportedNumberIsNotAFloatArtifact() {
        for (int percent = FrameTypography.MIN_PERCENT; percent <= FrameTypography.MAX_PERCENT; percent++) {
            String printed = String.valueOf(FrameTypography.pxPerSp(percent));
            assertTrue(percent + "% renders as " + printed,
                    printed.matches("0\\.\\d{1,3}|[1-9]\\.\\d{1,3}|[1-9]"));
        }
        assertEquals("1.5", String.valueOf(FrameTypography.pxPerSp(100)));
        assertEquals("1.65", String.valueOf(FrameTypography.pxPerSp(110)));
        assertTrue(FrameTypography.describe(110), FrameTypography.describe(110).contains("1.65 px/sp"));
        assertFalse(FrameTypography.describe(110), FrameTypography.describe(110).contains("1.6500001"));
    }

    /**
     * No invented density. A platform that will not say what its density is must not be handed a font scale
     * computed from a guess — the caller is expected to leave the configuration alone (see
     * {@link FrameFontScale#apply}), and this is the only place that answers "unusable".
     */
    @Test
    public void anUnreportedDensityIsRefusedNotGuessed() {
        assertEquals(-1f, FrameTypography.fontScaleFor(100, 0f), 0f);
        assertEquals(-1f, FrameTypography.fontScaleFor(100, -1f), 0f);
        assertEquals(-1f, FrameTypography.fontScaleFor(100, Float.NaN), 0f);
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

    /**
     * The bounds are on the value sent, not on the int it rounds to. Testing only the rounded result let
     * {@code 0.249} and {@code 4.004} in as exactly 25 and 400, which is the silent clamping the parser's
     * own contract — and this file's documentation, and the page's prose — says never happens.
     */
    @Test
    public void justPastABoundIsRefusedRatherThanRoundedIntoRange() {
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("0.249"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("4.004"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("24%"));
        assertEquals(FrameTypography.INVALID, FrameTypography.parsePercent("401%"));
        // The bounds themselves are inclusive, and a value that lands on one is accepted exactly.
        assertEquals(FrameTypography.MIN_PERCENT, FrameTypography.parsePercent("0.25"));
        assertEquals(FrameTypography.MAX_PERCENT, FrameTypography.parsePercent("4"));
        assertEquals(FrameTypography.MAX_PERCENT, FrameTypography.parsePercent("400%"));
    }

    /** The line an operator reads on the board and in {@code /state}: asked-for and cost, together. */
    @Test
    public void describeNamesBothTheMultiplierAndWhatItCosts() {
        String s = FrameTypography.describe(125);
        assertTrue(s, s.contains("125%"));
        assertTrue(s, s.contains("1.875"));
    }
}
