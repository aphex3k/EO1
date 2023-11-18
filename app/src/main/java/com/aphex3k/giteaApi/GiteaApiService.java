package com.aphex3k.giteaApi;

import androidx.annotation.Keep;

import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;

@Keep
public interface GiteaApiService {
    @Keep
    @GET("api/v1/repos/{owner}/{repo}/releases")
    Call<List<GiteaApiGetReleasesResponse>> getReleases (
            @Path("owner") String owner,
            @Path("repo") String repository
    );
}
