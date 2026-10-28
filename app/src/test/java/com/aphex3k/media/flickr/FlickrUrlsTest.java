package com.aphex3k.media.flickr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.eo1.MediaCacheManager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The asset-id and tier rules {@link FlickrMediaBackend} is built on. These are the two places where a
 * plausible-looking Flickr backend goes quietly wrong on real hardware: an id that is not cache-name
 * safe leaves bytes that neither eviction nor deletion can touch, and a degraded rung that consults
 * anything beyond the id hands the pipeline two different artifacts under one file name.
 */
public class FlickrUrlsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A modern photo id, its farm server, and the shared secret that unlocks every tier below 1600px. */
    private static final String SERVER = "6553";
    private static final String PHOTO_ID = "54123456789";
    private static final String SECRET = "a1b2c3d4e5";

    private static String id() {
        return FlickrUrls.assetId(SERVER, PHOTO_ID, SECRET);
    }

    @Test
    public void idRoundTripsEveryField() {
        String assetId = id();
        assertNotNull(assetId);
        assertEquals("54123456789", FlickrUrls.photoIdOf(assetId));
        assertTrue("the id must be recognisable as ours", FlickrUrls.isOurs(assetId));
    }

    /**
     * The point of hex-encoding both halves rather than pasting Flickr's own strings in: a photo id is
     * decimal on modern accounts but has never been guaranteed to be, and {@code g}–{@code z} are outside
     * the cache-ownership alphabet. One such character would make that photo's bytes permanent — invisible
     * to {@code listEvictableMedia} and to {@code deleteCachedFile} alike.
     */
    @Test
    public void idsHoldingNonHexCharactersStayEvictable() throws Exception {
        String assetId = FlickrUrls.assetId("6553", "5pZgHkLmNoQ", "abcdefghij");
        assertNotNull(assetId);
        assertEquals("5pZgHkLmNoQ", FlickrUrls.photoIdOf(assetId));

        File cacheDir = tmp.newFolder("cache");
        File cached = new File(cacheDir, assetId + ".jpg");
        write(cached);
        assertTrue("a base-58-flavoured Flickr id must still name an owned cache file",
                MediaCacheManager.isOwnedMediaCacheFile(cached));

        File fallback = new File(cacheDir, assetId + "_fb.jpg");
        write(fallback);
        assertTrue(MediaCacheManager.isOwnedMediaCacheFile(fallback));
    }

    /** An id is only useful if both ladder rungs can be rebuilt from it, on a device with no state. */
    @Test
    public void bothRungsAreDerivableFromTheIdAlone() {
        String original = FlickrUrls.originalUrl(id(), FlickrUrls.DEFAULT_CDN_BASE);
        String fallback = FlickrUrls.fallbackUrl(id(), FlickrUrls.DEFAULT_CDN_BASE);

        assertNotNull(original);
        assertNotNull(fallback);
        assertNotEquals("one name, two artifacts, no checksum to tell them apart", original, fallback);
        assertEquals("https://live.staticflickr.com/6553/54123456789_a1b2c3d4e5_b.jpg", original);
        assertEquals("https://live.staticflickr.com/6553/54123456789_a1b2c3d4e5_z.jpg", fallback);
    }

    /**
     * A URL built by string surgery from an extras <em>field name</em> is the trap this guards: the
     * 1024px rendition's extras key is {@code url_l} while its file-name suffix is {@code b}, and the
     * {@code _h}/{@code _k} tiers carry their own secrets that a listing never returns.
     */
    @Test
    public void theLadderStaysBelowThePerSizeSecretLine() {
        // Flickr: "All sizes below the H (1600) size use one shared secret", and everything from H
        // upward carries its own — which a listing method never returns. Both rungs must therefore be
        // on the shared-secret side, or the degraded rung could not be rebuilt from the id at all.
        assertEquals("b", FlickrUrls.ORIGINAL_SUFFIX);
        assertEquals("z", FlickrUrls.FALLBACK_SUFFIX);
        assertTrue(FlickrUrls.ORIGINAL_SUFFIX.compareTo(FlickrUrls.FALLBACK_SUFFIX) != 0);
        assertEquals(FlickrUrls.originalUrl(id(), "https://cdn/"),
                "https://cdn/6553/54123456789_a1b2c3d4e5_b.jpg");
        assertEquals(FlickrUrls.fallbackUrl(id(), "https://cdn/"),
                "https://cdn/6553/54123456789_a1b2c3d4e5_z.jpg");
    }

    @Test
    public void anIncompleteListingYieldsNoId() {
        assertNull(FlickrUrls.assetId(null, PHOTO_ID, SECRET));
        assertNull(FlickrUrls.assetId(SERVER, null, SECRET));
        assertNull(FlickrUrls.assetId(SERVER, PHOTO_ID, ""));
        assertNull("a blank server segment would build a broken URL", FlickrUrls.assetId("  ", PHOTO_ID, SECRET));
    }

    /** Junk that merely looks like an id — including a real Immich UUID — must not be decoded. */
    @Test
    public void foreignIdentifiersAreNotOurs() {
        assertFalse(FlickrUrls.isOurs("01234567-89ab-cdef-0123-456789abcdef"));
        assertFalse(FlickrUrls.isOurs("not-an-id"));
        assertFalse(FlickrUrls.isOurs("zz-zz-zz"));
        assertFalse(FlickrUrls.isOurs(null));
        assertNull(FlickrUrls.photoIdOf("aaaa-bbbb"));
    }

    /**
     * An Immich UUID has the same alphabet and dash pattern as a piece of this scheme, so the check that
     * decides "may I delete this file" has to reject it rather than treat it as one of ours.
     */
    @Test
    public void anImmichUuidIsNotMistakenForAFlickrId() {
        String uuid = "01234567-89ab-cdef-0123-456789abcdef";
        assertFalse(FlickrUrls.isOurs(uuid));
        assertNull(FlickrUrls.originalUrl(uuid, FlickrUrls.DEFAULT_CDN_BASE));
        assertNull(FlickrUrls.fallbackUrl(uuid, FlickrUrls.DEFAULT_CDN_BASE));
    }

    private static void write(File file) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(new byte[]{1, 2, 3});
        } finally {
            out.close();
        }
    }
}
