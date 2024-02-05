package com.aphex3k.eo1;

import java.io.File;

class MockBrightnessListener implements BrightnessManagerListener {

    public float currentBrightness = 0.5f;
    @Override
    public void brightnessChanged(float toBrightness) {
        currentBrightness = toBrightness;
    }

    @Override
    public void handleException(Exception e) {

    }

    @Override
    public void debugInformationProvided(DebugInformation debugInformation) {

    }

    @Override
    public void displayPicture(File finalTempFile, String assetInfo) {

    }

    @Override
    public void displayVideo(File finalTempFile) {

    }
}
