package com.aphex3k.eo1.mqtt;

public interface PlaybackListener {
    void onPlaybackStarted(Payload payload);
    void onPlaybackStopped(Payload payload);
}
