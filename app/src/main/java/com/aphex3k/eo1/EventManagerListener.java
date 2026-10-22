package com.aphex3k.eo1;

public interface EventManagerListener extends ListenerInterface {
    void checkForUpdates();
    void toggleScreenOn();
    void showConfigurationUI();
    /**
     * @param via what asked for the step — {@code "button"} from a key on the frame, {@code "web"}
     *            from the LAN page. Recorded with the change so a frame that went dim can say who
     *            made it dim; it grants nothing.
     */
    void adjustMinimumBrightness(String via);
    void showNextImage();
    void openSystemSettings();
    void openUpdateWebsite();
}
