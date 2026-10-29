package android.os;

// Test-scope stand-in for android.os.SystemClock, so deadline arithmetic in the app is exercisable on
// the JVM. Same arrangement as android/util/Log.java in this source set: the JVM test classpath loads
// this ahead of the mockable android.jar, whose methods throw "Stub!".
//
// nanoTime's origin is arbitrary, which is exactly right here — every caller uses differences, never an
// absolute value, and unlike wall time it cannot jump backwards when the device's clock is corrected.
public class SystemClock {

    public static long elapsedRealtime() {
        return System.nanoTime() / 1_000_000L;
    }

    public static long uptimeMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}
