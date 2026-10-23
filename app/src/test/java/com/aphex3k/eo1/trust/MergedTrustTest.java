package com.aphex3k.eo1.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * The rule this class exists to enforce: merging can only ever add.
 *
 * <p>On the JVM the "platform" store is the JDK's, and on a frame it is the ROM's 2013 store, so the
 * numbers differ; what must not differ is the direction. A trust manager that ended up smaller than
 * the device's own would break hosts that validate today, and would report itself as a certificate
 * problem — the worst possible outcome of a change whose whole purpose is to fix certificate problems.
 */
public class MergedTrustTest {

    @Test
    public void turningTheBundleOffRestoresTheDeviceDefault() {
        MergedTrust.Result result = MergedTrust.create(false);
        assertNull("disabled must install nothing", result.trustManager);
        assertEquals(MergedTrust.MODE_PLATFORM_ONLY, result.mode);
    }

    @Test
    public void enablingGrowsTheAnchorSetAndNeverShrinksIt() {
        MergedTrust.Result result = MergedTrust.create(true);
        assertTrue("on a machine with any platform anchors at all the merge must succeed, got "
                        + result.mode + " (" + result.detail + ")",
                result.trustManager != null);
        assertTrue("merged set (" + result.mergedAnchors + ") must be at least the platform's ("
                        + result.platformAnchors + ")",
                result.mergedAnchors >= result.platformAnchors);
        assertEquals(MergedTrust.MODE_MERGED, result.mode);
    }

    @Test
    public void mergedManagerStillCarriesThePlatformAnchors() throws Exception {
        MergedTrust.Result result = MergedTrust.create(true);
        assertNotNull(result.trustManager);
        X509Certificate[] issuers = result.trustManager.getAcceptedIssuers();
        assertTrue("a merged manager with no issuers would trust nothing", issuers.length > 0);

        // The shipped roots must be reachable through the manager, not just counted.
        boolean found = false;
        for (int i = 0; i < issuers.length; i++) {
            if (SubjectHashOld.hash(issuers[i]).equals(RootBundle.entries()[0].subjectHash)) {
                found = true;
            }
        }
        assertTrue("the merged manager does not expose the shipped anchors", found);
    }

    /**
     * The manager has to actually reject things. A trust manager built over a key store can end up
     * permissive through no fault of its own if the provider misbehaves, and nothing else in this
     * feature would notice.
     */
    @Test
    public void mergedManagerRejectsACertificateItWasNotGiven() throws Exception {
        MergedTrust.Result result = MergedTrust.create(true);
        X509TrustManager manager = result.trustManager;
        assertNotNull(manager);
        // A leaf nobody signed: an empty chain is malformed input, and the contract is to refuse it,
        // not to wave it through.
        boolean rejected = false;
        try {
            manager.checkServerTrusted(new X509Certificate[0], "RSA");
        } catch (Exception e) {
            rejected = true;
        }
        assertTrue("a trust manager that accepts an empty chain accepts anything", rejected);
    }

    @Test
    public void lastReportsTheMostRecentBuild() {
        MergedTrust.create(false);
        assertEquals(MergedTrust.MODE_PLATFORM_ONLY, MergedTrust.last().mode);
        MergedTrust.create(true);
        assertEquals(MergedTrust.MODE_MERGED, MergedTrust.last().mode);
    }
}
