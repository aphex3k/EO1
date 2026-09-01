package com.aphex3k.eo1.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImageProbeTest {

    @Test
    public void parseProbeJsonReadsJpegStream() {
        String json = "{"
                + "\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"mjpeg\",\"width\":1920,\"height\":1080}],"
                + "\"format\":{\"format_name\":\"image2\"}"
                + "}";
        ImageProbeResult result = ImageProbe.parseProbeJson(json);
        assertTrue(result.isSuccess());
        assertEquals("mjpeg", result.getCodecName());
        assertEquals("image2", result.getFormatName());
        assertEquals(1920, result.getWidth());
        assertEquals(1080, result.getHeight());
        assertFalse(result.needsConvert());
    }

    @Test
    public void parseProbeJsonDetectsHeif() {
        String json = "{"
                + "\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"hevc\",\"width\":4032,\"height\":3024}],"
                + "\"format\":{\"format_name\":\"heif\"}"
                + "}";
        ImageProbeResult result = ImageProbe.parseProbeJson(json);
        assertTrue(result.isSuccess());
        assertTrue(result.needsConvert());
    }
}
