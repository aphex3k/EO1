package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

@Keep
public enum ImmichAssetVisibility {
    @SerializedName("archive")
    archive,
    @SerializedName("timeline")
    timeline,
    @SerializedName("hidden")
    hidden,
    @SerializedName("locked")
    locked
}
