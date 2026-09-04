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

    @Override
    public void seekTo(int positionMs) {
        videoView.seekTo(positionMs);
    }

    @Override
    public void release() {
        videoView.setListener(null);
        videoView.release();
    }
}
