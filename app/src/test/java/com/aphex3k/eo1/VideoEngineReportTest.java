package com.aphex3k.eo1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * Which engine a frame will actually run, and what its linker error is allowed to claim.
 *
 * <p>The case that produced this: an EO1 logged {@code player=TsPlayer} on every video, because
 * routing named an engine from its own verdict and the routing verdict is made before the engine
 * question is asked. A log that reads like an EO2 on a frame running MediaPlayer is the bug. Whether a
 * frame can run TsPlayer at all is a separate question answered by {@link
 * HardwareCapabilities#supportsTsPlayer()} — an EO1's silicon never could, and the linker error that
 * used to be quoted as the reason is not reached on a correctly-detected board at all.
 */
public class VideoEngineReportTest {

    /** Verbatim, minus the timestamp, from a live EO1's {@code eo1-app.log} before the gate. */
    private static final String EO1_LOAD_ERROR =
            "UnsatisfiedLinkError: dlopen failed: could not load library"
                    + " \"libsystemwriteservice.so\" needed by \"libTsPlayer-jni.so\"; caused by library"
                    + " \"libsystemwriteservice.so\" not found";

    @Test
    public void aFrameWithoutTheNativeLibraryNeverReportsTsPlayer() {
        assertEquals("routing said TsPlayer, but the engine is not available here",
                "MediaPlayer", VideoEngineReport.engineWord(false, false));
    }

    /** Three ways onto MediaPlayer, and they must not share a sentence. */
    @Test
    public void availabilityNamesTheReasonAndNotJustTheSymptom() {
        assertEquals("USE_TSPLAYER=true, not available on this board's silicon",
                VideoEngineReport.availability(false, true, false, EO1_LOAD_ERROR));
        assertEquals("USE_TSPLAYER=false, compiled out",
                VideoEngineReport.availability(true, false, false, null));
        assertEquals("USE_TSPLAYER=true, loaded",
                VideoEngineReport.availability(true, true, true, null));
        assertEquals("USE_TSPLAYER=true, failed: " + VideoEngineReport.loadSentence(EO1_LOAD_ERROR),
                VideoEngineReport.availability(true, true, false, EO1_LOAD_ERROR));
    }

    /**
     * A board that cannot run the engine must not be reported through the linker's mouth: the platform
     * answer comes first, so the {@code UnsatisfiedLinkError} text never reaches an operator who would
     * otherwise go looking for a missing file on hardware that never had one.
     */
    @Test
    public void thePlatformAnswerOvershadowsALinkerError() {
        String sentence = VideoEngineReport.availability(false, true, false, EO1_LOAD_ERROR);
        assertFalse("no linker quote", sentence.contains("dlopen"));
        assertFalse("no claim about a library path", sentence.contains("library path"));
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

    /**
     * The same wrapper form, for a dependency that <em>is</em> on the path and failed anyway. Saying
     * "not on this ROM's library path" here would send an operator off to ship a file the frame
     * already has.
     */
    @Test
    public void aDependencyThatExistsButWillNotLoadKeepsItsRealCause() {
        String unresolved = "UnsatisfiedLinkError: dlopen failed: could not load library"
                + " \"libsystemwriteservice.so\" needed by \"libTsPlayer-jni.so\"; caused by cannot"
                + " locate symbol \"_ZN7android19ISystemWriteService11asInterfaceERKNS_2spINS_7IBinderEEE\""
                + " referenced by \"libTsPlayer-jni.so\"";
        assertEquals(unresolved, VideoEngineReport.loadSentence(unresolved));

        String badElf = "UnsatisfiedLinkError: dlopen failed: could not load library"
                + " \"libsystemwriteservice.so\" needed by \"libTsPlayer-jni.so\"; caused by\"/system/lib"
                + "/libsystemwriteservice.so\" has bad ELF magic";
        assertEquals(badElf, VideoEngineReport.loadSentence(badElf));
    }

    /** A malformed shape must not become a confident wrong sentence. */
    @Test
    public void aTruncatedLinkerMessageIsNotRewritten() {
        String half = "could not load library \"libsystemwriteservice.so\"";
        assertEquals(half, VideoEngineReport.loadSentence(half));
    }
}
