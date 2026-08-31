package com.aphex3k.eo1.ffmpeg;

import java.lang.reflect.Method;

/**
 * Reflection-based runner so the module compiles without the ffmpeg-kit AAR on the compile classpath.
 */
public class FfmpegKitCommandRunner implements FfmpegCommandRunner {

    private final boolean available;
    private Object activeSession;

    public FfmpegKitCommandRunner() {
        boolean found = false;
        try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit");
            found = true;
        } catch (ClassNotFoundException ignored) {
        }
        available = found;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public CommandResult execute(String[] command) {
        if (!available || command == null || command.length == 0) {
            return new CommandResult(-1, "FFmpeg native library not available");
        }

        try {
            Class<?> ffmpegKitClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit");
            Class<?> sessionClass = Class.forName("com.arthenica.ffmpegkit.FFmpegSession");

            Method executeWithArguments = ffmpegKitClass.getMethod("executeWithArguments", String[].class);
            Object session = executeWithArguments.invoke(null, (Object) command);
            activeSession = session;

            Method getReturnCode = sessionClass.getMethod("getReturnCode");
            Object returnCodeObj = getReturnCode.invoke(session);
            int returnCode = returnCodeObj != null ? ((Number) returnCodeObj).intValue() : -1;

            Method getOutput = sessionClass.getMethod("getOutput");
            String output = (String) getOutput.invoke(session);

            Method getFailStackTrace = sessionClass.getMethod("getFailStackTrace");
            String stackTrace = (String) getFailStackTrace.invoke(session);
            if (stackTrace != null && !stackTrace.isEmpty()) {
                output = output + "\n" + stackTrace;
            }

            activeSession = null;
            return new CommandResult(returnCode, output);
        } catch (Exception e) {
            activeSession = null;
            return new CommandResult(-1, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    @Override
    public void cancel() {
        if (!available) {
            return;
        }
        try {
            Class<?> ffmpegKitClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit");
            Method cancel = ffmpegKitClass.getMethod("cancel");
            cancel.invoke(null);
        } catch (Exception ignored) {
        }
        activeSession = null;
    }
}
