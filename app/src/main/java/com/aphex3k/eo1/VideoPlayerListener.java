package com.aphex3k.eo1;

/**
 * Callbacks shared by TsPlayer and MediaPlayer video controllers.
 */
public interface VideoPlayerListener {

    void onVideoPrepared();

    void onVideoEnd();

    boolean onError(int what, int extra);

    boolean onInfo(int what, int extra);
}
