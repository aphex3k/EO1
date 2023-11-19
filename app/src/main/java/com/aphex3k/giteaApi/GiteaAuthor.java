package com.aphex3k.giteaApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.Date;

@Keep
public class GiteaAuthor {
    @SerializedName("id")
    private String id;
    @SerializedName("login")
    private String login;
    @SerializedName("login_name")
    private String loginName;
    @SerializedName("full_name")
    private String fullName;
    @SerializedName("email")
    private String email;
    @SerializedName("avatar_url")
    private String avatarUrl;
    @SerializedName("language")
    private String language;
    @SerializedName("is_admin")
    private boolean isAdmin;
    @SerializedName("last_login")
    private Date lastLogin;
    @SerializedName("created")
    private Date created;
    @SerializedName("restricted")
    private boolean restricted;
    @SerializedName("active")
    private boolean active;
    @SerializedName("prohibit_login")
    private boolean prohibitLogin;
    @SerializedName("location")
    private String location;
    @SerializedName("website")
    private String website;
    @SerializedName("description")
    private String description;
    @SerializedName("visibility")
    private String visibility;
    @SerializedName("followers_count")
    private Number followersCount;
    @SerializedName("following_count")
    private Number followingCount;
    @SerializedName("starred_repos_count")
    private Number starredReposCount;
    @SerializedName("username")
    private String username;

    public String getId() {
        return id;
    }
    public boolean isActive() {
        return active;
    }
    public boolean isAdmin() {
        return isAdmin;
    }
    public boolean isProhibitLogin() {
        return prohibitLogin;
    }
    public boolean isRestricted() {
        return restricted;
    }
    public Date getCreated() {
        return created;
    }
    public Date getLastLogin() {
        return lastLogin;
    }
    public Number getFollowersCount() {
        return followersCount;
    }
    public Number getFollowingCount() {
        return followingCount;
    }
    public Number getStarredReposCount() {
        return starredReposCount;
    }
    public String getAvatarUrl() {
        return avatarUrl;
    }
    public String getDescription() {
        return description;
    }
    public String getEmail() {
        return email;
    }
    public String getFullName() {
        return fullName;
    }
    public String getLanguage() {
        return language;
    }
    public String getLocation() {
        return location;
    }
    public String getLogin() {
        return login;
    }
    public String getLoginName() {
        return loginName;
    }
    public String getUsername() {
        return username;
    }
    public String getVisibility() {
        return visibility;
    }
    public String getWebsite() {
        return website;
    }
}
