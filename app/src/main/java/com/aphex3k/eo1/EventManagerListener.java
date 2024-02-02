package com.aphex3k.eo1;

public interface EventManagerListener extends ListenerInterface {
    void checkForUpdates();
    void toggleScreenOn();
    void showConfigurationUI();
    void adjustBrightness();
    void showNextImage();
}
