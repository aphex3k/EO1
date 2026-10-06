package com.dd.crop;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

import com.aphex3k.eo1.BuildConfig;

import java.io.IOException;

/*
 *    The MIT License (MIT)
 *
 *   Copyright (c) 2014 Danylyk Dmytro
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 */

public class TextureVideoView extends TextureView implements TextureView.SurfaceTextureListener {

    // Indicate if logging is on
    public static final boolean LOG_ON = true;

    // Log tag
    private static final String TAG = TextureVideoView.class.getName();

    private MediaPlayer mMediaPlayer;
    private Surface mVideoSurface;

    private float mVideoHeight;
    private float mVideoWidth;
    private int mVideoRotation;
    // Display rotation supplied by the app (VideoHeaderProbe); -1 when unknown.
    private int mDisplayRotationHint = -1;

    private boolean mIsDataSourceSet;
    private boolean mIsViewAvailable;
    private boolean mIsVideoPrepared;
    private boolean mIsPlayCalled;

    private State mState;

    public enum State {
        UNINITIALIZED, PLAY, STOP, PAUSE, END
    }

    public TextureVideoView(Context context) {
        super(context);
        initView();
    }

    public TextureVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    public TextureVideoView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initView();
    }

    private void initView() {
        initPlayer();
        setSurfaceTextureListener(this);
    }

    private void updateTextureViewSize() {
        if (mVideoWidth <= 0 || mVideoHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float pivotX = viewWidth / 2f;
        float pivotY = viewHeight / 2f;
        // TextureView maps the surface buffer stretched into the view bounds, so a uniform
        // scale alone renders distorted/over-zoomed — compensate that anisotropic stretch
        // with per-axis factors, then rotate.
        float[] factors = cropScaleFactors(viewWidth, viewHeight, mVideoWidth, mVideoHeight, mVideoRotation);

        Matrix transformMatrix = new Matrix();
        transformMatrix.setScale(factors[0], factors[1], pivotX, pivotY);
        transformMatrix.postRotate(mVideoRotation, pivotX, pivotY);
        setTransform(transformMatrix);

        if (BuildConfig.DEBUG) {
            log(String.format(
                    "view=%dx%d video=%dx%d rotation=%d scale=(%.3f,%.3f)",
                    getWidth(),
                    getHeight(),
                    (int) mVideoWidth,
                    (int) mVideoHeight,
                    mVideoRotation,
                    factors[0],
                    factors[1]));
        }
    }

    /**
     * Calculate the aspect-ratio correct scale matrix to display the video with center-crop
     * @param viewWidth The width of the target view
     * @param viewHeight The height of the target view
     * @param videoWidth The width of the video to play
     * @param videoHeight The height of the video to play
     * @return The matrix that displays the video content scaled and translated to the correct dimensions
     */
    public static float aspectScale(float viewWidth, float viewHeight, float videoWidth, float videoHeight) {
        return rotationAwareAspectScale(viewWidth, viewHeight, videoWidth, videoHeight, 0);
    }

    /**
     * Center-crop scale for a video, swapping coded dimensions when rotation is 90 or 270 degrees.
     */
    public static float rotationAwareAspectScale(float viewWidth, float viewHeight, float videoWidth, float videoHeight, int rotation) {
        float contentWidth = videoWidth;
        float contentHeight = videoHeight;
        if (rotation == 90 || rotation == 270) {
            contentWidth = videoHeight;
            contentHeight = videoWidth;
        }

        float scaleX = viewWidth / contentWidth;
        float scaleY = viewHeight / contentHeight;

        return Math.max(scaleX, scaleY);
    }

    /**
     * Per-axis transform factors that display the video with true center-crop (aspect-fill)
     * in the view. TextureView stretches the surface buffer into the view bounds, so each
     * axis must be compensated: factor = fillScale * videoDim / viewDim. For rotation 0 this
     * reduces to the original dd-crop postScale formulas.
     */
    public static float[] cropScaleFactors(float viewWidth, float viewHeight,
                                           float videoWidth, float videoHeight, int rotation) {
        float fillScale = rotationAwareAspectScale(viewWidth, viewHeight, videoWidth, videoHeight, rotation);
        float sx = fillScale * videoWidth / viewWidth;
        float sy = fillScale * videoHeight / viewHeight;
        return new float[]{sx, sy};
    }

    private int readVideoRotation(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) {
                return Integer.parseInt(rotation);
            }
        } catch (Exception e) {
            if (e.getMessage() != null) {
                Log.d(TAG, e.getMessage());
            }
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                if (e.getMessage() != null) {
                    Log.d(TAG, e.getMessage());
                }
            }
        }
        return 0;
    }

    private void initPlayer() {
        if (mMediaPlayer == null) {
            mMediaPlayer = new MediaPlayer();
        } else {
            mMediaPlayer.reset();
        }
        mIsVideoPrepared = false;
        mIsPlayCalled = false;
        mVideoRotation = 0;
        mVideoWidth = 0;
        mVideoHeight = 0;
        mState = State.UNINITIALIZED;
    }

    /**
     * @see android.media.MediaPlayer#setDataSource(String)
     */
    /**
     * Supply the display rotation (clockwise degrees, 0/90/180/270, or -1 when unknown) before
     * {@link #setDataSource(String)}. Taken from the app's own ISOBMFF header probe — the
     * platform MediaMetadataRetriever is unreliable on some devices — and preferred over it.
     */
    public void setDisplayRotation(int rotationDeg) {
        mDisplayRotationHint = rotationDeg;
    }

    public void setDataSource(String path) {
        initPlayer();

        try {
            Matrix transformMatrix = new Matrix();
            transformMatrix.setScale(1, 1, 0, 0);
            setTransform(transformMatrix);

            mVideoRotation = mDisplayRotationHint >= 0 ? mDisplayRotationHint : readVideoRotation(path);
            mMediaPlayer.setDataSource(path);
            mIsDataSourceSet = true;
            prepare();
        } catch (IOException e) {
            if (e.getMessage() != null)
                Log.d(TAG, e.getMessage());
            notifyListenerError();
        }
    }

    public void setVolume (int leftVolume, int rightVolume) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVolume(leftVolume, rightVolume);
        }
    }

    /**
     * @see android.media.MediaPlayer#setDataSource(android.content.Context, android.net.Uri)
     */
    public void setDataSource(Context context, Uri uri) {
        initPlayer();

        try {
            mMediaPlayer.setDataSource(context, uri);
            mIsDataSourceSet = true;
            prepare();
        } catch (IOException e) {
            if (e.getMessage() != null)
                Log.d(TAG, e.getMessage());
            notifyListenerError();
        }
    }

    /**
     * @see android.media.MediaPlayer#setDataSource(java.io.FileDescriptor)
     */
    public void setDataSource(AssetFileDescriptor afd) {
        initPlayer();

        try {
            long startOffset = afd.getStartOffset();
            long length = afd.getLength();
            mMediaPlayer.setDataSource(afd.getFileDescriptor(), startOffset, length);
            mIsDataSourceSet = true;
            prepare();
        } catch (IOException e) {
            if (e.getMessage() != null)
                Log.d(TAG, e.getMessage());
            notifyListenerError();
        }
    }

    private void prepare() {
        try {
            mMediaPlayer.setOnVideoSizeChangedListener(
                    new MediaPlayer.OnVideoSizeChangedListener() {
                        @Override
                        public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                            mVideoWidth = width;
                            mVideoHeight = height;
                            if (mIsViewAvailable) {
                                bindVideoSurface(getSurfaceTexture());
                            }
                            updateTextureViewSize();
                        }
                    }
            );
            mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    mState = State.END;
                    log("Video has ended.");

                    if (mListener != null) {
                        mListener.onVideoEnd();
                    }
                }
            });

            // Play video when the media source is ready for playback.
            mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mediaPlayer) {
                    mIsVideoPrepared = true;
                    if (mIsPlayCalled && mIsViewAvailable) {
                        log("Player is prepared and play() was called.");
                        play();
                    }

                    if (mListener != null) {
                        mListener.onVideoPrepared();
                    }
                }
            });

            mMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                    if (mListener != null) {
                        return mListener.onError(what, extra);
                    }
                    return false;
                }
            });
            mMediaPlayer.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mediaPlayer, int i, int i1) {
                    if (mListener != null) {
                        return mListener.onInfo(i, i1);
                    }
                    return false;
                }
            });

            bindVideoSurface(getSurfaceTexture());

            // don't forget to call MediaPlayer.prepareAsync() method when you use constructor for
            // creating MediaPlayer
            mMediaPlayer.prepareAsync();

        } catch (Exception e) {
            if (e.getMessage() != null)
                Log.d(TAG, e.getMessage());
            notifyListenerError();
        }
    }

    private void notifyListenerError() {
        if (mListener != null) {
            mListener.onError(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }

    /**
     * Bind MediaPlayer to the TextureView surface. When video size is known, size the
     * SurfaceTexture buffer to native video pixels so absolute center-crop scale is correct.
     */
    private void bindVideoSurface(SurfaceTexture surfaceTexture) {
        if (!mIsViewAvailable || mMediaPlayer == null || surfaceTexture == null) {
            return;
        }

        if (mVideoWidth > 0 && mVideoHeight > 0) {
            surfaceTexture.setDefaultBufferSize((int) mVideoWidth, (int) mVideoHeight);
        }

        if (mVideoSurface != null) {
            mMediaPlayer.setSurface(null);
            mVideoSurface.release();
            mVideoSurface = null;
        }

        mVideoSurface = new Surface(surfaceTexture);
        mMediaPlayer.setSurface(mVideoSurface);
    }

    /**
     * Play or resume video. Video will be played as soon as view is available and media player is
     * prepared.
     *
     * If video is stopped or ended and play() method was called, video will start over.
     */
    public void play() {
        if (!mIsDataSourceSet) {
            log("play() was called but data source was not set.");
            return;
        }

        mIsPlayCalled = true;

        if (!mIsVideoPrepared) {
            log("play() was called but video is not prepared yet, waiting.");
            return;
        }

        if (!mIsViewAvailable) {
            log("play() was called but view is not available yet, waiting.");
            return;
        }

        if (mState == State.PLAY) {
            log("play() was called but video is already playing.");
            return;
        }

        if (mState == State.PAUSE) {
            log("play() was called but video is paused, resuming.");
            mState = State.PLAY;
            mMediaPlayer.start();
            return;
        }

        if (mState == State.END || mState == State.STOP) {
            log("play() was called but video already ended, starting over.");
            mState = State.PLAY;
            mMediaPlayer.seekTo(0);
            mMediaPlayer.start();
            return;
        }

        mState = State.PLAY;
        mMediaPlayer.start();
    }

    /**
     * Pause video. If video is already paused, stopped or ended nothing will happen.
     */
    public void pause() {
        if (mMediaPlayer == null) {
            return;
        }
        if (mState == State.PAUSE) {
            log("pause() was called but video already paused.");
            return;
        }

        if (mState == State.STOP) {
            log("pause() was called but video already stopped.");
            return;
        }

        if (mState == State.END) {
            log("pause() was called but video already ended.");
            return;
        }

        mState = State.PAUSE;
        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
        }
    }

    /**
     * Stop video (pause and seek to beginning). If video is already stopped or ended nothing will
     * happen.
     */
    public void stop() {
        if (mMediaPlayer == null) {
            return;
        }
        if (mState == State.STOP) {
            log("stop() was called but video already stopped.");
            return;
        }

        if (mState == State.END) {
            log("stop() was called but video already ended.");
            return;
        }

        mState = State.STOP;
        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
            mMediaPlayer.seekTo(0);
        }
    }

    /**
     * Release the native MediaPlayer. A merely stopped player stays prepared and keeps its
     * decoder — and with it the exclusive Amlogic video pipeline — which starves a TsPlayer
     * started afterwards. The instance is unusable after release(), so it is discarded and the
     * next {@link #setDataSource(String)} creates a fresh one.
     */
    public void release() {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.release();
            } catch (Exception e) {
                if (e.getMessage() != null) {
                    Log.d(TAG, e.getMessage());
                }
            }
            mMediaPlayer = null;
        }
        if (mVideoSurface != null) {
            mVideoSurface.release();
            mVideoSurface = null;
        }
        mIsDataSourceSet = false;
        mIsVideoPrepared = false;
        mIsPlayCalled = false;
        mVideoWidth = 0;
        mVideoHeight = 0;
        mVideoRotation = 0;
        mDisplayRotationHint = -1;
        mState = State.UNINITIALIZED;
    }

    /**
     * @see android.media.MediaPlayer#setLooping(boolean)
     */
    public void setLooping(boolean looping) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setLooping(looping);
        }
    }

    /**
     * @see android.media.MediaPlayer#seekTo(int)
     */
    public void seekTo(int milliseconds) {
        if (mMediaPlayer != null) {
            mMediaPlayer.seekTo(milliseconds);
        }
    }

    /**
     * @see android.media.MediaPlayer#getDuration()
     */
    public int getDuration() {
        if (mMediaPlayer == null) {
            return 0;
        }
        return mMediaPlayer.getDuration();
    }

    /**
     * @see android.media.MediaPlayer#getCurrentPosition()
     */
    public int getCurrentPosition() {
        if (mMediaPlayer == null) {
            return 0;
        }
        return mMediaPlayer.getCurrentPosition();
    }

    static void log(String message) {
        if (LOG_ON) {
            Log.d(TAG, message);
        }
    }

    private MediaPlayerListener mListener;

    /**
     * Listener trigger 'onVideoPrepared' and `onVideoEnd` events
     */
    public void setListener(MediaPlayerListener listener) {
        mListener = listener;
    }

    public interface MediaPlayerListener {

        public void onVideoPrepared();

        public void onVideoEnd();

        public boolean onError(int what, int extra);

        public boolean onInfo(int what, int extra);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        mIsViewAvailable = true;
        bindVideoSurface(surfaceTexture);
        updateTextureViewSize();
        if (mIsDataSourceSet && mIsPlayCalled && mIsVideoPrepared) {
            log("View is available and play() was called.");
            play();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        updateTextureViewSize();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setSurface(null);
        }
        if (mVideoSurface != null) {
            mVideoSurface.release();
            mVideoSurface = null;
        }
        mIsViewAvailable = false;
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

    }
}