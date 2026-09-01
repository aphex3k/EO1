package com.aphex3k.eo1.ffmpeg;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts still images (HEIC/HEIF, oversized JPEG/PNG, …) to EO1-safe JPEG via FFmpeg.
 */
public class ImageConverter {

    private final FfmpegCommandRunner runner;

    public ImageConverter() {
        this(new FfmpegKitCommandRunner());
    }

    public ImageConverter(FfmpegCommandRunner runner) {
        this.runner = runner;
    }

    public boolean isAvailable() {
        return runner.isAvailable();
    }

    public String getUnavailableReason() {
        return runner.getUnavailableReason();
    }

    public TranscodeResult convert(File input, File output, ImageConvertOptions options) {
        if (input == null || !input.exists()) {
            return TranscodeResult.failure("Input file does not exist");
        }
        if (output == null) {
            return TranscodeResult.failure("Output file is null");
        }
        if (!runner.isAvailable()) {
            return TranscodeResult.failure("FFmpeg not available");
        }

        ImageConvertOptions opts = options != null ? options : ImageConvertOptions.defaults();

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
            return TranscodeResult.failure("FFmpeg image convert failed: " + result.getOutput());
        }

        if (!output.exists() || output.length() == 0) {
            return TranscodeResult.failure("Image convert produced empty output");
        }

        return TranscodeResult.success();
    }

    public void cancel() {
        runner.cancel();
    }

    static String[] buildCommand(File input, File output, ImageConvertOptions opts) {
        List<String> args = new ArrayList<>();
        args.add("ffmpeg");
        args.add("-y");
        args.add("-i");
        args.add(input.getAbsolutePath());
        args.add("-frames:v");
        args.add("1");
        args.add("-vf");
        args.add(opts.getVideoFilter());
        args.add("-q:v");
        args.add(String.valueOf(opts.getJpegQuality()));
        args.add("-threads");
        args.add(String.valueOf(opts.getThreadCount()));
        args.add(output.getAbsolutePath());
        return args.toArray(new String[0]);
    }
}
