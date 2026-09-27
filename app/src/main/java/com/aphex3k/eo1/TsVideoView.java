package com.aphex3k.eo1;

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import com.example.tsplayer.TsPlayerNative;

/**
 * SurfaceView-backed Amlogic TsPlayer view.
 * <p>
 * Looping: the .so ({@code libTsPlayer-jni.so}) loops a media file <b>natively</b> — after
 * reaching EOF it restarts from the beginning on its own, with no surface clear and no Java
 * intervention. We therefore create the player <b>once per asset</b> (createPlayer → setSurface →
 * start) and run <b>no</b> per-duration Java teardown loop. Full teardown (deletePlayer +
 * createPlayer) happens only on asset handoff, {@link #stop()}, or surface destruction.
 * See docs/TSPLAYER.md ("Looping (native)").
 * <p>
 * Do NOT reintroduce a per-duration deletePlayer/createPlayer loop: each {@code createPlayer()}
 * costs ~200ms and clears the Amlogic plane → a visible black flash every cycle, and it fights
 * the native loop. Alternatives that also fail: start()-alone at EOS EBUSYs on
 * {@code /dev/amstream_vbuf}; create-without-delete wedges after ~12 gens.
 */
public class TsVideoView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = "TsVideoView";
    private static final long PROGRESS_POLL_MS = 1000L;
    private static final long SURFACE_TIMEOUT_MS = 3000L;

    private static final long SURFACE_FALLBACK_MS = 500L;

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
    private Runnable surfaceFallbackRunnable;
    private Runnable restartRunnable;

    private int durationMs;
    /** Known duration (ms) supplied by the caller (Immich metadata); -1 if unknown. */
    private int durationHintMs = -1;
    private boolean recreatingForLoop;
    private boolean awaitingSurfaceForStart;
    private int lastLoggedStatus = Integer.MIN_VALUE;
    private int loopGeneration;

    // --- DEBUG telemetry: loop-gap / playback performance ---------------------------------
    // Updated on the UI thread. Counters persist in all builds; latencies are recorded for
    // DEBUG (logcat + /state "video" object). -1 means "not measured yet".
    private int videoErrorCount;
    private int lastRestartLatencyMs = -1;      // rewind trigger -> restart done
    private int lastCreatePlayerMs = -1;        // native createPlayer() cost
    private int lastSetSurfaceMs = -1;          // native setSurface() cost
    private int lastStartMs = -1;               // native start() cost
    private int lastRestartToPlayingMs = -1;    // restart done -> next pass playing
    private int lastEndToPlayingMs = -1;        // rewind trigger -> next pass playing (headline)
    private int lastLoopCycleMs = -1;           // previous pass start -> this one
    private int lastPosAtTriggerMs = -1;        // position read when the loop timer fired
    private String lastRestartMethod;           // "native-loop" (no Java restart) | "seek-restart"
    private long lastPassStartUptime;           // SystemClock.uptimeMillis at last (re)start

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

    public void setDataSource(String path) {
        if (released) {
            return;
        }
        pendingPath = path;
        playWhenReady = true;
        awaitingSurfaceForStart = false;
        recreatingForLoop = false;
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

    public void setDurationHint(int durationMs) {
        this.durationHintMs = durationMs;
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
        if (!looping) {
            cancelProgressPoll();
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
        cancelSurfaceTimeout();
        cancelSurfaceFallback();
        cancelRestart();
        tearDownPlayer();
        pendingPath = null;
        activePath = null;
        durationMs = 0;
        durationHintMs = -1;
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
            lastRestartMethod = "seek-restart";
            startOrRestartPlayer();
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

    // --- DEBUG telemetry getters (consumed by MainActivity /state + logcat) ----------------
    public int getLoopGeneration() {
        return loopGeneration;
    }

    public int getVideoErrorCount() {
        return videoErrorCount;
    }

    public boolean isPlayerCreated() {
        return playerCreated;
    }

    public boolean isSurfaceReady() {
        return surfaceReady;
    }

    public String getActivePath() {
        return activePath;
    }

    public int getLastRestartLatencyMs() {
        return lastRestartLatencyMs;
    }

    public int getLastCreatePlayerMs() {
        return lastCreatePlayerMs;
    }

    public int getLastSetSurfaceMs() {
        return lastSetSurfaceMs;
    }

    public int getLastStartMs() {
        return lastStartMs;
    }

    public int getLastRestartToPlayingMs() {
        return lastRestartToPlayingMs;
    }

    public int getLastEndToPlayingMs() {
        return lastEndToPlayingMs;
    }

    public int getLastLoopCycleMs() {
        return lastLoopCycleMs;
    }

    public int getLastPosAtTriggerMs() {
        return lastPosAtTriggerMs;
    }

    public String getLastRestartMethod() {
        return lastRestartMethod;
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
        if (!awaitingSurfaceForStart && !recreatingForLoop) {
            cancelProgressPoll();
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
        // Ensure no previous native instance (idempotent if already torn down).
        tearDownPlayer();

        boolean sameFile = path.equals(activePath) && durationMs > 0;

        try {
            if (!sameFile) {
                if (durationHintMs > 0) {
                    // Prefer the duration we already have. A system MediaMetadataRetriever
                    // opens /dev/amstream_vbuf in mediaserver and holds it, permanently
                    // starving the in-process TsPlayer codec with EBUSY.
                    durationMs = durationHintMs;
                } else {
                    Log.w("EO1", "TsPlayer: no duration hint; using system retriever for " + path);
                    durationMs = readDurationMs(path);
                }
            }
            Log.i("EO1", "TsPlayer createPlayer path=" + path + " durationMs=" + durationMs
                    + " loopGen=" + loopGeneration);
            long tCreate = SystemClock.uptimeMillis();
            if (!TsPlayerNative.createPlayer(path)) {
                Log.e("EO1", "TsPlayer createPlayer failed");
                notifyError();
                return;
            }
            lastCreatePlayerMs = (int) (SystemClock.uptimeMillis() - tCreate);
            playerCreated = true;
            activePath = path;
            long tSurface = SystemClock.uptimeMillis();
            TsPlayerNative.setSurface(surface);
            lastSetSurfaceMs = (int) (SystemClock.uptimeMillis() - tSurface);
            if (playWhenReady) {
                long tStart = SystemClock.uptimeMillis();
                if (!TsPlayerNative.start()) {
                    Log.e("EO1", "TsPlayer start failed");
                    tearDownPlayer();
                    notifyError();
                    return;
                }
                lastStartMs = (int) (SystemClock.uptimeMillis() - tStart);
                recreatingForLoop = false;
                awaitingSurfaceForStart = false;
                if (listener != null) {
                    listener.onVideoPrepared();
                }
                scheduleProgressPoll();
                lastRestartMethod = "native-loop";
                lastPassStartUptime = SystemClock.uptimeMillis();
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
        try {
            TsPlayerNative.pause();
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
        int nativeMs = 0;
        try {
            int raw = Math.max(0, TsPlayerNative.getCurrentTime());
            if (raw > 0) {
                if (durationMs >= 10000 && raw <= (durationMs / 1000) + 2) {
                    nativeMs = raw * 1000;
                } else {
                    nativeMs = raw;
                }
            }
        } catch (Throwable t) {
            nativeMs = 0;
        }
        if (nativeMs > 0) {
            return nativeMs;
        }
        // The native getCurrentTime() is unreliable on TsPlayer (often stays 0). Fall back
        // to elapsed time since the current pass started, clamped to the known duration,
        // so /state reports a real, advancing position.
        if (lastPassStartUptime > 0 && durationMs > 0) {
            long elapsed = SystemClock.uptimeMillis() - lastPassStartUptime;
            if (elapsed < 0) {
                elapsed = 0;
            }
            if (elapsed > durationMs) {
                elapsed = durationMs;
            }
            return (int) elapsed;
        }
        return 0;
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
        cancelSurfaceTimeout();
        cancelSurfaceFallback();
        cancelRestart();
        videoErrorCount++;
        awaitingSurfaceForStart = false;
        recreatingForLoop = false;
        if (listener != null) {
            listener.onError(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }
}
