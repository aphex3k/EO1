package com.aphex3k.eo1;

/**
 * Which video engine a frame will actually run, said in one place.
 *
 * <p>Two different questions used to be answered with the same word. Routing is content-deterministic
 * and engine-blind, so a line that names the player from the routing outcome alone claims TsPlayer on
 * a frame whose native library never loaded — which is what an EO1 reported while it played everything
 * on MediaPlayer. A report that cannot tell those two frames apart is the reason a remote operator
 * asks for a cable.
 */
public final class VideoEngineReport {

    public static final String TS_PLAYER = "TsPlayer";
    public static final String MEDIA_PLAYER = "MediaPlayer";

    private static final String NEEDED_PREFIX = "could not load library \"";
    private static final String NEEDED_MIDDLE = "\" needed by \"";
    private static final String NEEDED_SUFFIX = "\"";

    private VideoEngineReport() {
    }

    /**
     * The engine {@code selectVideoPlayer} will choose, as opposed to the one routing handed off to.
     *
     * @param forceFallback  the routing decision for this clip
     * @param preferTsPlayer the native library loaded and is allowed
     */
    public static String engineWord(boolean forceFallback, boolean preferTsPlayer) {
        return (forceFallback || !preferTsPlayer) ? MEDIA_PLAYER : TS_PLAYER;
    }

    /**
     * What the dynamic linker proved, and nothing it did not prove.
     *
     * <p>A missing {@code DT_NEEDED} entry says one thing: a soname is not on this ROM's library path.
     * It does not say the service that soname would have talked to is absent — that is a separate
     * read, in {@code /state}'s {@code video.systemWrite} block — and conflating the two is how a
     * frame gets reinstalled against the wrong problem.
     *
     * @return a sentence naming the missing library, or {@code rawLoadError} unchanged when the
     *         failure is not the linker's missing-dependency shape
     */
    public static String loadSentence(String rawLoadError) {
        if (rawLoadError == null) {
            return null;
        }
        int from = rawLoadError.indexOf(NEEDED_PREFIX);
        if (from < 0) {
            return rawLoadError;
        }
        int nameStart = from + NEEDED_PREFIX.length();
        int middle = rawLoadError.indexOf(NEEDED_MIDDLE, nameStart);
        if (middle < 0) {
            return rawLoadError;
        }
        int parentStart = middle + NEEDED_MIDDLE.length();
        int end = rawLoadError.indexOf(NEEDED_SUFFIX, parentStart);
        if (end < 0) {
            return rawLoadError;
        }
        return rawLoadError.substring(nameStart, middle) + " (a dependency of "
                + rawLoadError.substring(parentStart, end)
                + ") is not on this ROM's library path, so the native player cannot be loaded";
    }
}
