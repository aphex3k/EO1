package com.aphex3k.eo1;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

/**
 * The Android half of {@link FrameTypography}: installs the frame's font scale onto a {@code Resources}
 * object so that every {@code sp} resolved through it costs the pixels the rule says, whatever the ROM
 * claimed about density or font size.
 *
 * <p>{@link android.util.DisplayMetrics#scaledDensity} is what {@code sp} is multiplied by — the platform
 * computes it as {@code density × Configuration.fontScale}, and both of those come from the ROM. Overwriting
 * {@code fontScale} with {@link FrameTypography#fontScaleFor(int, int)} divides the ROM's density claim out
 * of the app's own text, which is what makes one layout render at one size on every board. It is also why
 * the reach is automatic: the options dialog, the framework widgets inside it, the debug board and the toast
 * text all resolve through these resources, so nothing has to remember to ask.
 *
 * <p>On API 19 this is {@link Resources#updateConfiguration(Configuration, DisplayMetrics)}. It is deprecated
 * on newer platforms, where a scaled {@code Context} would be built instead; this app's
 * {@code targetSdk}/{@code maxSdk} are 19 and the call is the current API there. Three details of its
 * behaviour are load-bearing and each is a way to re-break this quietly:
 *
 * <ul>
 *   <li><b>Both arguments are copies.</b> {@code getConfiguration()} and {@code getDisplayMetrics()} hand
 *       back the live objects, and {@code updateConfiguration} diffs the configuration it is given against
 *       the one it already holds — hand it the same instance and the diff is empty, which is also the
 *       condition under which it declines to recompute {@code scaledDensity}. Passing the live metrics
 *       object would additionally let the caller's edit outlive the call.</li>
 *   <li><b>{@code scaledDensity} is set here as well.</b> The platform recomputes it only when the
 *       configuration diff reports a font-scale change; setting it in the metrics copy we pass means the
 *       value is right under either behaviour. {@link FrameTypography} keeps the two formulas identical, so
 *       they cannot disagree.</li>
 *   <li><b>{@code densityDpi} is never touched.</b> It selects which resource buckets the platform resolves
 *       ({@code values-w600dp} and friends) and what the platform believes about the panel; only the
 *       font-scale factor is ours to change.</li>
 * </ul>
 *
 * <p>A push of a real configuration (the panel being turned, locale, the ROM's own font-size setting) makes
 * the framework re-supply its own {@code fontScale}, so callers must re-apply on
 * {@link android.app.Activity#onConfigurationChanged}. {@code MainActivity} does.
 */
public final class FrameFontScale {

    /**
     * What one {@code sp} cost on this frame's resources the first time this process looked at them — the
     * platform's own answer, before anything here normalised it. {@code -1} until then.
     *
     * <p>Kept because the first call is always the earliest one in the process ({@code Eo1Application}), so
     * this is the only moment the ROM's claim is still visible: every later read reports what
     * {@link #apply} installed. It is the number that answers "why does this frame look bigger than that
     * one" without anyone opening a shell, and it survives the operator changing their multiplier, since it
     * describes the platform rather than the setting.
     */
    private static volatile float romPxPerSp = -1f;

    private FrameFontScale() {
    }

    /** The pixels per {@code sp} this frame's ROM would have used on its own, or {@code -1} if unmeasured. */
    public static float romPxPerSp() {
        return romPxPerSp;
    }

    /**
     * Installs {@code percent} on {@code res}.
     *
     * <p>Refuses, and changes nothing, when the platform will not say what its density is: guessing a
     * density is how a frame ends up with text that matches no panel, and the {@code /state} read-back
     * ({@link #appliedPxPerSp}) then shows the untouched ROM value instead of a fabricated one.
     *
     * @return true when the configuration was written.
     */
    public static boolean apply(Resources res, int percent) {
        if (res == null || !FrameTypography.isAllowed(percent)) {
            return false;
        }
        DisplayMetrics current = res.getDisplayMetrics();
        if (current == null) {
            return false;
        }
        if (romPxPerSp < 0f && current.scaledDensity > 0f) {
            // Two threads can both reach this before either writes; they compute the same value, and a
            // duplicate store of an unchanged number is not a race worth a lock across a resource read.
            romPxPerSp = current.scaledDensity;
        }
        if (current.densityDpi < 1) {
            return false;
        }
        float fontScale = FrameTypography.fontScaleFor(percent, current.densityDpi);
        if (fontScale <= 0f) {
            return false;
        }
        Configuration config = new Configuration(res.getConfiguration());
        if (config.fontScale == fontScale) {
            // Already what the rule asks for. Returning without writing keeps a re-apply on every
            // configuration change from churning the resources, and from provoking the framework's
            // font-scale handling on a value that has not moved.
            return Math.abs(current.scaledDensity - current.density * fontScale) < 1e-4f;
        }
        config.fontScale = fontScale;
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setTo(current);
        metrics.scaledDensity = metrics.density * fontScale;
        res.updateConfiguration(config, metrics);
        return true;
    }

    /**
     * What one {@code sp} actually costs in pixels on {@code res} right now — the read-back that separates
     * "the scale is installed" from "the scale was set and the platform did not take it". Reported in
     * {@code /state} beside the requested value for exactly that reason.
     */
    public static float appliedPxPerSp(Resources res) {
        if (res == null) {
            return -1f;
        }
        DisplayMetrics m = res.getDisplayMetrics();
        return m == null ? -1f : m.scaledDensity;
    }
}
