package android.util;

// Test-scope stand-in for the framework Log class: the JVM test classpath loads
// this before the mockable android.jar, so app code under test must only see
// these statics. Keep in sync with the android.util.Log surface the app uses.
public class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int v(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, String msg) {
        System.out.println("WARN: " + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        System.out.println("WARN: " + tag + ": " + msg + " " + tr);
        return 0;
    }

    public static int w(String tag, Throwable tr) {
        System.out.println("WARN: " + tag + ": " + tr);
        return 0;
    }

    public static int e(String tag, String msg) {
        System.out.println("ERROR: " + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        System.out.println("ERROR: " + tag + ": " + msg + " " + tr);
        return 0;
    }

    public static int wtf(String tag, String msg) {
        return 0;
    }

    public static int wtf(String tag, Throwable tr) {
        return 0;
    }

    public static int wtf(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static boolean isLoggable(String tag, int level) {
        return false;
    }

    public static String getStackTraceString(Throwable tr) {
        java.io.StringWriter sw = new java.io.StringWriter();
        tr.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    public static int println(int priority, String tag, String msg) {
        return 0;
    }
}
