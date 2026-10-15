package com.aphex3k.eo1;

import java.io.File;

public interface ListenerInterface {
    void handleException(Exception e);
    void debugInformationProvided(DebugInformation debugInformation);
    void displayPicture(File finalTempFile, String assetId);
    void displayVideo(File finalTempFile, String assetId);

    /**
     * Shows the degraded source (preview thumbnail / transcoded stream) of an asset whose
     * original already failed once. The last rung of the recovery ladder: a listener that
     * distinguishes display contexts must not route a failure here back into another fallback
     * attempt, it must report the asset as undisplayable.
     *
     * @param assetId pipeline asset key ("backendId:rawId") of the asset being recovered
     */
    default void displayFallbackPicture(File finalTempFile, String assetId) {
        displayPicture(finalTempFile, null);
    }

    /** Video half of {@link #displayFallbackPicture}. */
    default void displayFallbackVideo(File finalTempFile, String assetId) {
        displayVideo(finalTempFile, null);
    }

    /**
     * Non-fatal: something the user should know about that did not stop the pipeline.
     * Defaulted so a warning path cannot break existing listeners.
     */
    default void warnUser(String message) {
    }
}
