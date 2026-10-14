package com.aphex3k.media.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;

import org.junit.Test;

import java.util.List;

/**
 * The web-page backend: one configured URL, one pool record, and the address travelling through
 * {@link MediaSource} instead of the asset record. No Android classes are touched, so this runs
 * on the JVM.
 */
public class WebsiteBackendTest {

    private static WebsiteBackend backend(String id, String url) {
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_WEB;
        entry.id = id;
        entry.host = url;
        return new WebsiteBackend(entry);
    }

    @Test
    public void catalogIsTheSingleConfiguredPage() {
        WebsiteBackend b = backend("web-1", "http://192.168.1.20/dash/");
        List<MediaAsset> assets = b.fetchCatalog();

        assertEquals(1, assets.size());
        MediaAsset a = assets.get(0);
        assertEquals(MediaType.WEB, a.type);
        assertEquals("web-1", a.backendId);
        assertEquals(WebsiteBackend.webAssetIdFor("http://192.168.1.20/dash/"), a.id);
        assertEquals("web-1:" + a.id, a.key());
        // Nothing else about a page is knowable up front, and the pool must stay compact.
        assertEquals(-1, a.durationMs);
        assertNull(a.originalFileName);
        assertNull(a.originalPath);
        assertNull(a.localPath);
        assertNull(a.checksum);
        assertNull(a.sizeBytes);
        assertEquals(0, a.width);
        assertEquals(0, a.height);
    }

    @Test
    public void assetIdIsStableAcrossRebuilds() {
        String first = backend("web-1", "http://host/a").fetchCatalog().get(0).id;
        String second = backend("web-1", "http://host/a").fetchCatalog().get(0).id;
        assertEquals(first, second);
        assertFalse(first.equals(backend("web-1", "http://host/b").fetchCatalog().get(0).id));
    }

    @Test
    public void resolveOriginalCarriesTheUrlAndNothingElse() {
        WebsiteBackend b = backend("web-1", "http://host/a");
        MediaSource src = b.resolveOriginal(b.fetchCatalog().get(0));

        assertNotNull(src);
        assertEquals(MediaSource.Kind.REMOTE_URL, src.kind);
        assertEquals("http://host/a", src.url);
        assertFalse(src.isLocal());
        assertFalse(src.isFallback());
        assertNull(src.opener);
        assertNull(src.localFile);
    }

    @Test
    public void resolveOriginalIgnoresAnUnknownAsset() {
        MediaAsset other = new MediaAsset("someone-elses-id", "web-1", MediaType.WEB, -1,
                null, null, null, null, null, 0, 0);
        assertNull(backend("web-1", "http://host/a").resolveOriginal(other));
    }

    @Test
    public void metadataAndAbsentOptionalCapabilities() throws Exception {
        WebsiteBackend b = backend("web-1", "http://host/a");
        assertEquals("web-1", b.getId());
        assertEquals(ConfigurationBackendEntry.TYPE_WEB, b.getType());
        assertEquals("web page http://host/a", b.describeVersion());

        MediaAsset a = b.fetchCatalog().get(0);
        // A page has neither a degraded rendition nor a server-side incompatibility tag to write.
        assertNull(b.resolveThumbnailFallback(a));
        b.markIncompatible(a);
        b.initialize();
        b.close();
    }

    @Test
    public void configuredUrlIsTrimmedButOtherwiseVerbatim() {
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_WEB;
        entry.id = "web-1";
        entry.host = "  http://host/a/  ";
        WebsiteBackend b = new WebsiteBackend(entry);

        MediaSource src = b.resolveOriginal(b.fetchCatalog().get(0));
        assertEquals("http://host/a/", src.url);
    }
}
