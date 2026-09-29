package com.aphex3k.media.immich;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aphex3k.eo1.ApiServiceGenerator;
import com.aphex3k.eo1.AuthenticationFailedException;
import com.aphex3k.eo1.AuthenticationUnavailableException;
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
import java.util.function.Supplier;

import retrofit2.Call;
import retrofit2.Response;

/**
 * Immich client for API band 3.0.0–3.2.2, implemented over the frozen
 * {@code com.aphex3k.immichApi} service surface.
 *
 * <p>One instance owns one Retrofit service with its own in-memory session cookie
 * jar, so each configured Immich backend keeps an independent session. The session
 * persists across rotation ticks; a 401 mid-flight triggers one transparent re-login
 * instead of the legacy re-login-per-download behaviour.
 *
 * <p>{@code @Keep}: the band table instantiates this class reflectively
 * ({@code ImmichMediaBackend#instantiate}), so the release build must not strip
 * its constructor.
 */
@Keep
public class ImmichClientV3 implements ImmichClient {

    private static final int SEARCH_PAGE_SIZE = 1000;

    private final ImmichApiService service;
    private final String userid;
    private final String password;
    private volatile boolean loggedIn;

    public ImmichClientV3(String host, String userid, String password,
                          @Nullable ApiServiceGenerator.ProgressListener progressListener) {
        this.service = ApiServiceGenerator.createService(
                ImmichApiService.class, host, null, progressListener);
        this.userid = userid;
        this.password = password;
    }

    @Override
    @Nullable
    public Semver probeServerVersion() {
        try {
            Response<com.aphex3k.immichApi.ImmichApiServerVersionResponse> response =
                    service.getServerVersion().execute();
            try {
                com.aphex3k.immichApi.ImmichApiServerVersionResponse body = response.body();
                return body != null ? body.getVersion() : null;
            } finally {
                response.raw().close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void login()
            throws AuthenticationFailedException, AuthenticationUnavailableException,
            InvalidCredentialsException, IOException {
        Response<ImmichApiLoginResponse> response =
                service.login(new ImmichApiLogin(userid, password)).execute();
        try {
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
            String userId;
            if (loginResponse != null && !loginResponse.getUserId().isEmpty()) {
                userId = loginResponse.getUserId();
            } else {
                throw new AuthenticationFailedException(-1);
            }
            if (userId == null || userId.isEmpty()) {
                throw new InvalidCredentialsException();
            }
        } finally {
            response.raw().close();
        }
        loggedIn = true;
    }

    @Override
    public boolean isLoggedIn() {
        return loggedIn;
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
                    .withIsNotInAlbum(firstPage.getIsNotInAlbum());

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
    public InputStream originalStream(String assetId) throws IOException, MediaDownloadFailedException {
        return openStreaming(service.downloadFile(assetId, null));
    }

    @Override
    public InputStream thumbnailStream(String assetId) throws IOException, MediaDownloadFailedException {
        return openStreaming(service.getAssetThumbnail(assetId, ImmichSizeFormat.preview, null));
    }

    @Override
    public InputStream videoPlaybackStream(String assetId) throws IOException, MediaDownloadFailedException {
        return openStreaming(service.playAssetVideo(assetId, null));
    }

    private InputStream openStreaming(Call<okhttp3.ResponseBody> call)
            throws IOException, MediaDownloadFailedException {
        Response<okhttp3.ResponseBody> response = withSession(() -> call);
        if (!response.isSuccessful() || response.body() == null) {
            int code = response.code();
            response.raw().close();
            throw new MediaDownloadFailedException("Failed downloading immich asset: HTTP " + code);
        }
        return response.body().byteStream();
    }

    @Override
    public void tagAssetIncompatible(String assetId, String tagName)
            throws IOException, com.aphex3k.eo1.ImmichApiTagException {
        String tagId = null;
        Response<List<ImmichApiTagResponse>> allTagsResponse =
                withSession(() -> service.getAllTags());
        if (allTagsResponse.isSuccessful() && allTagsResponse.body() != null) {
            for (ImmichApiTagResponse tag : allTagsResponse.body()) {
                if (tagName.equals(tag.getName())) {
                    tagId = tag.getId();
                    break;
                }
            }
        }
        if (tagId == null) {
            Response<ImmichApiTagResponse> createTag =
                    withSession(() -> service.createTag(new ImmichApiTag(tagName)));
            if (createTag.isSuccessful() && createTag.body() != null) {
                tagId = createTag.body().getId();
            }
        }
        if (tagId != null) {
            final String finalTagId = tagId;
            Response<List<com.aphex3k.immichApi.ImmichApiTagAssetResponse>> addTag =
                    withSession(() -> service.tagAssets(
                            finalTagId, new ImmichApiTagAssetBody(Collections.singletonList(assetId))));
            if (!addTag.isSuccessful()) {
                throw new com.aphex3k.eo1.ImmichApiTagException();
            }
        }
    }

    /**
     * Executes an authenticated call; on a 401 the session is re-established once and the
     * call retried. Retrofit calls are one-shot, so the call factory is invoked again.
     */
    private <T> Response<T> withSession(Supplier<Call<T>> callFactory) throws IOException {
        Response<T> response = callFactory.get().execute();
        if (response.code() == 401) {
            response.raw().close();
            try {
                loggedIn = false;
                login();
            } catch (Exception e) {
                throw new IOException("re-login failed", e);
            }
            response = callFactory.get().execute();
        }
        return response;
    }
}
