package com.aphex3k.eo1;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.aphex3k.eo1.ffmpeg.FfmpegCommandRunner;
import com.aphex3k.eo1.ffmpeg.VideoProbe;
import com.aphex3k.eo1.ffmpeg.VideoTranscoder;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class MediaManagerReactiveTranscodeTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private MediaManager mediaManager;

    @Before
    public void setUp() {
        mediaManager = new MediaManager(
                mock(MediaManagerListener.class),
                mock(SettingsManager.class),
                mock(ApiServiceGenerator.ProgressListener.class),
                new VideoTranscodeManager(new VideoProbe(new StubRunner()), new VideoTranscoder(new StubRunner())));
    }

    @Test
    public void shouldAttemptReactiveTranscodeForNewAsset() throws IOException {
        File source = tempFolder.newFile("video.mp4");

        assertTrue(mediaManager.shouldAttemptReactiveTranscode("asset-1", source));
    }

    @Test
    public void shouldNotAttemptReactiveTranscodeForTranscodedFile() throws IOException {
        File transcoded = tempFolder.newFile("asset-1_eo1.mp4");

        assertFalse(mediaManager.shouldAttemptReactiveTranscode("asset-1", transcoded));
    }

    @Test
    public void shouldNotAttemptReactiveTranscodeTwiceForSameAsset() throws IOException {
        File source = tempFolder.newFile("video.mp4");

        assertTrue(mediaManager.shouldAttemptReactiveTranscode("asset-1", source));
        mediaManager.recordReactiveTranscodeAttempt("asset-1");
        assertFalse(mediaManager.shouldAttemptReactiveTranscode("asset-1", source));
    }

    @Test
    public void showNextImageClearsReactiveTranscodeAttempts() throws IOException {
        File source = tempFolder.newFile("video.mp4");
        mediaManager.recordReactiveTranscodeAttempt("asset-1");

        mediaManager.clearReactiveTranscodeAttempts();

        assertTrue(mediaManager.shouldAttemptReactiveTranscode("asset-1", source));
    }

    private static class StubRunner implements FfmpegCommandRunner {
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
            return new CommandResult(0, "");
        }

        @Override
        public void cancel() {
        }
    }
}
