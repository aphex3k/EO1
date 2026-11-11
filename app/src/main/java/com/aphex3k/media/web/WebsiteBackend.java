package com.aphex3k.media.web;

import android.util.Log;

import androidx.annotation.Nullable;

import com.aphex3k.eo1.ConfigurationBackendEntry;
import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaBackend;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backend that displays a web page instead of a file: the single URL configured on a {@code web}
 * entry, rendered fullscreen by the app's kiosk WebView.
 *
 * <p>Nothing is downloaded, cached or codec-checked — there are no bytes to acquire here. The page
 * address therefore stays out of the rotation pool record ({@link MediaAsset} has no URL field, on
 * purpose) and travels through {@link #resolveOriginal} as a {@link MediaSource.Kind#REMOTE_URL}
 * source instead.
 *
 * <p>Reaching the page is never validated here: a DNS failure during pool build would throw out of
 * {@code initialize} and drop this backend for the whole cycle, so load failures are left to the
 * display side, which falls through to the next asset.
 */
public class WebsiteBackend implements MediaBackend {

    private static final String TAG = "EO1";

    private final String id;
    private final String url;
    private final Map<String, String> urlById;

    public WebsiteBackend(ConfigurationBackendEntry entry) {
        this.id = entry.id;
        this.url = entry.host == null ? "" : entry.host.trim();
        this.urlById = Collections.singletonMap(webAssetIdFor(this.url), this.url);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return ConfigurationBackendEntry.TYPE_WEB;
    }

    @Override
    public List<MediaAsset> fetchCatalog() {
        List<MediaAsset> assets = new ArrayList<>(1);
        assets.add(new MediaAsset(webAssetIdFor(url), id, MediaType.WEB, -1,
                null, null, null, null, null, 0, 0));
        Log.d(TAG, "Web backend '" + id + "': " + url);
        return assets;
    }

    @Override
    @Nullable
    public MediaSource resolveOriginal(MediaAsset asset) {
        String page = asset == null ? null : urlById.get(asset.id);
        return page == null ? null : MediaSource.remoteUrl(page);
    }

    @Override
    public String describeVersion() {
        return "web page " + url;
    }

    /**
     * The configured page URL, but only if it is still the page that produced this asset id.
     *
     * <p>This is the check {@code /state}'s {@code media.current.url} needs and cannot make from the
     * backend id alone: editing an entry from page A to page B leaves the asset record for A selected
     * until some later rotation succeeds, and an id-only lookup would then print B beside A's key.
     * Because the id is derived from the URL, the pairing is not assumed — it is recomputed with the
     * same function, so an entry that has moved simply stops matching. Null means the configured entry
     * cannot account for this record, and the caller reports that by omitting the key rather than
     * guessing. The trim is the one the constructor applies, so a configured value with stray
     * whitespace matches instead of vanishing from the report for a reason no reader can see.
     */
    @Nullable
    public static String pageForAssetId(String configuredHost, String assetId) {
        if (configuredHost == null || assetId == null) {
            return null;
        }
        String page = configuredHost.trim();
        if (page.isEmpty() || !assetId.equals(webAssetIdFor(page))) {
            return null;
        }
        return page;
    }

    /** Deterministic UUID derived from the page URL; stable across pool rebuilds. */
    public static String webAssetIdFor(String url) {
        return UUID.nameUUIDFromBytes(url.getBytes(Charset.forName("UTF-8"))).toString();
    }
}
