package com.aphex3k.immichApi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.eo1.ApiServiceGenerator;

import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Ignore;

import java.net.UnknownHostException;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Response;

@Ignore
public class ImmichApiServiceTest {

    private String userId;
    private String exampleImageId;
    private String exampleAlbumId;
    private final String email = "demo@immich.app";
    private final String password = "demo";
    private ImmichApiService apiService;

    @Before
    public void authorize() throws Exception {

        if (userId == null) {

            this.apiService = ApiServiceGenerator.createService(ImmichApiService.class, "https://demo.immich.app/");

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

            Response<List<ImmichApiAssetResponse>> response = apiService.getAllAssets(
                    userId, null, null, null, null
            ).execute();

            for (ImmichApiAssetResponse asset: response.body()) {
                if (asset.getType() == ImmichType.IMAGE) {
                    exampleImageId = asset.getId();
                    break;
                }
            }

            if (exampleImageId == null) {
                Response<List<ImmichApiGetAlbumResponse>> shared = apiService.getAllAlbums(
                        true, null
                ).execute();

                for (ImmichApiGetAlbumResponse r: shared.body()) {
                    List<ImmichApiAssetResponse> assets = r.getAssets();

                    if (assets.isEmpty()) {
                        Response<ImmichApiGetAlbumResponse> infoResponse = apiService.getAlbumInfo(
                                r.getId(), false, null
                        ).execute();

                        assets = infoResponse.body().getAssets();
                    }

                    for (ImmichApiAssetResponse asset : assets) {
                        if (asset.getType() == ImmichType.IMAGE) {
                            exampleImageId = asset.getId();
                            exampleAlbumId = r.getId();
                            break;
                        }
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
            if (r.getAssetCount() != r.getAssets().size()) {
                throw new AssumptionViolatedException("???");
            } else {
                assertEquals(r.getAssetCount(), r.getAssets().size());
            }
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
            if (r.getAssetCount() != r.getAssets().size()) {
                throw new AssumptionViolatedException("???");
            }
            else {
                assertEquals(r.getAssetCount(), r.getAssets().size());
            }
        }
    }

    @org.junit.Test
    public void getAlbumInfo() throws Exception {

        if (exampleAlbumId == null) {
            throw new AssumptionViolatedException("The @Before function failed to find a valid album containing at least one image.");
        }

        Response<ImmichApiGetAlbumResponse> response = apiService.getAlbumInfo(exampleAlbumId, false, null).execute();

        assertNotNull(response);
        assertEquals(response.code(), 200);

        if (response.body().getAssets().isEmpty()) {
            throw new AssumptionViolatedException("We ended up with an example album that does not contain any image?!");
        }
    }

    @org.junit.Test
    public void getAllAssets() throws Exception {
        Response<List<ImmichApiAssetResponse>> response = apiService.getAllAssets(
            userId, null, null, null, null
        ).execute();

        assertNotNull(response);
        assertEquals(response.code(), 200);

        List<ImmichApiAssetResponse> body = response.body();

        assertNotNull(body);

        if (body.isEmpty()) {
            throw new AssumptionViolatedException("The demo album should contain images unless someone removed them...");
        }
    }

    @org.junit.Test
    public void downloadFile() throws Exception {

        if (exampleImageId == null) {
            throw new AssumptionViolatedException("The @Before function failed to find a valid example image id.");
        }

        Response<ResponseBody> response = apiService.downloadFile(
            exampleImageId
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
                ImmichThumbnailFormat.JPEG,
                null
        ).execute();

        assertNotNull(response);
        if (String.valueOf(response.code()).startsWith("5")) throw new AssumptionViolatedException("Server Error");
        assertTrue(response.isSuccessful());
    }
}