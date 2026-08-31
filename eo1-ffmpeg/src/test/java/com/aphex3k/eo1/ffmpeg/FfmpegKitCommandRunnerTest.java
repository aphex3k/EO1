package com.aphex3k.eo1.ffmpeg;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;

public class FfmpegKitCommandRunnerTest {

    @Test
    public void kitArgumentsFromStripsExecutableName() {
        String[] command = new String[]{
                "ffprobe",
                "-v", "quiet",
                "-print_format", "json",
                "-show_streams",
                "/cache/video.mp4"
        };

        assertArrayEquals(
                new String[]{"-v", "quiet", "-print_format", "json", "-show_streams", "/cache/video.mp4"},
                FfmpegKitCommandRunner.kitArgumentsFrom(command));
    }

    @Test
    public void kitArgumentsFromReturnsEmptyWhenOnlyExecutablePresent() {
        assertEquals(0, FfmpegKitCommandRunner.kitArgumentsFrom(new String[]{"ffmpeg"}).length);
    }

    @Test
    public void isAvailableFalseOnJvmWithoutNativeLibraries() {
        FfmpegKitCommandRunner runner = new FfmpegKitCommandRunner();
        assertFalse(runner.isAvailable());
        assertTrue(runner.getUnavailableReason() != null && !runner.getUnavailableReason().isEmpty());
    }

    @Test
    public void formatExceptionUnwrapsInvocationTargetException() {
        RuntimeException root = new RuntimeException("native load failed");
        InvocationTargetException wrapped = new InvocationTargetException(root);
        assertEquals("RuntimeException: native load failed", FfmpegKitCommandRunner.formatException(wrapped));
    }
}
