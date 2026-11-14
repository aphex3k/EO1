package com.aphex3k.eo1.mqtt;

import androidx.annotation.Keep;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

/**
 * The queue entry the coordinator names alongside the playing track. Same casing story as
 * {@link TTrack} — the frame reports {@code UpnpClass}, {@code ItemId} and {@code ParentId},
 * which is what {@link Reader} matches, ignoring case, rather than the
 * {@code @SerializedName} spellings this class used to carry.
 */
@Keep
public class EnqueuedMetadata {
    public String title;
    public String upnpClass;
    public String itemID;
    public String parentID;

    public static class Reader implements JsonDeserializer<EnqueuedMetadata> {
        @Override
        public EnqueuedMetadata deserialize(JsonElement element, Type type,
                                            JsonDeserializationContext context) throws JsonParseException {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject object = element.getAsJsonObject();
            EnqueuedMetadata metadata = new EnqueuedMetadata();
            metadata.title = WireFields.text(object, "title");
            metadata.upnpClass = WireFields.text(object, "upnpClass");
            metadata.itemID = WireFields.text(object, "itemID");
            metadata.parentID = WireFields.text(object, "parentID");
            return metadata;
        }
    }
}
