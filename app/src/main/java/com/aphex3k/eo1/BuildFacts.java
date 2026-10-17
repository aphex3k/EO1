package com.aphex3k.eo1;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What this binary is made of: version, variant, the source it was built from, and the certificate
 * that signed it.
 *
 * <p>Release builds otherwise show nothing about themselves on screen — the debug overlay that
 * carried a version string is {@code GONE} unless {@link BuildConfig#DEBUG} — so a frame in the
 * wild could not be identified without pulling {@code /state}. The options dialog's About tab,
 * that JSON document and the LAN page all render from here, never from their own copy.
 */
public final class BuildFacts {

    private static final String TAG = "BuildFacts";

    /**
     * Lowercase hex SHA-256 of the DER of the certificate that signs release APKs, compared against
     * the installed one to say whether a frame runs a build of ours. Blank means "not configured",
     * which is reported as such rather than as a mismatch — a clone that never filled this in must
     * not accuse a healthy frame of running the wrong binary.
     *
     * <p>Public data (it is inside every signed APK): {@code apksigner verify --print-certs <release.apk>},
     * or {@code keytool -list -v} against the release keystore. Read it from a published APK rather
     * than the keystore alone, so the value cannot drift from what actually signed a release.
     */
    public static final String RELEASE_CERT_SHA256 =
            "a7cfbdda28c93aa270502b38e0da5f5bd1836e3a243f8cb270a9a17fcdd7deb8";

    private static final String UNKNOWN = "unknown";

    /** Memoised: one binder call and one ASN.1 parse, however many times the tab is swiped to. */
    private static volatile Signer cachedSigner;

    private BuildFacts() {
    }

    /** Version as it is already reported everywhere: {@code 2.0.0.84}. */
    public static String version() {
        return BuildConfig.VERSION_NAME + "." + BuildConfig.VERSION_CODE;
    }

    public static String versionName() {
        return BuildConfig.VERSION_NAME;
    }

    public static int buildNumber() {
        return BuildConfig.VERSION_CODE;
    }

    /** Commit this was built from, and whether the tree was dirty at the time: {@code 8de9c19 (clean)}. */
    public static String source() {
        return source(gitSha(), gitDirty());
    }

    public static String source(String sha, String dirty) {
        String state = "true".equals(dirty) ? "uncommitted changes"
                : "false".equals(dirty) ? "clean" : "tree state unknown";
        return sha + " (" + state + ")";
    }

    /** No product flavors exist, so the build type is the whole variant. */
    public static String variant() {
        return BuildConfig.BUILD_TYPE;
    }

    public static String gitSha() {
        return orUnknown(BuildConfig.GIT_SHA);
    }

    /** Whether the working tree carried uncommitted edits at build time. */
    public static String gitDirty() {
        return orUnknown(BuildConfig.GIT_DIRTY);
    }

    /** Raw ISO-8601 commit timestamp baked in by Gradle. */
    public static String buildStamp() {
        return orUnknown(BuildConfig.BUILD_DATE);
    }

    /** Same stamp, shaped for a screen: seconds dropped, so it survives a narrow column. */
    public static String buildStampForDisplay() {
        return formatStamp(buildStamp());
    }

    /** Not an ISO-8601 stamp ({@code unknown}, empty, a bare date) — returned as-is. */
    public static String formatStamp(String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return UNKNOWN;
        }
        int t = iso.indexOf('T');
        if (t < 0 || iso.length() < t + 6) {
            return iso;
        }
        // 2026-10-17T12:09:37-07:00 -> 2026-10-17 12:09 -07:00
        String zone = "";
        if (iso.endsWith("Z")) {
            zone = " Z";          // UTC, e.g. a -PBUILD_DATE from a publish script
            iso = iso.substring(0, iso.length() - 1);
        } else {
            int offset = iso.lastIndexOf('+');
            if (offset < 0) {
                offset = iso.lastIndexOf('-');
                if (offset <= t) {
                    offset = -1;  // no offset at all, or only the date's own hyphens
                }
            }
            if (offset > 0) {
                zone = " " + iso.substring(offset);
                iso = iso.substring(0, offset);
            }
        }
        return iso.substring(0, t) + " " + iso.substring(t + 1, Math.min(iso.length(), t + 6)) + zone;
    }

    /** One line for the LAN page header. */
    public static String summary(Context context) {
        return version() + " · " + variant() + " · " + source()
                + " · " + buildStampForDisplay() + " · " + signing(context).keyVerdict;
    }

    /**
     * The certificate this running package was signed with, read the way the self-updater reads it.
     * Never {@code null}; an unreadable or absent signature comes back labelled.
     *
     * <p>Deliberately the API-19 shape: {@code PackageInfo.signatures}. The API-28
     * {@code signingInfo} accessors compile fine against SDK 34 and throw {@code NoSuchFieldError}
     * on the frames this runs on.
     */
    public static Signer signing(Context context) {
        Signer cached = cachedSigner;
        if (cached != null) {
            return cached;
        }
        byte[] der = null;
        X509Certificate cert = null;
        String error = null;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_SIGNATURES);
            if (info.signatures != null && info.signatures.length > 0) {
                der = info.signatures[0].toByteArray();
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
            }
        } catch (Exception e) {
            // A frame that cannot describe itself is a reporting gap, never a reason to crash.
            Log.w(TAG, "signing: " + e);
            error = e.getClass().getSimpleName();
        }
        Signer signer = new Signer(der, cert, error);
        cachedSigner = signer;
        return signer;
    }

    /** The installed package's signing certificate, with every fact the About tab and {@code /state} show. */
    public static final class Signer {
        public final byte[] der;
        public final X509Certificate cert;
        /** Non-null only when the read itself failed. */
        public final String error;
        /** Whether the package carries a signature at all. */
        public final boolean signed;
        /** Lowercase hex, {@code ""} when unsigned — the value {@code keytool} prints. */
        public final String fingerprintHex;
        /** Same, broken into wrapped-friendly groups, or a stated reason there is none. */
        public final String fingerprint;
        public final String subject;
        public final String serial;
        public final String validFrom;
        public final String validTo;
        /** {@code valid}, {@code expired}, {@code not yet valid}, {@code unknown}. */
        public final String validityStatus;
        public final String algorithm;
        public final String keyVerdict;

        Signer(byte[] der, X509Certificate cert, String error) {
            long now = System.currentTimeMillis();
            this.der = der;
            this.cert = cert;
            this.error = error;
            this.signed = der != null && der.length > 0;
            String hex = signed ? fingerprintHex(der) : "";
            this.fingerprintHex = hex;
            this.fingerprint = hex.isEmpty()
                    ? (error == null ? "no signature found" : "unreadable (" + error + ")")
                    : groupHex(hex);
            this.subject = subject(cert);
            this.serial = serial(cert);
            this.validFrom = cert == null ? "n/a" : day(cert.getNotBefore());
            this.validTo = cert == null ? "n/a" : day(cert.getNotAfter());
            this.validityStatus = cert == null ? "unknown" : validityStatus(cert, now);
            this.algorithm = cert == null ? "n/a" : cert.getSigAlgName();
            this.keyVerdict = keyVerdict(hex, cert, RELEASE_CERT_SHA256);
        }

        /** One display line: the window, flagged when it does not contain today. */
        public String validity() {
            return "valid".equals(validityStatus) ? validFrom + " .. " + validTo
                    : validFrom + " .. " + validTo + " (" + validityStatus + ")";
        }
    }

    /** {@code valid}, {@code expired} or {@code not yet valid} for {@code now}. */
    public static String validityStatus(X509Certificate cert, long now) {
        Date from = cert.getNotBefore();
        Date to = cert.getNotAfter();
        if (from == null || to == null) {
            return "unknown";
        }
        if (now < from.getTime()) {
            return "not yet valid";
        }
        return now > to.getTime() ? "expired" : "valid";
    }

    /** SHA-256 of the certificate's DER, lowercase hex — the value {@code keytool} prints. */
    public static String fingerprintHex(byte[] der) {
        try {
            return MediaIntegrity.toHex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (Exception e) {
            Log.w(TAG, "fingerprintHex: " + e);
            return "";
        }
    }

    /**
     * {@code aabbcc..} → {@code aa:bb:cc:dd ee:ff:…} in groups of four pairs: an unbroken run of hex
     * has nowhere to break, so an unwrapped fingerprint is unreadable in a {@code TextView}.
     */
    public static String groupHex(String lowercaseHex) {
        StringBuilder sb = new StringBuilder(lowercaseHex.length() + lowercaseHex.length() / 2);
        for (int i = 0; i < lowercaseHex.length(); i += 2) {
            if (sb.length() > 0) {
                sb.append(((i / 2) % 4 == 0) ? ' ' : ':');
            }
            sb.append(lowercaseHex, i, Math.min(i + 2, lowercaseHex.length()));
        }
        return sb.toString();
    }

    /** RFC 2253 name, e.g. {@code CN=EO1 Release,O=Electric Objects}. */
    public static String subject(X509Certificate cert) {
        if (cert == null) {
            return "n/a";
        }
        try {
            return cert.getSubjectX500Principal().getName();
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /** The SDK's autogenerated debug key ({@code CN=Android Debug,O=Android,C=US}). */
    public static boolean isDebugKey(String subjectDn) {
        return subjectDn != null && subjectDn.toLowerCase(Locale.US).contains("android debug");
    }

    public static String serial(X509Certificate cert) {
        if (cert == null) {
            return "n/a";
        }
        try {
            return cert.getSerialNumber().toString(16);
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /**
     * Whether the signing key is the one that publishes this app.
     *
     * @param actualHex lowercase fingerprint of the installed certificate, {@code ""} if there was none
     */
    public static String keyVerdict(String actualHex, X509Certificate cert, String expectedHex) {
        if (actualHex == null || actualHex.isEmpty()) {
            return "none (unsigned build)";
        }
        if (isDebugKey(subject(cert))) {
            // Debug keystores are per-machine; matching one against a published value means nothing.
            return "debug key (machine-specific, not comparable)";
        }
        if (expectedHex == null || expectedHex.trim().isEmpty()) {
            return "not checked (no expected fingerprint in this build)";
        }
        return expectedHex.trim().toLowerCase(Locale.US).equals(actualHex)
                ? "matches the release key"
                : "MISMATCH - not the expected release key";
    }

    private static String day(Date date) {
        return date == null ? UNKNOWN : new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date);
    }

    private static String orUnknown(String value) {
        return (value == null || value.trim().isEmpty()) ? UNKNOWN : value.trim();
    }
}
