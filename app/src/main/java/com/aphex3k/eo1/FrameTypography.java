package com.aphex3k.eo1;

/**
 * The one rule for how big the frame's own text is, expressed in device-independent arithmetic so it can
 * be answered on a JVM host. Deliberately free of {@code android.*} — see {@link FrameFontScale} for the
 * half that touches a {@code Resources}.
 *
 * <p><b>Why a fixed number of pixels per sp, rather than the layouts' {@code sp} at face value.</b> An
 * {@code sp} costs {@code density × fontScale} pixels, and both of those numbers belong to the ROM, not to
 * this app. They are the whole reason one {@code activity_main.xml} renders a banner at two different sizes
 * on the two boards: the layout is shared, the claim about the panel is not. Measured on an EO2, {@code wm
 * density} answers 240 and the ROM's font size is unset (so 1.0) — which is what an EO2 shows today, and is
 * the look the operator asked the EO1 to match. <b>No EO1 density exists in this repository</b> (its frame
 * was offline when this was written, and no prop dump names one), and none is needed: pinning pixels-per-sp
 * makes the answer cause-agnostic. Whether the difference turns out to be {@code ro.sf.lcd_density}, the
 * {@code font_scale} setting, or both, the app's text reaches the panel at the same pixel size on either
 * board. A per-model constant would have had to guess one of those numbers for hardware nobody had read,
 * and AGENTS.md forbids model-specific {@code if}s outside {@link HardwareCapabilities} for the same reason.
 *
 * <p><b>What the reference is not.</b> It is a density, not a fraction of the panel. Every frame in this
 * fleet presents a 1080x1920 viewport, so at {@link #REFERENCE_DENSITY_DPI} a design size of 34sp is 51 px
 * on all of them. A board with a different panel geometry would need that constant revisited — text sized
 * this way does not grow with the panel on its own — and the operator's multiplier is the reachable knob
 * until someone measures one.
 *
 * <p><b>The multiplier</b> is the operator's, stored per-device (see {@link SettingsManager}'s
 * {@code font_scale_percent}) and set from the LAN page behind the configuration token. It scales every text
 * size this app draws by one number — the debug board, the now-playing banner, the options dialog, and the
 * framework's own widgets in those windows — because it is applied to the {@code Configuration.fontScale}
 * these sizes are resolved through, not written onto individual views. It deliberately does <em>not</em>
 * reach a kiosk web page's typography: a Chromium-33 WebView sizes its own document, and that page's fonts
 * are the page's business.
 */
public final class FrameTypography {

    /**
     * The density the design sizes were drawn against, and the density at which this app's text costs
     * {@code 1.5 px} per sp at multiplier 1.0. Read off a live EO2 ({@code wm density} = 240, ROM font size
     * unset), which is the rendering the EO1 is being matched to.
     */
    public static final int REFERENCE_DENSITY_DPI = 240;

    /** Multiplier 1.0: this app's text costs exactly what the layouts say at {@link #REFERENCE_DENSITY_DPI}. */
    public static final int DEFAULT_PERCENT = 100;

    /**
     * Bounds on the operator's multiplier. A quarter-size-to-four-times is far outside anything this fleet
     * needs for the density differences it actually has, and a bound is what keeps a mistyped
     * {@code 15} from turning the debug board into one glyph per screen — the board is a fixed set of rows
     * with no scroll of its own, so an unbounded scale does not just look wrong, it hides most of what it
     * is reporting.
     */
    public static final int MIN_PERCENT = 25;
    /** Upper bound; see {@link #MIN_PERCENT}. */
    public static final int MAX_PERCENT = 400;

    /** What {@link #parsePercent(String)} answers for input it will not accept. */
    public static final int INVALID = -1;

    private FrameTypography() {
    }

    /**
     * Pixels one {@code sp} must cost on this frame, for the operator's {@code percent}.
     *
     * <p>One division, not two multiplications: {@code (240/160f) * (percent/100f)} rounds twice, and this
     * number is printed to operators — on the debug board, in {@code /state}, and in the page's prose — so
     * the two-step form renders 38 of the 376 legal values as {@code 1.6500001} or {@code 1.5749999}. The
     * product stays inside int range (240 × 400), and at {@code percent == 100} both forms are exactly
     * 1.5f, so the design point this was measured against does not move.
     */
    public static float pxPerSp(int percent) {
        return (REFERENCE_DENSITY_DPI * percent) / 16000f;
    }

    /**
     * The absolute pixel size of a text view declared as {@code designSp} in a layout.
     *
     * <p>Rounded to whole pixels on purpose: "the EO1 looks exactly like the EO2" is a claim about two
     * panels, and an unrounded float would let the two differ by a fraction of a pixel depending on which
     * divisor recovered the declared size. Whole pixels also mean the number a screenshot shows is the
     * number this rule produced.
     */
    public static int sizePx(float designSp, int percent) {
        return Math.round(designSp * pxPerSp(percent));
    }

    /**
     * The {@code Configuration.fontScale} that makes an {@code sp} cost {@link #pxPerSp(int)} on resources
     * whose {@code density} is {@code density} — i.e. the value that divides the ROM's density claim out of
     * every text size this app resolves.
     *
     * <p>The parameter is {@code DisplayMetrics.density} and not {@code densityDpi} on purpose: {@code sp}
     * is resolved as {@code value × metrics.density × fontScale}, so the divisor that guarantees the product
     * lands on {@link #pxPerSp(int)} has to be the same number the platform multiplies by. On this platform
     * the two agree ({@code density == densityDpi/160}, set by {@code DisplayMetrics.updateDensity()}), and
     * {@link FrameFontScale} re-reads {@code scaledDensity} afterwards rather than trusting either.
     *
     * @param density the device's reported density; zero, negative or {@code NaN} means the platform did not
     *                answer, and the caller must then leave the configuration alone (see the return).
     * @return the font scale to install, or {@code -1} when {@code density} is unusable. Returning a number
     *         here would mean inventing a density, and an invented density is exactly the failure this class
     *         exists to avoid.
     */
    public static float fontScaleFor(int percent, float density) {
        if (!(density > 0f) || Float.isNaN(density)) {
            return -1f;
        }
        return pxPerSp(percent) / density;
    }

    /** Whether {@code percent} is a value the operator may put on a frame. */
    public static boolean isAllowed(int percent) {
        return percent >= MIN_PERCENT && percent <= MAX_PERCENT;
    }

    /**
     * Reads an operator-supplied scale. Two spellings are accepted, because both are natural at a keyboard
     * and neither is ambiguous against the other:
     *
     * <ul>
     *   <li>a multiplier — {@code 1}, {@code 1.25}, {@code .5}, optionally suffixed with {@code x} or
     *       {@code *}: the unit the request for this feature used;</li>
     *   <li>a percentage — {@code 125} with a trailing {@code %}.</li>
     * </ul>
     *
     * <p>A bare {@code 125} is <em>not</em> read as a percentage. That is the one input where the two
     * readings are not visually distinguishable, and guessing wrong by a factor of 100 is how an operator
     * ends up with text that fills the panel while believing they typed nothing unusual. Out-of-range and
     * unparseable input answer {@link #INVALID}, and the caller reports that rather than clamping: a
     * request that asks for 16x is a typo, and a frame silently rendering at 4x is a frame doing something
     * nobody asked it to do. (A value already on disk is a different case — see
     * {@link SettingsManager#fontScalePercent()}, which falls back to the default rather than propagating
     * damage out of a preferences file edited by hand.)
     */
    public static int parsePercent(String raw) {
        if (raw == null) {
            return INVALID;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return INVALID;
        }
        boolean percentForm = s.endsWith("%");
        if (percentForm) {
            s = s.substring(0, s.length() - 1).trim();
        } else if (s.endsWith("x") || s.endsWith("*")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.isEmpty()) {
            return INVALID;
        }
        float value;
        try {
            value = Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return INVALID;
        }
        if (!Float.isFinite(value) || value <= 0f) {
            return INVALID;
        }
        // The bounds are tested on the value the operator sent, before rounding it into an int. Testing only
        // the rounded result would accept 0.249 and 4.004 as 25 and 400 — which is the silent clamping this
        // method's whole contract says it does not do.
        float asPercent = percentForm ? value : value * 100f;
        if (asPercent < MIN_PERCENT || asPercent > MAX_PERCENT) {
            return INVALID;
        }
        int percent = Math.round(asPercent);
        return isAllowed(percent) ? percent : INVALID;
    }

    /** The human-readable form used by the debug board, {@code /state} and the web page's echo. */
    public static String describe(int percent) {
        return percent + "% (x" + (percent / 100f) + ", " + pxPerSp(percent) + " px/sp)";
    }
}
