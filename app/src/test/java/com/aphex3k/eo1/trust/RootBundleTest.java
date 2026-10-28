package com.aphex3k.eo1.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Every shipped anchor agrees with itself.
 *
 * <p>{@link RootBundle} is generated, and the value of generating it is exactly this test: the bytes
 * and the name are both in the file, so a build can prove they still describe the same certificate.
 * The generator checks the same thing with {@code openssl} at authoring time; this is the copy that
 * runs on a machine that never had the input, and it catches the two ways the file could rot — an
 * entry hand-edited after generation, or a root whose date has simply run out.
 *
 * <p>A mismatch here is not a cosmetic problem. A bundled anchor under the wrong hash is a name the
 * trust path will never probe, so it is inert on the frame while looking, in every listing, like it
 * was installed.
 *
 * <p>Expiry is deliberately the one property asserted softly. The bundle is now whatever pinned
 * {@code certs/roots/debian.json} said a distribution trusted, and a distribution store always contains
 * something on its way out; a hard assertion here would redden {@code ./gradlew test} on a date, across
 * every branch and every old commit, with no code changed and nothing for anyone to fix in the test.
 * The place that <em>should</em> refuse is a release, and {@code publish-update.sh} does exactly that
 * before it builds anything. So this reports, and the gate is the guard.
 */
public class RootBundleTest {

    /**
     * Far below any distribution's store and far above the ten this repo used to hand-curate, so a
     * truncated ingest trips it while a future Mozilla reduction — Debian sid went 150 → 120 between
     * suites — does not turn this into another calendar-date fuse.
     */
    private static final int PLAUSIBLE_BUNDLE_FLOOR = 50;

    @Test
    public void everyEntryRehashesToItsEmittedName() throws Exception {
        RootBundle.Entry[] entries = RootBundle.entries();
        for (int i = 0; i < entries.length; i++) {
            X509Certificate certificate = entries[i].certificate();
            assertEquals("entry " + i + " (" + entries[i].subject + ") is filed under a name that is not its own",
                    entries[i].subjectHash, SubjectHashOld.hash(certificate));
        }
    }

    @Test
    public void expiredAnchorsAreReported() throws Exception {
        RootBundle.Entry[] entries = RootBundle.entries();
        Date now = new Date();
        List<String> lapsed = new ArrayList<String>();
        for (int i = 0; i < entries.length; i++) {
            X509Certificate certificate = entries[i].certificate();
            assertNotNull("entry " + i + " has no notAfter", certificate.getNotAfter());
            if (certificate.getNotAfter().before(now)) {
                lapsed.add(entries[i].subject + " (" + entries[i].subjectHash + ") since "
                        + certificate.getNotAfter());
            }
            assertTrue("shipped anchor " + entries[i].subject + " is not valid until "
                            + certificate.getNotBefore(),
                    certificate.getNotBefore() == null || !certificate.getNotBefore().after(now));
        }
        if (!lapsed.isEmpty()) {
            System.err.println("WARN: " + lapsed.size() + " of " + entries.length
                    + " shipped anchors are past their own notAfter and are inert on every frame:");
            for (int i = 0; i < lapsed.size(); i++) {
                System.err.println("WARN:   " + lapsed.get(i));
            }
            System.err.println("WARN: run ./update-roots.sh and commit the result. A release would be"
                    + " refused over this; a test should not be, because these dates pass whether or"
                    + " not anyone has changed the code.");
        }
    }

    /**
     * Roots are self-signed, so an entry whose issuer does not equal its subject is not a root and
     * does not belong in a bundle whose stated purpose is to extend trust anchors. Bundling an
     * intermediate would also silently depend on the server to send it.
     */
    @Test
    public void everyEntryIsASelfSignedRoot() throws Exception {
        RootBundle.Entry[] entries = RootBundle.entries();
        for (int i = 0; i < entries.length; i++) {
            X509Certificate certificate = entries[i].certificate();
            assertEquals("entry " + entries[i].subject + " is not self-signed, so it is not a root",
                    certificate.getSubjectX500Principal(), certificate.getIssuerX500Principal());
        }
    }

    @Test
    public void theBundleCoversWhatItClaims() {
        // A count check with a reason attached: the failure worth guarding is the silent one where an
        // entry is deleted, renamed, or dropped by a generator change — or where an ingest took a
        // partial directory and every check downstream of it passed anyway.
        assertTrue("bundle unexpectedly shrank to " + RootBundle.size()
                        + " anchors — regenerate from certs/roots and check what the ingest extracted",
                RootBundle.size() >= PLAUSIBLE_BUNDLE_FLOOR);
        boolean isrg = false;
        RootBundle.Entry[] entries = RootBundle.entries();
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].subject.indexOf("ISRG Root X1") >= 0) {
                isrg = true;
                assertEquals("6187b673", entries[i].subjectHash);
            }
        }
        assertTrue("ISRG Root X1 must be in the bundle; without it no Let's Encrypt host validates", isrg);
    }

    @Test
    public void entriesAreDistinct() throws Exception {
        // Parsed once, not once per pair: this bundle carries a whole distribution's store, and the
        // pairwise form above was ~7,000 CertificateFactory calls on a set of this size.
        RootBundle.Entry[] entries = RootBundle.entries();
        X509Certificate[] certificates = new X509Certificate[entries.length];
        for (int i = 0; i < entries.length; i++) {
            certificates[i] = entries[i].certificate();
        }
        for (int i = 0; i < certificates.length; i++) {
            for (int j = i + 1; j < certificates.length; j++) {
                assertTrue("bundle entries " + i + " and " + j + " are the same certificate",
                        !certificates[i].equals(certificates[j]));
            }
        }
    }
}
