package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.eo1.ffmpeg.FfmpegCommandRunner;
import com.aphex3k.eo1.ffmpeg.ImageConverter;
import com.aphex3k.eo1.ffmpeg.ImageProbe;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class ImageConvertManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private StubFfmpegRunner stubRunner;
    private ImageConvertManager manager;

    @Before
    public void setUp() {
        stubRunner = new StubFfmpegRunner();
        manager = new ImageConvertManager(new ImageProbe(stubRunner), new ImageConverter(stubRunner));
    }

    @Test
    public void convertCacheFileNameUsesEo1JpgSuffix() {
        assertEquals("abc123_eo1.jpg", ImageConvertManager.convertCacheFileName("abc123"));
    }

    @Test
    public void isConvertedFileDetectsEo1JpgSuffix() throws IOException {
        File converted = tempFolder.newFile("abc123_eo1.jpg");
        File original = tempFolder.newFile("abc123.heic");
        assertTrue(ImageConvertManager.isConvertedFile(converted));
        assertFalse(ImageConvertManager.isConvertedFile(original));
    }

    @Test
    public void prepareForDisplaySkipsGif() throws IOException {
        File gif = tempFolder.newFile("anim.gif");
        writeBytes(gif, "GIF89a");

        File result = manager.prepareForDisplay(tempFolder.getRoot(), "asset1", gif);

        assertEquals(gif, result);
        assertEquals(0, stubRunner.probeCalls);
        assertEquals(0, stubRunner.convertCalls);
    }

    @Test
    public void prepareForDisplayReturnsOriginalWhenJpegInBounds() throws IOException {
        File source = tempFolder.newFile("photo.jpg");
        writeBytes(source, "jpeg");
        stubRunner.probeJson = probeJson("mjpeg", "image2", 1920, 1080);

        File result = manager.prepareForDisplay(tempFolder.getRoot(), "asset1", source);

        assertEquals(source, result);
        assertEquals(0, stubRunner.convertCalls);
    }

    @Test
    public void prepareForDisplayConvertsHeic() throws IOException {
        File source = tempFolder.newFile("photo.heic");
        writeBytes(source, "heic");
        stubRunner.probeJson = probeJson("hevc", "heif", 4032, 3024);
        stubRunner.convertSucceeds = true;

        File result = manager.prepareForDisplay(tempFolder.getRoot(), "asset1", source);

        assertNotNull(result);
        assertTrue(ImageConvertManager.isConvertedFile(result));
        assertEquals(1, stubRunner.convertCalls);
    }

    @Test
    public void prepareForDisplayConvertsOversizedJpeg() throws IOException {
        File source = tempFolder.newFile("photo.jpg");
        writeBytes(source, "jpeg");
        stubRunner.probeJson = probeJson("mjpeg", "image2", 4000, 3000);
        stubRunner.convertSucceeds = true;

        File result = manager.prepareForDisplay(tempFolder.getRoot(), "asset1", source);

        assertNotNull(result);
        assertTrue(ImageConvertManager.isConvertedFile(result));
        assertEquals(1, stubRunner.convertCalls);
    }

    @Test
    public void prepareForDisplayReturnsNullWhenHeicConvertFails() throws IOException {
        File source = tempFolder.newFile("photo.heic");
        writeBytes(source, "heic");
        stubRunner.probeJson = probeJson("hevc", "heif", 2000, 1500);
        stubRunner.convertSucceeds = false;

        File result = manager.prepareForDisplay(tempFolder.getRoot(), "asset1", source);

        assertNull(result);
    }

    @Test
    public void prepareForDisplayReturnsNullForHeicWhenFfmpegUnavailable() throws IOException {
        File source = tempFolder.newFile("photo.heic");
        writeBytes(source, "heic");
        stubRunner.available = false;

        File result = manager.prepareForDisplay(tempFolder.getRoot(), "asset1", source);

        assertNull(result);
    }

    @Test
    public void prepareForDisplayUsesCacheHit() throws IOException {
        File source = tempFolder.newFile("photo.heic");
        writeBytes(source, "heic");
        File cached = new File(tempFolder.getRoot(), "asset1_eo1.jpg");
        writeBytes(cached, "converted");
        cached.setLastModified(source.lastModified() + 1000);
        stubRunner.probeJson = probeJson("hevc", "heif", 2000, 1500);

        File result = manager.prepareForDisplay(tempFolder.getRoot(), "asset1", source);

        assertEquals(cached, result);
        assertEquals(0, stubRunner.convertCalls);
    }

    @Test
    public void prepareForDisplayReturnsNullWhenDiskGuardFails() throws IOException {
        File source = tempFolder.newFile("photo.heic");
        writeBytes(source, "heic");
        stubRunner.probeJson = probeJson("hevc", "heif", 2000, 1500);
        ImageConvertManager guarded = new ImageConvertManager(
                new ImageProbe(stubRunner),
                new ImageConverter(stubRunner),
                (cacheDir, src, protectedPaths) -> false);

        assertNull(guarded.prepareForDisplay(tempFolder.getRoot(), "asset1", source));
        assertEquals(0, stubRunner.convertCalls);
    }

    private static String probeJson(String codec, String format, int width, int height) {
        return "{"
                + "\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"" + codec
                + "\",\"width\":" + width + ",\"height\":" + height + "}],"
                + "\"format\":{\"format_name\":\"" + format + "\"}"
                + "}";
    }

    private static void writeBytes(File file, String content) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    private static final class StubFfmpegRunner implements FfmpegCommandRunner {
        String probeJson = "";
        boolean convertSucceeds = true;
        boolean available = true;
        int probeCalls;
        int convertCalls;

        @Override
        public CommandResult execute(String[] command) {
            if (command.length > 0 && "ffprobe".equals(command[0])) {
                probeCalls++;
                return new CommandResult(0, probeJson);
            }
            convertCalls++;
            if (convertSucceeds && command.length > 0) {
                String outPath = command[command.length - 1];
                try {
                    writeBytes(new File(outPath), "out");
                } catch (IOException ignored) {
                }
            }
            return new CommandResult(convertSucceeds ? 0 : 1,
                    convertSucceeds ? "ok" : "fail");
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String getUnavailableReason() {
            return available ? null : "stub unavailable";
        }
    }
}
