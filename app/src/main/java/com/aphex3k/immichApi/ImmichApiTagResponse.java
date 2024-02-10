package com.aphex3k.immichApi;

import com.google.gson.annotations.SerializedName;

public class ImmichApiTagResponse extends ImmichApiResponse {

    @SerializedName("id")
    private String id;
    @SerializedName("name")
    private String name;
    @SerializedName("type")
    private ImmichTagType type;
    @SerializedName("userId")
    private String userId;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ImmichTagType getType() {
        return type;
    }

    public String getUserId() {
        return userId;
    }
}
