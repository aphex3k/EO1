package com.aphex3k.eo1.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class ImageConverterTest {

    @Test
    public void buildCommandScalesAndWritesSingleJpegFrame() {
        File input = new File("/cache/photo.heic");
        File output = new File("/cache/photo_eo1.jpg");

        String[] command = ImageConverter.buildCommand(input, output, ImageConvertOptions.defaults());
        String joined = String.join(" ", command);

        assertTrue(joined.contains("-frames:v 1"));
        assertTrue(joined.contains("-q:v 2"));
        assertTrue(joined.contains("-threads 2"));
        assertTrue(joined.contains("min(1920,ih)"));
        assertEquals(output.getAbsolutePath(), command[command.length - 1]);
    }
}
