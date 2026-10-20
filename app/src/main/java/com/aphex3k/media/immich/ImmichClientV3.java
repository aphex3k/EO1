package com.aphex3k.media.immich;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aphex3k.eo1.ApiServiceGenerator;
import com.aphex3k.eo1.AuthenticationFailedException;
import com.aphex3k.eo1.AuthenticationUnavailableException;
import com.aphex3k.eo1.ImmichApiTagException;
import com.aphex3k.eo1.InvalidCredentialsException;
import com.aphex3k.eo1.MediaDownloadFailedException;
import com.aphex3k.immichApi.ImmichApiAssetResponse;
import com.aphex3k.immichApi.ImmichApiGetAlbumResponse;
import com.aphex3k.immichApi.ImmichApiLogin;
import com.aphex3k.immichApi.ImmichApiLoginResponse;
import com.aphex3k.immichApi.ImmichApiMetadataSearchBody;
import com.aphex3k.immichApi.ImmichApiMetadataSearchResponse;
import com.aphex3k.immichApi.ImmichApiService;
import com.aphex3k.immichApi.ImmichApiTag;
import com.aphex3k.immichApi.ImmichApiTagAssetBody;
import com.aphex3k.immichApi.ImmichApiTagResponse;
import com.aphex3k.immichApi.ImmichSizeFormat;
import com.vdurmont.semver4j.Semver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

/**
 * Immich client for the whole 3.x API line (endpoints verified identical through 3.3.3),
 * implemented over the frozen {@code com.aphex3k.immichApi} service surface.
 *
 * <p>One instance owns one Retrofit service, and authenticates through the process-wide
 * {@link ImmichSessionStore} rather than a session of its own: the token is a bearer JWT attached
 * to every request that needs one, it is renewed when its own expiry says so or when the server
 * answers 401, and it survives this instance being replaced by the next pool rebuild. The
 * username and password are spent on {@code /api/auth/login} and on nothing else.
 *
 * <p>{@code @Keep}: the band table instantiates this class reflectively
 * ({@code ImmichMediaBackend#instantiate}), so the release build must not strip
 * its constructor.
 *
 * <p>Responses are never closed via {@code response.raw().close()}: after
 * {@code execute()} returns, Retrofit 2.6 has already replaced the raw body with a
 * placeholder whose {@code close()} throws {@code IllegalStateException}. The real
 * body is closed by the Gson converter on 2xx and by Retrofit itself on error/204
 * paths; for the {@code Call<okhttp3.ResponseBody>} streaming calls the returned
 * stream is the body and the pipeline closes it.
 */
@Keep
public class ImmichClientV3 implements ImmichClient {

    private static final int SEARCH_PAGE_SIZE = 1000;

    private final ImmichApiService service;
    private final String userid;
    private final String password;
    private final ImmichSessionStore.Session session;

    /** Held by the store to mint a token; never calls back into {@link #login()}. */
    private final ImmichSessionStore.LoginRunner loginRunner =
            new ImmichSessionStore.LoginRunner() {
                @Override
                public String run()
                        throws AuthenticationFailedException, AuthenticationUnavailableException,
                        InvalidCredentialsException, IOException {
                    return doLogin();
                }
            };

    public ImmichClientV3(String host, String userid, String password,
                          @Nullable ApiServiceGenerator.ProgressListener progressListener) {
        this.session = ImmichSessionStore.get().attach(host, userid, password);
        this.service = ApiServiceGenerator.createService(ImmichApiService.class, host, null,
                progressListener, new ImmichAuthInterceptor(session));
        this.userid = userid;
        this.password = password;
    }

    /**
     * One-shot Retrofit call factory for {@link #withSession}. A plain app interface rather
     * than {@code java.util.function.Supplier}, which does not exist on API 19 and would
     * fail class linking on the device.
     */
    interface CallFactory<T> {
        Call<T> newCall();
    }

    @Override
    @Nullable
    public Semver probeServerVersion() {
        try {
            Response<com.aphex3k.immichApi.ImmichApiServerVersionResponse> response =
                    service.getServerVersion().execute();
            com.aphex3k.immichApi.ImmichApiServerVersionResponse body = response.body();
            if (body == null) {
                return null;
            }
            Semver version = body.getVersion();
            // All-zero fields mean major/minor/patch were absent from the payload; treat
            // that as a failed probe instead of an out-of-band "0.0.0" server.
            return version != null && !"0.0.0".equals(version.getValue()) ? version : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Makes sure this backend holds a usable token — which, after a pool rebuild or a config
     * reload, usually means reusing the one the process already has and touching no network.
     * The exceptions below are therefore raised by the login this call may trigger, not by the
     * call itself.
     */
    @Override
    public void login()
            throws AuthenticationFailedException, AuthenticationUnavailableException,
            InvalidCredentialsException, IOException {
        session.token(loginRunner);
    }

    /** What the session store runs when it actually needs a new token. */
    private String doLogin()
            throws AuthenticationFailedException, AuthenticationUnavailableException,
            InvalidCredentialsException, IOException {
        Response<ImmichApiLoginResponse> response =
                service.login(new ImmichApiLogin(userid, password)).execute();
        ImmichApiLoginResponse loginResponse = response.body();
        if (response.code() == 401) {
            throw new AuthenticationFailedException(response.code());
        }
        if (response.code() == 404) {
            throw new AuthenticationUnavailableException(response.code());
        }
        if (response.isSuccessful() && loginResponse == null) {
            throw new AuthenticationUnavailableException(response.code());
        }
        String userId = loginResponse != null ? loginResponse.getUserId() : null;
        if (userId == null) {
            throw new AuthenticationFailedException(-1);
        }
        if (userId.isEmpty()) {
            throw new InvalidCredentialsException();
        }
        // The one place the token is read out of the login response. It used to be discarded and
        // the session carried by a replayed cookie instead; Immich's cookie is this same JWT, so
        // holding it here changes nothing about what the server sees and everything about what
        // this app knows — an expiry it can check, and a credential it can stop sending.
        return loginResponse.getAccessToken();
    }

    @Override
    public boolean isLoggedIn() {
        return session.hasToken();
    }

    @Override
    public List<ImmichApiAssetResponse> fetchCatalogAssets() throws IOException {
        List<ImmichApiAssetResponse> assets = new ArrayList<>();
        // Owned albums (isShared=false) and shared albums (isShared=true).
        for (int i = 0; i < 2; i++) {
            final boolean shared = i == 1;
            Response<List<ImmichApiGetAlbumResponse>> albumsResponse =
                    withSession(() -> service.getAllAlbums(shared, null));
            List<ImmichApiGetAlbumResponse> albums =
                    albumsResponse.body() != null ? albumsResponse.body() : new ArrayList<>();
            for (ImmichApiGetAlbumResponse album : albums) {
                if (album.getId() == null || album.getAssetCount() == 0) {
                    continue;
                }
                addAssetsFromSearch(assets, new ImmichApiMetadataSearchBody(1, SEARCH_PAGE_SIZE)
                        .withAlbumIds(Collections.singletonList(album.getId())));
            }
        }
        // All timeline assets visible to this account.
        addAssetsFromSearch(assets, new ImmichApiMetadataSearchBody(1, SEARCH_PAGE_SIZE));
        return assets;
    }

    /** Pages through a metadata search until a short page comes back. */
    private void addAssetsFromSearch(List<ImmichApiAssetResponse> out,
                                     ImmichApiMetadataSearchBody firstPage)
            throws IOException {
        int page = firstPage.getPage() != null ? firstPage.getPage() : 1;
        int pageSize = firstPage.getSize() != null ? firstPage.getSize() : SEARCH_PAGE_SIZE;
        int count = pageSize;

        while (count == pageSize) {
            ImmichApiMetadataSearchBody body = new ImmichApiMetadataSearchBody(page, pageSize)
                    .withAlbumIds(firstPage.getAlbumIds())
                    .withIsFavorite(firstPage.getIsFavorite())
                    .withIsNotInAlbum(firstPage.getIsNotInAlbum())
                    .withTagIds(firstPage.getTagIds());

            Response<ImmichApiMetadataSearchResponse> assetsResponse =
                    withSession(() -> service.getAllAssets(body));

            List<ImmichApiAssetResponse> items = null;
            if (assetsResponse.body() != null && assetsResponse.body().getAssets() != null) {
                items = assetsResponse.body().getAssets().getItems();
            }

            if (assetsResponse.isSuccessful() && items != null && !items.isEmpty()) {
                out.addAll(items);
            }

            page++;
            count = items != null ? items.size() : 0;
        }
    }

    @Override
    public List<String> listTaggedAssetIds(String tagName) throws IOException {
        final List<String> ids = new ArrayList<>();
        String tagId = findTagId(tagName);
        if (tagId == null) {
            return ids;
        }
        final List<ImmichApiAssetResponse> tagged = new ArrayList<>();
        addAssetsFromSearch(tagged, new ImmichApiMetadataSearchBody(1, SEARCH_PAGE_SIZE)
                .withTagIds(Collections.singletonList(tagId)));
        for (ImmichApiAssetResponse asset : tagged) {
            if (asset.getId() != null) {
                ids.add(asset.getId());
            }
        }
        return ids;
    }

    @Override
    public InputStream originalStream(String assetId) throws IOException, MediaDownloadFailedException {
        return openStreaming(() -> service.downloadFile(assetId, null));
    }

    @Override
    public InputStream thumbnailStream(String assetId) throws IOException, MediaDownloadFailedException {
        return openStreaming(() -> service.getAssetThumbnail(assetId, ImmichSizeFormat.preview, null));
    }

    @Override
    public InputStream videoPlaybackStream(String assetId) throws IOException, MediaDownloadFailedException {
        return openStreaming(() -> service.playAssetVideo(assetId, null));
    }

    /**
     * Asks the playback endpoint how big its answer would be, without taking it.
     *
     * <p>The status code decides which header is the truth. A {@code 206} that honoured the range
     * reports {@code Content-Length: 2}, and reading that as the file's size would call every
     * transcode on the server a two-byte file; the total lives after the {@code /} in
     * {@code Content-Range}. A plain {@code 200} means the server ignored {@code Range} and the
     * body it is about to send is the whole asset, so {@code Content-Length} is correct there.
     * Anything ambiguous yields {@code null}, which callers read as "assume there is something
     * worth fetching" — the safe direction, because the mistake it avoids is skipping a rung that
     * would have worked.
     */
    @Override
    public Long videoPlaybackSize(String assetId) throws IOException, MediaDownloadFailedException {
        Response<okhttp3.ResponseBody> response =
                withSession(() -> service.probeAssetVideoPlayback(assetId, null));
        okhttp3.ResponseBody body = response.body();
        try {
            if (!response.isSuccessful()) {
                throw new MediaDownloadFailedException(
                        "Failed probing immich playback: HTTP " + response.code());
            }
            if (response.code() == 206) {
                return contentRangeTotal(response.headers().get("Content-Range"));
            }
            String length = response.headers().get("Content-Length");
            if (length == null) {
                return null;
            }
            try {
                long parsed = Long.parseLong(length.trim());
                return parsed > 0L ? parsed : null;
            } catch (NumberFormatException e) {
                return null;
            }
        } finally {
            if (body != null) {
                body.close();
            }
        }
    }

    /** {@code bytes 0-0/165908629} to {@code 165908629}; anything else to null. */
    private static Long contentRangeTotal(String contentRange) {
        if (contentRange == null) {
            return null;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1) {
            return null;
        }
        String total = contentRange.substring(slash + 1).trim();
        if ("*".equals(total)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(total);
            return parsed > 0L ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Takes a factory rather than a {@code Call} because a stream that answered 401 needs a
     * second attempt, and a Retrofit call can only be executed once — handing the same instance to
     * {@link #withSession} twice threw {@code IllegalStateException: Already Executed}. Expired
     * tokens make that 401 an ordinary Tuesday on this path, which is the one that feeds the
     * display.
     */
    private InputStream openStreaming(CallFactory<okhttp3.ResponseBody> callFactory)
            throws IOException, MediaDownloadFailedException {
        Response<okhttp3.ResponseBody> response = withSession(callFactory);
        if (!response.isSuccessful() || response.body() == null) {
            int code = response.code();
            throw new MediaDownloadFailedException("Failed downloading immich asset: HTTP " + code);
        }
        return response.body().byteStream();
    }

    /**
     * Finds or creates {@code tagName} and adds {@code assetId} to it. Every step reports its
     * failure: a tag that was never written used to return quietly, which made "nothing in the
     * Immich tag list" indistinguishable from "nothing needed tagging".
     */
    @Override
    public void tagAssetIncompatible(String assetId, String tagName)
            throws IOException, ImmichApiTagException {
        Response<List<ImmichApiTagResponse>> allTagsResponse =
                withSession(() -> service.getAllTags());
        if (!allTagsResponse.isSuccessful()) {
            throw new ImmichApiTagException(
                    "could not list tags to find " + tagName + ": " + describe(allTagsResponse));
        }
        String tagId = matchTagIdByName(allTagsResponse.body(), tagName);
        if (tagId == null) {
            Response<ImmichApiTagResponse> createTag =
                    withSession(() -> service.createTag(new ImmichApiTag(tagName)));
            if (!createTag.isSuccessful()) {
                throw new ImmichApiTagException("could not create tag "
                        + tagName + ": " + describe(createTag));
            }
            ImmichApiTagResponse created = createTag.body();
            tagId = created != null ? created.getId() : null;
            if (tagId == null) {
                throw new ImmichApiTagException(
                        "created tag " + tagName + " but the server returned no id");
            }
        }
        final String finalTagId = tagId;
        Response<List<com.aphex3k.immichApi.ImmichApiTagAssetResponse>> addTag =
                withSession(() -> service.tagAssets(
                        finalTagId, new ImmichApiTagAssetBody(Collections.singletonList(assetId))));
        if (!addTag.isSuccessful()) {
            throw new ImmichApiTagException("could not tag asset " + assetId
                    + " with " + tagName + " (tag " + finalTagId + "): " + describe(addTag));
        }
    }

    /**
     * Finds the id of a tag with the given name via {@code GET /api/tags}.
     *
     * @return the tag id, or null when the server has no such tag
     */
    @Nullable
    private String findTagId(String tagName) throws IOException {
        Response<List<ImmichApiTagResponse>> allTagsResponse =
                withSession(() -> service.getAllTags());
        return allTagsResponse.isSuccessful()
                ? matchTagIdByName(allTagsResponse.body(), tagName)
                : null;
    }

    @Nullable
    private static String matchTagIdByName(List<ImmichApiTagResponse> tags, String tagName) {
        if (tags == null) {
            return null;
        }
        for (ImmichApiTagResponse tag : tags) {
            if (tag != null && tagName.equals(tag.getName())) {
                return tag.getId();
            }
        }
        return null;
    }

    /** Status line of a failed response, with whatever reason the server body carries. */
    private static String describe(Response<?> response) {
        String detail = "";
        try {
            okhttp3.ResponseBody error = response.errorBody();
            if (error != null) {
                String text = error.string().trim();
                if (!text.isEmpty()) {
                    detail = " " + (text.length() > 200 ? text.substring(0, 200) : text);
                }
            }
        } catch (Exception e) {
            // The status code already says the most useful part.
        }
        return "HTTP " + response.code() + detail;
    }

    /**
     * Executes an authenticated call against a token known to be current: renewed before the first
     * attempt if its own expiry has arrived, and re-established once if the server disagrees and
     * answers 401. Retrofit calls are one-shot, so the call factory is invoked again.
     *
     * <p>A refused re-login propagates as an {@code IOException} <em>with a cause</em>, which is
     * how {@link com.aphex3k.eo1.MediaManager} tells "this asset is undecodable" from "this frame
     * could not reach its server": the second is nobody's asset and must not get tagged.
     */
    private <T> Response<T> withSession(CallFactory<T> callFactory) throws IOException {
        try {
            session.renewIfSpent(loginRunner);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("immich login failed: " + e, e);
        }
        Response<T> response = callFactory.newCall().execute();
        if (response.code() == 401) {
            try {
                session.reauthenticate(loginRunner);
            } catch (Exception e) {
                throw new IOException("re-login failed: " + e, e);
            }
            response = callFactory.newCall().execute();
        }
        return response;
    }
}
