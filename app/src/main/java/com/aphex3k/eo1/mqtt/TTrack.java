package com.aphex3k.eo1.mqtt;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

@Keep
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

    // The UPPERCASE keys are pinned by the @SerializedName annotations above, not by a
// naming strategy: there is no custom TypeAdapterFactory anywhere in this app, however
// the older comments in this package claimed. @Keep on the class is what stops R8
// merging this type away - it is built reflectively, and it had been.
}
