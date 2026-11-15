package com.aphex3k.eo1.mqtt;

import androidx.annotation.Keep;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

/**
 * One track, as the coordinator describes it.
 *
 * The names below are the canonical spellings, and the wire's are not. The keys this app is
 * actually served were read off a live frame on 2026-11-14 (v2.0.1.20, a
 * {@code sonos/RINCON_…} state document):
 *
 *   UpnpClass, ItemId, ParentId, TrackUri, ProtocolInfo
 *
 * That is neither the {@code UPNPClass}/{@code ItemID}/{@code TrackURI} this class used to pin
 * with {@code @SerializedName}, nor lowercase — and Gson's field matching is case-sensitive,
 * so those annotations matched nothing and the banner drew no text on any build, debug
 * included. A third spelling lives one level out: the coordinator writes
 * {@code coordinatorUuid} where this package declared {@code coordinatorUUID}. Since the
 * casing is per-key rather than a policy, and no capture yet shows the display keys at all,
 * the mapping is done by {@link Reader} below: keys are matched ignoring case, in code.
 *
 * {@code @Keep} still matters and is not decoration: {@link Payload} is constructed
 * reflectively, and R8 merging a DTO away is a feature that works only on debug builds
 * (app/proguard-rules.pro).
 */
@Keep
public class TTrack {
    public String album;
    public String artist;
    public String albumArtURI;
    public String title;
    public String upnpClass;
    public String duration;
    public String itemID;
    public String parentID;
    public String trackURI;
    public String protocolInfo;

    /**
     * Reads a track document reflectively-free: every assignment here is a Java statement,
     * so R8 has nothing to infer, and a casing change on the bridge cannot quietly blank the
     * banner because the lookup folds case. Returning null for a non-object is deliberate —
     * a coordinator that puts something else under {@code currentTrack} is a state with no
     * track, not a parse failure. Read-only by design: nothing in this app serialises a track
     * back to JSON, and registering a deserializer only is what keeps the mapping in one
     * place. (Gson answers an attempt to serialise one with UnsupportedOperationException —
     * if that ever becomes a requirement, write() belongs here, not in a second mechanism.)
     */
    public static class Reader implements JsonDeserializer<TTrack> {
        @Override
        public TTrack deserialize(JsonElement element, Type type, JsonDeserializationContext context)
                throws JsonParseException {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject object = element.getAsJsonObject();
            TTrack track = new TTrack();
            track.album = WireFields.text(object, "album");
            track.artist = WireFields.text(object, "artist");
            track.albumArtURI = WireFields.text(object, "albumArtURI");
            track.title = WireFields.text(object, "title");
            track.upnpClass = WireFields.text(object, "upnpClass");
            track.duration = WireFields.text(object, "duration");
            track.itemID = WireFields.text(object, "itemID");
            track.parentID = WireFields.text(object, "parentID");
            track.trackURI = WireFields.text(object, "trackURI");
            track.protocolInfo = WireFields.text(object, "protocolInfo");
            return track;
        }
    }
}
