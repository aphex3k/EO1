package com.aphex3k.eo1;

import java.util.Locale;

/**
 * Picks the {@link HardwareCapabilities} implementation matching the device
 * the app runs on.
 *
 * <p>Detection is on {@code ro.product.device} / {@code ro.product.model}
 * (the live EO2 reports "eo2" / "EO2"). Anything else defaults to the
 * original full-feature EO1 profile, so unknown devices keep the historical
 * behaviour.
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
        return new Eo1Capabilities();
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
