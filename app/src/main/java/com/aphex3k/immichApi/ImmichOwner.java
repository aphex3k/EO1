package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.Date;

@Keep
public class ImmichOwner {

    @SerializedName("createdAt")
    private Date createdAt;
    @SerializedName("deletedAt")
    private Date deletedAt;
    @SerializedName("email")
    private String email;
    @SerializedName("externalPath")
    private String externalPath;
    @SerializedName("firstName")
    private String firstName;
    @SerializedName("id")
    private String id;
    @SerializedName("isAdmin")
    private Boolean isAdmin;
    @SerializedName("lastName")
    private String lastName;
    @SerializedName("memoriesEnabled")
    private Boolean memoriesEnabled;
    @SerializedName("oauthId")
    private String oauthId;
    @SerializedName("profileImagePath")
    private String profileImagePath;
    @SerializedName("shouldChangePassword")
    private Boolean shouldChangePassword;
    @SerializedName("storageLabel")
    private String storageLabel;
    @SerializedName("updatedAt")
    private Date updatedAt;

    public String getId() {
        return id;
    }

    public Boolean getAdmin() {
        return isAdmin;
    }

    public Boolean getMemoriesEnabled() {
        return memoriesEnabled;
    }

    public Boolean getShouldChangePassword() {
        return shouldChangePassword;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public Date getDeletedAt() {
        return deletedAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public String getEmail() {
        return email;
    }

    public String getExternalPath() {
        return externalPath;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getOauthId() {
        return oauthId;
    }

    public String getProfileImagePath() {
        return profileImagePath;
    }

    public String getStorageLabel() {
        return storageLabel;
    }
}
