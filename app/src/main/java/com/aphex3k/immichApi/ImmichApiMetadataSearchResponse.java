package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.List;

@Keep
public class ImmichApiMetadataSearchResponse extends ImmichApiResponse {

    @SerializedName("assets")
    private ImmichApiMetadataSearchResponseAssets assets;

    public ImmichApiMetadataSearchResponseAssets getAssets() {
        return assets;
    }
}

