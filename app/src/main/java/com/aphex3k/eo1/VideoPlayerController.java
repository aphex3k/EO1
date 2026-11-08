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

    /**
     * The longest interval between two successive composited frames since this controller was pointed
     * at its current source, or {@link FrameGapWindow#NO_GAP} when there is no such record — which
     * covers both "fewer than two frames have arrived" and an engine that reports none.
     *
     * <p>Where {@link #getLastFrameAtMs()} says when the last buffer arrived, this says whether buffers
     * kept arriving at an even rate. Every other signal in the process is a clock, which is why it
     * exists — but read it narrowly: a wide interval is positive evidence that nothing arrived, a small
     * interval is <em>not</em> evidence that the picture changed (a producer can re-queue a frame the
     * panel has already shown), and neither says what the panel was displaying.
     * {@link FrameGapWindow} carries the full account.
     */
    int getMaxFrameGapMs();

    /**
     * Spacing of the two most recently composited frames, or {@link FrameGapWindow#NO_GAP}.
     */
    int getLastFrameGapMs();

    void seekTo(int positionMs);

    /**
     * Tear down native resources when switching away from this controller.
     */
    void release();
}
