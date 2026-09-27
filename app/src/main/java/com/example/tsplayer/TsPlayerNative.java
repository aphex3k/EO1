package com.example.tsplayer;

import android.util.Log;
import android.view.Surface;

/**
 * JNI bindings for Amlogic/Geniatech {@code libTsPlayer-jni.so}, reconstructed from the
 * original Electric Objects app ({@code com.example.tsplayer.TsPlayerNative}).
 * <p>
 * Only works on matching Amlogic EO1/EO2 firmware. Call {@link #isAvailable()} before use.
 */
public final class TsPlayerNative {

    private static final String TAG = "TsPlayerNative";

    public static final int PLAYER_IDLE = 0;
    public static final int PLAYER_START = 1;
    public static final int PLAYER_PAUSE = 2;
    public static final int PLAYER_STOP = 3;

    private static final Boolean AVAILABLE;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("TsPlayer-jni");
            loaded = true;
            try {
                Log.i(TAG, "libTsPlayer-jni loaded");
            } catch (Throwable ignored) {
                // android.util.Log is a stub on JVM unit tests
            }
        } catch (UnsatisfiedLinkError | SecurityException e) {
            try {
                Log.w(TAG, "libTsPlayer-jni unavailable: " + e.getMessage());
            } catch (Throwable ignored) {
                // android.util.Log is a stub on JVM unit tests
            }
        } catch (Throwable t) {
            // Defensive: never let class init crash the process
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private TsPlayerNative() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static native boolean createPlayer(String path);

    public static native void deletePlayer();

    public static native int getCurrentTime();

    public static native int getStatus();

    public static native boolean pause();

    public static native boolean resume();

    public static native boolean start();

    public static native boolean stop();

    public static native void setSurface(Surface surface);
}
