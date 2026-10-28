package com.aphex3k.media.flickr;

import androidx.annotation.Nullable;

import com.aphex3k.eo1.ApiServiceGenerator;
import com.aphex3k.eo1.MediaDownloadFailedException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.QueryMap;
import retrofit2.http.Streaming;
import retrofit2.http.Url;

/**
 * Reads a Flickr photostream or album over the REST API, authenticated by an application key alone.
 *
 * <p>Every listing method used here says "This method does not require authentication" in Flickr's own
 * documentation, and a key-only call is the unsigned machine-to-machine path — the server complains
 * about the key, not about a missing signature. What that buys is no login handshake at all, which
 * matters on a headless kiosk: Flickr's OAuth step is a browser redirect with no documented out-of-band
 * PIN mode, so there is nothing for a frame to complete. What it costs is stated in
 * {@link FlickrMediaBackend#compatibilityWarning()}: an unauthenticated call sees public <em>and Safe</em>
 * content only.
 *
 * <p>Responses are read as a {@link JsonObject} tree rather than bound to classes. Flickr publishes no
 * JSON schema — every example on its method pages is the XML form — and the one measured discrepancy
 * matters: the docs show an error {@code code} as a string while a live answer carries it as a number.
 * A typed DTO built from the documentation would mismatch exactly where a frame needs the message, so
 * numbers are read leniently here instead.
 */
public class FlickrClient {

    /**
     * Origin of the REST endpoint. The path lives on the service method, so a test can hand this
     * constructor a local origin and the same relative path resolves.
     */
    public static final String REST_ORIGIN = "https://api.flickr.com/";

    /**
     * 100 per page rather than the allowed 500: Flickr's API terms prohibit displaying more than 30
     * photos per page, and metadata paging at 500 reads against the letter of that even though the
     * frame shows one photo at a time. The paging cost of 100 is a few more requests on a rebuild.
     */
    static final int PER_PAGE = 100;

    /** Ceiling on records one catalog build will accumulate, so a huge stream cannot own the heap. */
    static final int MAX_ASSETS = 20000;

    /**
     * The fields this backend needs. Asked for wholesale and read tolerantly:
     * {@code photosets.getPhotos} publishes a stale, shorter extras list than {@code people.getPhotos},
     * so a missing key is a normal answer rather than a bug.
     */
    static final String EXTRAS = "url_l,url_c,url_z,media,original_format,icon_server,owner_name";

    /** {@code .../photos/<owner>/albums/<setId>} — the album form, with any host and any trailing slash. */
    private static final Pattern ALBUM_URL =
            Pattern.compile("/photos/([^/?#]+)/albums/([^/?#]+)", Pattern.CASE_INSENSITIVE);

    /** {@code .../photos/<owner>/} — a photostream, profile or path alias. */
    private static final Pattern PERSON_URL =
            Pattern.compile("/photos/([^/?#]+)", Pattern.CASE_INSENSITIVE);

    /** Retrofit's view of the endpoint. Package-private, like the frozen Immich service surface. */
    interface Service {
        @GET("services/rest/")
        Call<JsonObject> rest(@QueryMap Map<String, String> params);

        @Streaming
        @GET
        Call<ResponseBody> open(@Url String url);
    }

    /** What a configured {@code source} resolved to: whose stream, and optionally which album. */
    static final class Target {
        @Nullable final String nsid;
        @Nullable final String photosetId;

        Target(@Nullable String nsid, @Nullable String photosetId) {
            this.nsid = nsid;
            this.photosetId = photosetId;
        }
    }

    /** One photo as a listing method described it — enough to build both ladder rungs. */
    static final class Photo {
        final String photoId;
        @Nullable final String secret;
        @Nullable final String server;
        @Nullable final String title;
        /** Flickr's {@code media} field: {@code "photo"} or {@code "video"}. */
        @Nullable final String media;
        final int width;
        final int height;

        Photo(String photoId, @Nullable String secret, @Nullable String server,
              @Nullable String title, @Nullable String media, int width, int height) {
            this.photoId = photoId;
            this.secret = secret;
            this.server = server;
            this.title = title;
            this.media = media;
            this.width = width;
            this.height = height;
        }
    }

    /** One page, plus the paging facts the caller needs in order to stop honestly. */
    static final class Page {
        final List<Photo> photos = new ArrayList<>();
        int page = 1;
        int pages = 1;
        long total;
    }

    private final String apiKey;
    private final Service service;

    public FlickrClient(String apiKey) {
        this(apiKey, REST_ORIGIN);
    }

    /**
     * @param origin REST origin, {@code https://api.flickr.com/} in production and a local server's in
     *        tests. No {@code Context} reaches here, matching how every backend builds its client.
     */
    public FlickrClient(String apiKey, String origin) {
        this.apiKey = apiKey;
        this.service = ApiServiceGenerator.createService(Service.class, origin, null, null);
    }

    /**
     * Turns a configured {@code source} into something the listing methods accept.
     *
     * <p>A URL goes through {@code urls.lookupUser} rather than being split apart, because the segment
     * after {@code /photos/} may be an nsid, a username or a path alias, and only Flickr knows which.
     * A value holding {@code @} is already an nsid and costs no request.
     */
    public Target resolve(String source) throws Exception {
        String trimmed = source == null ? "" : source.trim();
        if (trimmed.isEmpty()) {
            throw new FlickrApiException(-1, "Flickr source is empty");
        }
        Matcher album = ALBUM_URL.matcher(trimmed);
        if (album.find()) {
            return new Target(lookupNsid(album.group(1), trimmed), album.group(2));
        }
        Matcher person = PERSON_URL.matcher(trimmed);
        if (person.find()) {
            return new Target(lookupNsid(person.group(1), trimmed), null);
        }
        if (trimmed.contains("@")) {
            // Already an nsid: no lookup, and no way to confuse it with a username.
            return new Target(trimmed, null);
        }
        return new Target(findByUsername(trimmed), null);
    }

    /**
     * nsid for the owner half of a URL. A numeric segment is a path alias rather than an nsid, so
     * {@code urls.lookupUser} is asked about the page rather than about the segment.
     */
    private String lookupNsid(String ownerSegment, String fullUrl) throws Exception {
        if (ownerSegment.contains("@")) {
            return ownerSegment;
        }
        Map<String, String> params = base("flickr.urls.lookupUser");
        params.put("url", fullUrl);
        JsonObject rsp = call(params);
        JsonObject user = object(rsp, "user");
        String nsid = user == null ? null : string(user, "id");
        if (nsid != null && !nsid.isEmpty()) {
            return nsid;
        }
        // lookupUser answers with the id at the top level in some shapes; accept either before failing,
        // but never fall back to the raw segment: a path alias is not an nsid and would list nothing.
        String atTop = string(rsp, "id");
        if (atTop != null && !atTop.isEmpty()) {
            return atTop;
        }
        throw new FlickrApiException(-1, "Flickr did not recognise this stream: " + fullUrl);
    }

    private String findByUsername(String username) throws Exception {
        Map<String, String> params = base("flickr.people.findByUsername");
        params.put("username", username);
        JsonObject rsp = call(params);
        JsonObject user = object(rsp, "user");
        String nsid = user == null ? null : string(user, "nsid");
        if (nsid == null || nsid.isEmpty()) {
            throw new FlickrApiException(-1, "No Flickr user named '" + username + "'");
        }
        return nsid;
    }

    /** One page of a photostream. */
    public Page publicPhotos(String nsid, int page) throws Exception {
        Map<String, String> params = base("flickr.people.getPublicPhotos");
        params.put("user_id", nsid);
        return page(params, page, "photos");
    }

    /** One page of one album. */
    public Page photosetPhotos(String nsid, String photosetId, int page) throws Exception {
        Map<String, String> params = base("flickr.photosets.getPhotos");
        params.put("user_id", nsid);
        params.put("photoset_id", photosetId);
        return page(params, page, "photoset");
    }

    /**
     * Reads one page. The photo array sits beside the wrapper object in the JSON form of both methods
     * ({@code photos.photo} and {@code photoset.photo} become a top-level {@code photo} array), which is
     * why the array is looked for in two places rather than only under {@code containerKey}.
     */
    private Page page(Map<String, String> params, int pageNumber, String containerKey)
            throws Exception {
        params.put("page", String.valueOf(pageNumber));
        params.put("per_page", String.valueOf(PER_PAGE));
        JsonObject rsp = call(params);

        Page result = new Page();
        result.page = pageNumber;
        JsonObject container = object(rsp, containerKey);
        if (container != null) {
            result.pages = positiveInt(container, "pages", 1);
            result.total = positiveLong(container, "total", 0L);
        }
        JsonArray array = array(rsp, "photo");
        if (array == null && container != null) {
            array = array(container, "photo");
        }
        if (array != null) {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject photo = element.getAsJsonObject();
                String id = string(photo, "id");
                if (id == null || id.isEmpty()) {
                    continue;
                }
                result.photos.add(new Photo(id, string(photo, "secret"), string(photo, "server"),
                        string(photo, "title"), string(photo, "media"),
                        intOr(string(photo, "width_l"), string(photo, "width_c")),
                        intOr(string(photo, "height_l"), string(photo, "height_c"))));
            }
        }
        return result;
    }

    /**
     * Opens the bytes behind one photo URL.
     *
     * <p>A non-2xx becomes a failure with a message and <em>no cause</em>, which is how this pipeline
     * distinguishes "this asset's source is not there" (descend one rung, once) from "the wire is down"
     * (propagate, because no asset caused it). Flickr answers a tier the account cannot receive with
     * {@code 410} and a 462-byte body, so letting the body through as image bytes would cache an error
     * page and flag a healthy photo.
     */
    public InputStream openBytes(String url) throws IOException, MediaDownloadFailedException {
        Response<ResponseBody> response = service.open(url).execute();
        if (!response.isSuccessful() || response.body() == null) {
            int code = response.code();
            throw new MediaDownloadFailedException("Flickr returned HTTP " + code + " for " + url);
        }
        return response.body().byteStream();
    }

    /** Parameters every call carries, including the key. */
    private Map<String, String> base(String method) {
        Map<String, String> params = new HashMap<>();
        params.put("method", method);
        params.put("api_key", apiKey);
        // Without nojsoncallback the body is JSONP wrapped in jsonFlickrApi(...) and Gson will not parse it.
        params.put("format", "json");
        params.put("nojsoncallback", "1");
        params.put("extras", EXTRAS);
        return params;
    }

    /** Executes one REST call and unwraps Flickr's envelope, raising on any {@code stat=fail}. */
    private JsonObject call(Map<String, String> params) throws Exception {
        Response<JsonObject> response = service.rest(params).execute();
        if (!response.isSuccessful()) {
            // Transport-level failure: an HTTP status, not an API answer. Message only, no cause, for
            // the reason openBytes gives.
            throw new FlickrApiException(-1, "Flickr returned HTTP " + response.code());
        }
        JsonObject body = response.body();
        if (body == null) {
            throw new FlickrApiException(-1, "Flickr returned no body");
        }
        String stat = string(body, "stat");
        if (stat != null && !"ok".equalsIgnoreCase(stat)) {
            throw new FlickrApiException(errorCode(body), errorMessage(body));
        }
        return body;
    }

    /** The failure code, from either documented shape: {@code code} at top level or {@code err.code}. */
    private static int errorCode(JsonObject body) {
        JsonObject err = object(body, "err");
        Integer top = integer(body, "code");
        if (top != null) {
            return top;
        }
        Integer nested = err == null ? null : integer(err, "code");
        return nested == null ? -1 : nested;
    }

    /** The failure text, from whichever field the answer used ({@code message} or {@code err.msg}). */
    private static String errorMessage(JsonObject body) {
        String message = string(body, "message");
        if (message != null && !message.isEmpty()) {
            return message;
        }
        JsonObject err = object(body, "err");
        String msg = err == null ? null : string(err, "msg");
        return msg == null || msg.isEmpty() ? "Flickr reported a failure" : msg;
    }

    @Nullable
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    @Nullable
    private static JsonArray array(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    @Nullable
    private static String string(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Integer integer(JsonObject parent, String key) {
        String raw = string(parent, key);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Flickr numbers arrive both as JSON numbers and as strings; neither is an error. */
    private static int positiveInt(JsonObject parent, String key, int fallback) {
        Integer value = integer(parent, key);
        return value == null || value <= 0 ? fallback : value;
    }

    private static long positiveLong(JsonObject parent, String key, long fallback) {
        String raw = string(parent, key);
        if (raw == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** First parseable of two strings, so {@code url_l} and its absence both work. */
    private static int intOr(@Nullable String first, @Nullable String second) {
        Integer a = parse(first);
        if (a != null) {
            return a;
        }
        Integer b = parse(second);
        return b == null ? 0 : b;
    }

    @Nullable
    private static Integer parse(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
