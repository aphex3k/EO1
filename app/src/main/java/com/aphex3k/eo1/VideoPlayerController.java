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

    /**
     * Set the known duration (ms) before {@link #setDataSource(String)}. The TsPlayer path uses
     * this instead of a system {@code MediaMetadataRetriever} (which would grab the Amlogic
     * video-buffer node and starve the in-process codec). Implementations that derive their own
     * duration (platform MediaPlayer) may ignore it.
     */
    void setDurationHint(int durationMs);

    /**
     * Set the display rotation in clockwise degrees (0/90/180/270, or -1 when unknown) before
     * {@link #setDataSource(String)}. Implementations that cannot apply a rotation may no-op.
     */
    void setDisplayRotation(int rotationDeg);

    void play();

    void stop();

    void setLooping(boolean looping);

    void setVolume(int leftVolume, int rightVolume);

    void setFocusable(boolean focusable);

    void setVisibility(int visibility);

    int getVisibility();

    int getCurrentPosition();

    int getDuration();

    /**
     * Whether this engine can prove that a frame reached the screen. Where it cannot, the caller must
     * not wait for {@link #getLastFrameAtMs()} to move — there is nothing behind it.
     */
    boolean supportsPaintSignal();

    /**
     * Wall-clock milliseconds of the last composited video frame, or 0 when none has arrived since the
     * current data source was set. Meaningless unless {@link #supportsPaintSignal()} is true.
     *
     * <p>Polled rather than delivered as a callback on purpose: {@link #release()} detaches the listener,
     * so a callback-only signal would go quiet during exactly the handoffs that need it.
     */
    long getLastFrameAtMs();

    void seekTo(int positionMs);

    /**
     * Tear down native resources when switching away from this controller.
     */
    void release();
}
