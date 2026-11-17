package com.aphex3k.eo1;

import java.io.File;

class MockBrightnessListener implements BrightnessManagerListener {

    public float currentBrightness = 0.5f;

    /**
     * Every row the manager asked for, in order, including the ones whose keys collide on the real board.
     *
     * <p>The board keeps one row per key, so counting rows is the only way to ask how often a producer wrote
     * — which is the question the light-sensor gate exists to answer ({@code BrightnessManagerTest}'s
     * {@code theSensorPathDoesNotWriteThreeRowsPerSample}). Additive: nothing asserted on it before, because
     * {@link #debugInformationProvided} used to drop everything it was handed.
     */
    public final java.util.List<DebugInformation> rows = new java.util.ArrayList<DebugInformation>();

    /**
     * How many times the level was handed to the panel. Held separately from {@link #currentBrightness}
     * because the flood fix's one real risk is a gate placed above the application rather than below it: a
     * frame that computes the right level and never applies it is dim, and every existing brightness test
     * would stay green.
     */
    public int brightnessChangedCalls;

    @Override
    public void brightnessChanged(float toBrightness) {
        currentBrightness = toBrightness;
        brightnessChangedCalls++;
    }

    @Override
    public void handleException(Exception e) {

    }

    @Override
    public void debugInformationProvided(DebugInformation debugInformation) {
        // The field above is only worth having if this records: a producer that writes nothing and a
        // producer whose writes are dropped are otherwise the same test result, and the second is what
        // every brightness assertion here would have seen.
        rows.add(debugInformation);
    }

    @Override
    public void displayPicture(File finalTempFile, String assetId) {

    }

    @Override
    public void displayVideo(File finalTempFile, String assetId) {

    }
}
