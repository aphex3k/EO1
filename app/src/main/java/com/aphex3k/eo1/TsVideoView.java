package com.aphex3k.eo1;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;

import com.example.tsplayer.TsPlayerNative;

/**
 * SurfaceView-backed Amlogic TsPlayer view.
 * <p>
 * Same-file looping never calls {@code deletePlayer} (reserved for asset handoff).
 * {@code stop}/{@code EOS} clear the Amlogic video plane, so loops show a still
 * {@link #setLoopCoverView(ImageView) cover} (sibling ImageView above this surface)
 * across the gap. Prefer {@code start()} without {@code stop} when it works.
 * See {@code docs/TSPLAYER.md}.
 */
public class TsVideoView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = "TsVideoView";
    private static final long PROGRESS_POLL_MS = 1000L;
    private static final long SURFACE_TIMEOUT_MS = 3000L;
    private static final long LOOP_END_MARGIN_MS = 0L;
    /** Restart before metadata EOS so the cover is up before the plane blanks. */
    private static final long LOOP_EARLY_RESTART_MS = 250L;
    private static final long SURFACE_FALLBACK_MS = 500L;
    /** Keep still cover up briefly after restart until new buffers paint. */
    private static final long LOOP_COVER_HIDE_DELAY_MS = 180L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String pendingPath;
    private String activePath;
    private boolean surfaceReady;
    private boolean looping = true;
    private boolean playerCreated;
    private boolean playWhenReady;
    private boolean released;

    private VideoPlayerListener listener;
    private Runnable progressPollRunnable;
    private Runnable surfaceTimeoutRunnable;
    private Runnable durationLoopRunnable;
    private Runnable surfaceFallbackRunnable;
    private Runnable restartRunnable;
    private Runnable hideLoopCoverRunnable;

    private int durationMs;
    private boolean recreatingForLoop;
    private boolean awaitingSurfaceForStart;
    private int lastLoggedStatus = Integer.MIN_VALUE;
    private int loopGeneration;

    /** Sibling ImageView above this SurfaceView (layout z-order); covers black gaps. */
    private ImageView loopCoverView;
    private Bitmap holdBitmap;
    private boolean loopCoverVisible;
    private int holdBitmapPathHash;

    public TsVideoView(Context context) {
        super(context);
        init();
    }

    public TsVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TsVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        getHolder().addCallback(this);
        getHolder().setFormat(PixelFormat.OPAQUE);
        setZOrderMediaOverlay(false);
        setZOrderOnTop(false);
    }

    public void setListener(VideoPlayerListener listener) {
        this.listener = listener;
    }

    /**
     * ImageView drawn above this SurfaceView (see activity_main). Used to hold a still
     * frame across loop restarts so Amlogic plane clears are not visible as black.
     */
    public void setLoopCoverView(ImageView coverView) {
        this.loopCoverView = coverView;
    }

    public void setDataSource(String path) {
        if (released) {
            return;
        }
        hideLoopCover();
        clearHoldBitmap();
        pendingPath = path;
        playWhenReady = true;
        awaitingSurfaceForStart = false;
        recreatingForLoop = false;
        loopGeneration = 0;
        if (getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
        }
        if (surfaceReady) {
            startOrRestartPlayer();
        } else {
            scheduleSurfaceTimeout();
            Log.i(TAG, "setDataSource waiting for surface path=" + path);
        }
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
        if (!looping) {
            cancelProgressPoll();
            cancelDurationLoop();
        }
    }

    public void play() {
        if (released || !TsPlayerNative.isAvailable()) {
            return;
        }
        playWhenReady = true;
        if (getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
        }
        if (!surfaceReady) {
            scheduleSurfaceTimeout();
            return;
        }
        if (!playerCreated) {
            startOrRestartPlayer();
        }
    }

    public void stop() {
        playWhenReady = false;
        awaitingSurfaceForStart = false;
        recreatingForLoop = false;
        cancelProgressPoll();
        cancelDurationLoop();
        cancelSurfaceTimeout();
        cancelSurfaceFallback();
        cancelRestart();
        cancelHideLoopCover();
        hideLoopCover();
        clearHoldBitmap();
        tearDownPlayer();
        pendingPath = null;
        activePath = null;
        durationMs = 0;
    }

    public int getCurrentPosition() {
        return readPositionMs();
    }

    public int getDuration() {
        return Math.max(0, durationMs);
    }

    public void seekTo(int positionMs) {
        if (activePath != null || pendingPath != null) {
            playWhenReady = true;
            restartForLoop();
        }
    }

    public void release() {
        released = true;
        stop();
        getHolder().removeCallback(this);
    }

    public boolean isRecreatingForLoop() {
        return recreatingForLoop || awaitingSurfaceForStart;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        cancelSurfaceTimeout();
        Log.i("EO1", "TsPlayer surfaceCreated awaiting=" + awaitingSurfaceForStart
                + " playerCreated=" + playerCreated);
        if (awaitingSurfaceForStart) {
            awaitingSurfaceForStart = false;
            cancelSurfaceFallback();
            startOrRestartPlayer();
        } else if (playWhenReady && pendingPath != null && !playerCreated && !recreatingForLoop) {
            startOrRestartPlayer();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceReady = true;
        if (awaitingSurfaceForStart && !playerCreated) {
            Log.i("EO1", "TsPlayer surfaceChanged — starting");
            awaitingSurfaceForStart = false;
            cancelSurfaceFallback();
            startOrRestartPlayer();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        Log.i("EO1", "TsPlayer surfaceDestroyed awaiting=" + awaitingSurfaceForStart);
        // During intentional loop recreate we already tore down the player.
        if (!awaitingSurfaceForStart && !recreatingForLoop) {
            cancelProgressPoll();
            cancelDurationLoop();
            tearDownPlayer();
        }
    }

    private void startOrRestartPlayer() {
        if (released || !TsPlayerNative.isAvailable()) {
            if (!TsPlayerNative.isAvailable()) {
                notifyError();
            }
            return;
        }
        String path = pendingPath != null ? pendingPath : activePath;
        if (path == null || path.isEmpty()) {
            return;
        }

        Surface surface = getHolder().getSurface();
        if (!surfaceReady || surface == null || !surface.isValid()) {
            Log.w("EO1", "TsPlayer start deferred — surface not ready");
            awaitingSurfaceForStart = true;
            scheduleSurfaceTimeout();
            return;
        }

        cancelSurfaceTimeout();
        cancelProgressPoll();
        cancelDurationLoop();
        // Ensure no previous native instance (idempotent if already torn down).
        tearDownPlayer();

        boolean sameFile = path.equals(activePath) && durationMs > 0;

        try {
            if (!sameFile) {
                durationMs = readDurationMs(path);
            }
            Log.i("EO1", "TsPlayer createPlayer path=" + path + " durationMs=" + durationMs
                    + " loopGen=" + loopGeneration);
            if (!TsPlayerNative.createPlayer(path)) {
                Log.e("EO1", "TsPlayer createPlayer failed");
                notifyError();
                return;
            }
            playerCreated = true;
            activePath = path;
            TsPlayerNative.setSurface(surface);
            if (playWhenReady) {
                if (!TsPlayerNative.start()) {
                    Log.e("EO1", "TsPlayer start failed");
                    tearDownPlayer();
                    notifyError();
                    return;
                }
                recreatingForLoop = false;
                awaitingSurfaceForStart = false;
                if (listener != null) {
                    listener.onVideoPrepared();
                }
                scheduleHoldBitmapAsync(path);
                scheduleProgressPoll();
                scheduleDurationLoop();
            }
        } catch (Throwable t) {
            Log.e(TAG, "startOrRestartPlayer failed", t);
            tearDownPlayer();
            notifyError();
        }
    }

    private void tearDownPlayer() {
        if (!playerCreated || !TsPlayerNative.isAvailable()) {
            playerCreated = false;
            return;
        }
        // Match OEM EoVideoView.restartPlayer: pause → stop → stop → deletePlayer.
        // Double stop is intentional in the original APK (logged twice).
        try {
            TsPlayerNative.pause();
        } catch (Throwable ignored) {
        }
        try {
            TsPlayerNative.stop();
        } catch (Throwable ignored) {
        }
        try {
            TsPlayerNative.stop();
        } catch (Throwable ignored) {
        }
        try {
            TsPlayerNative.deletePlayer();
        } catch (Throwable ignored) {
        }
        playerCreated = false;
    }

    private void scheduleDurationLoop() {
        cancelDurationLoop();
        if (!looping || released || durationMs <= 0) {
            if (durationMs <= 0) {
                Log.w("EO1", "TsPlayer cannot schedule loop — durationMs=" + durationMs);
            }
            return;
        }
        final int generation = loopGeneration;
        // Restart slightly before metadata end so new buffers can replace the last frame
        // instead of blanking after EOS.
        long delay = durationMs <= 500
                ? durationMs
                : Math.max(0L, durationMs - LOOP_EARLY_RESTART_MS) + LOOP_END_MARGIN_MS;
        durationLoopRunnable = () -> {
            durationLoopRunnable = null;
            if (generation != loopGeneration || released) {
                return;
            }
            Log.i("EO1", "TsPlayer duration elapsed (" + durationMs + "ms) — looping");
            restartForLoop();
        };
        handler.postDelayed(durationLoopRunnable, delay);
        Log.i("EO1", "TsPlayer loop scheduled in " + delay + "ms gen=" + generation);
    }

    private void cancelDurationLoop() {
        if (durationLoopRunnable != null) {
            handler.removeCallbacks(durationLoopRunnable);
            durationLoopRunnable = null;
        }
    }

    private void scheduleProgressPoll() {
        cancelProgressPoll();
        if (!looping || released) {
            return;
        }
        progressPollRunnable = this::pollProgress;
        handler.postDelayed(progressPollRunnable, PROGRESS_POLL_MS);
    }

    private void cancelProgressPoll() {
        if (progressPollRunnable != null) {
            handler.removeCallbacks(progressPollRunnable);
            progressPollRunnable = null;
        }
    }

    private void pollProgress() {
        progressPollRunnable = null;
        if (released || !looping || !playWhenReady || !playerCreated) {
            return;
        }
        int status = -1;
        try {
            status = TsPlayerNative.getStatus();
        } catch (Throwable ignored) {
        }
        int positionMs = readPositionMs();
        if (status != lastLoggedStatus) {
            lastLoggedStatus = status;
            Log.i("EO1", "TsPlayer status=" + status + " posMs=" + positionMs
                    + " durationMs=" + durationMs);
        }
        scheduleProgressPoll();
    }

    private void restartForLoop() {
        if (released || !looping || !playWhenReady) {
            return;
        }
        if (pendingPath == null) {
            pendingPath = activePath;
        }
        if (pendingPath == null) {
            return;
        }

        cancelProgressPoll();
        cancelDurationLoop();
        cancelSurfaceFallback();
        cancelRestart();
        cancelHideLoopCover();

        loopGeneration++;

        // Cover before any native call — stop/EOS blank the Amlogic plane under the surface.
        showLoopCover();

        if (trySeamlessRestart()) {
            Log.i("EO1", "TsPlayer loop gen=" + loopGeneration);
            scheduleProgressPoll();
            scheduleDurationLoop();
            scheduleHideLoopCover();
            return;
        }

        hideLoopCover();
        Log.w("EO1", "TsPlayer loop failed gen=" + loopGeneration + " — error (no delete)");
        notifyError();
    }

    /**
     * Same-file loop without {@code deletePlayer}. Prefer {@code start()} alone (no stop);
     * stop clears the video plane even with a cover briefly visible.
     */
    private boolean trySeamlessRestart() {
        if (!playerCreated || !TsPlayerNative.isAvailable()) {
            return false;
        }
        String path = pendingPath != null ? pendingPath : activePath;
        Surface surface = getHolder().getSurface();
        if (path == null || surface == null || !surface.isValid()) {
            return false;
        }

        // 1) start without stop — may rewind without clearing the plane.
        try {
            if (TsPlayerNative.start()) {
                Log.i("EO1", "TsPlayer loop via start-only gen=" + loopGeneration);
                return true;
            }
        } catch (Throwable t) {
            Log.w("EO1", "TsPlayer start-only loop failed: " + t.getMessage());
        }

        // 2) re-open without delete/stop.
        try {
            if (TsPlayerNative.createPlayer(path)) {
                TsPlayerNative.setSurface(surface);
                if (TsPlayerNative.start()) {
                    playerCreated = true;
                    activePath = path;
                    Log.i("EO1", "TsPlayer loop via create(no-delete) gen=" + loopGeneration);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w("EO1", "TsPlayer create(no-delete) loop failed: " + t.getMessage());
        }

        // 3) stop/start last — plane blanks; cover ImageView must already be visible.
        try {
            TsPlayerNative.stop();
            if (TsPlayerNative.start()) {
                Log.i("EO1", "TsPlayer loop via stop/start gen=" + loopGeneration);
                return true;
            }
        } catch (Throwable t) {
            Log.w("EO1", "TsPlayer stop/start loop failed: " + t.getMessage());
        }
        return false;
    }

    private void showLoopCover() {
        if (loopCoverView == null) {
            Log.w("EO1", "TsPlayer loop cover missing — black gap may show");
            return;
        }
        if (holdBitmap == null || holdBitmap.isRecycled()) {
            Log.w("EO1", "TsPlayer loop cover bitmap not ready gen=" + loopGeneration);
            return;
        }
        loopCoverView.setImageBitmap(holdBitmap);
        loopCoverView.setVisibility(View.VISIBLE);
        loopCoverVisible = true;
        Log.i("EO1", "TsPlayer loop cover shown gen=" + loopGeneration);
    }

    private void scheduleHideLoopCover() {
        cancelHideLoopCover();
        hideLoopCoverRunnable = () -> {
            hideLoopCoverRunnable = null;
            hideLoopCover();
        };
        handler.postDelayed(hideLoopCoverRunnable, LOOP_COVER_HIDE_DELAY_MS);
    }

    private void cancelHideLoopCover() {
        if (hideLoopCoverRunnable != null) {
            handler.removeCallbacks(hideLoopCoverRunnable);
            hideLoopCoverRunnable = null;
        }
    }

    private void hideLoopCover() {
        if (!loopCoverVisible) {
            return;
        }
        if (loopCoverView != null) {
            loopCoverView.setVisibility(View.INVISIBLE);
        }
        loopCoverVisible = false;
        Log.i("EO1", "TsPlayer loop cover hidden");
    }

    private void scheduleHoldBitmapAsync(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        final int pathHash = path.hashCode();
        final int durationForFrame = durationMs;
        if (holdBitmap != null && !holdBitmap.isRecycled() && holdBitmapPathHash == pathHash) {
            return;
        }
        new Thread(() -> {
            Bitmap bmp = extractHoldFrame(path, durationForFrame);
            handler.post(() -> {
                if (released || (activePath == null && pendingPath == null)) {
                    if (bmp != null) {
                        bmp.recycle();
                    }
                    return;
                }
                String current = pendingPath != null ? pendingPath : activePath;
                if (current == null || current.hashCode() != pathHash) {
                    if (bmp != null) {
                        bmp.recycle();
                    }
                    return;
                }
                clearHoldBitmap();
                holdBitmap = bmp;
                holdBitmapPathHash = pathHash;
                if (bmp != null) {
                    Log.i("EO1", "TsPlayer loop cover frame ready "
                            + bmp.getWidth() + "x" + bmp.getHeight());
                }
            });
        }, "ts-hold-frame").start();
    }

    private void clearHoldBitmap() {
        holdBitmapPathHash = 0;
        if (holdBitmap != null) {
            if (!holdBitmap.isRecycled()) {
                holdBitmap.recycle();
            }
            holdBitmap = null;
        }
    }

    private static Bitmap extractHoldFrame(String path, int durationMs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            long timeUs = 0L;
            if (durationMs > 500) {
                timeUs = (durationMs - 200L) * 1000L;
            }
            Bitmap bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bmp == null) {
                bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "hold frame extract failed: " + e.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void cancelRestart() {
        if (restartRunnable != null) {
            handler.removeCallbacks(restartRunnable);
            restartRunnable = null;
        }
    }

    private void scheduleSurfaceFallback() {
        cancelSurfaceFallback();
        surfaceFallbackRunnable = () -> {
            surfaceFallbackRunnable = null;
            if (released || playerCreated || !awaitingSurfaceForStart) {
                return;
            }
            Surface surface = getHolder().getSurface();
            boolean valid = surface != null && surface.isValid();
            Log.i("EO1", "TsPlayer surface fallback valid=" + valid);
            if (valid) {
                surfaceReady = true;
                awaitingSurfaceForStart = false;
                startOrRestartPlayer();
            } else {
                Log.e("EO1", "TsPlayer surface fallback failed");
                notifyError();
            }
        };
        handler.postDelayed(surfaceFallbackRunnable, SURFACE_FALLBACK_MS);
    }

    private void cancelSurfaceFallback() {
        if (surfaceFallbackRunnable != null) {
            handler.removeCallbacks(surfaceFallbackRunnable);
            surfaceFallbackRunnable = null;
        }
    }

    private int readPositionMs() {
        if (!playerCreated || !TsPlayerNative.isAvailable()) {
            return 0;
        }
        try {
            int raw = Math.max(0, TsPlayerNative.getCurrentTime());
            if (raw <= 0) {
                return 0;
            }
            if (durationMs >= 10000 && raw <= (durationMs / 1000) + 2) {
                return raw * 1000;
            }
            return raw;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int readDurationMs(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value == null) {
                return 0;
            }
            return Math.max(0, Integer.parseInt(value));
        } catch (Exception e) {
            Log.w(TAG, "duration metadata failed: " + e.getMessage());
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void scheduleSurfaceTimeout() {
        cancelSurfaceTimeout();
        surfaceTimeoutRunnable = () -> {
            surfaceTimeoutRunnable = null;
            if (released || surfaceReady || !playWhenReady) {
                return;
            }
            Log.e(TAG, "surface timeout — no SurfaceHolder after " + SURFACE_TIMEOUT_MS + "ms");
            notifyError();
        };
        handler.postDelayed(surfaceTimeoutRunnable, SURFACE_TIMEOUT_MS);
    }

    private void cancelSurfaceTimeout() {
        if (surfaceTimeoutRunnable != null) {
            handler.removeCallbacks(surfaceTimeoutRunnable);
            surfaceTimeoutRunnable = null;
        }
    }

    private void notifyError() {
        cancelProgressPoll();
        cancelDurationLoop();
        cancelSurfaceTimeout();
        cancelSurfaceFallback();
        cancelRestart();
        cancelHideLoopCover();
        hideLoopCover();
        awaitingSurfaceForStart = false;
        recreatingForLoop = false;
        if (listener != null) {
            listener.onError(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }
}
