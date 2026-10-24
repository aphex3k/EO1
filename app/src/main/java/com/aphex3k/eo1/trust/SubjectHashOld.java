package com.aphex3k.eo1.trust;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * The file-naming rule of an Android {@code cacerts} directory: OpenSSL's
 * {@code X509_NAME_hash_old}, which is what {@code openssl x509 -subject_hash_old} prints and what
 * a KitKat-era trust store names its files by.
 *
 * <p>The rule is MD5 over the DER encoding of the certificate's <em>SubjectName</em> — including the
 * name's own tag-and-length header, not just its contents — take the first four bytes, read them as a
 * little-endian unsigned 32-bit integer, print eight lowercase hex digits. That is verifiable against
 * the ROM rather than assumed: all 156 files in this project's EO2 system-store dump hash to their
 * own basename, and none of them hashes to {@code subject_hash} (the SHA-1 variant used since OpenSSL
 * 1.1), so picking the wrong one of those two produces a store where every name is wrong. The ROM's
 * own {@code /system/framework/conscrypt.jar} carries both {@code X509_NAME_hash_old} and
 * {@code md5_hash}, which is the platform saying the same thing.
 *
 * <p>Getting this right is not cosmetic. The framework reaches an anchor on the TLS path by probing
 * {@code hash + '.' + index} for index 0, 1, 2 … and stopping at the first index that is not there —
 * it does not scan the directory for anything that parses. A certificate that is valid, readable and
 * correctly formatted but filed under a name that is not its own hash is therefore <em>invisible to
 * validation while still being listed by the Settings UI</em>, which is the worst possible failure
 * mode for this feature: the install reports success, the frame still cannot connect, and the file
 * that proves it looks fine.
 *
 * <p>Conscrypt on this ROM does expose neither {@code X509_NAME_hash_old} nor a Java binding for it,
 * so the digest is computed here from primitives every API 19 device has.
 */
public final class SubjectHashOld {

    /** Suffix index ceiling for {@link #nameFor}: no real store carries this many same-subject certs. */
    static final int MAX_INDEX = 64;

    private SubjectHashOld() {
    }

    /**
     * @return the eight hex digits this certificate's subject hashes to, as the store would name it
     */
    public static String hash(X509Certificate certificate) {
        byte[] digest = md5(certificate.getSubjectX500Principal().getEncoded());
        long littleEndian = (digest[0] & 0xffL)
                | ((digest[1] & 0xffL) << 8)
                | ((digest[2] & 0xffL) << 16)
                | ((digest[3] & 0xffL) << 24);
        return String.format(java.util.Locale.US, "%08x", Long.valueOf(littleEndian));
    }

    /**
     * True when {@code name} is a store file name this code is prepared to act on — eight lowercase
     * hex digits, a dot, one or more decimal digits. The provisioner validates every name it is about
     * to hand to {@code cp} or {@code rm} with this, because those names are the only part of a store
     * command line that is not a compile-time constant, and {@code File.list()} on a directory owned
     * by root is not a source of trusted strings.
     */
    public static boolean isStoreFileName(String name) {
        if (name == null || name.length() < 10 || name.charAt(8) != '.') {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            char c = name.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        for (int i = 9; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The name this certificate occupies — or should occupy — in {@code storeDir}.
     *
     * <p>Absorbing a collision instead of causing one is the reason this exists. Two different
     * certificates can share a subject, and therefore a hash, and the ROM ships a pair of them
     * ({@code 33815e15.0} and {@code 33815e15.1}, two CAs under one DN). Writing to
     * {@code <hash>.0} unconditionally — which is all the upstream tool this feature is modelled on
     * does, because it appends a literal {@code ".0"} — would quietly retire whichever CA happened to
     * be there first, for every TLS consumer on the frame, in a build whose stated purpose is to add
     * trust rather than to remove any.
     *
     * <p>So: return the index that already holds <em>this</em> certificate, which makes the operation
     * idempotent and stops a re-run from leaving a trail of duplicate {@code .1}, {@code .2} entries;
     * otherwise the lowest index that holds nothing.
     *
     * @return a name ending in {@code .<index>}, or {@code null} when every index up to
     *         {@link #MAX_INDEX} is occupied by a different certificate
     */
    public static String nameFor(File storeDir, X509Certificate certificate) {
        String hash = hash(certificate);
        for (int index = 0; index < MAX_INDEX; index++) {
            File candidate = new File(storeDir, hash + '.' + index);
            if (!candidate.isFile()) {
                return hash + '.' + index;
            }
            if (isSameCertificate(candidate, certificate)) {
                return candidate.getName();
            }
        }
        return null;
    }

    /**
     * Byte equality of the stored certificate against the one being installed. Comparison is on the
     * DER, not on {@code equals()}: what decides whether a store entry is the same certificate for
     * the purposes of "do not write it twice" is the encoded form the trust path will hand to the
     * chain builder.
     */
    private static boolean isSameCertificate(File stored, X509Certificate certificate) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(stored);
            Certificate parsed = CertificateFactory.getInstance("X.509").generateCertificate(in);
            return parsed instanceof X509Certificate
                    && java.util.Arrays.equals(parsed.getEncoded(), certificate.getEncoded());
        } catch (IOException e) {
            // Unreadable or half-written: treat as occupied, never as free. Overwriting a file the
            // app cannot read is how a truncated install becomes a silent shadowing of a live CA.
            return false;
        } catch (CertificateException e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing actionable
                }
            }
        }
    }

    private static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is mandated of every JCA provider, including this platform's. A device without it
            // has bigger problems than this store, so say so loudly rather than naming certs wrong.
            throw new IllegalStateException("no MD5 in this provider", e);
        }
    }
}
