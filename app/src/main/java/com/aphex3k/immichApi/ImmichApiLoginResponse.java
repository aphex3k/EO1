package com.aphex3k.immichApi;

import com.google.gson.annotations.SerializedName;

public class ImmichApiLoginResponse extends ImmichApiResponse {
    @SerializedName("accessToken")
    private String accessToken;

    @SerializedName("firstName")
    private String firstName;

    @SerializedName("isAdmin")
    private Boolean isAdmin;

    @SerializedName("lastName")
    private String lastName;

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

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
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
