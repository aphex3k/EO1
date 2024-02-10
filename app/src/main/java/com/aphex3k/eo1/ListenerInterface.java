package com.aphex3k.eo1;

import java.io.File;

public interface ListenerInterface {
    void handleException(Exception e);
    void debugInformationProvided(DebugInformation debugInformation);
    void displayPicture(File finalTempFile, String assetId);
    void displayVideo(File finalTempFile, String assetId);
}
