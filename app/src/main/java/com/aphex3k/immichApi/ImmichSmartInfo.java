package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.List;

@Keep
public class ImmichSmartInfo {

    @Keep
    @SerializedName("objects")
    private List<String> objects;

    @Keep
    @SerializedName("tags")
    private List<String> tags;

    @Keep
    public List<String> getObjects() {
        return objects;
    }

    @Keep
    public List<String> getTags() {
        return tags;
    }
}
