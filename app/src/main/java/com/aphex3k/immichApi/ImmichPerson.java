package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.Date;

@Keep
public class ImmichPerson {

    @Keep
    @SerializedName("birthDate")
    private Date birthDate;

    @Keep
    @SerializedName("id")
    private String id;

    @Keep
    @SerializedName("isHidden")
    private Boolean isHidden;

    @Keep
    @SerializedName("name")
    private String name;

    @Keep
    @SerializedName("thumbnailPath")
    private String thumbnailPath;

    @Keep
    public Date getBirthDate() {
        return birthDate;
    }

    @Keep
    public String getId() {
        return id;
    }

    @Keep
    public Boolean getHidden() {
        return isHidden;
    }

    @Keep
    public String getName() {
        return name;
    }

    @Keep
    public String getThumbnailPath() {
        return thumbnailPath;
    }
}
