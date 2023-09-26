package com.aphex3k.giteaApi;

import com.google.gson.annotations.SerializedName;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;

import java.util.Date;
import java.util.List;

public class GiteaApiGetReleasesResponse extends GiteaApiResponse {
    @SerializedName("id")
    private String id;
    @SerializedName("tag_name")
    private String tag_name;
    @SerializedName("target_commitish")
    private String targetCommitish;
    @SerializedName("name")
    private String name;
    @SerializedName("body")
    private String body;
    @SerializedName("url")
    private String url;
    @SerializedName("html_url")
    private String htmlUrl;
    @SerializedName("tarball_url")
    private String tarballUrl;
    @SerializedName("zipball_url")
    private String zipballUrl;
    @SerializedName("draft")
    private boolean draft;
    @SerializedName("prerelease")
    private boolean prerelease;
    @SerializedName("created_at")
    private Date createdAt;
    @SerializedName("published_at")
    private Date publishedAt;
    @SerializedName("author")
    private GiteaAuthor author;
    @SerializedName("assets")
    private List<GiteaAsset> assets;

    public boolean isDraft() { return draft; }
    public boolean isPrerelease() {
        return prerelease;
    }
    public Date getCreatedAt() {
        return createdAt;
    }
    public Date getPublishedAt() {
        return publishedAt;
    }
    public GiteaAuthor getAuthor() {
        return author;
    }
    public List<GiteaAsset> getAssets() {
        return assets;
    }
    public String getBody() {
        return body;
    }
    public String getHtmlUrl() {
        return htmlUrl;
    }
    public String getId() {
        return id;
    }
    public String getName() {
        return name;
    }
    public String getTag_name() {
        return tag_name;
    }
    public String getTarballUrl() {
        return tarballUrl;
    }
    public String getTargetCommitish() {
        return targetCommitish;
    }
    public String getUrl() {
        return url;
    }
    public String getZipballUrl() {
        return zipballUrl;
    }

    public Semver version() {

        String versionString = tag_name.trim().replaceFirst("v", "");

        Semver version = null;

        try {
            version = new Semver(versionString);
        }
        catch (SemverException e1) {
            try {
                version = new Semver(versionString, Semver.SemverType.LOOSE);
            }
            catch (SemverException e2) {

            }
        }
        return version;
    }
    public boolean isStable() {
        Semver version = version();
        if (version != null && !version.isStable()) {
            return false;
        }

        return isPrerelease();
    }
}
