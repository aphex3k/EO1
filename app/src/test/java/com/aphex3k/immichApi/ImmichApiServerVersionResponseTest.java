package com.aphex3k.immichApi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.aphex3k.media.immich.ImmichClientRegistry;
import com.aphex3k.media.immich.ImmichClientV3;
import com.google.gson.Gson;
import com.vdurmont.semver4j.Semver;

import org.junit.Test;

/**
 * Version-probe responses parse into a Semver that lands inside (or outside) the
 * client band registered in {@link ImmichClientRegistry}.
 */
public class ImmichApiServerVersionResponseTest {

    @Test
    public void parsesMajorMinorPatch() {
        ImmichApiServerVersionResponse response = new Gson().fromJson(
                "{\"major\":3,\"minor\":2,\"patch\":2}",
                ImmichApiServerVersionResponse.class
        );

        Semver version = response.getVersion();
        assertNotNull(version);
        assertEquals("3.2.2", version.getValue());
        assertEquals(ImmichClientV3.class, ImmichClientRegistry.clientForVersion(version));
    }

    @Test
    public void marksPreV3AsIncompatible() {
        ImmichApiServerVersionResponse response = new Gson().fromJson(
                "{\"major\":1,\"minor\":119,\"patch\":1}",
                ImmichApiServerVersionResponse.class
        );

        Semver version = response.getVersion();
        assertNotNull(version);
        assertNull(ImmichClientRegistry.clientForVersion(version));
    }

    @Test
    public void marksPostMaxAsUnsupported() {
        ImmichApiServerVersionResponse response = new Gson().fromJson(
                "{\"major\":3,\"minor\":3,\"patch\":0}",
                ImmichApiServerVersionResponse.class
        );

        Semver version = response.getVersion();
        assertNotNull(version);
        assertNull(ImmichClientRegistry.clientForVersion(version));
    }
}
