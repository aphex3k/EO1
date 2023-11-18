package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

@Keep
public class ImmichTag {

    @Keep
    @SerializedName("id")
    private String id;

    @Keep
    @SerializedName("name")
    private String name;

    @Keep
    @SerializedName("type")
    private ImmichTagType type;

    @Keep
    @SerializedName("userId")
    private String userId;

    @Keep
    public String getId() {
        return id;
    }

    @Keep
    public String getName() {
        return name;
    }

    @Keep
    public ImmichTagType getType() {
        return type;
    }

    @Keep
    public String getUserId() {
        return userId;
    }
}
