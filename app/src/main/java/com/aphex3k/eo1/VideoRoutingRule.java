package com.aphex3k.eo1;

import androidx.annotation.Nullable;

/**
 * The rule that decides which video engine a clip is suitable for, apart from anything about the
 * frame that has to run it.
 *
 * <p>It exists as a separate type for one reason: the decision it makes is the whole of the product
 * rule, and it was unreachable from a JVM test. Its caller sits inside an {@code Activity} behind a
 * header probe, so the only thing the suite could assert about it was the arithmetic of the predicate
 * it delegates to — which stayed green through a change that moved most of a library from one engine
 * to the other. Everything here is static and free of Android, so the rule can be pinned directly.
 *
 * <p>It answers "which engine can draw this correctly", never "which engine this frame has". On a
 * board whose native library never loaded the answer here is discarded by {@code selectVideoPlayer},
 * which is why {@link VideoEngineReport#engineWord} takes both inputs and says them together. Keeping
 * the frame out of this class is what stops a routing line from claiming TsPlayer on an EO1.
 */
public final class VideoRoutingRule {

    private VideoRoutingRule() {
    }

    /**
     * Whether TsPlayer may composite this clip.
     *
     * <p>TsPlayer's native player exposes no scale and no rotation control — its JNI surface is
     * create/setSurface/start/stop/pause/resume/delete/getStatus/getCurrentTime and nothing else — so
     * it draws with one fixed scaling and is correct only when the clip's displayed shape already
     * equals the panel's. Everything else goes to the platform player, which center-crops to fill.
     * That is why the rule is "is this native" rather than "is this upside-down for the panel": the
     * old test for the aspect being merely <em>opposite</em> left a square, a 3:4 and a 2.4:1 on the
     * engine that cannot fill any of them, so they reached the wall letterboxed.
     *
     * <p>Two terms, and they are not interchangeable. The rotation term is an engine question and
     * lives only here: a clip whose display matrix turns needs a player that will honour the turn, and
     * the {@code .so} ignores display-matrix metadata outright. Whether the same clip also needs a
     * different <em>source</em> is a separate question that {@link MediaCompatibility} asks of the same
     * numbers before the bytes are ever fetched, through the one shared
     * {@link MediaCompatibility#videoAspectDiffersFromPanel}.
     *
     * <p>An unmeasurable panel yields {@code true} — TsPlayer keeps the clip. A frame that cannot say
     * its own shape must not lose its preferred engine on a guess, since the only alternative this
     * rule can reach is the slower compositor. An unknown matrix is read as unturned for the aspect
     * term while the rotation term simply cannot fire, which is the asymmetry
     * {@code docs/TSPLAYER.md} records for a container whose angle this probe cannot read.
     *
     * @param rotationDeg display-matrix angle from the file, or a negative value when unknown
     * @param width coded width in pixels, or {@code <= 0} when unknown
     * @param height coded height in pixels, or {@code <= 0} when unknown
     * @param panel the panel's measured shape, or {@code null} when it cannot be measured yet
     * @return {@code false} when the clip must be handed to the platform MediaPlayer instead
     */
    public static boolean allowsTsPlayer(int rotationDeg, int width, int height,
                                         @Nullable MediaCompatibility.PanelShape panel) {
        if (rotationDeg > 0) {
            return false;
        }
        return !MediaCompatibility.videoAspectDiffersFromPanel(width, height, rotationDeg, panel);
    }

    /**
     * Whether the display ladder's "try the other engine" rung has an engine left to offer.
     *
     * <p>Three conditions, and the middle one is the bug this expresses. The rung used to ask only
     * whether the <em>frame</em> owns two engines, but the ladder re-derives the engine from the
     * file's own header on every entry — so any clip routing had already handed to the platform player
     * was replayed, byte for byte, on the engine that had just failed it. That cost a full attempt
     * (watchdog arming, prepare, paint deadline) before the ladder moved on, and once every non-native
     * aspect is platform-player material it became the common case rather than the exception.
     *
     * <p>The third condition is why this is a count and not a flag: a create or surface failure in the
     * native player is usually deterministic and retrying it buys nothing, but "usually" is not
     * "always", and an unattended frame should not lose a clip that would have played on a second try.
     *
     * @param frameHasTwoEngines       {@code preferTsPlayer} — an EO1 has one engine and never enters
     *                                 this rung at all
     * @param routingAllowedTsPlayer   the routing verdict for the bytes in hand, recorded where
     *                                 routing decided it rather than re-derived here
     * @param tsAttemptsSoFar          attempts already started on TsPlayer for this asset
     * @param maxAttemptsPerEngine     the budget
     */
    public static boolean offersSecondEngineAttempt(boolean frameHasTwoEngines,
                                                    boolean routingAllowedTsPlayer,
                                                    int tsAttemptsSoFar,
                                                    int maxAttemptsPerEngine) {
        return frameHasTwoEngines
                && routingAllowedTsPlayer
                && tsAttemptsSoFar < maxAttemptsPerEngine;
    }

    /**
     * The panel's shape as the word {@code /state} has always reported it.
     *
     * <p>Derived from the measured shape rather than read from {@code Configuration.orientation}
     * alongside it, so there is one account of how the frame is hung and the two cannot disagree. It
     * says {@code "undefined"} for a panel that has not been measured, which is the truth and which
     * the old platform read could not tell — it happily reported an orientation for a window whose
     * size the layout had not settled.
     */
    public static String orientationWord(@Nullable MediaCompatibility.PanelShape panel) {
        if (panel == null) {
            return "undefined";
        }
        if (panel.height > panel.width) {
            return "portrait";
        }
        return panel.width > panel.height ? "landscape" : "square";
    }
}
