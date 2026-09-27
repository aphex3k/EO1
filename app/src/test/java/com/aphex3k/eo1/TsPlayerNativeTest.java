package com.aphex3k.eo1;

import com.example.tsplayer.TsPlayerNative;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests cannot load armeabi-v7a natives; availability must stay false here.
 * On-device validation is documented in docs/TSPLAYER.md.
 */
public class TsPlayerNativeTest {

    @Test
    public void isAvailable_isFalseOnJvmUnitTestHost() {
        assertFalse(TsPlayerNative.isAvailable());
    }

    @Test
    public void buildConfig_useTsPlayerFlagPresent() {
        // Ensures the BuildConfig field exists for MainActivity selection.
        boolean ignored = BuildConfig.USE_TSPLAYER;
        assertTrue(ignored || !ignored);
    }
}
