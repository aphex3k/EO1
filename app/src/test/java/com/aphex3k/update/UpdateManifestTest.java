package com.aphex3k.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateManifestTest {

    private static final String VALID_SHA = "110009dcee21620b166f3abfecb5eff7a873be729d1c2d53822e7acc5f34eb9b";

    private static String validJson(int version, String url, String sha, long size) {
        return "{\"versionCode\":" + version
                + ",\"versionName\":\"1.0.0\""
                + ",\"apkUrl\":\"" + url + "\""
                + ",\"sha256\":\"" + sha + "\""
                + ",\"sizeBytes\":" + size + "}";
    }

    @Test
    public void parseValidManifest() {
        UpdateManifest m = UpdateManifest.parse(validJson(7, "http://updates.local/eo1.apk", VALID_SHA, 1024));
        assertNotNull(m);
        assertEquals(7, m.versionCode);
        assertEquals("http://updates.local/eo1.apk", m.apkUrl);
        assertEquals(VALID_SHA, m.sha256);
        assertEquals(1024, m.sizeBytes);
        assertTrue(m.isValid());
    }

    @Test
    public void parseNullOrBlankIsRejected() {
        assertNull(UpdateManifest.parse(null));
        assertNull(UpdateManifest.parse(""));
        assertNull(UpdateManifest.parse("   \n\t "));
    }

    @Test
    public void parseGarbageIsRejected() {
        assertNull(UpdateManifest.parse("this is not json"));
        assertNull(UpdateManifest.parse("{broken"));
    }

    @Test
    public void zeroVersionCodeIsRejected() {
        UpdateManifest m = UpdateManifest.parse(validJson(0, "http://updates.local/eo1.apk", VALID_SHA, 1024));
        assertEquals("versionCode-must-be-positive", m.validationError());
        assertFalse(m.isValid());
    }

    @Test
    public void nonHttpUrlIsRejected() {
        UpdateManifest m = UpdateManifest.parse(validJson(7, "ftp://updates.local/eo1.apk", VALID_SHA, 1024));
        assertEquals("apkUrl-must-be-http", m.validationError());
        UpdateManifest emptyUrl = UpdateManifest.parse(validJson(7, "  ", VALID_SHA, 1024));
        assertEquals("apkUrl-must-be-http", emptyUrl.validationError());
    }

    @Test
    public void httpsUrlIsAccepted() {
        UpdateManifest m = UpdateManifest.parse(validJson(7, "https://updates.local/eo1.apk", VALID_SHA, 1024));
        assertTrue(m.isValid());
    }

    @Test
    public void badSha256IsRejected() {
        UpdateManifest shortSha = UpdateManifest.parse(validJson(7, "http://updates.local/eo1.apk", "abc", 1024));
        assertEquals("sha256-must-be-64-hex", shortSha.validationError());

        String badChars = "00112233445566778899aabbccddeeffgghh00112233445566778899aabbccddee";
        UpdateManifest nonHex = UpdateManifest.parse(validJson(7, "http://updates.local/eo1.apk", badChars, 1024));
        assertEquals("sha256-must-be-64-hex", nonHex.validationError());

        // 64 chars but wrong length after trim: uppercase is accepted (compared lowercased)
        UpdateManifest upper = UpdateManifest.parse(validJson(7, "http://updates.local/eo1.apk",
                VALID_SHA.toUpperCase(), 1024));
        assertTrue(upper.isValid());
    }

    @Test
    public void sizeValidation() {
        UpdateManifest zero = UpdateManifest.parse(validJson(7, "http://updates.local/eo1.apk", VALID_SHA, 0));
        assertEquals("sizeBytes-must-be-positive", zero.validationError());

        UpdateManifest tooBig = UpdateManifest.parse(
                validJson(7, "http://updates.local/eo1.apk", VALID_SHA, UpdateManifest.MAX_APK_BYTES + 1));
        assertEquals("sizeBytes-exceeds-cap", tooBig.validationError());

        UpdateManifest atCap = UpdateManifest.parse(
                validJson(7, "http://updates.local/eo1.apk", VALID_SHA, UpdateManifest.MAX_APK_BYTES));
        assertTrue(atCap.isValid());
    }

    @Test
    public void isNewerThanIsStrictlyGreater() {
        UpdateManifest m = UpdateManifest.parse(validJson(7, "http://updates.local/eo1.apk", VALID_SHA, 1024));
        assertTrue(m.isNewerThan(6));
        assertFalse(m.isNewerThan(7));
        assertFalse(m.isNewerThan(8));
    }
}
