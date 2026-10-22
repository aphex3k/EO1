package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import org.junit.Test;

public class BrightnessManagerTest {

    private static final float DELTA = 0.001f;

    /** A manager whose user level comes back from {@code storedStep}, on a platform with a sensor. */
    private static BrightnessManager withStoredStep(MockBrightnessListener listener, int storedStep) {
        SettingsManager store = mock(SettingsManager.class);
        when(store.readMinimumBrightnessStep()).thenReturn(storedStep);
        when(store.setMinimumBrightnessStep(anyInt())).thenReturn(true);
        return new BrightnessManager(listener, mock(SensorManager.class), new Eo1Capabilities(), store);
    }

    @Test
    public void boundsAreSane() throws Exception {

        MockBrightnessListener listener = new MockBrightnessListener();

        BrightnessManager brightnessManager = withStoredStep(listener, BrightnessManager.MIN_STEP);

        assertTrue(brightnessManager.maxBrightness > 0.0f &&
                brightnessManager.maxBrightness <= 1.0 &&
                brightnessManager.maxBrightness > brightnessManager.minBrightness);

        assertTrue(brightnessManager.minBrightness < 1.0f &&
                brightnessManager.minBrightness >= 0.0 &&
                brightnessManager.minBrightness < brightnessManager.maxBrightness);

        assertTrue(brightnessManager.minLux.value < brightnessManager.maxLux.value);

        assertTrue("The brightness manager should assume the screen is on to begin with",
                brightnessManager.getShouldTheScreenBeOn());

        brightnessManager.raiseMinimumBrightnessStep("button");

        brightnessManager.toggleShouldTheScreenBeOn();

        assertFalse(brightnessManager.getShouldTheScreenBeOn());
    }

    /**
     * The reported bug: the panel dimmed below the level the user set "at random", because the
     * level lived only in a field of an object MainActivity rebuilds in onCreate.
     */
    @Test
    public void freshManagerRestoresPersistedStep() {
        MockBrightnessListener listener = new MockBrightnessListener();

        // Step 4 is the fifth tap of a session that has since been restarted.
        BrightnessManager brightnessManager = withStoredStep(listener, 4);

        assertEquals(0.7f, brightnessManager.minimumBrightness(), DELTA);
        assertEquals(0.7f, brightnessManager.currentBrightness(), DELTA);

        // The level is only worth restoring if it reaches the panel: the first thing to ask the
        // manager what to show (MainActivity at startup, or the first sensor reading) gets it.
        brightnessManager.onLightValue(BrightnessManager.minLux.value);
        assertEquals("the panel must be showing the restored level, not just remembering it",
                0.7f, listener.currentBrightness, DELTA);
    }

    @Test
    public void unpersistedFrameStartsOnTheFloor() {
        BrightnessManager brightnessManager =
                new BrightnessManager(new MockBrightnessListener(), mock(SensorManager.class),
                        new Eo1Capabilities(), null);

        assertEquals(BrightnessManager.MIN_STEP, brightnessManager.getMinimumBrightnessStep());
        assertEquals(BrightnessManager.minBrightnessFloor, brightnessManager.minimumBrightness(), DELTA);
    }

    /**
     * A tap used to write the window attributes by hand, leaving MainActivity's own cache behind;
     * the next rotation tick then re-applied the stale value. Re-reading the level after a tap must
     * be the tapped level, since that is what every apply path now reads.
     */
    @Test
    public void tapSurvivesTheNextApply() {
        BrightnessManager brightnessManager = withStoredStep(new MockBrightnessListener(), BrightnessManager.MIN_STEP);

        brightnessManager.raiseMinimumBrightnessStep("button");
        float tapped = brightnessManager.currentBrightness();

        // What the rotation tick does next: re-derive from the ambient light. It must not fall
        // back to where the manager's own cache was before the tap.
        brightnessManager.onLightValue(0.0f);

        assertEquals(0.4f, tapped, DELTA);
        assertEquals(0.4f, brightnessManager.currentBrightness(), DELTA);
    }

    @Test
    public void rolloverWrapsBackToTheFloorNotBelowIt() {
        MockBrightnessListener listener = new MockBrightnessListener();
        SettingsManager store = mock(SettingsManager.class);
        when(store.readMinimumBrightnessStep()).thenReturn(BrightnessManager.MIN_STEP);
        when(store.setMinimumBrightnessStep(anyInt())).thenReturn(true);
        BrightnessManager brightnessManager =
                new BrightnessManager(listener, mock(SensorManager.class), new Eo1Capabilities(), store);

        for (int step = BrightnessManager.MIN_STEP + 1; step <= BrightnessManager.MAX_STEP; step++) {
            brightnessManager.raiseMinimumBrightnessStep("button");
            assertEquals(step, brightnessManager.getMinimumBrightnessStep());
            assertTrue("every step stays on or above the floor",
                    brightnessManager.minimumBrightness() >= BrightnessManager.minBrightnessFloor);
            assertTrue("and never past full brightness",
                    brightnessManager.currentBrightness() <= brightnessManager.maxBrightness + DELTA);
            verify(store).setMinimumBrightnessStep(step);
        }

        // One past the top: back to the floor the button starts at, never below it.
        brightnessManager.raiseMinimumBrightnessStep("button");
        assertEquals(BrightnessManager.MIN_STEP, brightnessManager.getMinimumBrightnessStep());
        assertEquals(BrightnessManager.minBrightnessFloor, brightnessManager.minimumBrightness(), DELTA);
        assertEquals(BrightnessManager.minBrightnessFloor, listener.currentBrightness, DELTA);
    }

    /** The curve is two-way: the sensor may lift the panel above the user's floor and lower it again. */
    @Test
    public void ambientLightRaisesAndLowersTheLevelWithinTheRange() {
        BrightnessManager brightnessManager = withStoredStep(new MockBrightnessListener(), BrightnessManager.MIN_STEP);

        brightnessManager.onLightValue(0.0f);
        assertEquals(0.3f, brightnessManager.currentBrightness(), DELTA);

        brightnessManager.onLightValue(BrightnessManager.maxLux.value);
        assertEquals(1.0f, brightnessManager.currentBrightness(), DELTA);

        // Darker than anything the curve maps: still clamped to the floor, never under it.
        brightnessManager.onLightValue(-50.0f);
        assertEquals(0.3f, brightnessManager.currentBrightness(), DELTA);

        brightnessManager.onLightValue(100000.0f);
        assertEquals(1.0f, brightnessManager.currentBrightness(), DELTA);

        float previous = -1.0f;
        for (float lux = 0.0f; lux <= BrightnessManager.maxLux.value; lux += 10.0f) {
            brightnessManager.onLightValue(lux);
            float brightness = brightnessManager.currentBrightness();
            assertTrue("monotonic in ambient light", brightness >= previous);
            previous = brightness;
        }
    }

    @Test
    public void offAppliesZeroAndWakeRestoresTheUsersLevel() {
        MockBrightnessListener listener = new MockBrightnessListener();
        BrightnessManager brightnessManager = withStoredStep(listener, 5);

        brightnessManager.setShouldTheScreenBeOn(false);
        assertEquals(0.0f, brightnessManager.currentBrightness(), DELTA);
        assertEquals(0.0f, listener.currentBrightness, DELTA);

        brightnessManager.setShouldTheScreenBeOn(true);
        assertEquals(0.8f, brightnessManager.currentBrightness(), DELTA);
        assertEquals("waking must not fall back to the default floor",
                0.8f, listener.currentBrightness, DELTA);
    }

    /**
     * The EO2 has no brightness <em>button</em>, which was read as "no brightness control at all" and
     * left its floor pinned at the default forever — the panel takes a value, the frame simply had no
     * way to ask for one, and the web page's Brightness+ reported "fired" while nothing was stored.
     * Nothing in the stepping or the storing is platform-specific, so a level set by any route has to
     * land on the panel and in the store here exactly as it does on an EO1.
     */
    @Test
    public void frameWithNoBrightnessButtonStillRaisesAndStoresTheFloor() {
        MockBrightnessListener listener = new MockBrightnessListener();
        SettingsManager store = mock(SettingsManager.class);
        when(store.readMinimumBrightnessStep()).thenReturn(BrightnessManager.MIN_STEP);
        when(store.setMinimumBrightnessStep(anyInt())).thenReturn(true);
        BrightnessManager brightnessManager =
                new BrightnessManager(listener, mock(SensorManager.class), new Eo2Capabilities(), store);

        assertEquals(BrightnessManager.minBrightnessFloor, brightnessManager.minimumBrightness(), DELTA);

        assertTrue("the store accepted the new level", brightnessManager.raiseMinimumBrightnessStep("button"));
        verify(store).setMinimumBrightnessStep(BrightnessManager.MIN_STEP + 1);
        assertEquals(0.4f, brightnessManager.minimumBrightness(), DELTA);
        // No sensor means no curve, so the floor is the whole answer — and it is now the set floor.
        assertEquals("the level the panel was given must be the raised one",
                0.4f, listener.currentBrightness, DELTA);
    }

    /** The EO2 has no light sensor and no brightness button; it sits exactly on the floor. */
    @Test
    public void noSensorMeansTheFloor() {
        SettingsManager store = mock(SettingsManager.class);
        when(store.readMinimumBrightnessStep()).thenReturn(BrightnessManager.MIN_STEP);
        SensorManager sensorManager = mock(SensorManager.class);

        BrightnessManager brightnessManager =
                new BrightnessManager(new MockBrightnessListener(), sensorManager, new Eo2Capabilities(), store);

        assertEquals(BrightnessManager.minBrightnessFloor, brightnessManager.currentBrightness(), DELTA);
        verify(sensorManager, never()).getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    @Test
    public void registersLightSensorWhenSupported() {
        MockBrightnessListener listener = new MockBrightnessListener();
        SensorManager sensorManager = mock(SensorManager.class);
        Sensor lightSensor = mock(Sensor.class);
        when(sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)).thenReturn(lightSensor);
        SettingsManager store = mock(SettingsManager.class);

        new BrightnessManager(listener, sensorManager, new Eo1Capabilities(), store);

        verify(sensorManager).registerListener(any(SensorEventListener.class), eq(lightSensor),
                eq(SensorManager.SENSOR_DELAY_UI));
    }

    /**
     * MainActivity is rebuilt in onCreate, and a listener nobody unregistered keeps firing for the
     * dead Activity — two managers would then be writing the panel.
     */
    @Test
    public void sensorIsUnregisteredOnShutdown() {
        SensorManager sensorManager = mock(SensorManager.class);
        SettingsManager store = mock(SettingsManager.class);

        BrightnessManager brightnessManager = new BrightnessManager(new MockBrightnessListener(),
                sensorManager, new Eo1Capabilities(), store);
        brightnessManager.shutdown();

        verify(sensorManager).unregisterListener(any(SensorEventListener.class));
    }

    @Test
    public void shutdownIsHarmlessWithoutASensor() {
        SensorManager sensorManager = mock(SensorManager.class);
        SettingsManager store = mock(SettingsManager.class);

        new BrightnessManager(new MockBrightnessListener(), sensorManager, new Eo2Capabilities(), store).shutdown();

        verify(sensorManager, never()).unregisterListener(any(SensorEventListener.class));
    }

    @Test
    public void skipsLightSensorRegistrationWhenUnsupported() {
        MockBrightnessListener listener = new MockBrightnessListener();
        SensorManager sensorManager = mock(SensorManager.class);

        new BrightnessManager(listener, sensorManager, new Eo2Capabilities(), mock(SettingsManager.class));

        verify(sensorManager, never()).getDefaultSensor(Sensor.TYPE_LIGHT);
        verify(sensorManager, never()).registerListener(any(SensorEventListener.class),
                any(Sensor.class), any(Integer.class));
    }

    /**
     * A store that would not answer is not a frame that was never adjusted. Both used to arrive at
     * step 0, which is also the dimmest level there is — so a single failed read dimmed the panel to
     * 0.3 and the operator's next tap wrote that dimming to disk as if it had been chosen.
     */
    @Test
    public void unreadableStoreRestoresTheLastKnownLevelNotTheFloor() {
        SettingsManager store = mock(SettingsManager.class);
        when(store.readMinimumBrightnessStep()).thenReturn(SettingsManager.STEP_UNREADABLE);
        when(store.lastKnownGoodBrightnessStep()).thenReturn(5);
        when(store.setMinimumBrightnessStep(anyInt())).thenReturn(true);

        BrightnessManager brightnessManager = new BrightnessManager(new MockBrightnessListener(),
                mock(SensorManager.class), new Eo1Capabilities(), store);

        assertEquals(5, brightnessManager.getMinimumBrightnessStep());
        assertEquals(0.8f, brightnessManager.minimumBrightness(), DELTA);
        assertEquals("lastKnownGood", brightnessManager.stepSource());
        assertFalse("and it is a guess, so the next step must re-read",
                brightnessManager.isStepAuthoritative());
    }

    /** A store that says nothing is stored answers {@code absent}, and the floor it shows is honest. */
    @Test
    public void absentStoreIsTheFloorWithAName() {
        SettingsManager store = mock(SettingsManager.class);
        when(store.readMinimumBrightnessStep()).thenReturn(SettingsManager.STEP_ABSENT);

        BrightnessManager brightnessManager = new BrightnessManager(new MockBrightnessListener(),
                mock(SensorManager.class), new Eo1Capabilities(), store);

        assertEquals(BrightnessManager.minBrightnessFloor, brightnessManager.minimumBrightness(), DELTA);
        assertEquals("default", brightnessManager.stepSource());
    }

    /**
     * A step this build has never heard of came from a build with <em>more</em> steps, so the level
     * nearest the one stored is the honest answer. Sending it to the floor is what made an ordinary
     * number read as a frame nobody had ever touched.
     */
    @Test
    public void aStepBeyondThisBuildsRangeClampsToTheBrightEnd() {
        BrightnessManager brightnessManager = withStoredStep(new MockBrightnessListener(), BrightnessManager.MAX_STEP + 2);

        assertEquals(BrightnessManager.MAX_STEP, brightnessManager.getMinimumBrightnessStep());
        assertEquals(BrightnessManager.maxBrightness, brightnessManager.minimumBrightness(), DELTA);
    }

    /**
     * The self-perpetuation guard: when the store could not be read at startup and can be read now,
     * the new level grows from what is on disk, never from the value this process guessed at.
     */
    @Test
    public void aFailedRestoreReReadsBeforeItWritesAnything() {
        SettingsManager store = mock(SettingsManager.class);
        when(store.readMinimumBrightnessStep())
                .thenReturn(SettingsManager.STEP_UNREADABLE)
                .thenReturn(4);
        when(store.lastKnownGoodBrightnessStep()).thenReturn(SettingsManager.STEP_UNREADABLE);
        when(store.setMinimumBrightnessStep(anyInt())).thenReturn(true);

        BrightnessManager brightnessManager = new BrightnessManager(new MockBrightnessListener(),
                mock(SensorManager.class), new Eo1Capabilities(), store);

        assertEquals("nothing was known, so this process is on the floor",
                BrightnessManager.MIN_STEP, brightnessManager.getMinimumBrightnessStep());

        brightnessManager.raiseMinimumBrightnessStep("button");

        verify(store).setMinimumBrightnessStep(5);
        assertEquals("the level on disk, raised — not the guessed one", 5,
                brightnessManager.getMinimumBrightnessStep());
    }

    /** Every step change says who asked, so the next "it dimmed by itself" is answered by reading. */
    @Test
    public void aStepChangeRemembersItsActorAndWhetherItLanded() {
        SettingsManager store = persistingStoreAt(BrightnessManager.MAX_STEP);
        BrightnessManager brightnessManager = new BrightnessManager(new MockBrightnessListener(),
                mock(SensorManager.class), new Eo1Capabilities(), store);

        assertTrue(brightnessManager.raiseMinimumBrightnessStep("web"));

        assertEquals("web", brightnessManager.changedVia());
        assertEquals(BrightnessManager.MAX_STEP, brightnessManager.changedFromStep());
        assertEquals(BrightnessManager.MIN_STEP, brightnessManager.changedToStep());
        assertTrue("a change made before /state is read has a timestamp", brightnessManager.changedAtMs() > 0L);
        assertTrue(brightnessManager.changedDurably());

        when(store.setMinimumBrightnessStep(anyInt())).thenReturn(false);
        assertFalse(brightnessManager.raiseMinimumBrightnessStep("button"));
        assertFalse("a level the panel has and the store does not is the report this exists for",
                brightnessManager.changedDurably());
    }

    /**
     * A store that answers neither read nor write grows every press from the same stale cache, and
     * the panel stops moving — on the one gesture an operator reaches for when a frame has gone dim.
     * A press has to press, whether or not it can be written down; whether it outlives the process is
     * a separate fact, and it is {@code changedDurably()} that says so.
     */
    @Test
    public void aPressStillStepsWhenNothingCanBeStored() {
        SettingsManager store = mock(SettingsManager.class);
        when(store.readMinimumBrightnessStep()).thenReturn(SettingsManager.STEP_UNREADABLE);
        when(store.lastKnownGoodBrightnessStep()).thenReturn(3);
        when(store.setMinimumBrightnessStep(anyInt())).thenReturn(false);

        BrightnessManager brightnessManager = new BrightnessManager(new MockBrightnessListener(),
                mock(SensorManager.class), new Eo1Capabilities(), store);

        assertFalse("the store refused, and the frame says so",
                brightnessManager.raiseMinimumBrightnessStep("button"));
        assertEquals(4, brightnessManager.getMinimumBrightnessStep());
        assertFalse(brightnessManager.raiseMinimumBrightnessStep("button"));
        assertEquals("the second press moved it too", 5, brightnessManager.getMinimumBrightnessStep());
        assertFalse(brightnessManager.changedDurably());
    }

    /** A store holding {@code storedStep} that says yes to every write. */
    private static SettingsManager persistingStoreAt(int storedStep) {
        SettingsManager store = mock(SettingsManager.class);
        when(store.readMinimumBrightnessStep()).thenReturn(storedStep);
        when(store.setMinimumBrightnessStep(anyInt())).thenReturn(true);
        return store;
    }
}
