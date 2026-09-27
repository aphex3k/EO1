package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

@Keep
public class ImmichApiLoginResponse extends ImmichApiResponse {
    @SerializedName("accessToken")
    private String accessToken;

    @SerializedName("isAdmin")
    private Boolean isAdmin;

    @SerializedName("name")
    private String name;

    @SerializedName("profileImagePath")
    private String profileImagePath;

    @SerializedName("shouldChangePassword")
    private Boolean shouldChangePassword;

    @SerializedName("userEmail")
    private String userEmail;

    @SerializedName("userId")
    private String userId;

    public String getAccessToken() {
        return accessToken;
    }

    public String getName() {
        return name;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public String getProfileImagePath() {
        return profileImagePath;
    }

    public Boolean getIsAdmin() {
        return isAdmin;
    }

    public Boolean getShouldChangePassword() {
        return shouldChangePassword;
    }
}
