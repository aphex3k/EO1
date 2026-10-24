package com.aphex3k.eo1.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.Date;
import java.util.List;

/**
 * The census is the part of this feature that can be trusted to say something true, so it is tested
 * against real ROM files rather than synthetic ones.
 *
 * <p>The distinction that matters most is between a store that is empty and a store the app was not
 * allowed to read. On this platform {@code File.listFiles()} returns {@code null} for the second case
 * and an empty array for the first, and reporting "0 anchors" for a directory that was never opened is
 * how a diagnostic ends up arguing with an operator about a frame that is fine.
 */
public class TrustCensusTest {

    @Rule
    public org.junit.rules.TemporaryFolder folder = new org.junit.rules.TemporaryFolder();

    private static File fixtureDir() {
        URL url = TrustCensusTest.class.getClassLoader().getResource("cacerts/00673b5b.0");
        assertNotNull("cacerts fixtures are missing", url);
        return new File(url.getFile()).getParentFile();
    }

    @Test
    public void countsEveryWellNamedEntry() {
        TrustCensus.Store store = TrustCensus.censusDirectory(fixtureDir(), new Date());
        assertTrue("the fixture directory should be readable", store.isKnown());
        assertEquals(7, store.total);
        assertEquals("none of these is a malformed file", 0, store.unparseable);
        assertEquals("the collision pair shares a hash, so six names", 6, store.hashes.size());
        assertTrue("at least one fixture is expired (12d55845)", store.expired >= 1);
        assertEquals(0, store.notYetValid);
    }

    @Test
    public void anUnreadableDirectoryIsNotAnEmptyOne() {
        TrustCensus.Store store =
                TrustCensus.censusDirectory(new File("/proc/1/root/definitely-not-here"), new Date());
        assertFalse("must report cannot-look, not zero", store.isKnown());
        assertEquals(0, store.total);
        // And the report this produces must not read as "this frame is missing every root".
        List<String> missing = TrustCensus.missingFromBundle(store);
        assertEquals(RootBundle.size(), missing.size());
    }

    /**
     * The claim a census exists to answer: which shipped anchors does <em>this</em> frame not have.
     * Against an EO2-shaped store the answer includes ISRG Root X1, which is the whole reason an Immich
     * server on a current Let's Encrypt certificate stopped validating.
     */
    @Test
    public void reportsWhichShippedAnchorsAreAbsent() {
        TrustCensus.Store store = TrustCensus.censusDirectory(fixtureDir(), new Date());
        List<String> missing = TrustCensus.missingFromBundle(store);
        boolean isrgMissing = false;
        for (int i = 0; i < missing.size(); i++) {
            if (missing.get(i).indexOf("ISRG Root X1") >= 0) {
                isrgMissing = true;
            }
        }
        assertTrue("the fixtures ship no ISRG anchor, so it must be reported missing", isrgMissing);

        // Anything the store does hold must not appear in the missing list, which is reported as
        // "<hash> <subject>" strings rather than bare hashes.
        for (int i = 0; i < RootBundle.size(); i++) {
            String hash = RootBundle.entries()[i].subjectHash;
            if (store.hashes.contains(hash)) {
                for (int j = 0; j < missing.size(); j++) {
                    assertFalse("present anchor reported missing: " + hash,
                            missing.get(j).startsWith(hash + " "));
                }
            }
        }
    }

    @Test
    public void ignoresFilesThatAreNotStoreEntries() throws Exception {
        File dir = folder.newFolder("mixedstore");
        // A stray README and a wrongly-named certificate: neither counts, and neither may be parsed
        // into the totals, because a store directory accumulates junk on a real device.
        write(new File(dir, "README"), "not a certificate");
        write(new File(dir, "00673b5b.txt"), "not a certificate");
        TrustCensus.Store store = TrustCensus.censusDirectory(dir, new Date());
        assertTrue(store.isKnown());
        assertEquals(0, store.total);
    }

    /**
     * The four causes of "cannot connect" must not collapse into one message. A network timeout in
     * particular must never be classified as a trust problem — that is how an operator ends up
     * reinstalling certificates against a dead radio.
     */
    @Test
    public void classifiesEachFailureCauseApart() {
        assertEquals("TRUST_ANCHOR_MISSING", TrustCensus.classifyFailure(
                new javax.net.ssl.SSLHandshakeException(
                        "unable to find valid certification path to requested target")));
        assertEquals("TRUST_ANCHOR_MISSING", TrustCensus.classifyFailure(
                new javax.net.ssl.SSLException("peer not authenticated")));
        assertEquals("NO_SHARED_CIPHER", TrustCensus.classifyFailure(
                new javax.net.ssl.SSLException("SSL handshake aborted: no shared cipher")));
        assertEquals("CERT_NOT_YET_VALID_CHECK_CLOCK", TrustCensus.classifyFailure(
                new java.security.cert.CertificateException(
                        "java.security.cert.CertificateNotYetValidException: Certificate not yet valid")));
        assertEquals("CERT_EXPIRED", TrustCensus.classifyFailure(
                new javax.net.ssl.SSLHandshakeException("certificate has expired")));
    }

    @Test
    public void saysNothingAboutAFailureThatIsNotAboutTrust() {
        assertNull("a timeout is not a certificate problem",
                TrustCensus.classifyFailure(new java.net.SocketTimeoutException("timeout")));
        assertNull(TrustCensus.classifyFailure(new java.io.IOException("connection refused")));
        assertNull(TrustCensus.classifyFailure(null));
    }

    /** A failure wrapped the way Retrofit wraps one still has to be classified. */
    @Test
    public void looksThroughTheCauseChain() {
        Exception wrapped = new RuntimeException("wrapped",
                new javax.net.ssl.SSLHandshakeException("unable to find valid certification path"));
        assertEquals("TRUST_ANCHOR_MISSING", TrustCensus.classifyFailure(wrapped));
    }

    /**
     * The note is what a backend record and an update's {@code lastError} carry, so both halves
     * matter: a certificate failure must name its gate, and a network failure must stay silent.
     * A timeout that arrives wearing a "[TLS gate: ...]" suffix sends an operator to reinstall
     * roots against a dead radio.
     */
    @Test
    public void notesOnlyTheFailuresThatAreAboutTrust() {
        assertEquals(" [TLS gate: NO_SHARED_CIPHER]", TrustCensus.gateNote(
                new java.io.IOException("SSL handshake aborted: no shared cipher")));
        assertEquals(" [TLS gate: CERT_NOT_YET_VALID_CHECK_CLOCK]", TrustCensus.gateNote(
                new javax.net.ssl.SSLException(new java.security.cert.CertificateException(
                        "Certificate not yet valid"))));
        assertEquals("a timeout is nobody's certificate", "",
                TrustCensus.gateNote(new java.net.SocketTimeoutException("timeout")));
        assertEquals("", TrustCensus.gateNote(new java.io.IOException("connection refused")));
        assertEquals("", TrustCensus.gateNote(null));
    }

    private static void write(File file, String text) throws Exception {
        java.io.FileOutputStream out = new java.io.FileOutputStream(file);
        out.write(text.getBytes("UTF-8"));
        out.close();
    }
}
