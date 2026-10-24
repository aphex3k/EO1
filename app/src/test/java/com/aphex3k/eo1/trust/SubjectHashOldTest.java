package com.aphex3k.eo1.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * JVM tests for {@link SubjectHashOld}.
 *
 * <p>The oracle is not a table of expected values someone typed in — it is the shipping certificate
 * store of the device this app runs on. The fixtures under {@code cacerts/} are copies of real files
 * from this repository's EO2 dump of {@code /system/etc/security/cacerts}, and each one's file name
 * was chosen by the platform's own {@code X509_NAME_hash_old}. An implementation that disagrees with
 * them would disagree with the device, and a disagreeing implementation does not fail: it writes a
 * valid certificate to a name nothing will ever look for.
 *
 * <p>Seven of the ROM's 156 anchors are committed here rather than all of them, because they are
 * 1.2 MB of duplicated data; the full 156-file sweep was run by hand when this rule was established
 * and passes. The set is chosen for shape, not volume: a multi-valued subject (two
 * {@code OU}s in one DN, where an implementation that hashed the whole {@code SubjectPublicKeyInfo}
 * or the printed DN would drift), the one genuine same-hash collision the ROM ships, and an expired
 * anchor.
 */
public class SubjectHashOldTest {

    @Rule
    public org.junit.rules.TemporaryFolder folder = new org.junit.rules.TemporaryFolder();

    private static final String[] FIXTURES = {
            "00673b5b.0",   // thawte Primary Root CA — multi-valued subject (2 OUs)
            "052e396b.0",
            "0d188d89.0",
            "10531352.0",
            "12d55845.0",   // past its notAfter: expiry must not change the name
            "33815e15.0",   // the ROM's one real collision pair, same subject, two CAs
            "33815e15.1",
    };

    private static X509Certificate certificate(String resource) throws Exception {
        InputStream in = SubjectHashOldTest.class.getClassLoader().getResourceAsStream("cacerts/" + resource);
        assertNotNull("fixture cacerts/" + resource + " is missing from test resources", in);
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } finally {
            in.close();
        }
    }

    /** The directory the fixtures were loaded from — one directory, so the collision index is real. */
    private static File fixtureDir() {
        java.net.URL url = SubjectHashOldTest.class.getClassLoader().getResource("cacerts/" + FIXTURES[0]);
        assertNotNull("fixture cacerts/" + FIXTURES[0] + " is missing from test resources", url);
        return new File(url.getFile()).getParentFile();
    }

    @Test
    public void hashesToTheFileNameTheDeviceItselfChose() throws Exception {
        for (int i = 0; i < FIXTURES.length; i++) {
            String name = FIXTURES[i];
            assertEquals("name of " + name, name.substring(0, 8),
                    SubjectHashOld.hash(certificate(name)));
        }
    }

    @Test
    public void isLowercaseEightHexDigits() throws Exception {
        String hash = SubjectHashOld.hash(certificate("00673b5b.0"));
        assertEquals(8, hash.length());
        assertEquals(hash, hash.toLowerCase(java.util.Locale.US));
        assertTrue(SubjectHashOld.isStoreFileName(hash + ".0"));
    }

    /**
     * The whole point of {@link SubjectHashOld#nameFor}: the ROM ships two different certificates
     * under one subject hash, and an installer that appends a literal {@code .0} — as the upstream
     * tool this feature is adapted from does — would overwrite one of them for every consumer on the
     * frame. Each half must land on its own index, and re-running must not add a third.
     */
    @Test
    public void collisionPairKeepsBothCertificates() throws Exception {
        File dir = fixtureDir();
        assertTrue("both halves of the collision pair must be present to mean anything",
                new File(dir, "33815e15.0").isFile() && new File(dir, "33815e15.1").isFile());

        assertEquals("33815e15.0", SubjectHashOld.nameFor(dir, certificate("33815e15.0")));
        assertEquals("33815e15.1", SubjectHashOld.nameFor(dir, certificate("33815e15.1")));
    }

    @Test
    public void namingAnAbsentCertificateTakesTheFreeIndex() throws Exception {
        // A store holding one member of the pair, and not the other: the missing one is named .1,
        // and the present one is named .0 again rather than duplicated at a new index.
        File sparse = new File(folder.newFolder(), "cacerts");
        copy("33815e15.0", sparse);
        assertEquals("33815e15.0", SubjectHashOld.nameFor(sparse, certificate("33815e15.0")));
        assertEquals("33815e15.1", SubjectHashOld.nameFor(sparse, certificate("33815e15.1")));
    }

    /**
     * A file that cannot be parsed must never be treated as free space. Overwriting it would let a
     * half-written install silently take over the name of a live CA — and the store has no way to
     * report the difference.
     */
    @Test
    public void unreadableEntryIsOccupiedNotFree() throws Exception {
        File dir = folder.newFolder("brokenstore");
        File corrupt = new File(dir, "00673b5b.0");
        java.io.FileOutputStream out = new java.io.FileOutputStream(corrupt);
        out.write("this is not a certificate".getBytes("UTF-8"));
        out.close();
        // Index 0 is occupied by something that is not this certificate, so it must move on — and
        // the name it returns has to be a legal store name for the copy command to be handed.
        String name = SubjectHashOld.nameFor(dir, certificate("00673b5b.0"));
        assertEquals("00673b5b.1", name);
        assertTrue(SubjectHashOld.isStoreFileName(name));
    }

    @Test
    public void rejectsAnythingThatIsNotAStoreFileName() {
        assertFalse(SubjectHashOld.isStoreFileName(null));
        assertFalse(SubjectHashOld.isStoreFileName(""));
        assertFalse(SubjectHashOld.isStoreFileName("00673b5b"));
        assertFalse(SubjectHashOld.isStoreFileName("00673B5B.0"));       // uppercase is not this rule
        assertFalse(SubjectHashOld.isStoreFileName("00673b5b/../x"));    // no traversal, ever
        assertFalse(SubjectHashOld.isStoreFileName("00673b5b.0; rm -rf /system"));
        assertFalse(SubjectHashOld.isStoreFileName("a;rm -rf /"));
        assertTrue(SubjectHashOld.isStoreFileName("00673b5b.12"));
    }

    /** One fixture, copied into a writable directory so the store being probed is a real store. */
    private void copy(String resource, File intoDir) throws Exception {
        if (!intoDir.isDirectory()) {
            assertTrue(intoDir.mkdirs());
        }
        InputStream in = SubjectHashOldTest.class.getClassLoader().getResourceAsStream("cacerts/" + resource);
        java.io.FileOutputStream out = new java.io.FileOutputStream(new File(intoDir, resource));
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.close();
        in.close();
    }
}
