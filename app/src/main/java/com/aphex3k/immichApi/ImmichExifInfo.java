package com.aphex3k.immichApi;

import com.google.gson.annotations.SerializedName;

import java.util.Date;

public class ImmichExifInfo {

    @SerializedName("city")
    private String city;

    @SerializedName("country")
    private String country;

    @SerializedName("dateTimeOriginal")
    private Date dateTimeOriginal;

    @SerializedName("description")
    private String description;

    @SerializedName("exifImageHeight")
    private Integer exifImageHeight;

    @SerializedName("exifImageWidth")
    private Integer exifImageWidth;

    @SerializedName("exposureTime")
    private String exposureTime;

    @SerializedName("fNumber")
    private Float fNumber;

    @SerializedName("fileSizeInByte")
    private Integer fileSizeInByte;

    @SerializedName("focalLength")
    private Float focalLength;

    @SerializedName("iso")
    private Integer iso;

    @SerializedName("latitude")
    private Float latitude;

    @SerializedName("lensModel")
    private String lensModel;

    @SerializedName("longitude")
    private Float longitude;

    @SerializedName("make")
    private String make;

    @SerializedName("model")
    private String model;

    @SerializedName("modifyDate")
    private Date modifyDate;

    @SerializedName("orientation")
    private String orientation;

    @SerializedName("projectionType")
    private String projectionType;

    @SerializedName("state")
    private String state;

    @SerializedName("timeZone")
    private String timeZone;

    public String getCity() {
        return city;
    }

    public String getCountry() {
        return country;
    }

    public Date getDateTimeOriginal() {
        return dateTimeOriginal;
    }

    public String getDescription() {
        return description;
    }

    public Integer getExifImageHeight() {
        return exifImageHeight;
    }

    public Integer getExifImageWidth() {
        return exifImageWidth;
    }

    public String getExposureTime() {
        return exposureTime;
    }

    public Float getfNumber() {
        return fNumber;
    }

    public Integer getFileSizeInByte() {
        return fileSizeInByte;
    }

    public Float getFocalLength() {
        return focalLength;
    }

    public Date getModifyDate() {
        return modifyDate;
    }

    public Integer getIso() {
        return iso;
    }

    public Float getLatitude() {
        return latitude;
    }

    public Float getLongitude() {
        return longitude;
    }

    public String getLensModel() {
        return lensModel;
    }

    public String getMake() {
        return make;
    }

    public String getModel() {
        return model;
    }

    public String getOrientation() {
        return orientation;
    }

    public String getProjectionType() {
        return projectionType;
    }

    public String getState() {
        return state;
    }

    public String getTimeZone() {
        return timeZone;
    }
}
