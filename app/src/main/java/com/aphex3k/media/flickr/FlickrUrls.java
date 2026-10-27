package com.aphex3k.media.flickr;

import androidx.annotation.Nullable;

/**
 * The one place a Flickr asset's identity and its byte address are decided.
 *
 * <h2>Why the id carries everything</h2>
 *
 * A pipeline asset id has to be more than a handle: {@code MediaManager} calls the degraded rung
 * {@link com.aphex3k.media.MediaBackend#resolveThumbnailFallback} with a record it rebuilds from an
 * opaque key alone — {@code assetFromKey} hands back an asset whose type, sizes, file name and paths
 * are all empty — and the acquisition path calls the same method with the real asset. If the fallback
 * rule read any of those fields, the two paths would hand back different URLs for one asset, and both
 * would then be cached under the single {@code <id>_fb.jpg} name with no checksum to tell them apart.
 * So the rule here is a function of the id and nothing else, and the id is
 *
 * <pre>hex(server) "-" hex(photoId) "-" hex(secret)</pre>
 *
 * each part hex-encoded rather than pasted in, because Flickr photo ids are decimal-and-sometimes-more
 * (older ones carry letters, and a base-58-flavoured id can hold {@code g}–{@code z}) while the cache
 * ownership pattern is {@code ^[0-9a-fA-F-]{8,}(_fb)?(\.ext)} — applied to the <em>whole</em> file
 * name. A name outside that pattern is invisible to eviction <em>and</em> to deletion, so one unusual
 * character would make every byte of that photo permanent. Encoding both halves makes the alphabet
 * safe by construction instead of by assumption about Flickr's id scheme, and dash-free inside each
 * part means the three fields split apart unambiguously.
 *
 * <h2>Why only two tiers exist</h2>
 *
 * Sizes below 1600px share the photo's secret, so {@code _b} (1024px) and {@code _z} (640px) are both
 * derivable from what a listing response already gives. The 1600px/2048px renditions and the original
 * each carry their own secret, which the listing methods this backend calls do not return — and since
 * 2025 Flickr restricts anything above 1024px to accounts paying for Pro, on the evidence of a
 * measured {@code HTTP 410} at {@code _h}, {@code _k} and {@code _o}. So the original rung is
 * {@code _b} and the degraded rung is {@code _z}: strictly smaller, genuinely a different artifact,
 * and never the same URL twice — which is what keeps the "a byte-identical fallback must not exist on
 * the device" rule from ever being in play here, unlike Immich's playback endpoint.
 *
 * <p>Adding an originals-capable tier later means putting the tier in the id, not reading it off the
 * asset: an {@code url_o} answer cannot be reconstructed from the shared secret, so a rung that needs
 * it must be recoverable from the key alone some other way.
 */
final class FlickrUrls {

    /** The bytes this backend treats as an asset's original: 1024px on the longest edge. */
    static final String ORIGINAL_SUFFIX = "b";

    /** The degraded rung: 640px. Smaller than {@link #ORIGINAL_SUFFIX}, always, by construction. */
    static final String FALLBACK_SUFFIX = "z";

    /**
     * Production image host. A test injects its own origin through
     * {@link FlickrMediaBackend}'s three-argument constructor rather than mutating anything shared:
     * MockWebServer can substitute a configured {@code host}, but a Flickr entry has no host field, and
     * the {@code server} component of a photo URL is constrained by the cache-name pattern above, so
     * neither could hold {@code localhost:53123} without breaking eviction.
     */
    static final String DEFAULT_CDN_BASE = "https://live.staticflickr.com/";

    private FlickrUrls() {
    }

    /** The pipeline asset id for one photo: three hex fields, dash-separated, lowercase. */
    static String assetId(@Nullable String server, @Nullable String photoId,
                          @Nullable String secret) {
        if (!usable(server) || !usable(photoId) || !usable(secret)) {
            return null;
        }
        return hex(server) + "-" + hex(photoId) + "-" + hex(secret);
    }

    /** Photo id half of {@link #assetId}, or null when the string is not one this class wrote. */
    @Nullable
    static String photoIdOf(String assetId) {
        String[] parts = split(assetId);
        return parts == null ? null : unhex(parts[1]);
    }

    /** True for a string this class could have produced, and whose parts all survive a round trip. */
    static boolean isOurs(@Nullable String assetId) {
        String[] parts = split(assetId);
        if (parts == null) {
            return false;
        }
        for (String part : parts) {
            if (unhex(part) == null || part.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Address of one photo at one derivable tier, under {@code cdnBase}. */
    static String imageUrl(String assetId, String suffix, String cdnBase) {
        String[] parts = split(assetId);
        if (parts == null) {
            return null;
        }
        String server = unhex(parts[0]);
        String photoId = unhex(parts[1]);
        String secret = unhex(parts[2]);
        if (server == null || photoId == null || secret == null) {
            return null;
        }
        StringBuilder url = new StringBuilder(cdnBase).append(server).append('/').append(photoId)
                .append('_').append(secret);
        // The 500px rendition is the bare form; every other tier names itself in the file name.
        if (suffix != null && !suffix.isEmpty()) {
            url.append('_').append(suffix);
        }
        return url.append(".jpg").toString();
    }

    /** The original rung's address. Depends on nothing but the id. */
    static String originalUrl(String assetId, String cdnBase) {
        return imageUrl(assetId, ORIGINAL_SUFFIX, cdnBase);
    }

    /** The degraded rung's address. Depends on nothing but the id, and is never the original's URL. */
    static String fallbackUrl(String assetId, String cdnBase) {
        return imageUrl(assetId, FALLBACK_SUFFIX, cdnBase);
    }

    /**
     * The three hex fields of an asset id, or null if it is not dash-separated into exactly three.
     * Callers that then decode must check {@link #unhex} per part: a hex-shaped string can still
     * decode to nothing useful.
     */
    private static String[] split(String assetId) {
        if (assetId == null) {
            return null;
        }
        String[] parts = assetId.split("-");
        return parts.length == 3 ? parts : null;
    }

    private static boolean usable(@Nullable String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** Lowercase hex of a value's UTF-8 bytes. */
    static String hex(String value) {
        byte[] bytes = value.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = digits[b >>> 4];
            out[i * 2 + 1] = digits[b & 0x0F];
        }
        return new String(out);
    }

    /** The UTF-8 text behind a hex field, or null if {@code value} is not hex or does not decode. */
    @Nullable
    static String unhex(String value) {
        int length = value == null ? 0 : value.length();
        if (length == 0 || (length & 1) != 0) {
            return null;
        }
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = digit(value.charAt(i * 2));
            int low = digit(value.charAt(i * 2 + 1));
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        try {
            return new String(bytes, java.nio.charset.Charset.forName("UTF-8"));
        } catch (Exception e) {
            // Not every byte sequence is UTF-8 text; an id someone typed is not proof of one.
            return null;
        }
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
