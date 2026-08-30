package com.aphex3k.immichApi;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

import java.util.List;

@Keep
public class ImmichApiMetadataSearchBody {

    @SerializedName("page")
    private Integer page;

    @SerializedName("size")
    private Integer size;

    @SerializedName("withExif")
    private Boolean withExif;

    @SerializedName("visibility")
    private ImmichAssetVisibility visibility;

    @SerializedName("isFavorite")
    @Nullable
    private Boolean isFavorite;

    @SerializedName("isNotInAlbum")
    @Nullable
    private Boolean isNotInAlbum;

    @SerializedName("albumIds")
    @Nullable
    private List<String> albumIds;

    public ImmichApiMetadataSearchBody(Integer page, Integer size) {
        this.page = page;
        this.size = size;
        this.withExif = true;
        this.visibility = ImmichAssetVisibility.timeline;
    }

    public ImmichApiMetadataSearchBody withAlbumIds(@Nullable List<String> albumIds) {
        this.albumIds = albumIds;
        return this;
    }

    public ImmichApiMetadataSearchBody withIsFavorite(@Nullable Boolean isFavorite) {
        this.isFavorite = isFavorite;
        return this;
    }

    public ImmichApiMetadataSearchBody withIsNotInAlbum(@Nullable Boolean isNotInAlbum) {
        this.isNotInAlbum = isNotInAlbum;
        return this;
    }

    public Integer getPage() {
        return page;
    }

    public Integer getSize() {
        return size;
    }

    public Boolean getWithExif() {
        return withExif;
    }

    public ImmichAssetVisibility getVisibility() {
        return visibility;
    }

    @Nullable
    public Boolean getIsFavorite() {
        return isFavorite;
    }

    @Nullable
    public Boolean getIsNotInAlbum() {
        return isNotInAlbum;
    }

    @Nullable
    public List<String> getAlbumIds() {
        return albumIds;
    }
}
