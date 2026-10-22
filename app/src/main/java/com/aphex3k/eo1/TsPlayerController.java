package com.aphex3k.eo1;

import android.view.View;

/**
 * {@link VideoPlayerController} backed by Amlogic {@link TsVideoView}.
 */
public class TsPlayerController implements VideoPlayerController {

    private final TsVideoView videoView;

    public TsPlayerController(TsVideoView videoView) {
        this.videoView = videoView;
    }

    @Override
    public View getView() {
        return videoView;
    }

    @Override
    public void setListener(VideoPlayerListener listener) {
        videoView.setListener(listener);
    }

    @Override
    public void setDataSource(String path) {
        videoView.setDataSource(path);
    }

    @Override
    public void setDurationHint(int durationMs) {
        videoView.setDurationHint(durationMs);
    }

    @Override
    public void setDisplayRotation(int rotationDeg) {
        // No-op: routing only sends identity-rotation videos to TsPlayer, and the .so has no
        // rotation control anyway.
    }

    @Override
    public void play() {
        videoView.play();
    }

    @Override
    public void stop() {
        videoView.stop();
    }

    @Override
    public void setLooping(boolean looping) {
        videoView.setLooping(looping);
    }

    @Override
    public void setVolume(int leftVolume, int rightVolume) {
        // TsPlayer has no volume API; EO frames have no speakers — always silent.
    }

    @Override
    public void setFocusable(boolean focusable) {
        videoView.setFocusable(focusable);
    }

    @Override
    public void setVisibility(int visibility) {
        videoView.setVisibility(visibility);
    }

    @Override
    public int getVisibility() {
        return videoView.getVisibility();
    }

    @Override
    public int getCurrentPosition() {
        return videoView.getCurrentPosition();
    }

    @Override
    public int getDuration() {
        return videoView.getDuration();
    }

    /**
     * TsPlayer renders through a SurfaceView the .so owns: there is no per-frame callback to hook, and
     * its reported position falls back to wall-clock elapsed time when the native clock is unusable
     * (TsVideoView), so anything measured against it would be measuring a timer rather than a decoder.
     */
    @Override
    public boolean supportsPaintSignal() {
        return false;
    }

    @Override
    public long getLastFrameAtMs() {
        return 0L;
    }

    @Override
    public void seekTo(int positionMs) {
        videoView.seekTo(positionMs);
    }

    @Override
    public void release() {
        videoView.setListener(null);
        // stop() already performs the full native teardown (deletePlayer) and leaves the view
        // reusable; TsVideoView.release() would permanently retire it.
        videoView.stop();
    }
}
