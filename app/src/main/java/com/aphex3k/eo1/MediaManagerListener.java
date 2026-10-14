package com.aphex3k.eo1;

public interface MediaManagerListener extends ListenerInterface {

    /**
     * Displays a web page fullscreen in the kiosk browser view. Unlike
     * {@link ListenerInterface#displayPicture}, the asset is an address rather than a file: it is
     * never downloaded, and whether it actually rendered is only known asynchronously, which is
     * why a failed load goes back through {@link MediaManagerInterface#webDisplayFailed} instead of
     * the file-based {@code assetFallback} path.
     *
     * @param url     absolute http(s) page address
     * @param assetId pipeline asset key ("backendId:rawId")
     */
    void displayWebsite(String url, String assetId);
}
