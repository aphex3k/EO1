package com.aphex3k.eo1.mqtt;

import com.google.gson.annotations.SerializedName;

public class TTrack {
    @SerializedName("Album")
    public String album;
    @SerializedName("Artist")
    public String artist;
    @SerializedName("AlbumArtURI")
    public String albumArtURI;
    @SerializedName("Title")
    public String title;
    @SerializedName("UPNPClass")
    public String upnpClass;
    @SerializedName("Duration")
    public String duration;
    @SerializedName("ItemID")
    public String itemID;
    @SerializedName("ParentID")
    public String parentID;
    @SerializedName("TrackURI")
    public String trackURI;
    @SerializedName("ProtocolInfo")
    public String protocolInfo;

    // No changes needed; mapping handled by custom TypeAdapterFactory
}
