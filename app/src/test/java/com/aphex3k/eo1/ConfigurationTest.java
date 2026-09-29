package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

public class ConfigurationTest {
    @org.junit.Test
    public void DeserializationTest() throws Exception {

        String testString = "{host:'test'}";

        Configuration configuration = new Gson().fromJson(testString, Configuration.class);

        assertTrue(!configuration.host.isEmpty());
        assertTrue(configuration.userid.isEmpty());
    }

    @Test
    public void legacyFlatTripleMigratesToSingleImmichBackend() {
        Configuration c = new Gson().fromJson(
                "{\"host\":\"https://immich.local/\",\"userid\":\"u@e.com\",\"password\":\"pw\"}",
                Configuration.class);

        assertTrue(Configuration.normalize(c));
        assertEquals(1, c.backendsOrEmpty().size());
        ConfigurationBackendEntry e = c.backends.get(0);
        assertTrue(e.isImmich());
        assertEquals("immich", e.id);
        assertEquals("https://immich.local/", e.host);
        assertEquals("u@e.com", e.userid);
        assertEquals("pw", e.password);
        assertEquals(ConfigurationBackendEntry.API_VERSION_AUTO, e.apiVersion);
        assertTrue(e.isValid());
        assertEquals(1, c.validBackendCount());
    }

    @Test
    public void legacyTripleWithoutTrailingSlashGetsSlash() {
        Configuration c = new Gson().fromJson(
                "{\"host\":\"http://immich.local:3001\",\"userid\":\"u@e.com\",\"password\":\"pw\"}",
                Configuration.class);

        Configuration.normalize(c);
        assertEquals("http://immich.local:3001/", c.backends.get(0).host);
    }

    @Test
    public void noBackendsAndNoLegacyHostLeavesEmpty() {
        Configuration c = new Configuration();
        assertTrue(Configuration.normalize(c)); // backends list materialized
        assertTrue(c.backendsOrEmpty().isEmpty());
        assertEquals(0, c.validBackendCount());
    }

    @Test
    public void explicitBackendsWinOverLegacyFields() {
        Configuration c = new Gson().fromJson(
                "{\"host\":\"https://old.example/\",\"userid\":\"old\",\"password\":\"old\","
                        + "\"backends\":[{\"type\":\"immich\",\"id\":\"b1\",\"host\":\"https://new.example\","
                        + "\"userid\":\"u\",\"password\":\"p\"}]}",
                Configuration.class);

        Configuration.normalize(c);
        assertEquals(1, c.backendsOrEmpty().size());
        assertEquals("https://new.example/", c.backends.get(0).host);
        // legacy fields still mirror on save, but migration must not duplicate
        assertEquals(1, c.validBackendCount());
    }

    @Test
    public void localEntryIsAlwaysValidAndOnlyOneKept() {
        Configuration c = new Gson().fromJson(
                "{\"backends\":[{\"type\":\"local\"},{\"type\":\"local\"},"
                        + "{\"type\":\"immich\",\"host\":\"https://a.example\",\"userid\":\"u\",\"password\":\"p\"}]}",
                Configuration.class);

        Configuration.normalize(c);
        assertEquals(2, c.backendsOrEmpty().size());
        assertTrue(c.backends.get(0).isLocal());
        assertEquals("local", c.backends.get(0).id);
        assertEquals(2, c.validBackendCount());
    }

    @Test
    public void duplicateImmichHostAndUserAreDropped() {
        Configuration c = new Gson().fromJson(
                "{\"backends\":["
                        + "{\"type\":\"immich\",\"id\":\"a\",\"host\":\"https://h.example/\",\"userid\":\"u\",\"password\":\"p1\"},"
                        + "{\"type\":\"immich\",\"id\":\"b\",\"host\":\"https://h.example/\",\"userid\":\"u\",\"password\":\"p2\"},"
                        + "{\"type\":\"immich\",\"id\":\"c\",\"host\":\"https://h.example/\",\"userid\":\"other\",\"password\":\"p3\"}]}",
                Configuration.class);

        Configuration.normalize(c);
        assertEquals(2, c.backendsOrEmpty().size());
        assertEquals("a", c.backends.get(0).id);
        assertEquals("c", c.backends.get(1).id);
    }

    @Test
    public void unparseablePinnedVersionRelaxesToAuto() {
        Configuration c = new Gson().fromJson(
                "{\"backends\":[{\"type\":\"immich\",\"id\":\"x\",\"host\":\"https://h.example/\",\"userid\":\"u\","
                        + "\"password\":\"p\",\"apiVersion\":\"banana\"}]}",
                Configuration.class);

        Configuration.normalize(c);
        assertEquals(ConfigurationBackendEntry.API_VERSION_AUTO, c.backends.get(0).apiVersion);
    }

    @Test
    public void blankApiVersionBecomesAuto() {
        Configuration c = new Gson().fromJson(
                "{\"backends\":[{\"type\":\"immich\",\"id\":\"x\",\"host\":\"https://h.example/\",\"userid\":\"u\","
                        + "\"password\":\"p\",\"apiVersion\":\"  \"}]}",
                Configuration.class);

        Configuration.normalize(c);
        assertEquals(ConfigurationBackendEntry.API_VERSION_AUTO, c.backends.get(0).apiVersion);
    }

    @Test
    public void localEntryApiVersionCleared() {
        Configuration c = new Gson().fromJson(
                "{\"backends\":[{\"type\":\"local\",\"apiVersion\":\"3.1.0\"}]}",
                Configuration.class);

        Configuration.normalize(c);
        assertEquals("", c.backends.get(0).apiVersion);
    }

    @Test
    public void blankIdsAreAutoAssigned() {
        Configuration c = new Gson().fromJson(
                "{\"backends\":["
                        + "{\"type\":\"immich\",\"host\":\"https://a.example/\",\"userid\":\"u1\",\"password\":\"p\"},"
                        + "{\"type\":\"immich\",\"id\":\"custom\",\"host\":\"https://b.example/\",\"userid\":\"u2\",\"password\":\"p\"},"
                        + "{\"type\":\"immich\",\"host\":\"https://c.example/\",\"userid\":\"u3\",\"password\":\"p\"}]}",
                Configuration.class);

        Configuration.normalize(c);
        assertEquals("immich-1", c.backends.get(0).id);
        assertEquals("custom", c.backends.get(1).id);
        assertEquals("immich-3", c.backends.get(2).id);
    }

    @Test
    public void incompleteImmichEntryIsInvalidButKept() {
        Configuration c = new Gson().fromJson(
                "{\"backends\":[{\"type\":\"immich\",\"id\":\"x\",\"host\":\"https://h.example/\",\"userid\":\"\"}]}",
                Configuration.class);

        Configuration.normalize(c);
        assertEquals(1, c.backendsOrEmpty().size());
        assertFalse(c.backends.get(0).isValid());
        assertEquals(0, c.validBackendCount());
    }

    @Test
    public void schemelessHostIsInvalid() {
        Configuration c = new Gson().fromJson(
                "{\"backends\":[{\"type\":\"immich\",\"id\":\"x\",\"host\":\"immich.local\",\"userid\":\"u\",\"password\":\"p\"}]}",
                Configuration.class);

        Configuration.normalize(c);
        assertEquals("immich.local", c.backends.get(0).host); // not mutated
        assertFalse(c.backends.get(0).isValid());
    }

    @Test
    public void normalizeIsIdempotent() {
        Configuration c = new Gson().fromJson(
                "{\"host\":\"https://h.example\",\"userid\":\"u\",\"password\":\"p\",\"backends\":null}",
                Configuration.class);

        assertTrue(Configuration.normalize(c));
        assertFalse(Configuration.normalize(c));
        assertEquals(ConfigurationBackendEntry.API_VERSION_AUTO, c.backends.get(0).apiVersion);
    }
}
