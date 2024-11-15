package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import java.util.Date;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
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
    @GET("/api/albums")
    Call<List<ImmichApiGetAlbumResponse>> getAllAlbums (
            @Query("shared") Boolean shared,
            @Query("assetId") String assetId
    );

    @Keep
    @POST("/api/search/metadata/ ")
    Call<ImmichApiMetadataSearchResponse> getAllAssets (
            @Query("isFavorite") Boolean isFavorite,
            @Query("isArchived") Boolean isArchived,
            @Query("isNotInAlbum") Boolean isNotInAlbum,
            @Query("count") Integer count,
            @Query("page") Integer page
    );

    @Keep
    @GET("/api/assets/{id}/original")
    @Streaming
    Call<ResponseBody> downloadFile (
            @Path("id") String id,
            @Query("key") String key
    );

    @Keep
    @GET("/api/assets/{id}/thumbnail")
    @Streaming
    Call<ResponseBody> getAssetThumbnail(
            @Path("id") String id,
            @Query("size") ImmichSizeFormat sizeFormat,
            @Query("key") String key
    );

    @Keep
    @GET("/api/assets/{id}/video/playback")
    @Streaming
    Call<ResponseBody> playAssetVideo(
            @Path("id") String id,
            @Query("key") String key
    );

    @Keep
    @GET("/api/albums/{id}")
    Call<ImmichApiGetAlbumResponse> getAlbumInfo (
            @Path("id") String id,
            @Query("withoutAssets") Boolean withoutAssets,
            @Query("key") String key
    );

    @Keep
    @POST("/api/tag")
    Call<ImmichApiTagResponse> createTag (
            @Body ImmichApiTag tag
    );

    @Keep
    @GET("/api/tag")
    Call<List<ImmichApiTagResponse>> getAllTags ();

    @Keep
    @PUT("/api/tag/{id}/assets")
    Call<List<ImmichApiTagAssetResponse>> tagAssets (
            @Path("id") String tagId,
            @Body ImmichApiTagAssetBody body
    );
}
