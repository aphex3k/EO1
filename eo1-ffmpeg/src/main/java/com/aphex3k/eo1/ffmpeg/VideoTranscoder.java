package com.aphex3k.eo1.ffmpeg;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class VideoTranscoder {

    private final FfmpegCommandRunner runner;

    public VideoTranscoder() {
        this(new FfmpegKitCommandRunner());
    }

    public VideoTranscoder(FfmpegCommandRunner runner) {
        this.runner = runner;
    }

    public boolean isAvailable() {
        return runner.isAvailable();
    }

    public String getUnavailableReason() {
        return runner.getUnavailableReason();
    }

    public TranscodeResult transcode(File input, File output, TranscodeOptions options) {
        if (input == null || !input.exists()) {
            return TranscodeResult.failure("Input file does not exist");
        }
        if (output == null) {
            return TranscodeResult.failure("Output file is null");
        }
        if (!runner.isAvailable()) {
            return TranscodeResult.failure("FFmpeg not available");
        }

        TranscodeOptions opts = options != null ? options : TranscodeOptions.defaults();

        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return TranscodeResult.failure("Unable to create output directory");
        }

        if (output.exists() && !output.delete()) {
            return TranscodeResult.failure("Unable to remove existing output file");
        }

        String[] command = buildCommand(input, output, opts);
        FfmpegCommandRunner.CommandResult result = runner.execute(command);

        if (!result.isSuccess()) {
            if (output.exists()) {
                //noinspection ResultOfMethodCallIgnored
                output.delete();
            }
            return TranscodeResult.failure("FFmpeg transcode failed: " + result.getOutput());
        }

        if (!output.exists() || output.length() == 0) {
            return TranscodeResult.failure("Transcode produced empty output");
        }

        return TranscodeResult.success();
    }

    public void cancel() {
        runner.cancel();
    }

    static String[] buildCommand(File input, File output, TranscodeOptions opts) {
        List<String> args = new ArrayList<>();
        args.add("ffmpeg");
        args.add("-y");
        args.add("-i");
        args.add(input.getAbsolutePath());
        if (opts.isVideoCopy()) {
            args.add("-c:v");
            args.add("copy");
            args.add("-an");
            args.add("-movflags");
            args.add("+faststart");
        } else {
            args.add("-c:v");
            args.add("libx264");
            args.add("-profile:v");
            args.add("baseline");
            args.add("-level");
            args.add("3.1");
            args.add("-pix_fmt");
            args.add("yuv420p");
            args.add("-vf");
            args.add(opts.getVideoFilter());
            args.add("-an");
            args.add("-movflags");
            args.add("+faststart");
            args.add("-threads");
            args.add(String.valueOf(opts.getThreadCount()));
        }
        args.add(output.getAbsolutePath());
        return args.toArray(new String[0]);
    }
}
