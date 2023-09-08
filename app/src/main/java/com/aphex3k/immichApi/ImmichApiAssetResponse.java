package com.aphex3k.immichApi;

import com.google.gson.annotations.SerializedName;

import java.util.Date;
import java.util.List;

public class ImmichApiAssetResponse extends ImmichApiResponse {

    @SerializedName("checksum")
    private String checksum;

    @SerializedName("deviceAssetId")
    private String deviceAssetId;

    @SerializedName("deviceId")
    private String deviceId;

    @SerializedName("duration")
    private String duration;

    @SerializedName("exifInfo")
    private ImmichExifInfo exifInfo;

    @SerializedName("fileCreatedAt")
    private Date fileCreatedAt;

    @SerializedName("fileModifiedAt")
    private Date fileModifiedAt;

    @SerializedName("id")
    private String id;

    @SerializedName("isArchived")
    private Boolean isArchived;

    @SerializedName("isFavorite")
    private Boolean isFavorite;

    @SerializedName("livePhotoVideoId")
    private String livePhotoVideoId;

    @SerializedName("originalFileName")
    private String originalFileName;

    @SerializedName("originalPath")
    private String originalPath;

    @SerializedName("ownerId")
    private String ownerId;

    @SerializedName("people")
    private ImmichPerson people;

    @SerializedName("resized")
    private Boolean resized;

    @SerializedName("smartInfo")
    private ImmichSmartInfo smartInfo;

    @SerializedName("tags")
    private List<ImmichTag> tags;

    @SerializedName("thumbhash")
    private String thumbHash;

    @SerializedName("type")
    private ImmichType type;

    @SerializedName("updatedAt")
    private Date updatedAt;

    public String getDeviceAssetId() {
        return deviceAssetId;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getDuration() {
        return duration;
    }

    public ImmichExifInfo getExifInfo() {
        return exifInfo;
    }

    public String getId() {
        return id;
    }

    public Boolean getArchived() {
        return isArchived;
    }

    public Boolean getFavorite() {
        return isFavorite;
    }

    public Boolean getResized() {
        return resized;
    }

    public Date getFileCreatedAt() {
        return fileCreatedAt;
    }

    public Date getFileModifiedAt() {
        return fileModifiedAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public ImmichPerson getPeople() {
        return people;
    }

    public ImmichSmartInfo getSmartInfo() {
        return smartInfo;
    }

    public ImmichType getType() {
        return type;
    }

    public List<ImmichTag> getTags() {
        return tags;
    }

    public String getLivePhotoVideoId() {
        return livePhotoVideoId;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getThumbHash() {
        return thumbHash;
    }
}
