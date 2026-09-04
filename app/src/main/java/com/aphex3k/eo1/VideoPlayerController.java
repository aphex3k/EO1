package com.aphex3k.eo1;

import android.view.View;

/**
 * Unified video playback surface used by {@link MainActivity} so TsPlayer and
 * platform MediaPlayer can be swapped with a shared lifecycle.
 */
public interface VideoPlayerController {

    View getView();

    void setListener(VideoPlayerListener listener);

    void setDataSource(String path);

    void play();

    void stop();

    void setLooping(boolean looping);

    void setVolume(int leftVolume, int rightVolume);

    void setFocusable(boolean focusable);

    void setVisibility(int visibility);

    int getVisibility();

    int getCurrentPosition();

    int getDuration();

    void seekTo(int positionMs);

    /**
     * Tear down native resources when switching away from this controller.
     */
    void release();
}
