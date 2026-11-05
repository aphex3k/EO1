package com.aphex3k.eo1;

import java.util.Locale;

/**
 * Picks the {@link HardwareCapabilities} implementation matching the device the app runs on.
 *
 * <p>Detection is on {@code ro.product.device} / {@code ro.product.model} (the live EO2 reports
 * "eo2" / "EO2", the live EO1 "EO1"). Both names are now matched <em>positively</em>, and that is the
 * whole design change: this factory used to ask only "is it an EO2?" and hand everything else the EO1
 * profile, which was harmless while the two differed only in buttons and sensors. The EO1's silicon
 * turns out to be genuinely different — no Amlogic player — so an unrecognised frame must not inherit
 * that, because the only thing distinguishing them is a string. An EO2 on firmware that renames
 * itself would otherwise lose its preferred engine silently: no error, no log line, just slower
 * video. See {@link GenericCapabilities}.
 */
public final class HardwareCapabilitiesFactory {

    private HardwareCapabilitiesFactory() {
    }

    /**
     * @param device value of {@code android.os.Build.DEVICE}, may be null
     * @param model  value of {@code android.os.Build.MODEL}, may be null
     */
    public static HardwareCapabilities detect(String device, String model) {
        if (containsIgnoreCase(device, "eo2") || containsIgnoreCase(model, "eo2")) {
            return new Eo2Capabilities();
        }
        if (containsIgnoreCase(device, "eo1") || containsIgnoreCase(model, "eo1")) {
            return new Eo1Capabilities();
        }
        return new GenericCapabilities();
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
