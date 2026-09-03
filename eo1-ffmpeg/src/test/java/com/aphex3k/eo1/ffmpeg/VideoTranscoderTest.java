package com.aphex3k.eo1.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class VideoTranscoderTest {

    @Test
    public void buildCommandUsesSoftwareX264Profile() {
        File input = new File("/cache/input.mov");
        File output = new File("/cache/output_eo1.mp4");

        String[] command = VideoTranscoder.buildCommand(input, output, TranscodeOptions.defaults());

        String joined = String.join(" ", command);
        assertTrue(joined.contains("libx264"));
        assertTrue(joined.contains("-profile:v baseline"));
        assertTrue(joined.contains("-an"));
        assertTrue(joined.contains("-threads 2"));
        assertTrue(joined.contains("min(1920,ih)"));
        assertFalse(joined.contains("min(1080,ih)"));
        assertEquals(output.getAbsolutePath(), command[command.length - 1]);
    }

    @Test
    public void buildCommandCopiesVideoAndStripsAudio() {
        File input = new File("/cache/input.mp4");
        File output = new File("/cache/output_eo1.mp4");
        ProbeResult probe = ProbeResult.success("h264", "mp4", 1920, 1080, true);

        String[] command = VideoTranscoder.buildCommand(
                input, output, TranscodeOptions.forProbe(probe));

        String joined = String.join(" ", command);
        assertTrue(joined.contains("-c:v copy"));
        assertTrue(joined.contains("-an"));
        assertFalse(joined.contains("libx264"));
        assertFalse(joined.contains("-vf"));
    }

    @Test
    public void forProbeReencodesIncompatibleVideoEvenWithAudio() {
        ProbeResult probe = ProbeResult.success("hevc", "mp4", 1920, 1080, true);

        assertTrue(TranscodeOptions.forProbe(probe).isVideoCopy() == false);
    }
}
