package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.List;

@Keep
public class ImmichApiTagAssetBody {

    @SerializedName("assetIds")
    private List<String> assetIds;

    public ImmichApiTagAssetBody(List<String> assetIds) {
        this.assetIds = assetIds;
    }

    public List<String> getAssetIds() {
        return assetIds;
    }
}
