package com.aphex3k.eo1;

import android.view.View;

import com.dd.crop.TextureVideoView;

/**
 * {@link VideoPlayerController} backed by platform {@link android.media.MediaPlayer}
 * via {@link TextureVideoView}.
 */
public class MediaPlayerController implements VideoPlayerController {

    private final TextureVideoView videoView;

    public MediaPlayerController(TextureVideoView videoView) {
        this.videoView = videoView;
    }

    @Override
    public View getView() {
        return videoView;
    }

    @Override
    public void setListener(VideoPlayerListener listener) {
        if (listener == null) {
            videoView.setListener(null);
            return;
        }
        videoView.setListener(new TextureVideoView.MediaPlayerListener() {
            @Override
            public void onVideoPrepared() {
                listener.onVideoPrepared();
            }

            @Override
            public void onVideoEnd() {
                listener.onVideoEnd();
            }

            @Override
            public boolean onError(int what, int extra) {
                return listener.onError(what, extra);
            }

            @Override
            public boolean onInfo(int what, int extra) {
                return listener.onInfo(what, extra);
            }
        });
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
        videoView.setVolume(leftVolume, rightVolume);
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
        videoView.stop();
    }
}
