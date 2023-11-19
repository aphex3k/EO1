package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.Date;
import java.util.List;

@Keep
public class ImmichApiGetAlbumResponse extends ImmichApiResponse {

    @SerializedName("albumName")
    private String albumName;
    @SerializedName("albumThumbnailAssetId")
    private String albumThumbnailAssetId;
    @SerializedName("assetCount")
    private Integer assetCount;
    @SerializedName("assets")
    private List<ImmichApiAssetResponse> assets;
    @SerializedName("createdAt")
    private Date createdAt;
    @SerializedName("description")
    private String description;
    @SerializedName("endDate")
    private Date endDate;
    @SerializedName("hasSharedLink")
    private Boolean hasSharedLink;
    @SerializedName("id")
    private String id;
    @SerializedName("lastModifiedAssetTimestamp")
    private Date lastModifiedAssetTimestamp;
    @SerializedName("owner")
    private ImmichOwner owner;
    @SerializedName("ownerId")
    private String ownerId;
    @SerializedName("shared")
    private Boolean shared;
    @SerializedName("sharedUsers")
    private List<ImmichOwner> sharedUsers;
    @SerializedName("startDate")
    private Date startDate;
    @SerializedName("updatedAt")
    private Date updatedAt;

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public Boolean getHasSharedLink() {
        return hasSharedLink;
    }

    public Boolean getShared() {
        return shared;
    }

    public Date getEndDate() {
        return endDate;
    }

    public Date getLastModifiedAssetTimestamp() {
        return lastModifiedAssetTimestamp;
    }

    public Date getStartDate() {
        return startDate;
    }

    public ImmichOwner getOwner() {
        return owner;
    }

    public List<ImmichApiAssetResponse> getAssets() {
        return assets;
    }

    public List<ImmichOwner> getSharedUsers() {
        return sharedUsers;
    }

    public String getAlbumName() {
        return albumName;
    }

    public String getAlbumThumbnailAssetId() {
        return albumThumbnailAssetId;
    }

    public int getAssetCount() {
        return assetCount;
    }
}
