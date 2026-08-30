package com.aphex3k.immichApi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.eo1.ApiServiceGenerator;
import com.aphex3k.eo1.TestConfiguration;

import org.junit.AssumptionViolatedException;
import org.junit.Before;

import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Response;


public class ImmichApiServiceTest {

    private String userId;
    private String exampleImageId;
    private String exampleVideoId;
    private String exampleAlbumId;
    private final String email = "demo@immich.app";
    private final String password = "demo";
    private ImmichApiService apiService;

    @Before
    public void authorize() throws Exception {

        if (!TestConfiguration.RunImmichTests) {
            throw new AssumptionViolatedException("Skipping ImmichTest...");
        }

        if (userId == null) {

            this.apiService = ApiServiceGenerator.createService(ImmichApiService.class, "https://demo.immich.app/", null, null);

            try {
                Response<ImmichApiLoginResponse> response = apiService.login(
                        new ImmichApiLogin(email, password)
                ).execute();

                if (response.code() != 201) {
                    throw new AssumptionViolatedException("Did somebody change the password of the demo account?");
                }

                assert response.body() != null;
                userId = response.body().getUserId();
            }
            catch (UnknownHostException e) {
                throw new AssumptionViolatedException(e.getMessage());
            }

            Response<ImmichApiMetadataSearchResponse> response = apiService.getAllAssets(
                    new ImmichApiMetadataSearchBody(1, 3)
            ).execute();

            assert response.body() != null;

            for (ImmichApiAssetResponse asset: response.body().getAssets().getItems()) {
                if (asset.getType() == ImmichType.IMAGE) {
                    exampleImageId = asset.getId();
                    break;
                }
            }

            Response<List<ImmichApiGetAlbumResponse>> shared = apiService.getAllAlbums(
                    true, null
            ).execute();

            for (ImmichApiGetAlbumResponse r: shared.body()) {
                Response<ImmichApiMetadataSearchResponse> albumAssets = apiService.getAllAssets(
                        new ImmichApiMetadataSearchBody(1, 50)
                                .withAlbumIds(Collections.singletonList(r.getId()))
                ).execute();

                List<ImmichApiAssetResponse> assets =
                        albumAssets.body() != null && albumAssets.body().getAssets() != null
                                ? albumAssets.body().getAssets().getItems()
                                : Collections.<ImmichApiAssetResponse>emptyList();

                for (ImmichApiAssetResponse asset : assets) {
                    if (asset.getType() == ImmichType.VIDEO) {
                        exampleVideoId = asset.getId();
                    }
                    if (asset.getType() == ImmichType.IMAGE) {
                        exampleImageId = asset.getId();
                        exampleAlbumId = r.getId();
                        break;
                    }
                }
            }

        }
    }

    @org.junit.Test
    public void login() throws Exception {

        if (userId == null) {
            Response<ImmichApiLoginResponse> response = apiService.login(
                    new ImmichApiLogin(email, password)
            ).execute();

            assertNotNull(response);
            assertEquals(response.code(), 201);

            ImmichApiLoginResponse body = response.body();

            assertNotNull(body);
            assertNotNull(body.getAccessToken());
            assertEquals(email, body.getUserEmail());
        }
    }

    @org.junit.Test
    public void getAllAlbums() throws Exception {
        Response<List<ImmichApiGetAlbumResponse>> response = apiService.getAllAlbums(
                null, null
        ).execute();

        assertNotNull(response);
        assertEquals(response.code(), 200);

        for (ImmichApiGetAlbumResponse r: response.body()) {
            assertTrue(r.getAssetCount() >= 0);
            assertNotNull(r.getAssets());
        }
    }

    @org.junit.Test
    public void getAllSharedAlbums() throws Exception {
        Response<List<ImmichApiGetAlbumResponse>> response = apiService.getAllAlbums(
                true, null
        ).execute();

        assertNotNull(response);
        assertEquals(response.code(), 200);

        for (ImmichApiGetAlbumResponse r: response.body()) {
            assertTrue(r.getAssetCount() >= 0);
            assertNotNull(r.getAssets());
        }
    }

    @org.junit.Test
    public void getAlbumInfo() throws Exception {

        if (exampleAlbumId == null) {
            throw new AssumptionViolatedException("The @Before function failed to find a valid album containing at least one image.");
        }

        Response<ImmichApiGetAlbumResponse> response = apiService.getAlbumInfo(exampleAlbumId, true, null).execute();

        assertNotNull(response);
        assertEquals(response.code(), 200);
        assertNotNull(response.body());
        assertTrue(response.body().getAssetCount() > 0);
    }

    @org.junit.Test
    public void getAllAssets() throws Exception {
        Response<ImmichApiMetadataSearchResponse> response = apiService.getAllAssets(
            new ImmichApiMetadataSearchBody(1, 3)
        ).execute();

        assertNotNull(response);
        assertEquals(response.code(), 200);

        ImmichApiMetadataSearchResponse body = response.body();

        assertNotNull(body);

        if (body.getAssets().getItems().isEmpty()) {
            throw new AssumptionViolatedException("The demo album should contain images unless someone removed them...");
        }
    }

    @org.junit.Test
    public void downloadFile() throws Exception {

        if (exampleImageId == null) {
            throw new AssumptionViolatedException("The @Before function failed to find a valid example image id.");
        }

        Response<ResponseBody> response = apiService.downloadFile(
            exampleImageId, null
        ).execute();

        assertNotNull(response);
        assertEquals(response.code(), 200);

    }

    @org.junit.Test
    public void downloadVideo() throws Exception {

        if (exampleVideoId == null) {
            throw new AssumptionViolatedException("The @Before function failed to find a valid example video id.");
        }

        Response<ResponseBody> response = apiService.playAssetVideo(
                exampleVideoId, null
        ).execute();

        assertNotNull(response);
        assertEquals(response.code(), 200);

    }

    @org.junit.Test
    public void downloadThumbnail() throws Exception {

        if (exampleImageId == null) {
            throw new AssumptionViolatedException("The @Before function failed to find a valid example image id.");
        }

        Response<ResponseBody> response = apiService.getAssetThumbnail(
                exampleImageId,
                ImmichSizeFormat.thumbnail,
                null
        ).execute();

        assertNotNull(response);
        if (String.valueOf(response.code()).startsWith("5")) throw new AssumptionViolatedException("Server Error");
        assertTrue(response.isSuccessful());
    }
}
