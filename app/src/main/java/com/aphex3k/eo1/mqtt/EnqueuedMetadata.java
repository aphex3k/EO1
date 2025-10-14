package com.aphex3k.eo1.mqtt;

import com.google.gson.annotations.SerializedName;

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

// No changes needed; mapping handled by custom TypeAdapterFactory
