package com.aphex3k.media.immich;

import androidx.annotation.Nullable;

import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;

/**
 * Static band table mapping Immich server versions to frozen client implementations.
 *
 * <p>When Immich ships a breaking API change: add a new DTO package
 * ({@code com.aphex3k.immichApi.vN}), a new {@code ImmichClientVN} frozen against it, and a
 * band entry below. Frozen client packages are never modified.
 *
 * <p>Band ceilings are drawn at major boundaries rather than at the last release someone
 * happened to check. Immich marks its own stability in the spec ({@code x-immich-state} /
 * {@code x-immich-history}) and defers removal of anything deprecated to the next major, so a
 * minor release cannot break a client that matches an earlier release of the same line.
 * {@code V3_VERIFIED_MAX} records how far that claim was actually verified.
 */
public final class ImmichClientRegistry {

    /** Inclusive lower bound of the band covered by {@link ImmichClientV3}. */
    public static final Semver V3_MIN = new Semver("3.0.0", Semver.SemverType.STRICT);
    /** Exclusive upper bound of the V3 band: the 4.x line needs its own client. */
    public static final Semver V4_FLOOR = new Semver("4.0.0", Semver.SemverType.STRICT);
    /** Highest release whose endpoints were diffed and found identical to this client's. */
    public static final Semver V3_VERIFIED_MAX = new Semver("3.3.3", Semver.SemverType.STRICT);

    /** Bands in ascending floor order; the last entry is the newest implementation we ship. */
    private static final Band[] BANDS = {
            new Band(V3_MIN, V4_FLOOR, ImmichClientV3.class),
    };

    private ImmichClientRegistry() {
    }

    /** Human-readable union of supported bands, for user-facing messages. */
    public static String supportedRange() {
        return V3_MIN + "–3.x (verified through " + V3_VERIFIED_MAX + ")";
    }

    /**
     * Picks the client implementation whose band covers a concrete server version.
     *
     * @return the implementation class, or null when no band covers the version
     *         (or the version is null).
     */
    @Nullable
    public static Class<? extends ImmichClient> clientForVersion(@Nullable Semver version) {
        Semver v = normalize(version);
        if (v == null) {
            return null;
        }
        for (Band band : BANDS) {
            if (!v.isLowerThan(band.floor) && v.isLowerThan(band.ceiling)) {
                return band.client;
            }
        }
        return null;
    }

    /**
     * Expands an abbreviated version — a {@code "3.1"} or {@code "4"} pin parses as
     * {@link Semver.SemverType#LOOSE}, whose missing components compare as lower than any
     * value, which would place {@code "4"} inside a band ending at 4.0.0.
     */
    @Nullable
    private static Semver normalize(@Nullable Semver version) {
        if (version == null) {
            return null;
        }
        try {
            return version.toStrict();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves a client for a server version, allowing a best-effort answer above the
     * highest known band: a server newer than anything we have seen gets the newest client
     * we ship rather than no client at all, because Immich's 3.x line only ever adds.
     *
     * <p>There is no such mercy for a server <em>older</em> than the first band — for that
     * we know the client speaks the wrong API, so it stays a clean refusal.
     *
     * @return the resolution, or null when the version is null or predates every band
     */
    @Nullable
    public static Resolution resolve(@Nullable Semver version) {
        Semver v = normalize(version);
        if (v == null || BANDS.length == 0) {
            return null;
        }
        Class<? extends ImmichClient> exact = clientForVersion(v);
        if (exact != null) {
            return new Resolution(exact, true);
        }
        Band newest = BANDS[BANDS.length - 1];
        return v.isLowerThan(newest.floor) ? null : new Resolution(newest.client, false);
    }

    /**
     * The newest client implementation this build ships — the one to probe an unknown server
     * with, so the probe itself never lags behind the band table.
     */
    public static Class<? extends ImmichClient> newestClient() {
        return BANDS[BANDS.length - 1].client;
    }

    /** A server-version band and the client that covers it. */
    private static final class Band {
        final Semver floor;
        final Semver ceiling;
        final Class<? extends ImmichClient> client;

        Band(Semver floor, Semver ceiling, Class<? extends ImmichClient> client) {
            this.floor = floor;
            this.ceiling = ceiling;
            this.client = client;
        }
    }

    /** Which client to use for a server version, and whether that was an actual match. */
    public static final class Resolution {
        public final Class<? extends ImmichClient> client;
        /** False when no band covers the version and we are proceeding best effort. */
        public final boolean exact;

        Resolution(Class<? extends ImmichClient> client, boolean exact) {
            this.client = client;
            this.exact = exact;
        }
    }

    /**
     * Parses a pinned {@code apiVersion} config value into a {@link Semver}.
     *
     * @return the parsed version, or null for "auto", blank, or unparseable values (an
     *         unparseable pin is treated as "auto" — {@link com.aphex3k.eo1.Configuration#normalize}
     *         already relaxes it at config load, so this is a defensive fallback)
     */
    @Nullable
    public static Semver parsePin(@Nullable String apiVersion) {
        if (apiVersion == null) {
            return null;
        }
        String s = apiVersion.trim();
        if (s.isEmpty() || ConfigurationBackendEntry.API_VERSION_AUTO.equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return new Semver(s, Semver.SemverType.STRICT);
        } catch (SemverException e) {
            try {
                return new Semver(s, Semver.SemverType.LOOSE);
            } catch (SemverException e2) {
                return null;
            }
        }
    }
}
