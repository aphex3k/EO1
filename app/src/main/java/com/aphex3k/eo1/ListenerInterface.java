package com.aphex3k.eo1;

import java.io.File;

public interface ListenerInterface {
    void handleException(Exception e);
    void debugInformationProvided(DebugInformation debugInformation);
    void displayPicture(File finalTempFile, String assetId);
    void displayVideo(File finalTempFile, String assetId);

    /**
     * Non-fatal: something the user should know about that did not stop the pipeline.
     * Defaulted so a warning path cannot break existing listeners.
     */
    default void warnUser(String message) {
    }
}
