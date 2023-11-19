package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import java.util.Date;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.Streaming;

@Keep
public interface ImmichApiService {

    @Keep
    @POST("/api/auth/login")
    Call<ImmichApiLoginResponse> login(
            @Body ImmichApiLogin params
    );

    @Keep
    @GET("/api/album")
    Call<List<ImmichApiGetAlbumResponse>> getAllAlbums (
            @Query("shared") Boolean shared,
            @Query("assetId") String assetId
    );

    @Keep
    @GET("/api/asset")
    Call<List<ImmichApiAssetResponse>> getAllAssets (
            @Query("userId") String userId,
            @Query("isFavorite") Boolean isFavorite,
            @Query("isArchived") Boolean isArchived,
            @Query("skip") Integer skip,
            @Query("updatedAfter") Date updatedAfter
    );

    @Keep
    @POST("/api/asset/download/{id}")
    @Streaming
    Call<ResponseBody> downloadFile (
            @Path("id") String id
    );

    @Keep
    @GET("/api/asset/file/{id}")
    @Streaming
    Call<ResponseBody> serveFile (
            @Path("id") String id,
            @Query("isThumb") boolean isThumb,
            @Query("isWeb") boolean isWeb,
            @Query("key") String key
    );

    @Keep
    @GET("/api/asset/thumbnail/{id}")
    @Streaming
    Call<ResponseBody> getAssetThumbnail(
            @Path("id") String id,
            @Query("format") ImmichThumbnailFormat format,
            @Query("key") String key
    );

    @Keep
    @GET("/api/album/{id}")
    Call<ImmichApiGetAlbumResponse> getAlbumInfo (
            @Path("id") String id,
            @Query("withoutAssets") Boolean withoutAssets,
            @Query("key") String key
    );
}
