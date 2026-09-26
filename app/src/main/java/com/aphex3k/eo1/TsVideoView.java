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
 * Looping: duration timer → tear down player → one Surface GONE/VISIBLE cycle →
 * createPlayer/setSurface/start on the new surface. Avoid setFormat+visibility together
 * (that double-destroys the surface and black-screens after the first pass).
 */
public class TsVideoView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = "TsVideoView";
    private static final long PROGRESS_POLL_MS = 1000L;
    private static final long SURFACE_TIMEOUT_MS = 3000L;
    private static final long LOOP_END_MARGIN_MS = 0L;

    /**
     * DEBUG-only: how often the loop-gap probe samples position while waiting for the next pass to
     * start playing after a restart.
     */
    private static final long LOOP_PROBE_INTERVAL_MS = 30L;
    /**
     * DEBUG-only: give up probing for a playing next pass after this long (record a timeout).
     */
    private static final long LOOP_PROBE_TIMEOUT_MS = 2500L;
    /**
     * DEBUG-only: position (ms) at or beyond which the next pass is considered "playing".
     */
    private static final int LOOP_PROBE_PLAYING_THRESHOLD_MS = 200;
    private static final long LOOP_EARLY_RESTART_MS = 80L;
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
    private Runnable durationLoopRunnable;
    private Runnable surfaceFallbackRunnable;
    private Runnable restartRunnable;

    private int durationMs;
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
    private String lastRestartMethod;           // "create-no-delete" | "stop-start" | "full-recreate"
    private long lastPassStartUptime;           // SystemClock.uptimeMillis at last (re)start
    private Runnable loopProbeRunnable;         // DEBUG-only: polls position until next pass plays

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
        cancelLoopProbe();
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
                scheduleProgressPoll();
                scheduleDurationLoop();
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
            Log.i("EO1", "TsPlayer duration elapsed (" + durationMs + "ms) — recreating for loop");
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

        loopGeneration++;

        long triggerUptime = SystemClock.uptimeMillis();
        lastPosAtTriggerMs = readPositionMs();
        lastLoopCycleMs = lastPassStartUptime > 0 ? (int) (triggerUptime - lastPassStartUptime) : -1;

        // Seamless path: never blank the SurfaceView (no GONE/VISIBLE, no delete delay).
        if (trySeamlessRestart()) {
            lastRestartLatencyMs = (int) (SystemClock.uptimeMillis() - triggerUptime);
            lastPassStartUptime = SystemClock.uptimeMillis();
            Log.i("EO1", "TsPlayer seamless loop gen=" + loopGeneration);
            scheduleProgressPoll();
            scheduleDurationLoop();
            logLoopPerfSummary();
            startLoopProbe(triggerUptime);
            return;
        }

        lastRestartLatencyMs = (int) (SystemClock.uptimeMillis() - triggerUptime);
        lastRestartMethod = "full-recreate";
        Log.w("EO1", "TsPlayer seamless loop failed — immediate full recreate gen=" + loopGeneration);
        recreatingForLoop = true;
        if (listener != null) {
            listener.onVideoEnd();
        }
        tearDownPlayer();
        recreatingForLoop = false;
        startOrRestartPlayer();
        logLoopPerfSummary();
        startLoopProbe(triggerUptime);
    }

    /**
     * Restart from the beginning without tearing down the video plane (avoids black flash).
     * Tries in-place createPlayer first; then stop/start on the existing instance.
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
        try {
            // In-place reopen — keep the same Surface bound so the last frame stays up
            // until new buffers arrive.
            long c0 = SystemClock.uptimeMillis();
            boolean created = TsPlayerNative.createPlayer(path);
            lastCreatePlayerMs = (int) (SystemClock.uptimeMillis() - c0);
            if (created) {
                long s0 = SystemClock.uptimeMillis();
                TsPlayerNative.setSurface(surface);
                lastSetSurfaceMs = (int) (SystemClock.uptimeMillis() - s0);
                long st0 = SystemClock.uptimeMillis();
                if (TsPlayerNative.start()) {
                    lastStartMs = (int) (SystemClock.uptimeMillis() - st0);
                    lastRestartMethod = "create-no-delete";
                    return true;
                }
                lastStartMs = (int) (SystemClock.uptimeMillis() - st0);
            }
        } catch (Throwable t) {
            Log.w("EO1", "TsPlayer in-place createPlayer loop failed: " + t.getMessage());
        }
        try {
            long st0 = SystemClock.uptimeMillis();
            TsPlayerNative.stop();
            boolean started = TsPlayerNative.start();
            lastStartMs = (int) (SystemClock.uptimeMillis() - st0);
            if (started) {
                lastRestartMethod = "stop-start";
            }
            return started;
        } catch (Throwable t) {
            Log.w("EO1", "TsPlayer stop/start loop failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * DEBUG-only: poll position until the next pass is playing, then record the restart-done
     * and rewind-trigger -> playing gaps. Bounded by a timeout; stops on release.
     */
    private void startLoopProbe(long triggerUptime) {
        cancelLoopProbe();
        if (!BuildConfig.DEBUG) {
            return;
        }
        long started = SystemClock.uptimeMillis();
        loopProbeRunnable = new Runnable() {
            @Override
            public void run() {
                if (released) {
                    return;
                }
                int pos = readPositionMs();
                if (pos >= LOOP_PROBE_PLAYING_THRESHOLD_MS) {
                    long now = SystemClock.uptimeMillis();
                    lastRestartToPlayingMs = (int) (now - lastPassStartUptime);
                    lastEndToPlayingMs = (int) (now - triggerUptime);
                    Log.i("EO1", "TsPlayer loop gap: restart->playing=" + lastRestartToPlayingMs
                            + "ms end->playing=" + lastEndToPlayingMs + "ms");
                    cancelLoopProbe();
                    return;
                }
                if (SystemClock.uptimeMillis() - started >= LOOP_PROBE_TIMEOUT_MS) {
                    Log.w("EO1", "TsPlayer loop gap probe timed out after " + LOOP_PROBE_TIMEOUT_MS
                            + "ms (lastPos=" + pos + ")");
                    cancelLoopProbe();
                    return;
                }
                handler.postDelayed(this, LOOP_PROBE_INTERVAL_MS);
            }
        };
        handler.postDelayed(loopProbeRunnable, LOOP_PROBE_INTERVAL_MS);
    }

    private void cancelLoopProbe() {
        if (loopProbeRunnable != null) {
            handler.removeCallbacks(loopProbeRunnable);
            loopProbeRunnable = null;
        }
    }

    private void logLoopPerfSummary() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        Log.i("EO1", "TsPlayer loop perf: triggerPos=" + lastPosAtTriggerMs + "ms restartLatency="
                + lastRestartLatencyMs + "ms method=" + lastRestartMethod
                + " create=" + lastCreatePlayerMs + "ms setSurface=" + lastSetSurfaceMs
                + "ms start=" + lastStartMs + "ms loopCycle=" + lastLoopCycleMs
                + "ms gen=" + loopGeneration);
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
        videoErrorCount++;
        awaitingSurfaceForStart = false;
        recreatingForLoop = false;
        if (listener != null) {
            listener.onError(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }
}
