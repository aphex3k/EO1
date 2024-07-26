package com.aphex3k.immichApi;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class ImmichApiMetadataSearchResponseAssets {

    @SerializedName("count")
    private Integer count;

    @SerializedName("items")
    private List<ImmichApiAssetResponse> items;

    public Integer getCount() {
        return count;
    }
    
    public List<ImmichApiAssetResponse> getItems() {
        return items;
    }
}
