package android.util;

// This will mock the Log.e() function for JUnit
public class Log {
    public static int e(String tag, String msg, Throwable throwable) {
        System.out.println("ERROR: " + tag + ": " + msg);
        return 0;
    }
}
