package com.aphex3k.eo1.mqtt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Key lookup for a document whose casing is not the Java field's.
 *
 * This exists because the Sonos coordinator's key spellings have disagreed with this package
 * in three ways at once — {@code UPNPClass} annotated in source against {@code UpnpClass} on
 * the wire, {@code coordinatorUUID} against {@code coordinatorUuid} — and Gson's own match is
 * exact. Every one of those disagreements is silent: the field stays null and the banner draws
 * nothing, which from a wall is indistinguishable from a house with no music.
 */
final class WireFields {

    private WireFields() {
    }

    /**
     * The value of the key in {@code object} whose name matches {@code name} ignoring case,
     * or null if there is no such key, the value is JSON null, or it is not a scalar (an
     * object under a name the app reads as text is a bridge that changed shape, not a value).
     * Iterating the entry set rather than calling {@code get(name)} is the whole point.
     */
    static String text(JsonObject object, String name) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                JsonElement value = entry.getValue();
                if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
                    return null;
                }
                return value.getAsString();
            }
        }
        return null;
    }
}
