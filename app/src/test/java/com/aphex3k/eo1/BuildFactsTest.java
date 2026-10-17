package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/** The formatting half of the About tab: what a cert and a build stamp turn into on screen. */
public class BuildFactsTest {

    private static final String FIXTURES = "src/test/resources/update-fixtures";

    private X509Certificate loadCert(String name) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        File file = new File(FIXTURES, name);
        try (FileInputStream in = new FileInputStream(file)) {
            return (X509Certificate) factory.generateCertificate(in);
        }
    }

    @Test
    public void fingerprintIsLowercaseHexOfTheDer() throws Exception {
        X509Certificate cert = loadCert("cert-a.pem");
        String hex = BuildFacts.fingerprintHex(cert.getEncoded());
        assertEquals(64, hex.length());
        assertTrue("expected lowercase hex, got " + hex, hex.matches("[0-9a-f]{64}"));
    }

    @Test
    public void groupedFingerprintSurvivesAWidthLimit() throws Exception {
        X509Certificate cert = loadCert("cert-a.pem");
        String hex = BuildFacts.fingerprintHex(cert.getEncoded());
        String grouped = BuildFacts.groupHex(hex);

        // Wrapping needs whitespace: a single 63-character run of hex never breaks in a TextView.
        assertTrue(grouped, grouped.contains(" "));
        for (String chunk : grouped.split(" ")) {
            assertTrue(chunk, chunk.length() <= 11);
            assertTrue(chunk, chunk.matches("[0-9a-f]{2}(:[0-9a-f]{2}){0,3}"));
        }
        assertEquals(hex, grouped.replace(":", "").replace(" ", ""));
    }

    @Test
    public void unsignedComesBackAsUnsigned() {
        assertEquals("none (unsigned build)", BuildFacts.keyVerdict("", null, "aa"));
    }

    @Test
    public void blankExpectedFingerprintIsNotCalledAMismatch() {
        assertEquals("not checked (no expected fingerprint in this build)",
                BuildFacts.keyVerdict("aabb", null, ""));
        assertEquals("not checked (no expected fingerprint in this build)",
                BuildFacts.keyVerdict("aabb", null, null));
    }

    @Test
    public void expectedFingerprintIsComparedCaseInsensitively() {
        assertEquals("matches the release key", BuildFacts.keyVerdict("aabb", null, "AABB"));
        assertEquals("MISMATCH - not the expected release key", BuildFacts.keyVerdict("aabb", null, "ccdd"));
    }

    @Test
    public void theBakedReleaseFingerprintIsHexAndNotLeftBlank() {
        assertTrue("fill in BuildFacts.RELEASE_CERT_SHA256",
                BuildFacts.RELEASE_CERT_SHA256.matches("[0-9a-f]{64}"));
    }

    @Test
    public void debugKeyIsReportedInsteadOfCompared() {
        assertTrue(BuildFacts.isDebugKey("CN=Android Debug,O=Android,C=US"));
        assertFalse(BuildFacts.isDebugKey("CN=EO1 Release,O=Electric Objects,C=US"));
        assertFalse(BuildFacts.isDebugKey(null));
    }

    @Test
    public void certValidityIsClassifiedAgainstNow() throws Exception {
        X509Certificate cert = loadCert("cert-a.pem");
        long start = cert.getNotBefore().getTime();
        long end = cert.getNotAfter().getTime();
        assertEquals("not yet valid", BuildFacts.validityStatus(cert, start - 1000L));
        assertEquals("valid", BuildFacts.validityStatus(cert, (start + end) / 2));
        assertEquals("expired", BuildFacts.validityStatus(cert, end + 1000L));
    }

    @Test
    public void subjectOfNoCertIsStated() {
        assertEquals("n/a", BuildFacts.subject(null));
    }

    @Test
    public void stampLosesSecondsAndKeepsItsZone() {
        assertEquals("2026-10-17 12:09 -07:00", BuildFacts.formatStamp("2026-10-17T12:09:37-07:00"));
        assertEquals("2026-10-17 12:09 +00:00", BuildFacts.formatStamp("2026-10-17T12:09:37+00:00"));
        assertEquals("2026-10-17 12:09 Z", BuildFacts.formatStamp("2026-10-17T12:09:37Z"));
    }

    @Test
    public void aStampThatWasNeverRecordedSaysSo() {
        assertEquals("unknown", BuildFacts.formatStamp("unknown"));
        assertEquals("unknown", BuildFacts.formatStamp(""));
        assertEquals("unknown", BuildFacts.formatStamp(null));
        assertEquals("2026-10-17", BuildFacts.formatStamp("2026-10-17"));
    }

    @Test
    public void dirtyTreeIsNamedOnTheSourceLine() {
        assertEquals("8de9c19 (uncommitted changes)", BuildFacts.source("8de9c19", "true"));
        assertEquals("8de9c19 (clean)", BuildFacts.source("8de9c19", "false"));
        assertEquals("unknown (tree state unknown)", BuildFacts.source("unknown", "unknown"));
    }
}
