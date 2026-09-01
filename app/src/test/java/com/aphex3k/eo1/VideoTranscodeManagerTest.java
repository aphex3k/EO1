package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.eo1.ffmpeg.FfmpegCommandRunner;
import com.aphex3k.eo1.ffmpeg.ProbeResult;
import com.aphex3k.eo1.ffmpeg.TranscodeResult;
import com.aphex3k.eo1.ffmpeg.VideoProbe;
import com.aphex3k.eo1.ffmpeg.VideoTranscoder;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class VideoTranscodeManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private StubFfmpegRunner stubRunner;
    private VideoTranscodeManager manager;

    @Before
    public void setUp() {
        stubRunner = new StubFfmpegRunner();
        manager = new VideoTranscodeManager(new VideoProbe(stubRunner), new VideoTranscoder(stubRunner));
    }

    @Test
    public void transcodeCacheFileNameUsesEo1Suffix() {
        assertEquals("abc123_eo1.mp4", VideoTranscodeManager.transcodeCacheFileName("abc123"));
    }

    @Test
    public void isTranscodedFileDetectsEo1Suffix() throws IOException {
        File transcoded = tempFolder.newFile("abc123_eo1.mp4");
        File original = tempFolder.newFile("abc123.mp4");
        assertTrue(VideoTranscodeManager.isTranscodedFile(transcoded));
        assertFalse(VideoTranscodeManager.isTranscodedFile(original));
    }

    @Test
    public void prepareForPlaybackReturnsOriginalWhenCodecIsCompatible() throws IOException {
        File source = tempFolder.newFile("video.mp4");
        stubRunner.probeJson = probeJson("h264", 1920, 1080);

        File result = manager.prepareForPlayback(tempFolder.getRoot(), "asset1", source);

        assertEquals(source, result);
        assertTrue(stubRunner.probeCalls > 0);
        assertEquals(0, stubRunner.transcodeCalls);
    }

    @Test
    public void prepareForPlaybackTranscodesHevcProactively() throws IOException {
        File source = tempFolder.newFile("video.mp4");
        stubRunner.probeJson = probeJson("hevc", 3840, 2160);
        stubRunner.transcodeSucceeds = true;

        File result = manager.prepareForPlayback(tempFolder.getRoot(), "asset1", source);

        assertNotNull(result);
        assertTrue(VideoTranscodeManager.isTranscodedFile(result));
        assertEquals(1, stubRunner.transcodeCalls);
    }

    @Test
    public void prepareForPlaybackTranscodesOversizedH264Proactively() throws IOException {
        File source = tempFolder.newFile("video.mp4");
        stubRunner.probeJson = probeJson("h264", 3840, 2160);
        stubRunner.transcodeSucceeds = true;

        File result = manager.prepareForPlayback(tempFolder.getRoot(), "asset1", source);

        assertNotNull(result);
        assertTrue(VideoTranscodeManager.isTranscodedFile(result));
        assertEquals(1, stubRunner.transcodeCalls);
    }

    @Test
    public void prepareForPlaybackSkipsInBoundsH264() throws IOException {
        File source = tempFolder.newFile("video.mp4");
        stubRunner.probeJson = probeJson("h264", 1920, 1080);

        File result = manager.prepareForPlayback(tempFolder.getRoot(), "asset1", source);

        assertEquals(source, result);
        assertEquals(0, stubRunner.transcodeCalls);
    }

    @Test
    public void prepareForPlaybackStripsAudioFromCompatibleH264() throws IOException {
        File source = tempFolder.newFile("video.mp4");
        stubRunner.probeJson = probeJsonWithAudio("h264", 1920, 1080);
        stubRunner.transcodeSucceeds = true;

        File result = manager.prepareForPlayback(tempFolder.getRoot(), "asset1", source);

        assertNotNull(result);
        assertTrue(VideoTranscodeManager.isTranscodedFile(result));
        assertEquals(1, stubRunner.transcodeCalls);
        assertTrue(stubRunner.lastTranscodeUsedVideoCopy);
    }

    @Test
    public void prepareForPlaybackReturnsNullWhenTranscodeFails() throws IOException {
        File source = tempFolder.newFile("video.mp4");
        stubRunner.probeJson = probeJson("hevc", 1920, 1080);
        stubRunner.transcodeSucceeds = false;

        File result = manager.prepareForPlayback(tempFolder.getRoot(), "asset1", source);

        assertNull(result);
    }

    @Test
    public void attemptReactiveTranscodeSkipsAlreadyTranscodedFile() throws IOException {
        File transcoded = tempFolder.newFile("asset1_eo1.mp4");

        File result = manager.attemptReactiveTranscode(tempFolder.getRoot(), "asset1", transcoded);

        assertNull(result);
        assertEquals(0, stubRunner.probeCalls);
    }

    @Test
    public void findCachedSourceFileLocatesDownloadedAsset() throws IOException {
        File cacheDir = tempFolder.getRoot();
        File source = new File(cacheDir, "asset1.mp4");
        assertTrue(source.createNewFile());
        new File(cacheDir, "asset1_eo1.mp4").createNewFile();

        File found = VideoTranscodeManager.findCachedSourceFile(cacheDir, "asset1");

        assertEquals(source, found);
    }

    @Test
    public void resolveReactiveSourceFallsBackToCacheLookup() throws IOException {
        File cacheDir = tempFolder.getRoot();
        File source = new File(cacheDir, "asset1.mov");
        assertTrue(source.createNewFile());
        File stale = new File(cacheDir, "missing.mp4");

        File resolved = VideoTranscodeManager.resolveReactiveSource(cacheDir, "asset1", stale);

        assertEquals(source, resolved);
    }

    @Test
    public void attemptReactiveTranscodeTranscodesUnknownCodec() throws IOException {
        File source = tempFolder.newFile("video.mp4");
        stubRunner.transcodeSucceeds = true;

        File result = manager.attemptReactiveTranscode(tempFolder.getRoot(), "asset1", source);

        assertNotNull(result);
        assertTrue(VideoTranscodeManager.isTranscodedFile(result));
        assertEquals(1, stubRunner.transcodeCalls);
    }

    @Test
    public void hasDiskSpaceRequiresTwiceSourceSizePlusMargin() throws IOException {
        File cacheDir = tempFolder.newFolder("cache");
        File source = new File(cacheDir, "large.bin");
        try (FileWriter writer = new FileWriter(source)) {
            for (int i = 0; i < 1024; i++) {
                writer.write('x');
            }
        }

        // Host temp dirs usually have >> 128MB free; formula is 2×source + margin.
        assertTrue(VideoTranscodeManager.hasDiskSpace(cacheDir, source));
        assertEquals(
                source.length() * 2L + MediaCacheManager.SAFETY_MARGIN_BYTES,
                MediaCacheManager.requiredFreeBytes(source.length() * 2L));
    }

    @Test
    public void prepareForPlaybackFailsWhenDiskSpaceGuardRejects() throws IOException {
        File source = tempFolder.newFile("video.mp4");
        stubRunner.probeJson = probeJson("hevc", 1920, 1080);
        VideoTranscodeManager guarded = new VideoTranscodeManager(
                new VideoProbe(stubRunner),
                new VideoTranscoder(stubRunner),
                (cacheDir, src, protectedPaths) -> false);

        File result = guarded.prepareForPlayback(tempFolder.getRoot(), "asset1", source);

        assertNull(result);
        assertEquals(0, stubRunner.transcodeCalls);
    }

    private static String probeJson(String codec, int width, int height) {
        return "{"
                + "\"format\":{\"format_name\":\"mov,mp4\"},"
                + "\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"" + codec + "\","
                + "\"width\":" + width + ",\"height\":" + height + "}]"
                + "}";
    }

    private static String probeJsonWithAudio(String codec, int width, int height) {
        return "{"
                + "\"format\":{\"format_name\":\"mov,mp4\"},"
                + "\"streams\":["
                + "{\"codec_type\":\"audio\",\"codec_name\":\"aac\"},"
                + "{\"codec_type\":\"video\",\"codec_name\":\"" + codec + "\","
                + "\"width\":" + width + ",\"height\":" + height + "}"
                + "]}";
    }

    private static class StubFfmpegRunner implements FfmpegCommandRunner {
        String probeJson;
        boolean transcodeSucceeds;
        int probeCalls;
        int transcodeCalls;
        boolean lastTranscodeUsedVideoCopy;
        private final Map<String, File> outputs = new HashMap<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getUnavailableReason() {
            return null;
        }

        @Override
        public CommandResult execute(String[] command) {
            if (command[0].equals("ffprobe")) {
                probeCalls++;
                return new CommandResult(0, probeJson != null ? probeJson : probeJson("h264", 1920, 1080));
            }
            if (command[0].equals("ffmpeg")) {
                transcodeCalls++;
                lastTranscodeUsedVideoCopy = String.join(" ", command).contains("-c:v copy");
                String outputPath = command[command.length - 1];
                if (transcodeSucceeds) {
                    try {
                        File output = new File(outputPath);
                        File parent = output.getParentFile();
                        if (parent != null) {
                            //noinspection ResultOfMethodCallIgnored
                            parent.mkdirs();
                        }
                        try (FileWriter writer = new FileWriter(output)) {
                            writer.write("transcoded");
                        }
                        outputs.put(outputPath, output);
                        return new CommandResult(0, "");
                    } catch (IOException e) {
                        return new CommandResult(1, e.getMessage());
                    }
                }
                return new CommandResult(1, "transcode failed");
            }
            return new CommandResult(-1, "unknown command");
        }

        @Override
        public void cancel() {
        }
    }
}
