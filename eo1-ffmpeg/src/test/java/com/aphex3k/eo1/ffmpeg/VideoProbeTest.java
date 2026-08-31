package com.aphex3k.eo1.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VideoProbeTest {

    @Test
    public void parseProbeJsonExtractsHevcCodec() {
        String json = "{"
                + "\"format\":{\"format_name\":\"mov,mp4,m4a\"},"
                + "\"streams\":["
                + "{\"codec_type\":\"audio\",\"codec_name\":\"aac\"},"
                + "{\"codec_type\":\"video\",\"codec_name\":\"hevc\",\"width\":1920,\"height\":1080}"
                + "]}";

        ProbeResult result = VideoProbe.parseProbeJson(json);

        assertTrue(result.isSuccess());
        assertEquals("hevc", result.getVideoCodec());
        assertEquals("mov,mp4,m4a", result.getContainerFormat());
        assertEquals(1920, result.getWidth());
        assertEquals(1080, result.getHeight());
        assertTrue(result.isKnownIncompatible());
    }

    @Test
    public void parseProbeJsonMarksH264AsCompatible() {
        String json = "{"
                + "\"format\":{\"format_name\":\"mp4\"},"
                + "\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1280,\"height\":720}]"
                + "}";

        ProbeResult result = VideoProbe.parseProbeJson(json);

        assertTrue(result.isSuccess());
        assertFalse(result.isKnownIncompatible());
    }

    @Test
    public void knownIncompatibleCodecsIncludesVp9AndAv1() {
        assertTrue(KnownIncompatibleCodecs.isKnownIncompatible("vp9"));
        assertTrue(KnownIncompatibleCodecs.isKnownIncompatible("av01"));
        assertFalse(KnownIncompatibleCodecs.isKnownIncompatible("h264"));
    }

    @Test
    public void needsTranscodeForHevcAndOversizedH264() {
        ProbeResult hevc = ProbeResult.success("hevc", "mp4", 1920, 1080);
        ProbeResult oversized = ProbeResult.success("h264", "mp4", 3840, 2160);
        ProbeResult inBounds = ProbeResult.success("h264", "mp4", 1920, 1080);

        assertTrue(hevc.needsTranscode());
        assertTrue(oversized.needsTranscode());
        assertFalse(inBounds.needsTranscode());
    }

    @Test
    public void exceedsMaxLongestAxisUsesLargerDimension() {
        ProbeResult portrait = ProbeResult.success("h264", "mp4", 1080, 2160);
        assertTrue(portrait.exceedsMaxLongestAxis());
        assertEquals(2160, portrait.getLongestAxis());
    }
}
