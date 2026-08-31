package com.aphex3k.eo1.ffmpeg;

/**
 * Executes FFprobe / FFmpeg commands. Production uses reflection against ffmpeg-kit;
 * tests inject a mock implementation.
 */
public interface FfmpegCommandRunner {

    boolean isAvailable();

    /**
     * @param command executable name (ffprobe or ffmpeg) followed by arguments
     * @return result with exit code and combined stdout/stderr
     */
    CommandResult execute(String[] command);

    void cancel();

    final class CommandResult {
        private final int returnCode;
        private final String output;

        public CommandResult(int returnCode, String output) {
            this.returnCode = returnCode;
            this.output = output != null ? output : "";
        }

        public int getReturnCode() {
            return returnCode;
        }

        public String getOutput() {
            return output;
        }

        public boolean isSuccess() {
            return returnCode == 0;
        }
    }
}
