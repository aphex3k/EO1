package com.aphex3k.eo1;

import java.io.File;

public interface ListenerInterface {
    void handleException(Exception e);
    void debugInformationProvided(DebugInformation debugInformation);
    void displayPicture(File finalTempFile, String assetInfo);
    void displayVideo(File finalTempFile);
}
