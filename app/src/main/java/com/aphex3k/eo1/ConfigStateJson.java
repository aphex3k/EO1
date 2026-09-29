package com.aphex3k.eo1;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import androidx.annotation.Nullable;

/**
 * Builds the {@code config} section of the /state web payload. Pure and unit-testable.
 * Backend entries are listed without secrets (no password, no userid).
 */
public final class ConfigStateJson {

    private ConfigStateJson() {
    }

    public static JsonObject configStateJson(@Nullable Configuration c) {
        JsonObject config = new JsonObject();
        if (c == null) {
            return config;
        }
        JsonArray backends = new JsonArray();
        for (ConfigurationBackendEntry entry : c.backendsOrEmpty()) {
            JsonObject backend = new JsonObject();
            backend.addProperty("id", entry.id);
            backend.addProperty("type", entry.type);
            if (entry.isImmich()) {
                backend.addProperty("host", entry.host);
                backend.addProperty("apiVersion", entry.apiVersion);
            }
            backend.addProperty("valid", entry.isValid());
            backends.add(backend);
        }
        config.add("backends", backends);
        // Deprecated alias: keep the first Immich host for older /state consumers.
        String legacyHost = "";
        for (ConfigurationBackendEntry entry : c.backendsOrEmpty()) {
            if (entry.isImmich()) {
                legacyHost = entry.host;
                break;
            }
        }
        config.addProperty("host", legacyHost);
        config.addProperty("intervalMinutes", c.interval);
        config.addProperty("quietHours", c.startQuietHour + "-" + c.endQuietHour);
        config.addProperty("timezone", c.selectedTimeZoneId);
        config.addProperty("mqttHost", c.mqttHost);
        return config;
    }
}
