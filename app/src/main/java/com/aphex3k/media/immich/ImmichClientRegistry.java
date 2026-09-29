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
 */
public final class ImmichClientRegistry {

    /** Inclusive lower bound of the band covered by {@link ImmichClientV3}. */
    public static final Semver V3_MIN = new Semver("3.0.0", Semver.SemverType.STRICT);
    /** Inclusive upper bound of the band covered by {@link ImmichClientV3}. */
    public static final Semver V3_MAX = new Semver("3.2.2", Semver.SemverType.STRICT);

    private ImmichClientRegistry() {
    }

    /** Human-readable union of supported bands, for user-facing messages. */
    public static String supportedRange() {
        return V3_MIN + "–" + V3_MAX;
    }

    /**
     * Picks the client implementation for a concrete server version.
     *
     * @return the implementation class, or null when no implementation covers the version
     *         (or the version is null).
     */
    @Nullable
    public static Class<? extends ImmichClient> clientForVersion(@Nullable Semver version) {
        if (version == null) {
            return null;
        }
        if (!version.isLowerThan(V3_MIN) && !version.isGreaterThan(V3_MAX)) {
            return ImmichClientV3.class;
        }
        return null;
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
