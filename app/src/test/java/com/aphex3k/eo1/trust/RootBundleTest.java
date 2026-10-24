package com.aphex3k.eo1.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Every shipped anchor agrees with itself.
 *
 * <p>{@link RootBundle} is generated, and the value of generating it is exactly this test: the bytes
 * and the name are both in the file, so a build can prove they still describe the same certificate.
 * The generator checks the same thing with {@code openssl} at authoring time; this is the copy that
 * runs on a machine that never had the input, and it catches the two ways the file could rot — an
 * entry hand-edited after generation, or a root whose date has simply run out.
 *
 * <p>A mismatch here is not a cosmetic problem. A bundled anchor under the wrong hash is provisioned
 * to a filename the trust path will never probe, so it is inert on the frame while looking, in every
 * listing, like it was installed.
 */
public class RootBundleTest {

    @Test
    public void everyEntryRehashesToItsEmittedName() throws Exception {
        for (int i = 0; i < RootBundle.size(); i++) {
            RootBundle.Entry entry = RootBundle.entries()[i];
            X509Certificate certificate = entry.certificate();
            assertEquals("entry " + i + " (" + entry.subject + ") is filed under a name that is not its own",
                    entry.subjectHash, SubjectHashOld.hash(certificate));
        }
    }

    @Test
    public void noAnchorIsExpiredOrNotYetValid() throws Exception {
        Date now = new Date();
        for (int i = 0; i < RootBundle.size(); i++) {
            RootBundle.Entry entry = RootBundle.entries()[i];
            X509Certificate certificate = entry.certificate();
            assertNotNull("entry " + i + " has no notAfter", certificate.getNotAfter());
            assertTrue("shipped anchor " + entry.subject + " expired " + certificate.getNotAfter()
                            + " — regenerate the bundle from a current root",
                    !certificate.getNotAfter().before(now));
            assertTrue("shipped anchor " + entry.subject + " is not valid until "
                            + certificate.getNotBefore(),
                    certificate.getNotBefore() == null || !certificate.getNotBefore().after(now));
        }
    }

    /**
     * Roots are self-signed, so an entry whose issuer does not equal its subject is not a root and
     * does not belong in a bundle whose stated purpose is to extend trust anchors. Bundling an
     * intermediate would also silently depend on the server to send it.
     */
    @Test
    public void everyEntryIsASelfSignedRoot() throws Exception {
        for (int i = 0; i < RootBundle.size(); i++) {
            RootBundle.Entry entry = RootBundle.entries()[i];
            X509Certificate certificate = entry.certificate();
            assertEquals("entry " + entry.subject + " is not self-signed, so it is not a root",
                    certificate.getSubjectX500Principal(), certificate.getIssuerX500Principal());
        }
    }

    @Test
    public void theBundleCoversWhatItClaims() {
        // A count check with a reason attached: the failure worth guarding is the silent one where an
        // entry is deleted, renamed, or dropped by a generator change.
        assertTrue("bundle unexpectedly shrank to " + RootBundle.size()
                        + " anchors — ISRG Root X1 is the one that fixes Let's Encrypt",
                RootBundle.size() >= 8);
        boolean isrg = false;
        for (int i = 0; i < RootBundle.size(); i++) {
            if (RootBundle.entries()[i].subject.indexOf("ISRG Root X1") >= 0) {
                isrg = true;
                assertEquals("6187b673", RootBundle.entries()[i].subjectHash);
            }
        }
        assertTrue("ISRG Root X1 must be in the bundle; without it no Let's Encrypt host validates", isrg);
    }

    @Test
    public void entriesAreDistinct() throws Exception {
        for (int i = 0; i < RootBundle.size(); i++) {
            for (int j = i + 1; j < RootBundle.size(); j++) {
                assertTrue("bundle entries " + i + " and " + j + " are the same certificate",
                        !RootBundle.entries()[i].certificate().equals(RootBundle.entries()[j].certificate()));
            }
        }
    }
}
