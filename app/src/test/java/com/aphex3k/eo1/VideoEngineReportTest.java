package com.aphex3k.eo1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * Which engine a frame will actually run, and what its linker error is allowed to claim.
 *
 * <p>The case that produced this: an EO1 whose native library never loaded logged
 * {@code player=TsPlayer} on every video, because routing names an engine from its own verdict and the
 * routing verdict is made before the engine question is asked. A log that reads like an EO2 on a frame
 * running MediaPlayer is the bug; whether the frame could run TsPlayer at all is a different question,
 * answered by {@code /state}'s {@code video.systemWrite} block and not by these two functions.
 */
public class VideoEngineReportTest {

    /** Verbatim, minus the timestamp, from a live EO1's {@code eo1-app.log}. */
    private static final String EO1_LOAD_ERROR =
            "UnsatisfiedLinkError: dlopen failed: could not load library"
                    + " \"libsystemwriteservice.so\" needed by \"libTsPlayer-jni.so\"; caused by library"
                    + " \"libsystemwriteservice.so\" not found";

    @Test
    public void aFrameWithoutTheNativeLibraryNeverReportsTsPlayer() {
        assertEquals("routing said TsPlayer, but the .so is not here — the EO1 case",
                "MediaPlayer", VideoEngineReport.engineWord(false, false));
    }

    @Test
    public void aHealthyFrameStillReportsTsPlayer() {
        assertEquals("TsPlayer", VideoEngineReport.engineWord(false, true));
    }

    @Test
    public void routingOverridesPreference() {
        assertEquals("MediaPlayer", VideoEngineReport.engineWord(true, true));
        assertEquals("MediaPlayer", VideoEngineReport.engineWord(true, false));
    }

    @Test
    public void theLinkerSentenceNamesTheLibraryAndClaimsNothingElse() {
        String s = VideoEngineReport.loadSentence(EO1_LOAD_ERROR);
        assertEquals("libsystemwriteservice.so (a dependency of libTsPlayer-jni.so) is not on this"
                + " ROM's library path, so the native player cannot be loaded", s);
        // The distinction that decides whether shipping the library fixes the frame or freezes it is
        // the service registrant, and the linker proved nothing about it.
        assertFalse("the sentence must not guess at the service", s.contains("system_write"));
        assertFalse("nor at what would happen if the library were there", s.contains("wait"));
    }

    @Test
    public void aFailureThatIsNotAMissingDependencyIsPassedThrough() {
        String other = "SecurityException: /data/app/com.aphex3k.eo1-1/lib/arm/libTsPlayer-jni.so: perm denial";
        assertEquals(other, VideoEngineReport.loadSentence(other));
        assertEquals("dlopen failed: cannot locate symbol _ZN7android6MemoryE",
                VideoEngineReport.loadSentence("dlopen failed: cannot locate symbol _ZN7android6MemoryE"));
        assertNull(VideoEngineReport.loadSentence(null));
    }

    /** A malformed shape must not become a confident wrong sentence. */
    @Test
    public void aTruncatedLinkerMessageIsNotRewritten() {
        String half = "could not load library \"libsystemwriteservice.so\"";
        assertEquals(half, VideoEngineReport.loadSentence(half));
    }
}
