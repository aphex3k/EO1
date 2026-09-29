package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The /state config section lists backends without ever leaking credentials. */
public class ConfigStateJsonTest {

    private static ConfigurationBackendEntry immich(String id, String host, String apiVersion) {
        ConfigurationBackendEntry e = new ConfigurationBackendEntry();
        e.type = ConfigurationBackendEntry.TYPE_IMMICH;
        e.id = id;
        e.host = host;
        e.userid = "user-" + id;
        e.password = "secret-" + id;
        e.apiVersion = apiVersion;
        return e;
    }

    private static ConfigurationBackendEntry local() {
        ConfigurationBackendEntry e = new ConfigurationBackendEntry();
        e.type = ConfigurationBackendEntry.TYPE_LOCAL;
        e.id = "local";
        return e;
    }

    private static Configuration config(ConfigurationBackendEntry... backends) {
        Configuration c = new Configuration();
        List<ConfigurationBackendEntry> list = new ArrayList<>();
        for (ConfigurationBackendEntry b : backends) {
            list.add(b);
        }
        c.backends = list;
        c.interval = 15;
        c.startQuietHour = 22;
        c.endQuietHour = 7;
        c.selectedTimeZoneId = "America/Los_Angeles";
        c.mqttHost = "mqtt.local";
        return c;
    }

    @Test
    public void listsBackendsWithoutSecrets() {
        Configuration c = config(
                immich("immich-1", "https://one.example/", ConfigurationBackendEntry.API_VERSION_AUTO),
                immich("immich-2", "https://two.example/", "3.1.0"),
                local());
        JsonObject json = ConfigStateJson.configStateJson(c);

        JsonArray backends = json.getAsJsonArray("backends");
        assertEquals(3, backends.size());

        JsonObject first = backends.get(0).getAsJsonObject();
        assertEquals("immich-1", first.get("id").getAsString());
        assertEquals("immich", first.get("type").getAsString());
        assertEquals("https://one.example/", first.get("host").getAsString());
        assertEquals("auto", first.get("apiVersion").getAsString());
        assertTrue(first.get("valid").getAsBoolean());

        JsonObject second = backends.get(1).getAsJsonObject();
        assertEquals("3.1.0", second.get("apiVersion").getAsString());

        JsonObject third = backends.get(2).getAsJsonObject();
        assertEquals("local", third.get("type").getAsString());
        assertTrue(third.get("valid").getAsBoolean());
        // Local entries carry no host/apiVersion.
        assertFalse(third.has("host"));
        assertFalse(third.has("apiVersion"));

        String serialized = json.toString();
        assertFalse(serialized.contains("secret-"));
        assertFalse(serialized.contains("user-"));
        assertFalse(serialized.contains("password"));
        assertFalse(serialized.contains("userid"));

        // Deprecated alias: first Immich host for older /state consumers.
        assertEquals("https://one.example/", json.get("host").getAsString());
        assertEquals(15, json.get("intervalMinutes").getAsInt());
        assertEquals("22-7", json.get("quietHours").getAsString());
        assertEquals("America/Los_Angeles", json.get("timezone").getAsString());
        assertEquals("mqtt.local", json.get("mqttHost").getAsString());
    }

    @Test
    public void localOnlyConfigHasEmptyLegacyHostAlias() {
        Configuration c = config(local());
        JsonObject json = ConfigStateJson.configStateJson(c);
        assertEquals("", json.get("host").getAsString());
        assertEquals(1, json.getAsJsonArray("backends").size());
    }

    @Test
    public void invalidBackendIsFlagged() {
        ConfigurationBackendEntry incomplete = immich("immich-1", "", ConfigurationBackendEntry.API_VERSION_AUTO);
        incomplete.userid = null;
        incomplete.password = null;
        JsonObject json = ConfigStateJson.configStateJson(config(incomplete));
        JsonObject entry = json.getAsJsonArray("backends").get(0).getAsJsonObject();
        assertFalse(entry.get("valid").getAsBoolean());
    }

    @Test
    public void nullConfigurationYieldsEmptyObject() {
        JsonObject json = ConfigStateJson.configStateJson(null);
        assertEquals(0, json.size());
    }
}
