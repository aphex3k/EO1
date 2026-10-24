package com.aphex3k.eo1.trust;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * A trust manager for the anchors this app actually needs: the device's own, plus the roots in
 * {@link RootBundle}.
 *
 * <p>This is the no-privilege half of fixing TLS on a frame. The ROM's CA store is from about 2013 —
 * 63 of the EO2 store's 156 anchors are past their own expiry and not one of them is Let's Encrypt's,
 * which is why an Immich server on a current certificate stopped validating when LE's DST Root CA X3
 * cross-signature lapsed in September 2024. Writing to the system store would fix that for every
 * process on the device, but it needs a root shell, and on an EO1 there may not be one reachable.
 * Extending the anchors this app trusts needs nothing at all, ships through the ordinary update, and is
 * undone by installing the previous APK.
 *
 * <p><b>Adding, never narrowing.</b> The one way to make a frame worse with this feature is to build a
 * trust manager whose anchor set is smaller than the platform's: hosts that validate today would stop,
 * and the failure would look like a certificate problem rather than like a bad merge. So the platform's
 * anchors are enumerated at runtime and merged with the bundle, and if that enumeration yields nothing
 * the manager is not used at all — {@link #create} reports {@link #MODE_PLATFORM_ONLY} and the caller
 * keeps trusting exactly what it trusted before. A bundle that cannot be read degrades the same way.
 *
 * <p>Two properties this deliberately does not have. It is not a permissive fallback: an
 * {@link X509TrustManager} that catches a verification failure and returns is a way to turn any
 * man-in-the-middle into a successful connection, and the temptation to add one arrives precisely when
 * a frame will not connect. And it does not cover every TLS consumer in the process. OkHttp 3.12
 * performs its own hostname verification against the peer certificate
 * ({@code okhttp3.internal.connection.RealConnection} → {@code OkHostnameVerifier}), which is why
 * replacing the trust anchors here leaves identity checking intact — but a {@code WebView} validates
 * inside its own engine and ignores any Java trust manager entirely. That gap is
 * {@code CertStoreProvisioner}'s job, and it is why an https kiosk page is still broken after this
 * class has done its work.
 *
 * <p>Deliberately free of {@code android.*}: this runs, unchanged, in a JVM unit test.
 */
public final class MergedTrust {

    /** Platform anchors plus the shipped bundle. The intended state. */
    public static final String MODE_MERGED = "merged";
    /**
     * The merged manager was not installed — either the bundle was switched off, or the platform's
     * anchors could not be enumerated. Trust is exactly what this device's ROM says it is.
     */
    public static final String MODE_PLATFORM_ONLY = "platform-only";

    /** What the last {@link #create} in this process decided, for {@code /state} and {@code /logs}. */
    private static volatile Result last = new Result(MODE_PLATFORM_ONLY, -1, -1, "not yet built", null);

    private MergedTrust() {
    }

    /**
     * Candidate {@link TrustManagerFactory} algorithm names, tried in order.
     *
     * <p>"X.509" is the name the JDK's tests accept and Android 4.4 does not: this ROM's provider
     * answers {@code NoSuchAlgorithmException: TrustManagerFactory X.509 implementation not found} for
     * it, which a unit test on a workstation could never reveal. The platform's own default is asked
     * for first because that is the only spelling guaranteed to exist on whatever device this runs on;
     * the literals are fallbacks for a provider that reports a default this call path dislikes.
     */
    private static final String[] TMF_ALGORITHMS = {"X509", "PKIX", "SunX509", "X.509"};

    /** A factory for some algorithm this provider actually has, or {@code null} if it has none. */
    private static TrustManagerFactory factory() {
        String preferred = null;
        try {
            preferred = TrustManagerFactory.getDefaultAlgorithm();
        } catch (Throwable ignored) {
            // No preferred algorithm: fall through to the literals.
        }
        String[] names = new String[1 + TMF_ALGORITHMS.length];
        names[0] = preferred;
        System.arraycopy(TMF_ALGORITHMS, 0, names, 1, TMF_ALGORITHMS.length);
        for (int i = 0; i < names.length; i++) {
            if (names[i] == null) {
                continue;
            }
            try {
                return TrustManagerFactory.getInstance(names[i]);
            } catch (Throwable ignored) {
                // Try the next spelling. Every one of them failing is reported by the caller.
            }
        }
        return null;
    }

    /** The outcome of a trust-manager build. Immutable, because it is read from another thread. */
    public static final class Result {
        public final String mode;
        public final int platformAnchors;
        public final int mergedAnchors;
        public final String detail;
        /** The merged manager to install, or {@code null} to leave the platform default alone. */
        public final X509TrustManager trustManager;

        Result(String mode, int platformAnchors, int mergedAnchors, String detail,
               X509TrustManager trustManager) {
            this.mode = mode;
            this.platformAnchors = platformAnchors;
            this.mergedAnchors = mergedAnchors;
            this.detail = detail == null ? "" : detail;
            this.trustManager = trustManager;
        }

        /** True when installing this result would change nothing — the safe reading of it. */
        public boolean isPlatformOnly() {
            return trustManager == null;
        }
    }

    public static Result last() {
        return last;
    }

    /**
     * The cipher suites this device's TLS provider is <em>capable</em> of, whether or not they are
     * enabled.
     *
     * <p>A certificate chain is only ever examined after the server accepts the client's
     * {@code ClientHello}, so on an ECDSA-certified host a frame with no ECDSA suite in this list
     * fails with {@code handshake_failure} and never reaches the question the root bundle answers.
     * Naming the list turns "installing the certificates did not work" into a statement about which
     * of the four gates is the one that actually binds, and it is the only way to see it on a frame
     * that has no shell and no {@code openssl}.
     *
     * @return suite names, or an empty array if the provider will not say
     */
    public static String[] platformCipherSuites() {
        try {
            String[] supported = javax.net.ssl.SSLContext.getDefault()
                    .getSupportedSSLParameters().getCipherSuites();
            return supported == null ? new String[0] : supported;
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /**
     * @param enabled {@code false} restores the pre-feature behaviour exactly: the platform's trust
     *        store and nothing else. Wired to a SharedPreferences flag, because the escape hatch from a
     *        trust change has to be reachable without shipping another APK.
     */
    public static synchronized Result create(boolean enabled) {
        Result result = build(enabled);
        last = result;
        return result;
    }

    private static Result build(boolean enabled) {
        if (!enabled) {
            return new Result(MODE_PLATFORM_ONLY, -1, -1, "bundle disabled by setting", null);
        }

        List<X509Certificate> platform;
        try {
            platform = platformAnchors();
        } catch (Throwable t) {
            // Throwable, not Exception: on a device where the trust provider itself is broken this is
            // the moment to find out, and a NoClassDefFoundError escaping here would take the rotation
            // thread with it.
            return new Result(MODE_PLATFORM_ONLY, -1, -1,
                    "platform anchor enumeration failed: " + t, null);
        }

        if (platform.isEmpty()) {
            return new Result(MODE_PLATFORM_ONLY, 0, -1,
                    "platform anchors unreadable — keeping the device default", null);
        }

        Map<String, X509Certificate> merged = new LinkedHashMap<String, X509Certificate>();
        for (int i = 0; i < platform.size(); i++) {
            X509Certificate anchor = platform.get(i);
            merged.put(SubjectHashOld.hash(anchor) + '|' + anchor.getSerialNumber(), anchor);
        }

        int added = 0;
        int skipped = 0;
        RootBundle.Entry[] bundle = RootBundle.entries();
        for (int i = 0; i < bundle.length; i++) {
            RootBundle.Entry entry = bundle[i];
            try {
                X509Certificate certificate = entry.certificate();
                String key = SubjectHashOld.hash(certificate) + '|' + certificate.getSerialNumber();
                if (merged.containsKey(key)) {
                    skipped++;
                    continue;
                }
                merged.put(key, certificate);
                added++;
            } catch (Throwable t) {
                // One unreadable bundle entry must not cost the frame the other nine, and must not be
                // silent either: the count below is what an operator reads.
                skipped++;
            }
        }

        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            int index = 0;
            for (Map.Entry<String, X509Certificate> anchor : merged.entrySet()) {
                keyStore.setCertificateEntry("anchor-" + index++, anchor.getValue());
            }
            TrustManagerFactory tmf = factory();
            if (tmf == null) {
                return new Result(MODE_PLATFORM_ONLY, platform.size(), -1,
                        "no TrustManagerFactory algorithm this provider recognises", null);
            }
            tmf.init(keyStore);
            X509TrustManager manager = null;
            TrustManager[] managers = tmf.getTrustManagers();
            for (int i = 0; i < managers.length; i++) {
                if (managers[i] instanceof X509TrustManager) {
                    manager = (X509TrustManager) managers[i];
                    break;
                }
            }
            if (manager == null) {
                return new Result(MODE_PLATFORM_ONLY, platform.size(), -1,
                        "no X.509 trust manager from this provider", null);
            }

            // The final check is the one that matters: a manager that came up smaller than the
            // platform's own set would break hosts that work today.
            int installed = manager.getAcceptedIssuers() == null
                    ? -1 : manager.getAcceptedIssuers().length;
            if (installed >= 0 && installed < platform.size()) {
                return new Result(MODE_PLATFORM_ONLY, platform.size(), installed,
                        "merged set is smaller than the platform's — refusing to install", null);
            }

            return new Result(MODE_MERGED, platform.size(),
                    installed >= 0 ? installed : merged.size(),
                    added + " anchor(s) added, " + skipped + " already present", manager);
        } catch (Throwable t) {
            return new Result(MODE_PLATFORM_ONLY, platform.size(), -1,
                    "could not build a trust manager: " + t, null);
        }
    }

    /**
     * The device's own anchors.
     *
     * <p>Two routes, because they fail differently. {@code init(null)} means "the platform default",
     * which on this platform is the same store OkHttp is using today, so it is the first try. The
     * {@code AndroidCAStore} KeyStore is the documented direct read of the same anchors and is the
     * fallback for a ROM whose default manager answers {@code getAcceptedIssuers()} with an empty
     * array — which is permitted, and indistinguishable from a device that genuinely trusts nothing.
     */
    private static List<X509Certificate> platformAnchors() throws Exception {
        List<X509Certificate> anchors = new ArrayList<X509Certificate>();

        // Route 1, and it must not be able to abort route 2: a provider that cannot build a default
        // trust manager still usually serves the anchors through the keystore. The first cut of this
        // method let a NoSuchAlgorithmException from route 1 escape, which turned into "platform-only"
        // on a device that had a readable store all along.
        TrustManagerFactory tmf = factory();
        if (tmf != null) {
            try {
                tmf.init((KeyStore) null);
                TrustManager[] managers = tmf.getTrustManagers();
                for (int i = 0; i < managers.length; i++) {
                    if (managers[i] instanceof X509TrustManager) {
                        X509Certificate[] issuers = ((X509TrustManager) managers[i]).getAcceptedIssuers();
                        if (issuers != null) {
                            for (int j = 0; j < issuers.length; j++) {
                                anchors.add(issuers[j]);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Fall through to the direct keystore read.
            }
        }
        if (!anchors.isEmpty()) {
            return anchors;
        }

        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidCAStore");
            keyStore.load(null, null);
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate certificate = keyStore.getCertificate(alias);
                if (certificate instanceof X509Certificate) {
                    anchors.add((X509Certificate) certificate);
                }
            }
        } catch (Throwable ignored) {
            // Neither route worked; the caller reports an empty enumeration as platform-only.
        }
        return anchors;
    }
}
