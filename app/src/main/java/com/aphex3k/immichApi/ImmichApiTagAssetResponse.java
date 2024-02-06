package com.aphex3k.immichApi;

import com.google.gson.annotations.SerializedName;

import javax.annotation.Nullable;


public class ImmichApiTagAssetResponse {
    @SerializedName("assetId ")
    private String assetId ;
    @SerializedName("success")
    private Boolean success;
    @SerializedName("error")
    @Nullable
    private ImmichTagError error;

    public String getAssetId() {
        return assetId ;
    }

    public Boolean getSuccess() {
        return success;
    }

    @Nullable
    public ImmichTagError getError() {
        return error ;
    }
}
