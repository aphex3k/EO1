package com.aphex3k.update;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.nio.charset.Charset;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure-Java JAR/APK signature verification (no Android imports — runs on the JVM in tests).
 *
 * <p>Implements the JAR signature chain directly on top of {@link ZipFile} and
 * {@code java.security}, so it works on API 19 where the platform's APK signature APIs are
 * unavailable or unreliable:
 * <ol>
 *   <li>recomputes every {@code META-INF/MANIFEST.MF} per-entry digest and compares it against
 *       the manifest's {@code Digest-Manifest} (modern) or {@code Hash-MD5} (legacy)
 *       attribute;</li>
 *   <li>digests the raw {@code META-INF/MANIFEST.MF} bytes and compares them against the
 *       {@code *-Digest-Manifest} attribute of the signature file (a tampered manifest is
 *       caught here even when all entry digests match);</li>
 *   <li>recomputes every per-entry digest in the signature file against the zip entries;</li>
 *   <li>verifies the PKCS#7 signature; the signature algorithm is read from the SignerInfo
 *       OID, with a SHA256/SHA1/MD5-with-RSA probe as fallback for unusual jarsigner output.
 *       The signed byte sequence is the DER {@code SET OF} re-encoding of the signed
 *       attributes (byte-sorted attribute TLVs under tag 0x31) — what the JDK signer signs
 *       and {@code SignerInfo.verify} checks — not the {@code [0]} wrapper bytes stored in
 *       the file; the embedded {@code messageDigest} attribute is cross-checked against the
 *       raw signature-file bytes as stored in the zip;</li>
 *   <li>extracts the signer certificate chain from the PKCS#7 blob.</li>
 * </ol>
 *
 * <p>All digest computation streams through 32 KB buffers; only the (small) manifest, signature
 * files and certificate blobs are held in memory, so verification is safe on the ~800 MB device.
 */
public final class ApkSignatureVerifier {

    private static final int BUFFER = 32 * 1024;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String OID_SIGNED_DATA = "1.2.840.113549.1.7.2";
    private static final String OID_ATTR_MESSAGE_DIGEST = "1.2.840.113549.1.9.4";

    /** Verification outcome. */
    public static final class Result {
        public final boolean valid;
        /** {@code ""} when valid; otherwise one of:
         *  {@code no-signature}, {@code manifest-digest-mismatch},
         *  {@code entry-digest-mismatch:<entry name>}, {@code signature-does-not-verify},
         *  {@code zip-error}, {@code signer-cert-mismatch}, {@code no-cert-extracted}. */
        public final String reason;
        /** Signer certificate chain, leaf (signing certificate) first; empty when none was
         *  extractable. */
        public final X509Certificate[] signerCertificates;

        Result(boolean valid, String reason, X509Certificate[] certs) {
            this.valid = valid;
            this.reason = reason;
            this.signerCertificates = certs;
        }

        @Override
        public String toString() {
            return valid ? "valid" : "invalid(" + reason + ")";
        }
    }

    /**
     * Verifies the JAR signature without a signer check.
     *
     * @return valid only when at least one signature file verifies and a signer certificate
     *         could be extracted.
     */
    public static Result verify(File apk) {
        return verify(apk, null);
    }

    /**
     * Verifies the JAR signature of an APK and, when {@code expectedSigner} is non-null,
     * requires the extracted leaf signer certificate to DER-match it — the
     * "signed with the same key as the installed app" gate.
     *
     * @param apk the APK file to verify
     * @param expectedSigner expected signer (leaf) certificate, or null to skip the match
     */
    public static Result verify(File apk, X509Certificate expectedSigner) {
        if (apk == null || !apk.isFile()) {
            return result(false, "zip-error");
        }
        ZipFile zip;
        try {
            zip = new ZipFile(apk);
        } catch (Exception e) {
            return result(false, "zip-error");
        }
        try {
            byte[] manifestRaw = readEntry(zip, "META-INF/MANIFEST.MF");
            if (manifestRaw == null) {
                return result(false, "no-signature");
            }
            List<Section> manifestSections = parseSections(manifestRaw);

            List<String> sfNames = signatureFileNames(zip);
            if (sfNames.isEmpty()) {
                return result(false, "no-signature");
            }

            List<X509Certificate> chains = new ArrayList<X509Certificate>();
            for (String sfName : sfNames) {
                byte[] sfRaw = readEntry(zip, sfName);
                if (sfRaw == null) {
                    return result(false, "zip-error");
                }
                String base = sfName.substring(0, sfName.length() - ".SF".length());
                byte[] blobRaw = readEntry(zip, base + ".RSA");
                if (blobRaw == null) {
                    blobRaw = readEntry(zip, base + ".DSA");
                }
                if (blobRaw == null) {
                    return result(false, "no-signature");
                }

                String digestFailure = verifyEntryAndManifestDigests(zip, manifestSections, sfRaw, manifestRaw);
                if (digestFailure != null) {
                    return result(false, digestFailure);
                }
                // The messageDigest signed attribute covers the raw .SF bytes exactly as
                // stored in the zip (jarsigner digests the bytes it writes), so no
                // canonicalization here — unlike the per-entry manifest-section digests.
                Pkcs7 pkcs7 = verifyPkcs7(blobRaw, sfRaw);
                if (pkcs7.failure != null) {
                    return result(false, pkcs7.failure);
                }
                if (pkcs7.certificates.length == 0) {
                    return result(false, "no-cert-extracted");
                }
                for (X509Certificate cert : pkcs7.certificates) {
                    chains.add(cert);
                }
            }

            if (expectedSigner != null && !derEquals(chains.get(0), expectedSigner)) {
                return new Result(false, "signer-cert-mismatch", toCertificateArray(chains));
            }
            return new Result(true, "", toCertificateArray(chains));
        } catch (Exception e) {
            // Malformed zip or signature structure of any kind: fail closed.
            return result(false, "zip-error");
        } finally {
            try {
                zip.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Steps 1–3: digest integrity of the manifest entries (against the zip content),
     * the manifest file itself, and the signature-file entries (against the matching
     * MANIFEST.MF section bytes as stored in the file — that is what jarsigner and
     * apksigner actually sign: the raw section bytes with JAR line wrapping preserved,
     * not a re-normalized unwrapped form).
     *
     * @return null when everything matches, otherwise the first failure reason.
     */
    private static String verifyEntryAndManifestDigests(
            ZipFile zip, List<Section> manifestSections, byte[] sfRaw, byte[] manifestRaw)
            throws Exception {
        List<Section> sfSections = parseSections(sfRaw);
        if (sfSections.isEmpty()) {
            return "signature-does-not-verify";
        }

        // Whole-manifest digest from the .SF main section, over the raw manifest bytes.
        Section sfMain = sfSections.get(0);
        String manifestDigestKey = null;
        for (String key : sfMain.attrs.keySet()) {
            if (key.endsWith("-Digest-Manifest") && !key.endsWith("-Digest-Manifest-Main")) {
                manifestDigestKey = key;
                break;
            }
        }
        if (manifestDigestKey == null) {
            return "signature-does-not-verify";
        }
        String manifestAlg = jcaDigest(manifestDigestKey.substring(
                0, manifestDigestKey.length() - "-Digest-Manifest".length()));
        byte[] manifestExpected = base64Decode(sfMain.attrs.get(manifestDigestKey));
        if (manifestAlg == null || manifestExpected == null) {
            return "signature-does-not-verify";
        }
        if (!constantTimeEquals(digestOf(manifestRaw, manifestAlg), manifestExpected)) {
            return "manifest-digest-mismatch";
        }

        // Per-entry digests declared in MANIFEST.MF cover the zip entry content.
        for (Section section : manifestSections) {
            if (section.name == null) {
                continue;
            }
            String digestKey = manifestEntryDigestKey(section.attrs);
            if (digestKey == null) {
                continue; // entry carries no digest; the .SF entry digest still covers it
            }
            String alg = manifestEntryDigestAlg(section.attrs, digestKey);
            byte[] expected = base64Decode(section.attrs.get(digestKey));
            byte[] actual = alg != null ? digestOfZipEntry(zip, section.name, alg) : null;
            if (expected == null || !constantTimeEquals(actual, expected)) {
                return "entry-digest-mismatch:" + section.name;
            }
        }

        // Per-entry digests declared in the .SF cover the matching MANIFEST.MF section
        // exactly as stored in the file (wrapping preserved, original line endings, plus
        // the section's terminating blank line) — NOT the zip entry content. This is the
        // chain that catches a tampered manifest. Signers digest the raw section bytes
        // they wrote, so re-serializing the parsed attributes would mismatch whenever a
        // line was wrapped on disk (entry names over ~66 chars wrap at 72 columns).
        Map<String, RawSection> rawSections = rawSectionRanges(manifestRaw);
        for (Section section : sfSections) {
            if (section.name == null) {
                continue;
            }
            RawSection raw = rawSections.get(section.name);
            if (raw == null) {
                return "entry-digest-mismatch:" + section.name;
            }
            for (Map.Entry<String, String> attr : section.attrs.entrySet()) {
                String key = attr.getKey();
                if (!key.endsWith("-Digest")) {
                    continue;
                }
                String alg = jcaDigest(key.substring(0, key.length() - "-Digest".length()));
                byte[] expected = base64Decode(attr.getValue());
                byte[] actual = alg != null
                        ? digestOf(slice(manifestRaw, raw.start, raw.end), alg)
                        : null;
                if (expected == null || !constantTimeEquals(actual, expected)) {
                    return "entry-digest-mismatch:" + section.name;
                }
            }
        }
        return null;
    }

    /**
     * The digest attribute of a MANIFEST.MF entry section: a {@code <ALG>-Digest} key
     * (e.g. {@code SHA-256-Digest}) or a legacy {@code Hash-MD5}/{@code Hash-SHA1}/
     * {@code Hash-SHA256} key; null when the section carries no digest.
     */
    private static String manifestEntryDigestKey(Map<String, String> attrs) {
        for (String key : attrs.keySet()) {
            if ("Hash-MD5".equals(key) || "Hash-SHA1".equals(key) || "Hash-SHA256".equals(key)) {
                return key;
            }
            if (key.endsWith("-Digest")
                    && jcaDigest(key.substring(0, key.length() - "-Digest".length())) != null) {
                return key;
            }
        }
        return null;
    }

    /** The JCA digest algorithm implied by a manifest-entry digest key. */
    private static String manifestEntryDigestAlg(Map<String, String> attrs, String digestKey) {
        if (digestKey.startsWith("Hash-")) {
            String h = digestKey.substring("Hash-".length()).toUpperCase(Locale.US);
            if ("MD5".equals(h)) {
                return "MD5";
            }
            if ("SHA1".equals(h)) {
                return "SHA-1";
            }
            return "SHA-256";
        }
        String fromKey = jcaDigest(digestKey.substring(0, digestKey.length() - "-Digest".length()));
        if (fromKey != null) {
            return fromKey;
        }
        String declared = attrs.get("Digest-Alg");
        return declared != null ? jcaDigest(declared) : null;
    }

    /**
     * Byte range of one MANIFEST.MF section as stored in the file: from the start of its
     * {@code Name:} line to the end of the blank line that terminates the section (inclusive).
     * JAR signers compute the {@code .SF} per-entry digest over exactly these raw bytes —
     * with line wrapping preserved — so the range must not be re-serialized.
     */
    private static final class RawSection {
        final int start;
        final int end;

        RawSection(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Maps each manifest entry's unwrapped name to the raw byte range of its section.
     * Names are unwrapped by reassembling leading-space continuation lines, so long entry
     * names that the jar tool wrapped at 72 columns still resolve to their full name.
     */
    private static Map<String, RawSection> rawSectionRanges(byte[] raw) {
        Map<String, RawSection> out = new LinkedHashMap<String, RawSection>();
        int n = raw.length;
        int i = 0;
        int sectionStart = -1;
        StringBuilder name = null;
        boolean lastKeyWasName = false;
        while (i < n) {
            int nl = i;
            while (nl < n && raw[nl] != '\n') {
                nl++;
            }
            int bodyEnd = nl; // exclusive; the '\n' is not part of the body
            if (bodyEnd > i && raw[bodyEnd - 1] == '\r') {
                bodyEnd--; // strip one trailing CR for parsing only
            }
            boolean blank = bodyEnd == i;
            int lineEnd = nl < n ? nl + 1 : n; // section range includes the terminator
            if (!blank && raw[i] == ' ') {
                // Continuation of the previous line: it extends the section's raw bytes
                // and, if the wrapped line was a Name: line, the entry name.
                if (name != null && lastKeyWasName) {
                    name.append(new String(raw, i + 1, bodyEnd - i - 1, UTF_8));
                }
            } else {
                if (blank) {
                    // The terminating blank line belongs to the section.
                    if (sectionStart >= 0 && name != null) {
                        out.put(name.toString(), new RawSection(sectionStart, lineEnd));
                    }
                    sectionStart = -1;
                    name = null;
                } else {
                    int colon = i;
                    while (colon < bodyEnd && raw[colon] != ':') {
                        colon++;
                    }
                    if (colon > i && colon - i == 4
                            && raw[i] == 'N' && raw[i + 1] == 'a'
                            && raw[i + 2] == 'm' && raw[i + 3] == 'e') {
                        if (sectionStart >= 0 && name != null) {
                            // No blank line between sections (malformed): close at this line.
                            out.put(name.toString(), new RawSection(sectionStart, i));
                        }
                        sectionStart = i;
                        name = new StringBuilder();
                        int valueStart = colon + 1;
                        if (valueStart < bodyEnd && raw[valueStart] == ' ') {
                            valueStart++;
                        }
                        name.append(new String(raw, valueStart, bodyEnd - valueStart, UTF_8));
                        lastKeyWasName = true;
                    } else {
                        lastKeyWasName = false;
                    }
                }
            }
            i = lineEnd;
        }
        if (sectionStart >= 0 && name != null) {
            // Final section without a terminating blank line.
            out.put(name.toString(), new RawSection(sectionStart, n));
        }
        return out;
    }

    /**
     * Steps 4–5: verify the PKCS#7 signature and extract the embedded signer
     * certificates. Two SignerInfo shapes are accepted:
     *
     * <ul>
     *   <li>With signed attributes (JDK jarsigner shape): the canonicalized attribute
     *       TLVs are signed, and the {@code messageDigest} attribute is cross-checked
     *       against the raw signature-file bytes.</li>
     *   <li>Attribute-less (apksigner/AGP v1 shape): the signature covers the raw
     *       signature-file bytes directly, under the effective algorithm formed from
     *       {@code digestAlgorithm} + {@code signatureAlgorithm} (bare
     *       {@code rsaEncryption} + sha256 → {@code SHA256withRSA}).</li>
     * </ul>
     */
    private static Pkcs7 verifyPkcs7(byte[] blob, byte[] sfRaw) {
        Pkcs7 out = new Pkcs7();
        out.certificates = new X509Certificate[0];
        try {
            DerReader top = new DerReader(blob, 0, blob.length);
            int[] contentInfo = top.tlv();
            if ((blob[top.fullStart()] & 0xFF) != 0x30) {
                out.failure = "signature-does-not-verify";
                return out;
            }
            DerReader ci = new DerReader(blob, contentInfo[0], contentInfo[1]);
            int[] contentTypeOid = ci.tlv();
            if (!OID_SIGNED_DATA.equals(decodeOid(slice(blob, contentTypeOid)))) {
                out.failure = "signature-does-not-verify";
                return out;
            }
            // [0] EXPLICIT wrapping the SignedData sequence
            int[] explicit = ci.tlv();
            int[] signedData = new DerReader(blob, explicit[0], explicit[1]).tlv();
            DerReader sd = new DerReader(blob, signedData[0], signedData[1]);
            sd.skip(); // version
            sd.skip(); // digestAlgorithms
            sd.skip(); // contentInfo

            // optional [0] IMPLICIT: certificates
            if (sd.more() && isContextSpecific(blob[sd.p])) {
                int[] certSet = sd.tlv();
                out.certificates = extractCertificates(blob, certSet);
            }
            // optional [1] IMPLICIT: crls
            if (sd.more() && isContextSpecific(blob[sd.p])) {
                sd.skip();
            }
            if (!sd.more()) {
                out.failure = "signature-does-not-verify";
                return out;
            }
            int[] signerInfos = sd.tlv();
            DerReader siReader = new DerReader(blob, signerInfos[0], signerInfos[1]);
            int[] signerInfo = siReader.tlv();
            DerReader si = new DerReader(blob, signerInfo[0], signerInfo[1]);
            si.skip(); // version
            si.skip(); // signerIdentifier (IssuerAndSerialNumber)
            int[] digestAlg = si.tlv();
            String digestName = digestForOid(decodeOid(slice(blob, firstElement(blob, digestAlg))));
            if (digestName == null) {
                out.failure = "signature-does-not-verify";
                return out;
            }
            // The next element is either the [0] SignedAttributes wrapper or the
            // signatureAlgorithm directly (an attribute-less SignerInfo).
            int[] next = si.tlv();
            int nextTag = blob[si.fullStart()] & 0xFF;
            byte[] signedTarget;
            int[] signatureAlg;
            if (nextTag == 0xA0) {
                byte[] signedAttrsRaw = slice(blob, si.fullStart(), next[1]);
                // The JDK signer (sun.security.pkcs.PKCS9Attributes.getDerEncoding) signs the
                // attribute TLVs as a DER SET OF (tag 0x31, byte-sorted) — not the [0] wrapper
                // bytes stored in the file. Re-encode before verifying.
                signedTarget = canonicalSignedAttributes(signedAttrsRaw);
                if (signedTarget == null) {
                    out.failure = "signature-does-not-verify";
                    return out;
                }
                signatureAlg = si.tlv();
                // Cross-check: the messageDigest attribute must cover the raw .SF bytes as
                // stored in the zip (jarsigner digests the bytes it writes, not a
                // re-normalized form).
                byte[] messageDigest = extractMessageDigest(signedAttrsRaw);
                if (messageDigest == null
                        || !constantTimeEquals(messageDigest, digestOf(sfRaw, digestName))) {
                    out.failure = "signature-does-not-verify";
                    return out;
                }
            } else {
                // Attribute-less SignerInfo (the shape apksigner/AGP v1 signing writes):
                // the signature covers the raw .SF bytes directly, with the effective
                // algorithm formed from digestAlgorithm + signatureAlgorithm
                // (e.g. sha256 + rsaEncryption → SHA256withRSA).
                signedTarget = sfRaw;
                signatureAlg = next;
            }
            String signatureAlgorithm = sigAlgForOid(decodeOid(slice(blob, firstElement(blob, signatureAlg))));
            if ("rsaEncryption".equals(signatureAlgorithm)) {
                signatureAlgorithm = rsaWithDigest(digestName);
            }
            int[] signatureValue = si.tlv();
            int signatureValueTag = blob[si.fullStart()] & 0xFF;
            if (signatureValueTag != 0x04 && signatureValueTag != 0x03) {
                out.failure = "signature-does-not-verify";
                return out;
            }

            if (out.certificates.length == 0) {
                out.failure = "no-cert-extracted";
                return out;
            }
            PublicKey signerKey = out.certificates[0].getPublicKey();

            // The signatureValue content. JDK's JAR signer writes an OCTET STRING of raw
            // signature bytes; apksigner writes a BIT STRING whose content starts with an
            // unused-bits count byte. Every candidate is still cryptographically verified
            // against the signer's public key, so the extra candidate costs nothing.
            byte[] signatureContent = slice(blob, signatureValue);
            List<byte[]> signatureCandidates = new ArrayList<byte[]>();
            signatureCandidates.add(signatureContent);
            if (signatureContent.length > 1 && signatureContent[0] == 0x00) {
                signatureCandidates.add(slice(signatureContent, 1, signatureContent.length));
            }

            List<String> candidates = new ArrayList<String>();
            if (signatureAlgorithm != null) {
                candidates.add(signatureAlgorithm);
            }
            for (String probe : new String[]{"SHA256withRSA", "SHA1withRSA", "MD5withRSA"}) {
                if (!candidates.contains(probe)) {
                    candidates.add(probe);
                }
            }
            for (String alg : candidates) {
                for (byte[] signatureBytes : signatureCandidates) {
                    Signature signature;
                    try {
                        signature = Signature.getInstance(alg);
                        signature.initVerify(signerKey);
                    } catch (GeneralSecurityException e) {
                        continue;
                    }
                    try {
                        signature.update(signedTarget);
                        if (signature.verify(signatureBytes)) {
                            return out;
                        }
                    } catch (Exception e) {
                        // wrong key size or malformed signature value: next candidate
                    }
                }
            }
            out.failure = "signature-does-not-verify";
            return out;
        } catch (Exception e) {
            out.failure = "signature-does-not-verify";
            return out;
        }
    }

    private static final class Pkcs7 {
        String failure;
        X509Certificate[] certificates;
    }

    private static X509Certificate[] extractCertificates(byte[] blob, int[] certSet) throws Exception {
        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        collectCertificates(blob, certSet[0], certSet[1], certs);
        return certs.toArray(new X509Certificate[0]);
    }

    /**
     * Collects X.509 certificates from a DER range. JDK jarsigner emits the
     * certificates field's {@code [0]} content as the certificate bytes directly;
     * some signers instead wrap it in a {@code SEQUENCE OF}, so any SEQUENCE that
     * is not itself a valid certificate is recursed into.
     */
    private static void collectCertificates(byte[] blob, int start, int end,
            List<X509Certificate> certs) throws Exception {
        DerReader r = new DerReader(blob, start, end);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        while (r.more()) {
            int[] tlv = r.tlv();
            int tag = blob[r.fullStart()] & 0xFF;
            if (tag != 0x30) {
                continue;
            }
            byte[] full = slice(blob, r.fullStart(), tlv[1]);
            try {
                certs.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(full)));
            } catch (Exception notACertificate) {
                collectCertificates(blob, tlv[0], tlv[1], certs);
            }
        }
    }

    /** Reads the first element (the OID) of a SEQUENCE at the given content bounds. */
    private static int[] firstElement(byte[] blob, int[] seq) {
        return new DerReader(blob, seq[0], seq[1]).tlv();
    }

    /** Copies {@code [bounds[0], bounds[1])} of the blob into a new array. */
    private static byte[] slice(byte[] blob, int[] bounds) {
        return slice(blob, bounds[0], bounds[1]);
    }

    /** Copies {@code [start, end)} of the blob into a new array. */
    private static byte[] slice(byte[] blob, int start, int end) {
        byte[] out = new byte[end - start];
        System.arraycopy(blob, start, out, 0, end - start);
        return out;
    }

    /** Decodes DER OID content bytes to dotted form, e.g. {@code 2.16.840.113549.1.7.2}. */
    private static String decodeOid(byte[] content) {
        if (content.length == 0) {
            return "";
        }
        int first = content[0] & 0xFF;
        StringBuilder sb = new StringBuilder(32);
        sb.append(first / 40).append('.').append(first % 40);
        long value = 0;
        for (int i = 1; i < content.length; i++) {
            int b = content[i] & 0xFF;
            value = (value << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) {
                sb.append('.').append(value);
                value = 0;
            }
        }
        return sb.toString();
    }

    /**
     * Reads the {@code messageDigest} signed attribute (OID
     * {@code 1.2.840.113549.1.9.4}) from the raw SignedAttributes encoding. JAR
     * signatures wrap each attribute in a context-specific {@code [0]} wrapper holding
     * {@code OID, value}; the messageDigest value is a plain OCTET STRING.
     *
     * @return the digest bytes, or null when absent or malformed
     */
    private static byte[] extractMessageDigest(byte[] signedAttrsRaw) {
        try {
            DerReader attrs = new DerReader(signedAttrsRaw, 0, signedAttrsRaw.length);
            int[] outer = attrs.tlv(); // SignedAttributes: [0] SET OF attribute
            DerReader iter = new DerReader(signedAttrsRaw, outer[0], outer[1]);
            while (iter.more()) {
                int[] attr = iter.tlv(); // one attribute wrapper
                DerReader ar = new DerReader(signedAttrsRaw, attr[0], attr[1]);
                int[] oidTlv = ar.tlv();
                if (OID_ATTR_MESSAGE_DIGEST.equals(decodeOid(slice(signedAttrsRaw, oidTlv)))) {
                    // JAR signers wrap the attribute value in a SET OF AttributeValue
                    // even for a single value: SEQUENCE { OID, SET { OCTET STRING } }.
                    int[] valuesTlv = ar.tlv();
                    if ((signedAttrsRaw[ar.fullStart()] & 0xFF) != 0x31) {
                        return null;
                    }
                    DerReader vr = new DerReader(signedAttrsRaw, valuesTlv[0], valuesTlv[1]);
                    int[] valueTlv = vr.tlv();
                    if ((signedAttrsRaw[vr.fullStart()] & 0xFF) != 0x04) {
                        return null;
                    }
                    return slice(signedAttrsRaw, valueTlv);
                }
            }
        } catch (Exception ignored) {
            // fall through to null
        }
        return null;
    }

    /**
     * Re-encodes the signed attributes the way the JDK signer signed them: a DER
     * {@code SET OF} (tag 0x31) whose elements are the attribute SEQUENCE TLVs, sorted in
     * unsigned byte order — mirroring
     * {@code sun.security.pkcs.PKCS9Attributes.getDerEncoding()} /
     * {@code DerValue.putOrderedSetOf}. The {@code [0]} wrapper bytes as stored in the file
     * are not what the signature covers, so verifying over them always fails.
     *
     * @param signedAttrsWrapper the full {@code [0]} TLV (tag, length, attribute TLVs)
     * @return the SET OF re-encoding, or null when the attributes are malformed
     */
    private static byte[] canonicalSignedAttributes(byte[] signedAttrsWrapper) {
        try {
            DerReader iter = new DerReader(signedAttrsWrapper, 0, signedAttrsWrapper.length);
            int[] outer = iter.tlv(); // the [0] wrapper
            List<byte[]> attributes = new ArrayList<byte[]>();
            DerReader walk = new DerReader(signedAttrsWrapper, outer[0], outer[1]);
            while (walk.more()) {
                int[] attr = walk.tlv();
                attributes.add(slice(signedAttrsWrapper, walk.fullStart(), attr[1]));
            }
            if (attributes.isEmpty()) {
                return null;
            }
            Collections.sort(attributes, new Comparator<byte[]>() {
                @Override
                public int compare(byte[] a, byte[] b) {
                    int n = Math.min(a.length, b.length);
                    for (int i = 0; i < n; i++) {
                        int cmp = (a[i] & 0xFF) - (b[i] & 0xFF);
                        if (cmp != 0) {
                            return cmp;
                        }
                    }
                    return a.length - b.length;
                }
            });
            int total = 0;
            for (byte[] attr : attributes) {
                total += attr.length;
            }
            byte[] length;
            if (total < 0x80) {
                length = new byte[]{(byte) total};
            } else if (total <= 0xFF) {
                length = new byte[]{(byte) 0x81, (byte) total};
            } else if (total <= 0xFFFF) {
                length = new byte[]{(byte) 0x82, (byte) (total >>> 8), (byte) total};
            } else {
                return null;
            }
            byte[] out = new byte[1 + length.length + total];
            out[0] = 0x31;
            System.arraycopy(length, 0, out, 1, length.length);
            int offset = 1 + length.length;
            for (byte[] attr : attributes) {
                System.arraycopy(attr, 0, out, offset, attr.length);
                offset += attr.length;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** All {@code META-INF/*.SF} signature-file names in the zip. */
    private static List<String> signatureFileNames(ZipFile zip) {
        List<String> names = new ArrayList<String>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith("META-INF/") && name.endsWith(".SF")) {
                names.add(name);
            }
        }
        return names;
    }

    private static byte[] readEntry(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        InputStream in = zip.getInputStream(entry);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(
                    Math.max(entry.getSize(), 1024), 8L * 1024 * 1024));
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Splits on {@code \n}, stripping one trailing {@code \r} per line. */
    private static List<String> lines(byte[] raw) {
        List<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (byte b : raw) {
            int c = b & 0xFF;
            if (c == '\n') {
                if (current.length() > 0 && current.charAt(current.length() - 1) == '\r') {
                    current.deleteCharAt(current.length() - 1);
                }
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append((char) c);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static final class Section {
        /** Non-final: JAR line wrapping splits the {@code Name:} line and the
         *  continuation fragments are appended during parsing. */
        String name;
        final Map<String, String> attrs = new LinkedHashMap<String, String>();

        Section(String name) {
            this.name = name;
        }
    }

    /**
     * Parses JAR manifest / signature-file syntax: {@code Key: value} pairs separated into
     * sections by blank lines; a {@code Name:} line starts a new per-entry section. Lines
     * starting with a space continue the previous attribute value (JAR line wrapping).
     */
    private static List<Section> parseSections(byte[] raw) {
        List<Section> sections = new ArrayList<Section>();
        Section current = new Section(null);
        String lastKey = null;
        for (String line : lines(raw)) {
            if (line.length() == 0) {
                if (current.name != null || !current.attrs.isEmpty()) {
                    sections.add(current);
                }
                current = new Section(null);
                lastKey = null;
                continue;
            }
            if (line.charAt(0) == ' ') {
                if (lastKey != null) {
                    String continued = current.attrs.get(lastKey) + line.substring(1);
                    current.attrs.put(lastKey, continued);
                    if ("Name".equals(lastKey)) {
                        // Wrapped "Name:" line: Section.name only holds the first fragment;
                        // carry the continuation into the name so entry lookups use the
                        // full unwrapped name.
                        current.name = continued;
                    }
                }
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon);
            String value = line.substring(colon + 1);
            if (value.length() > 0 && value.charAt(0) == ' ') {
                value = value.substring(1);
            }
            if ("Name".equals(key) && (current.name != null || !current.attrs.isEmpty())) {
                sections.add(current);
                current = new Section(value);
            } else if ("Name".equals(key) && current.name == null && current.attrs.isEmpty()) {
                current = new Section(value);
            }
            current.attrs.put(key, value);
            lastKey = key;
        }
        if (current.name != null || !current.attrs.isEmpty()) {
            sections.add(current);
        }
        return sections;
    }

    /** Normalizes a manifest/sf digest algorithm name to a JCA {@link MessageDigest} name. */
    private static String jcaDigest(String manifestAlg) {
        if (manifestAlg == null) {
            return null;
        }
        String a = manifestAlg.trim().toUpperCase(Locale.US);
        if (a.equals("SHA-256") || a.equals("SHA256")) {
            return "SHA-256";
        }
        if (a.equals("SHA-384") || a.equals("SHA384")) {
            return "SHA-384";
        }
        if (a.equals("SHA-512") || a.equals("SHA512")) {
            return "SHA-512";
        }
        if (a.equals("SHA-224") || a.equals("SHA224")) {
            return "SHA-224";
        }
        if (a.equals("SHA-1") || a.equals("SHA1")) {
            return "SHA-1";
        }
        if (a.equals("MD5")) {
            return "MD5";
        }
        return null;
    }

    private static String digestForOid(String oid) {
        switch (oid) {
            case "1.3.14.3.2.26":
                return "SHA-1";
            case "2.16.840.1.101.3.4.2.1":
                return "SHA-256";
            case "2.16.840.1.101.3.4.2.4":
                return "SHA-224";
            case "2.16.840.1.101.3.4.2.2":
                return "SHA-384";
            case "2.16.840.1.101.3.4.2.3":
                return "SHA-512";
            case "1.2.840.113549.2.5":
                return "MD5";
            default:
                return null;
        }
    }

    private static String sigAlgForOid(String oid) {
        switch (oid) {
            case "1.2.840.113549.1.1.1":
                // Plain RSA (no digest in the OID): combined with the SignerInfo's
                // digestAlgorithm by {@link #rsaWithDigest}.
                return "rsaEncryption";
            case "1.2.840.113549.1.1.4":
                return "MD5withRSA";
            case "1.2.840.113549.1.1.5":
                return "SHA1withRSA";
            case "1.2.840.113549.1.1.11":
                return "SHA256withRSA";
            case "1.2.840.113549.1.1.12":
                return "SHA384withRSA";
            case "1.2.840.113549.1.1.13":
                return "SHA512withRSA";
            case "1.2.840.113549.1.1.14":
                return "SHA224withRSA";
            case "1.2.840.10045.4.1":
                return "ecdsa-with-SHA1";
            case "1.2.840.10045.4.3.1":
                return "ecdsa-with-SHA224";
            case "1.2.840.10045.4.3.2":
                return "ecdsa-with-SHA256";
            case "1.2.840.10045.4.3.3":
                return "ecdsa-with-SHA384";
            case "1.2.840.10045.4.3.4":
                return "ecdsa-with-SHA512";
            default:
                return null;
        }
    }

    /**
     * JCA signature algorithm for the bare {@code rsaEncryption} OID combined with the
     * SignerInfo's {@code digestAlgorithm} (e.g. SHA-256 → {@code SHA256withRSA}).
     */
    private static String rsaWithDigest(String digestName) {
        switch (digestName) {
            case "SHA-256":
                return "SHA256withRSA";
            case "SHA-384":
                return "SHA384withRSA";
            case "SHA-512":
                return "SHA512withRSA";
            case "SHA-224":
                return "SHA224withRSA";
            case "SHA-1":
                return "SHA1withRSA";
            case "MD5":
                return "MD5withRSA";
            default:
                return null;
        }
    }

    private static byte[] digestOf(byte[] data, String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static byte[] digestOfZipEntry(ZipFile zip, String name, String algorithm) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        InputStream in = zip.getInputStream(entry);
        try {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return md.digest();
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private static boolean derEquals(X509Certificate a, X509Certificate b) {
        try {
            return constantTimeEquals(a.getEncoded(), b.getEncoded());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isContextSpecific(byte tag) {
        return (tag & 0xC0) == 0x80;
    }

    /** Minimal DER TLV reader; throws on anything outside the shapes jarsigner emits. */
    private static final class DerReader {
        private final byte[] d;
        private final int end;
        int p;
        private int lastTlvStart;

        DerReader(byte[] d, int start, int end) {
            this.d = d;
            this.p = start;
            this.end = end;
        }

        boolean more() {
            return p < end;
        }

        /**
         * Reads one TLV at the current position.
         *
         * @return content bounds {@code [start, end)}; the tag position is {@link #fullStart()}.
         */
        int[] tlv() {
            lastTlvStart = p;
            int tag = d[p++] & 0xFF;
            if (tag == 0x1F) {
                throw new IllegalArgumentException("multi-byte DER tags unsupported");
            }
            int first = d[p++] & 0xFF;
            int len;
            if (first < 0x80) {
                len = first;
            } else {
                int n = first & 0x7F;
                if (n == 0 || n > 4) {
                    throw new IllegalArgumentException("DER length out of range");
                }
                len = 0;
                for (int i = 0; i < n; i++) {
                    len = (len << 8) | (d[p++] & 0xFF);
                }
            }
            if (len < 0 || len > end - p) {
                throw new IllegalArgumentException("truncated DER structure");
            }
            int start = p;
            p += len;
            return new int[]{start, p};
        }

        void skip() {
            tlv();
        }

        int fullStart() {
            return lastTlvStart;
        }
    }

    private static final int[] B64 = new int[128];

    static {
        java.util.Arrays.fill(B64, -1);
        for (int i = 0; i < 26; i++) {
            B64['A' + i] = i;
            B64['a' + i] = 26 + i;
        }
        for (int i = 0; i < 10; i++) {
            B64['0' + i] = 52 + i;
        }
        B64['+'] = 62;
        B64['/'] = 63;
    }

    /** Decodes standard base64 (whitespace tolerated, padding optional); null on invalid input. */
    private static byte[] base64Decode(String in) {
        if (in == null) {
            return null;
        }
        int[] values = new int[in.length()];
        int n = 0;
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '=' || c == ' ' || c == '\r' || c == '\n' || c == '\t') {
                continue;
            }
            int v = c < 128 ? B64[c] : -1;
            if (v < 0) {
                return null;
            }
            values[n++] = v;
        }
        if (n % 4 == 1) {
            return null; // truncated base64
        }
        byte[] out = new byte[(n * 3) / 4];
        int o = 0;
        for (int i = 0; i + 3 < n; i += 4) {
            int triple = (values[i] << 18) | (values[i + 1] << 12) | (values[i + 2] << 6) | values[i + 3];
            out[o++] = (byte) (triple >> 16);
            out[o++] = (byte) (triple >> 8);
            out[o++] = (byte) triple;
        }
        int rem = n % 4;
        if (rem == 2) {
            int triple = (values[n - 2] << 18) | (values[n - 1] << 12);
            out[o++] = (byte) (triple >> 16);
        } else if (rem == 3) {
            int triple = (values[n - 3] << 18) | (values[n - 2] << 12) | (values[n - 1] << 6);
            out[o++] = (byte) (triple >> 16);
            out[o++] = (byte) (triple >> 8);
        }
        return out;
    }

    private static X509Certificate[] toCertificateArray(List<X509Certificate> certs) {
        return certs.toArray(new X509Certificate[0]);
    }

    private static Result result(boolean valid, String reason) {
        return new Result(valid, reason, new X509Certificate[0]);
    }
}
