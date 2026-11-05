package com.aphex3k.eo1;

import java.lang.reflect.Method;

/**
 * Which media silicon a frame is built on, read from the properties the ROM itself publishes.
 *
 * <p>This exists because a model string cannot answer a silicon question. The EO2 is Amlogic
 * ("amlogic" / "stvm8b" / "meson8", read out of the dump in this repo); the EO1 is an NXP i.MX6Dual
 * ("MCIMX6D5EYM10AC", from the die marking in {@code _img/eo1-processor-fullwidth.jpg}). Both facts
 * came out of docs/RESEARCH.md, and the second axis is what makes them usable: buttons and sensors
 * belong to a model, the video engine belongs to a family of boards. Keying the engine on the model
 * makes "EO1" mean "Amlogic minus a library", which is what it used to, and is not true of any frame.
 *
 * <p>Only <em>Amlogic</em> is answered positively. There is no list of known-other families here on
 * purpose: the i.MX6 identification comes from a photograph and a teardown, not from a running EO1's
 * properties, and a table of strings nobody has measured on the device would fail closed on hardware
 * nobody has seen. So the result is two-valued, and "not Amlogic" is left to the model profile —
 * where an EO1 already says no.
 *
 * <p>Nothing here loads or touches a native library. It is a property read.
 */
public final class VideoSilicon {

    /** Confirmed Amlogic: the vendor player is at least worth attempting. */
    public static final String AMLOGIC = "amlogic";
    /** Not established either way; the model profile decides. */
    public static final String UNKNOWN = "unknown";

    /**
     * Substrings that mean "this is an Amlogic board". Every one is read from the EO2's own prop dump
     * in this repository, not from a vendor table.
     */
    private static final String[] AMLOGIC_MARKERS = {"amlogic", "meson", "stvm"};

    private VideoSilicon() {
    }

    /** @return {@link #AMLOGIC} or {@link #UNKNOWN} — see the class note on why nothing else. */
    public static String classify(String hardware, String board, String boardPlatform) {
        return containsAmlogicMarker(hardware) || containsAmlogicMarker(board)
                || containsAmlogicMarker(boardPlatform) ? AMLOGIC : UNKNOWN;
    }

    /**
     * Whether the engine is allowed, and which axis answered.
     *
     * <p>Amlogic silicon wins when it says yes, because a frame built on that family can run the
     * player whatever it calls itself; anything else falls through to the model profile. The two
     * cases are not symmetric by accident — silicon can only <em>permit</em> here, never forbid,
     * because a false "known not-Amlogic" would disable video acceleration on a board this repo has
     * only ever read about in a teardown.
     */
    public static boolean allowsTsPlayer(String silicon, boolean modelAllows) {
        return AMLOGIC.equals(silicon) || modelAllows;
    }

    /** Which axis settled it, for {@code /state}: a reader must be able to reconstruct the choice. */
    public static String decidingAxis(String silicon, boolean modelAllows) {
        if (AMLOGIC.equals(silicon)) {
            return AMLOGIC;
        }
        return modelAllows ? "model:allowed" : "model:denied";
    }

    /**
     * One line of {@code /proc/cpuinfo}, for a report that has to say which family a frame is on
     * without anyone opening it. Read on the {@code /state} thread, defensively: a null means the file
     * would not say, which is a different claim from a line that says something else.
     */
    public static String cpuModelLine() {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/cpuinfo"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Processor") || line.startsWith("model name")
                        || line.startsWith("Hardware")) {
                    return line.length() > 120 ? line.substring(0, 120) : line;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                    // Nothing to recover from; the answer is already decided.
                }
            }
        }
    }

    private static boolean containsAmlogicMarker(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < AMLOGIC_MARKERS.length; i++) {
            if (lower.contains(AMLOGIC_MARKERS[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code ro.board.platform} — the one input that is not a {@code Build} field on API 19. Read the
     * same reflective way {@code AdbdInstallClient} reads system properties; unreadable simply returns
     * null, which the classification treats as "this source said nothing" rather than "this board is
     * not Amlogic", and the remaining two inputs still get their say.
     */
    public static String boardPlatformProperty() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getMethod("get", String.class);
            Object value = get.invoke(null, "ro.board.platform");
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
