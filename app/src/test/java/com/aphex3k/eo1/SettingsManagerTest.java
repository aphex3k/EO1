package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.view.LayoutInflater;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Covers the trusted-network flag (SharedPreferences-backed, NOT part of
 * configuration.json) and the web config export/import paths.
 */
public class SettingsManagerTest {

    private static final String VALID_CONFIG_JSON =
            "{\"backends\":[{\"type\":\"immich\",\"id\":\"immich-1\","
                    + "\"host\":\"https://immich.local\",\"userid\":\"u@e.com\","
                    + "\"password\":\"secret-pw\",\"apiVersion\":\"auto\"}],"
                    + "\"interval\":7,\"selectedTimeZoneId\":\"America/Los_Angeles\"}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void trustedNetworkDefaultsOff() throws Exception {
        SettingsManager sm = new SettingsManager(new FakeListener(tmp.newFolder("files")));
        assertFalse(sm.isTrustedNetwork());
    }

    @Test
    public void trustedNetworkTogglePersists() throws Exception {
        SettingsManager sm = new SettingsManager(new FakeListener(tmp.newFolder("files")));
        sm.setTrustedNetwork(true);
        assertTrue(sm.isTrustedNetwork());
        sm.setTrustedNetwork(false);
        assertFalse(sm.isTrustedNetwork());
    }

    @Test
    public void importValidWritesFileAndUpdatesLiveConfiguration() throws Exception {
        File files = tmp.newFolder("files");
        SettingsManager sm = new SettingsManager(new FakeListener(files));

        assertNull(sm.importConfiguration(VALID_CONFIG_JSON));

        Configuration c = sm.getConfiguration();
        assertNotNull(c);
        assertEquals(1, c.validBackendCount());
        assertEquals("https://immich.local/", c.backends.get(0).host); // normalized: trailing slash
        assertEquals(7, c.interval);

        File file = new File(files, "configuration.json");
        assertTrue(file.isFile());
        String onDisk = readAll(file);
        assertTrue(onDisk.contains("secret-pw"));
        // Legacy flat fields are mirrored so older APKs can still read the file.
        assertTrue(onDisk.contains("\"host\""));
    }

    @Test
    public void exportIncludesCredentialsButNotTrustedFlag() throws Exception {
        File files = tmp.newFolder("files");
        SettingsManager sm = new SettingsManager(new FakeListener(files));
        assertNull(sm.importConfiguration(VALID_CONFIG_JSON));
        sm.setTrustedNetwork(true);

        String exported = sm.exportConfigurationJson();
        assertTrue(exported.contains("secret-pw"));
        // The trusted-network flag must never leak into the configuration document.
        assertFalse(exported.contains("trustedNetwork"));
        assertFalse(exported.contains("trusted_network"));

        // The on-disk file is written exactly like the export (legacy fields mirrored).
        String onDisk = readAll(new File(files, "configuration.json"));
        assertTrue(onDisk.contains("secret-pw"));
        assertFalse(onDisk.contains("trusted_network"));
    }

    @Test
    public void exportDoesNotMutateLiveConfiguration() throws Exception {
        SettingsManager sm = new SettingsManager(new FakeListener(tmp.newFolder("files")));
        assertNull(sm.importConfiguration(VALID_CONFIG_JSON));
        sm.exportConfigurationJson();
        sm.exportConfigurationJson();
        // Idempotent: still exactly one backend, normalized host untouched.
        assertEquals(1, sm.getConfiguration().backends.size());
        assertEquals("https://immich.local/", sm.getConfiguration().backends.get(0).host);
    }

    @Test
    public void importLegacyFlatTripleIsMigrated() throws Exception {
        SettingsManager sm = new SettingsManager(new FakeListener(tmp.newFolder("files")));
        assertNull(sm.importConfiguration(
                "{\"host\":\"https://legacy.local\",\"userid\":\"u@e.com\",\"password\":\"p\"}"));
        assertEquals(1, sm.getConfiguration().validBackendCount());
        assertTrue(sm.getConfiguration().backends.get(0).isImmich());
    }

    @Test
    public void importInvalidJsonIsRejectedWithoutReplacingConfiguration() throws Exception {
        SettingsManager sm = new SettingsManager(new FakeListener(tmp.newFolder("files")));
        assertNull(sm.importConfiguration(VALID_CONFIG_JSON));

        String error = sm.importConfiguration("this is not json");
        assertNotNull(error);
        assertTrue(error.contains("not valid JSON"));

        assertEquals(1, sm.getConfiguration().validBackendCount());
        assertEquals("https://immich.local/", sm.getConfiguration().backends.get(0).host);
    }

    @Test
    public void importWithoutUsableBackendIsRejected() throws Exception {
        SettingsManager sm = new SettingsManager(new FakeListener(tmp.newFolder("files")));

        String error = sm.importConfiguration("{}");
        assertNotNull(error);
        assertTrue(error.contains("no usable backend"));
        assertEquals(0, sm.getConfiguration().validBackendCount());
    }

    @Test
    public void importEmptyBodyIsRejected() throws Exception {
        SettingsManager sm = new SettingsManager(new FakeListener(tmp.newFolder("files")));
        assertNotNull(sm.importConfiguration(null));
        assertNotNull(sm.importConfiguration("   "));
    }

    @Test
    public void trustedNetworkTokenIsGeneratedOnceAndStable() throws Exception {
        SettingsManager sm = new SettingsManager(new FakeListener(tmp.newFolder("files")));
        String first = sm.trustedNetworkToken();
        assertNotNull(first);
        assertEquals(32, first.length());
        assertTrue(first.matches("[0-9a-f]{32}"));
        // Subsequent calls return the same persisted value.
        assertEquals(first, sm.trustedNetworkToken());
        assertEquals(first, sm.trustedNetworkToken());
    }

    @Test
    public void trustedNetworkTokenIsNotPartOfConfigurationDocument() throws Exception {
        File files = tmp.newFolder("files");
        SettingsManager sm = new SettingsManager(new FakeListener(files));
        String token = sm.trustedNetworkToken();
        assertNull(sm.importConfiguration(VALID_CONFIG_JSON));

        assertFalse(sm.exportConfigurationJson().contains(token));
        assertFalse(readAll(new File(files, "configuration.json")).contains(token));
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, Charset.forName("UTF-8")));
            }
            return sb.toString();
        } finally {
            in.close();
        }
    }

    /** In-memory {@link SettingsManagerListener} stub: real files dir, in-memory prefs. */
    private static class FakeListener implements SettingsManagerListener {
        private final File filesDir;
        private final FakeSharedPreferences prefs = new FakeSharedPreferences();

        FakeListener(File filesDir) {
            this.filesDir = filesDir;
        }

        @Override public void settingsChanged() {}
        @Override public File getFilesDir() { return filesDir; }
        @Override public LayoutInflater getLayoutInflater() { return null; }
        @Override public Object getSystemService(String alarmService) { return null; }
        @Override public SharedPreferences getDefaultSharedPreferences() { return prefs; }
        @Override public void handleException(Exception e) {}
        @Override public void debugInformationProvided(DebugInformation debugInformation) {}
        @Override public void displayPicture(File finalTempFile, String assetId) {}
        @Override public void displayVideo(File finalTempFile, String assetId) {}
    }

    /** Minimal in-memory SharedPreferences for JVM unit tests. */
    private static class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<String, Object>();
        private static final Object REMOVED = new Object();

        @Override
        public Set<String> getStringSet(String key, Set<String> defValue) {
            Object v = values.get(key);
            return v != null ? (Set<String>) v : defValue;
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v != null ? (String) v : defValue;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = values.get(key);
            return v != null ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = values.get(key);
            return v != null ? (Long) v : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = values.get(key);
            return v != null ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return v != null ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Map<String, ?> getAll() {
            return values;
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public Editor edit() {
            return new FakeEditor();
        }

        private class FakeEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<String, Object>();
            private boolean clearAll;

            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> set) { pending.put(key, set); return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { pending.put(key, REMOVED); return this; }
            @Override public Editor clear() { clearAll = true; pending.clear(); return this; }
            @Override public boolean commit() { apply(); return true; }

            @Override
            public void apply() {
                if (clearAll) {
                    values.clear();
                    clearAll = false;
                }
                for (Map.Entry<String, Object> e : pending.entrySet()) {
                    if (e.getValue() == REMOVED) {
                        values.remove(e.getKey());
                    } else {
                        values.put(e.getKey(), e.getValue());
                    }
                }
                pending.clear();
            }
        }
    }
}
