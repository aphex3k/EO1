package com.aphex3k.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * JVM tests for {@link ApkSignatureVerifier} against the pre-generated archives in
 * {@code src/test/resources/update-fixtures/} (see that directory's README for how the
 * fixtures are made). No Android framework classes are involved.
 */
public class ApkSignatureVerifierTest {

    private static final String FIXTURES = "src/test/resources/update-fixtures";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File fixture(String name) {
        File file = new File(FIXTURES, name);
        if (!file.isFile()) {
            throw new AssertionError("missing fixture " + file.getAbsolutePath()
                    + " — regenerate per " + FIXTURES + "/README.md");
        }
        return file;
    }

    private X509Certificate loadCert(String name) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        FileInputStream in = new FileInputStream(fixture(name));
        try {
            return (X509Certificate) factory.generateCertificate(in);
        } finally {
            in.close();
        }
    }

    @Test
    public void signedSha256_isValid() {
        ApkSignatureVerifier.Result r = ApkSignatureVerifier.verify(fixture("signed-sha256.zip"));
        assertTrue(r.valid);
        assertEquals("", r.reason);
        assertEquals(1, r.signerCertificates.length);
        assertTrue(r.signerCertificates[0].getSubjectX500Principal().getName()
                .contains("EO1 Fixture A"));
    }

    @Test
    public void signedSha256_matchesExpectedSigner() throws Exception {
        X509Certificate certA = loadCert("cert-a.pem");
        ApkSignatureVerifier.Result r = ApkSignatureVerifier.verify(fixture("signed-sha256.zip"), certA);
        assertTrue(r.valid);
        assertEquals(1, r.signerCertificates.length);
    }

    @Test
    public void signedSha256_rejectsDifferentSigner() throws Exception {
        X509Certificate certB = loadCert("cert-b.pem");
        ApkSignatureVerifier.Result r = ApkSignatureVerifier.verify(fixture("signed-sha256.zip"), certB);
        assertFalse(r.valid);
        assertEquals("signer-cert-mismatch", r.reason);
        // The certificate was still extracted; only the match failed.
        assertEquals(1, r.signerCertificates.length);
        assertTrue(r.signerCertificates[0].getSubjectX500Principal().getName()
                .contains("EO1 Fixture A"));
    }

    @Test
    public void signedSha1_isValid() {
        ApkSignatureVerifier.Result r = ApkSignatureVerifier.verify(fixture("signed-sha1.zip"));
        assertTrue(r.valid);
        assertEquals(1, r.signerCertificates.length);
        assertTrue(r.signerCertificates[0].getSubjectX500Principal().getName()
                .contains("EO1 Fixture A"));
    }

    @Test
    public void signedKeystoreB_isValidAndMatchesCertB() throws Exception {
        ApkSignatureVerifier.Result plain = ApkSignatureVerifier.verify(fixture("signed-keystore-b.zip"));
        assertTrue(plain.valid);
        assertTrue(plain.signerCertificates[0].getSubjectX500Principal().getName()
                .contains("EO1 Fixture B"));

        X509Certificate certB = loadCert("cert-b.pem");
        assertTrue(ApkSignatureVerifier.verify(fixture("signed-keystore-b.zip"), certB).valid);
    }

    @Test
    public void signedKeystoreB_rejectsCertA() throws Exception {
        X509Certificate certA = loadCert("cert-a.pem");
        ApkSignatureVerifier.Result r = ApkSignatureVerifier.verify(fixture("signed-keystore-b.zip"), certA);
        assertFalse(r.valid);
        assertEquals("signer-cert-mismatch", r.reason);
    }

    @Test
    public void unsigned_isNoSignature() {
        ApkSignatureVerifier.Result r = ApkSignatureVerifier.verify(fixture("unsigned.zip"));
        assertFalse(r.valid);
        assertEquals("no-signature", r.reason);
        assertEquals(0, r.signerCertificates.length);
    }

    @Test
    public void truncated_isZipError() {
        ApkSignatureVerifier.Result r = ApkSignatureVerifier.verify(fixture("truncated.zip"));
        assertFalse(r.valid);
        assertEquals("zip-error", r.reason);
    }

    @Test
    public void missingFile_isZipError() {
        ApkSignatureVerifier.Result r = ApkSignatureVerifier.verify(new File(folder.getRoot(), "nope.zip"));
        assertFalse(r.valid);
        assertEquals("zip-error", r.reason);
    }

    @Test
    public void tamperedEntry_failsEntryDigest() throws Exception {
        File tampered = new File(folder.getRoot(), "tampered.zip");
        rewriteWithTamperedHello(fixture("signed-sha256.zip"), tampered);

        ApkSignatureVerifier.Result r = ApkSignatureVerifier.verify(tampered);
        assertFalse(r.valid);
        assertEquals("entry-digest-mismatch:hello.txt", r.reason);
    }

    /** Copies the zip, replacing {@code hello.txt} with different content (signature untouched). */
    private static void rewriteWithTamperedHello(File source, File destination) throws Exception {
        byte[] replacement = "Hello EO1 fixture - doctored\n".getBytes("UTF-8");
        ZipOutputStream out = new ZipOutputStream(new java.io.FileOutputStream(destination));
        try {
            ZipInputStream in = new ZipInputStream(new FileInputStream(source));
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                if ("hello.txt".equals(entry.getName())) {
                    out.write(replacement);
                } else {
                    byte[] buffer = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                }
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }
}
