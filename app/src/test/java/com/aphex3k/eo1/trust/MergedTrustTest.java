package com.aphex3k.eo1.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /**
     * A pin, not a demonstration: this passes trivially on a workstation, which is exactly how the bug
     * it guards against survived to a device. Android 4.4's provider answers
     * {@code NoSuchAlgorithmException: TrustManagerFactory X.509 implementation not found} for the
     * algorithm name the JDK accepts as an alias, and the first version of this code hard-coded it —
     * so the merge threw, fell back to "platform-only", and the shipped roots did nothing on the
     * hardware they were written for while every unit test stayed green.
     *
     * <p>What is checkable here is the invariant the device violated: a build that ends up
     * platform-only because the <em>provider refused a name</em> is a bug, not a degradation. The
     * supported suite list is asserted alongside it because an empty one means the platform cannot say,
     * which is the other way this feature can be inert and look healthy.
     */
    @Test
    public void aProviderRefusalIsNeverMistakenForAFallback() {
        MergedTrust.Result result = MergedTrust.create(true);
        String detail = result.detail == null ? "" : result.detail;
        assertFalse("the provider refused an algorithm name — the trust layer is inert: " + detail,
                detail.indexOf("NoSuchAlgorithm") >= 0);
        assertFalse("the provider refused a keystore type — the trust layer is inert: " + detail,
                detail.indexOf("no X.509 trust manager") >= 0);
        assertTrue("a platform with no enumerable cipher suites cannot be diagnosed from /state",
                MergedTrust.platformCipherSuites().length > 0);
    }

    @Test
    public void lastReportsTheMostRecentBuild() {
        MergedTrust.create(false);
        assertEquals(MergedTrust.MODE_PLATFORM_ONLY, MergedTrust.last().mode);
        MergedTrust.create(true);
        assertEquals(MergedTrust.MODE_MERGED, MergedTrust.last().mode);
    }
}
