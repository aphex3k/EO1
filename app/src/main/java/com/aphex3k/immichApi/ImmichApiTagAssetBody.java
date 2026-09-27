package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.List;

@Keep
public class ImmichApiTagAssetBody {

    @SerializedName("ids")
    private List<String> ids;

    public ImmichApiTagAssetBody(List<String> ids) {
        this.ids = ids;
    }

    public List<String> getIds() {
        return ids;
    }
}
