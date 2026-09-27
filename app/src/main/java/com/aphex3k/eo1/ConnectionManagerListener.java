package com.aphex3k.eo1;

public interface ConnectionManagerListener {
    /**
     * This function gets called when the connectivity status changed to connected.
     */
    void connected();

    /**
     * This function gets called when the connectivity status changed to disconnected.
     */
    void disconnected();
}
