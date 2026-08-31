package com.aphex3k.eo1.ffmpeg;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reflection-based runner so the module compiles without the ffmpeg-kit AAR on the compile classpath.
 */
public class FfmpegKitCommandRunner implements FfmpegCommandRunner {

    private final boolean available;
    private final String unavailableReason;
    private Object activeSession;

    public FfmpegKitCommandRunner() {
        String reason = null;
        boolean found = false;
        try {
            ClassLoader loader = FfmpegKitCommandRunner.class.getClassLoader();
            // Force FFmpegKitConfig static init (native load + smart-exception). Class.forName on
            // FFmpegKit/FFprobeKit only references Config by name and does not initialize it.
            Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig", true, loader);
            found = true;
        } catch (Throwable t) {
            reason = formatException(t);
        }
        available = found;
        unavailableReason = reason;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String getUnavailableReason() {
        return unavailableReason;
    }

    @Override
    public CommandResult execute(String[] command) {
        if (!available) {
            String reason = unavailableReason != null ? unavailableReason : "FFmpeg native library not available";
            return new CommandResult(-1, reason);
        }
        if (command == null || command.length == 0) {
            return new CommandResult(-1, "FFmpeg command missing");
        }

        String executable = command[0];
        String[] kitArguments = kitArgumentsFrom(command);
        if (kitArguments.length == 0) {
            return new CommandResult(-1, "Missing command arguments for " + executable);
        }

        try {
            KitBinding binding = kitBindingFor(executable);
            if (binding == null) {
                return new CommandResult(-1, "Unsupported executable: " + executable);
            }

            Class<?> kitClass = Class.forName(binding.kitClassName);
            Class<?> sessionClass = Class.forName(binding.sessionClassName);

            Method executeWithArguments = kitClass.getMethod("executeWithArguments", String[].class);
            Object session = executeWithArguments.invoke(null, (Object) kitArguments);
            activeSession = session;

            int returnCode = readReturnCode(session, sessionClass);
            String output = readSessionOutput(session, sessionClass);

            activeSession = null;
            return new CommandResult(returnCode, output);
        } catch (Exception e) {
            activeSession = null;
            return new CommandResult(-1, formatException(e));
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

    static String[] kitArgumentsFrom(String[] command) {
        if (command == null || command.length < 2) {
            return new String[0];
        }
        String[] kitArguments = new String[command.length - 1];
        System.arraycopy(command, 1, kitArguments, 0, kitArguments.length);
        return kitArguments;
    }

    private static KitBinding kitBindingFor(String executable) {
        if ("ffprobe".equals(executable)) {
            return new KitBinding(
                    "com.arthenica.ffmpegkit.FFprobeKit",
                    "com.arthenica.ffmpegkit.FFprobeSession");
        }
        if ("ffmpeg".equals(executable)) {
            return new KitBinding(
                    "com.arthenica.ffmpegkit.FFmpegKit",
                    "com.arthenica.ffmpegkit.FFmpegSession");
        }
        return null;
    }

    private static int readReturnCode(Object session, Class<?> sessionClass) throws Exception {
        Method getReturnCode = sessionClass.getMethod("getReturnCode");
        Object returnCodeObj = getReturnCode.invoke(session);
        if (returnCodeObj == null) {
            return -1;
        }
        Method getValue = returnCodeObj.getClass().getMethod("getValue");
        Object value = getValue.invoke(returnCodeObj);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private static String readSessionOutput(Object session, Class<?> sessionClass) throws Exception {
        Method getOutput = sessionClass.getMethod("getOutput");
        String output = (String) getOutput.invoke(session);

        Method getFailStackTrace = sessionClass.getMethod("getFailStackTrace");
        String stackTrace = (String) getFailStackTrace.invoke(session);
        if (stackTrace != null && !stackTrace.isEmpty()) {
            output = (output != null ? output : "") + "\n" + stackTrace;
        }
        return output != null ? output : "";
    }

    static String formatException(Throwable throwable) {
        Throwable cause = throwable;
        if (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ExceptionInInitializerError && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message != null && !message.isEmpty()) {
            return cause.getClass().getSimpleName() + ": " + message;
        }
        return cause.toString();
    }

    private static final class KitBinding {
        private final String kitClassName;
        private final String sessionClassName;

        private KitBinding(String kitClassName, String sessionClassName) {
            this.kitClassName = kitClassName;
            this.sessionClassName = sessionClassName;
        }
    }
}
