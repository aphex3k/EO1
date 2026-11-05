package com.aphex3k.eo1;

/**
 * The profile for hardware the factory could not identify: an EO1's input and sensor assumptions,
 * with nothing switched off.
 *
 * <p>This class exists because "not an EO2" stopped meaning "an EO1". The EO1's silicon has no
 * Amlogic player, so if an unidentified box inherited {@link Eo1Capabilities} it would inherit that
 * limitation too — and a real EO2 whose {@code Build.DEVICE} or {@code Build.MODEL} merely changed
 * spelling would quietly stop using the engine it was built for, with nothing in the app reporting
 * that a choice was ever made. Losing TsPlayer to a string comparison is the same class of silent
 * regression as losing it to a linker error, and far harder to see.
 *
 * <p>It reports {@code "generic"} rather than {@code "EO1"} for the same reason {@code /state} exists:
 * once the two names carry different capabilities, a frame that answers as one while behaving as the
 * other is the bug, not the harmless default.
 */
public final class GenericCapabilities extends Eo1Capabilities {

    @Override
    public String platform() {
        return "generic";
    }

    /** Unknown hardware keeps the engine: nothing here says this board lacks it. */
    @Override
    public boolean supportsTsPlayer() {
        return true;
    }
}
