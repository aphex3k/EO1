package com.aphex3k.immichApi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.eo1.MainActivity;
import com.google.gson.Gson;
import com.vdurmont.semver4j.Semver;

import org.junit.Test;

public class ImmichApiServerVersionResponseTest {

    @Test
    public void parsesMajorMinorPatch() {
        ImmichApiServerVersionResponse response = new Gson().fromJson(
                "{\"major\":3,\"minor\":1,\"patch\":0}",
                ImmichApiServerVersionResponse.class
        );

        Semver version = response.getVersion();
        assertNotNull(version);
        assertEquals("3.1.0", version.getValue());
        assertFalse(version.isLowerThan(MainActivity.IMMICH_MIN_VERSION));
        assertFalse(version.isGreaterThan(MainActivity.IMMICH_MAX_VERSION));
    }

    @Test
    public void marksPreV3AsIncompatible() {
        ImmichApiServerVersionResponse response = new Gson().fromJson(
                "{\"major\":1,\"minor\":119,\"patch\":1}",
                ImmichApiServerVersionResponse.class
        );

        Semver version = response.getVersion();
        assertNotNull(version);
        assertTrue(version.isLowerThan(MainActivity.IMMICH_MIN_VERSION));
    }

    @Test
    public void marksPostMaxAsUnsupported() {
        ImmichApiServerVersionResponse response = new Gson().fromJson(
                "{\"major\":3,\"minor\":2,\"patch\":0}",
                ImmichApiServerVersionResponse.class
        );

        Semver version = response.getVersion();
        assertNotNull(version);
        assertTrue(version.isGreaterThan(MainActivity.IMMICH_MAX_VERSION));
    }
}
