package com.aphex3k.giteaApi;

import com.google.gson.annotations.SerializedName;

import java.util.Date;

public class GiteaAsset {
    @SerializedName("id")
    private String id;
    @SerializedName("name")
    private String name;
    @SerializedName("size")
    private Number size;
    @SerializedName("download_count")
    private Number downloadCount;
    @SerializedName("created_at")
    private Date createdAt;
    @SerializedName("uuid")
    private String uuid;
    @SerializedName("browser_download_url")
    private String browserDownloadUrl;

    public String getName() {
        return name;
    }
    public String getId() {
        return id;
    }
    public Date getCreatedAt() {
        return createdAt;
    }
    public Number getDownloadCount() {
        return downloadCount;
    }
    public Number getSize() {
        return size;
    }
    public String getBrowserDownloadUrl() {
        return browserDownloadUrl;
    }
    public String getUuid() {
        return uuid;
    }
}
