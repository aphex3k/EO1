package com.aphex3k.immichApi;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import com.google.gson.annotations.SerializedName;

@Keep
public class ImmichApiTagAssetResponse {
    @SerializedName("id")
    private String id;
    @SerializedName("success")
    private Boolean success;
    @SerializedName("error")
    @Nullable
    private ImmichTagError error;

    public String getId() {
        return id;
    }

    public Boolean getSuccess() {
        return success;
    }

    @Nullable
    public ImmichTagError getError() {
        return error;
    }
}
