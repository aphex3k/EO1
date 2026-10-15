package com.aphex3k.media.immich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
    public void releasesPastTheOldPatchCeilingAreCovered() {
        // 3.2.3/3.2.4 and the whole 3.3 line were refused by the previous 3.2.2 ceiling.
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.2.2")));
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.2.3")));
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.2.4")));
    }

    @Test
    public void versionsInsideBandResolveToV3() {
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.1.0")));
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.2.0")));
    }

    @Test
    public void wholeThreeXLineIsInBand() {
        // Immich marks API deprecations in-spec and defers removal to the next major, so
        // every 3.x release shares this client's endpoints.
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.2.4")));
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.3.0")));
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.3.1")));
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.3.3")));
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(v("3.99.0")));
    }

    @Test
    public void versionsBelowTheBandAreUnsupported() {
        assertNull(ImmichClientRegistry.clientForVersion(v("2.9.9")));
        assertNull(ImmichClientRegistry.clientForVersion(v("0.1.0")));
    }

    @Test
    public void versionsAtOrAboveTheNextMajorAreNotAnExactMatch() {
        assertNull(ImmichClientRegistry.clientForVersion(v("4.0.0")));
        assertNull(ImmichClientRegistry.clientForVersion(v("4.0.1")));
    }

    @Test
    public void newerThanKnownBandsResolveToBestEffort() {
        // Above every band we know: keep serving with the newest client, flagged as inexact
        // so the user gets a compatibility warning instead of a disabled backend.
        for (String s : new String[]{"4.0.0", "4.0.1", "9.9.9"}) {
            ImmichClientRegistry.Resolution r = ImmichClientRegistry.resolve(v(s));
            assertNotNull(s, r);
            assertEquals(ImmichClientV3.class, r.client);
            assertFalse(s + " must not count as a band match", r.exact);
        }
    }

    @Test
    public void exactBandMatchesResolveAsExact() {
        ImmichClientRegistry.Resolution r = ImmichClientRegistry.resolve(v("3.3.1"));
        assertNotNull(r);
        assertEquals(ImmichClientV3.class, r.client);
        assertTrue(r.exact);
    }

    @Test
    public void olderThanEveryBandResolvesToNothing() {
        // Below the first band there is nothing to fall back to: we know the client speaks
        // a post-3.0 API, so this stays a clean refusal rather than a warning.
        assertNull(ImmichClientRegistry.resolve(v("2.9.9")));
    }

    @Test
    public void nullVersionIsUnsupported() {
        assertNull(ImmichClientRegistry.clientForVersion(null));
        assertNull(ImmichClientRegistry.resolve(null));
    }

    @Test
    public void parsePinParsesStrictAndLoose() {
        // Assert the semantic outcome (which client band the pin resolves to),
        // not the string form — semver4j preserves the original input in toString().
        assertEquals(ImmichClientV3.class,
                ImmichClientRegistry.clientForVersion(ImmichClientRegistry.parsePin("3.1.0")));
        assertEquals(ImmichClientV3.class,
                ImmichClientRegistry.clientForVersion(ImmichClientRegistry.parsePin("3.1")));
        assertEquals(ImmichClientV3.class,
                ImmichClientRegistry.clientForVersion(ImmichClientRegistry.parsePin("3.3")));
    }

    @Test
    public void loosePinsAboveTheKnownBandFallBack() {
        // A two-part pin must compare against the exclusive ceiling the same way a full one
        // does; "4" means the 4.x line, which no client of ours covers exactly.
        ImmichClientRegistry.Resolution r = ImmichClientRegistry.resolve(
                ImmichClientRegistry.parsePin("4"));
        assertNotNull(r);
        assertEquals(ImmichClientV3.class, r.client);
        assertFalse(r.exact);
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
    public void supportedRangeNamesTheLineAndTheVerifiedPoint() {
        String range = ImmichClientRegistry.supportedRange();
        assertTrue(range, range.startsWith("3.0.0"));
        assertTrue(range, range.contains("3.x"));
        assertTrue(range, range.endsWith(ImmichClientRegistry.V3_VERIFIED_MAX + ")"));
    }

    @Test
    public void newestClientIsTheHighestBand() {
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.newestClient());
    }
}
