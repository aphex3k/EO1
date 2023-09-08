package com.aphex3k.immichApi;

import com.google.gson.annotations.SerializedName;

import java.util.Date;

public class ImmichPerson {

    @SerializedName("birthDate")
    private Date birthDate;

    @SerializedName("id")
    private String id;

    @SerializedName("isHidden")
    private Boolean isHidden;

    @SerializedName("name")
    private String name;

    @SerializedName("thumbnailPath")
    private String thumbnailPath;

    public Date getBirthDate() {
        return birthDate;
    }

    public String getId() {
        return id;
    }

    public Boolean getHidden() {
        return isHidden;
    }

    public String getName() {
        return name;
    }

    public String getThumbnailPath() {
        return thumbnailPath;
    }
}
