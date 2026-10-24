package com.aphex3k.eo1.trust;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a frame's certificate stores actually contain, and which shipped anchor is missing from them.
 *
 * <p>Written before anything that changes the device, on purpose. The diagnosis this feature answers is
 * "a frame cannot validate its Immich server", and there are four different reasons for that symptom —
 * a missing anchor, a cipher suite with nothing in common, a server certificate outside its own
 * validity window, and a frame whose clock is wrong — which look alike from where the operator is
 * standing and do not alike at all in what fixes them. A census that says which store holds which
 * subject, and how much of it has expired, is the step that stops the fix from being guessed at.
 *
 * <p>Read-only by construction: every method takes a directory and returns data. Nothing here can
 * write, and the class has no reference to adbd, so it is safe to run on a frame the operator has not
 * yet opted into having touched.
 *
 * <p>Free of {@code android.*} so the same code path runs in a JVM test against a directory of fixtures.
 */
public final class TrustCensus {

    /** Where a KitKat trust store keeps its anchors. Not a guess about this ROM: see AGENTS.md. */
    public static final String SYSTEM_STORE = "/system/etc/security/cacerts";
    /** Anchors an operator (or this app, with permission) added on top of the ROM's. */
    public static final String USER_ADDED_STORE = "/data/misc/keychain/cacerts-added";
    /** ROM anchors the framework has been told to ignore — an identical file here masks the original. */
    public static final String USER_REMOVED_STORE = "/data/misc/keychain/cacerts-removed";

    /** One store's inventory. */
    public static final class Store {
        public final String path;
        public final boolean readable;
        public final int total;
        public final int expired;
        public final int notYetValid;
        public final int unparseable;
        /** Subject hashes present, for membership tests against {@link RootBundle}. */
        public final Set<String> hashes;

        Store(String path, boolean readable, int total, int expired, int notYetValid, int unparseable,
              Set<String> hashes) {
            this.path = path;
            this.readable = readable;
            this.total = total;
            this.expired = expired;
            this.notYetValid = notYetValid;
            this.unparseable = unparseable;
            this.hashes = hashes;
        }

        /** A store the app could not even list is not an empty store; the difference is the report. */
        public boolean isKnown() {
            return readable;
        }
    }

    private TrustCensus() {
    }

    public static Store censusDirectory(File dir, Date now) {
        Set<String> hashes = new HashSet<String>();
        int total = 0;
        int expired = 0;
        int future = 0;
        int broken = 0;

        // listFiles(), not list()+new File(): a directory this uid cannot read returns null from
        // list() rather than throwing, and the difference between "no anchors" and "cannot look" is
        // the single most important thing this report says.
        File[] files = dir.listFiles();
        if (files == null) {
            return new Store(dir.getAbsolutePath(), false, 0, 0, 0, 0, hashes);
        }

        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (!file.isFile() || !SubjectHashOld.isStoreFileName(file.getName())) {
                continue;
            }
            total++;
            X509Certificate certificate = read(file);
            if (certificate == null) {
                // Includes the zero-length case, which is this platform's own delete tombstone and is
                // skipped by the framework without a word.
                broken++;
                continue;
            }
            hashes.add(file.getName().substring(0, 8));
            if (certificate.getNotAfter() != null && certificate.getNotAfter().before(now)) {
                expired++;
            }
            if (certificate.getNotBefore() != null && certificate.getNotBefore().after(now)) {
                future++;
            }
        }
        return new Store(dir.getAbsolutePath(), true, total, expired, future, broken, hashes);
    }

    /** Which shipped anchors this frame does not have. Empty is the goal state. */
    public static List<String> missingFromBundle(Store store) {
        List<String> missing = new ArrayList<String>();
        RootBundle.Entry[] entries = RootBundle.entries();
        for (int i = 0; i < entries.length; i++) {
            if (!store.hashes.contains(entries[i].subjectHash)) {
                missing.add(entries[i].subjectHash + " " + entries[i].subject);
            }
        }
        return missing;
    }

    /**
     * Sorts a TLS failure into the one of four causes it belongs to.
     *
     * <p>The classification is by exception type and message because that is everything the display
     * path has: an {@code SSLHandshakeException} arriving up through Retrofit carries no more detail
     * than its cause chain. A {@code null} return means "not a certificate problem" — a connect
     * timeout or a DNS failure must never be reported as though installing roots would help, because
     * that is how an operator ends up re-running an install against a dead network.
     */
    public static String classifyFailure(Throwable error) {
        // Two passes, and the order is the whole design. The specific reasons arrive as a cause several
        // links down — an SSLHandshakeException whose message says only "certificate verify failed"
        // wrapping a CertificateNotYetValidException that says why — so reading the outermost
        // exception first would report a clock problem as a missing anchor, which is the one
        // misdiagnosis that sends an operator off to reinstall roots.
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(java.util.Locale.US);
            if (message.indexOf("not yet valid") >= 0) {
                return "CERT_NOT_YET_VALID_CHECK_CLOCK";
            }
            if (message.indexOf("expired") >= 0) {
                return "CERT_EXPIRED";
            }
            // The handshake got far enough to negotiate and then found nothing usable. No root install
            // fixes this: it is a cipher-suite question, and the answer is server-side.
            if (message.indexOf("no shared cipher") >= 0 || message.indexOf("handshake failure") >= 0) {
                return "NO_SHARED_CIPHER";
            }
        }

        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(java.util.Locale.US);
            // instanceof, never a class-name comparison: the useful exceptions here are subclasses.
            if (t instanceof javax.net.ssl.SSLHandshakeException
                    || t instanceof java.security.cert.CertificateException) {
                return "TRUST_ANCHOR_MISSING";
            }
            if (t instanceof javax.net.ssl.SSLException) {
                if (message.indexOf("peer not authenticated") >= 0) {
                    return "TRUST_ANCHOR_MISSING";
                }
                return "TLS_PROTOCOL";
            }
        }
        return null;
    }

    /**
     * {@link #classifyFailure} as the suffix a failure report carries, so a frame says which gate it
     * hit instead of leaving an operator to infer it from exception text. Returns "" for a failure
     * that is not about trust at all — which is the more important half: a timeout that reads like a
     * certificate problem sends someone to reinstall roots against a dead radio.
     *
     * <p>Nothing here is remembered. The verdict belongs to the report it is appended to, not to a
     * process-wide fact a later, healthy connection would have to remember to clear.
     */
    public static String gateNote(Throwable error) {
        String verdict = classifyFailure(error);
        return verdict == null ? "" : " [TLS gate: " + verdict + "]";
    }

    private static X509Certificate read(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            java.security.cert.Certificate parsed = CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
            return parsed instanceof X509Certificate ? (X509Certificate) parsed : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // nothing actionable
                }
            }
        }
    }
}
