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
     * The frame-spacing pair in the single word it was published in: latest interval in the high half,
     * retained maximum in the low. Decode with {@link FrameGapWindow#latestOf(long)} and
     * {@link FrameGapWindow#maxOf(long)}; an engine with no per-frame signal returns
     * {@link FrameGapWindow#emptySnapshot()}.
     *
     * <p>There used to be a {@code getLastFrameGapMs()} and a {@code getMaxFrameGapMs()} alongside this,
     * and they are gone on purpose: two loads of an atomic value are still two samples, and a surface reset
     * — which {@code onSurfaceTextureDestroyed()} performs without touching the panel record or which
     * controller is compositing — can land between them and hand a report a worst gap beside a cleared
     * record's {@code -1}, past every staleness check there is. A caller cannot get that wrong through an
     * interface that only offers one sample, which is why the pair is not exposed as two numbers.
     *
     * <p>Where {@link #getLastFrameAtMs()} says when the last buffer arrived, this says whether buffers kept
     * arriving at an even rate. Read it narrowly: a wide interval is positive evidence that nothing arrived,
     * a small one is <em>not</em> evidence the picture changed, and neither says what the panel showed.
     * {@link FrameGapWindow} carries the account.
     */
    long getGapSnapshot();

    void seekTo(int positionMs);

    /**
     * Tear down native resources when switching away from this controller.
     */
    void release();
}
