package com.aphex3k.immichApi;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class ImmichSmartInfo {

    @SerializedName("objects")
    private List<String> objects;

    @SerializedName("tags")
    private List<String> tags;

    public List<String> getObjects() {
        return objects;
    }

    public List<String> getTags() {
        return tags;
    }
}
