package com.aphex3k.eo1.mqtt;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

@Keep
public class EnqueuedMetadata {
    @SerializedName("Title")
    public String title;
    @SerializedName("UPNPClass")
    public String upnpClass;
    @SerializedName("ItemID")
    public String itemID;
    @SerializedName("ParentID")
    public String parentID;
}

// UPPERCASE keys pinned by @SerializedName above; see TTrack for why that matters, and
// Payload for what forgetting @Keep did to this package.
