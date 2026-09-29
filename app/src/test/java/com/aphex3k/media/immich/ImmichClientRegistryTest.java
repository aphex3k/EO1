package com.aphex3k.media.immich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.vdurmont.semver4j.Semver;

import org.junit.Test;

/** Pure-JVM tests for band resolution and pin parsing. No HTTP is involved. */
public class ImmichClientRegistryTest {

    private static Semver v(String s) {
        return new Semver(s, Semver.SemverType.STRICT);
    }

    @Test
    public void v3BandLowerBoundIsInclusive() {
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.0.0")));
    }

    @Test
    public void v3BandUpperBoundIsInclusive() {
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.2.2")));
    }

    @Test
    public void versionsInsideBandResolveToV3() {
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.1.0")));
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.2.0")));
    }

    @Test
    public void versionsOutsideBandAreUnsupported() {
        assertNull(ImmichClientRegistry.clientForVersion(v("2.9.9")));
        assertNull(ImmichClientRegistry.clientForVersion(v("3.3.0")));
        assertNull(ImmichClientRegistry.clientForVersion(v("4.0.0")));
    }

    @Test
    public void nullVersionIsUnsupported() {
        assertNull(ImmichClientRegistry.clientForVersion(null));
    }

    @Test
    public void parsePinParsesStrictAndLoose() {
        // Assert the semantic outcome (which client band the pin resolves to),
        // not the string form — semver4j preserves the original input in toString().
        assertEquals(ImmichClientV3.class,
                ImmichClientRegistry.clientForVersion(ImmichClientRegistry.parsePin("3.1.0")));
        assertEquals(ImmichClientV3.class,
                ImmichClientRegistry.clientForVersion(ImmichClientRegistry.parsePin("3.1")));
    }

    @Test
    public void parsePinReturnsNullForAutoOrGarbage() {
        assertNull(ImmichClientRegistry.parsePin("auto"));
        assertNull(ImmichClientRegistry.parsePin("AUTO"));
        assertNull(ImmichClientRegistry.parsePin("  "));
        assertNull(ImmichClientRegistry.parsePin("banana"));
        assertNull(ImmichClientRegistry.parsePin(null));
    }

    @Test
    public void supportedRangeMentionsBothBounds() {
        String range = ImmichClientRegistry.supportedRange();
        org.junit.Assert.assertTrue(range.startsWith("3.0.0"));
        org.junit.Assert.assertTrue(range.endsWith("3.2.2"));
    }
}
