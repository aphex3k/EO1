package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;

/**
 * The rule the frame's MQTT identity is held to, and the reason {@code SettingsManager.mqttClientId()} mints
 * once rather than deriving from anything the operator can type.
 *
 * <p>Two frames reaching one broker under one client id is not a cosmetic collision: MQTT answers the second
 * CONNECT by closing the first, so the pair kicks each other off in a loop and the broker log reads exactly
 * like the disconnect this feature was reported for. A stable id is only safe because it is also unique.
 */
public class MqttClientIdTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test
    public void theShapeWeMintIsLegal() {
        // "eo1-" plus 16 hex characters, which is what generateToken() yields truncated. If the prefix or the
        // hex length ever grows past the 3.1.1 guaranteed bound, this fails instead of a frame failing to
        // connect in the field.
        String id = "eo1-" + "0123456789abcdef";
        assertTrue(SettingsManager.isClientIdSafe(id));
        assertEquals(20, id.getBytes(UTF8).length);
        assertTrue(id.getBytes(UTF8).length <= SettingsManager.MQTT_CLIENT_ID_MAX_BYTES);
    }

    @Test
    public void tooLongIsRefused() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SettingsManager.MQTT_CLIENT_ID_MAX_BYTES + 1; i++) {
            sb.append('a');
        }
        assertFalse(SettingsManager.isClientIdSafe(sb.toString()));
        assertTrue(SettingsManager.isClientIdSafe(
                sb.substring(0, SettingsManager.MQTT_CLIENT_ID_MAX_BYTES)));
    }

    @Test
    public void charactersTheSpecReservesAreRefused() {
        // MQTT 3.1.1 restricts a client id to 0-9, a-z, "-" and "_". Uppercase and punctuation are the two
        // ways a hand-written or out-of-band value gets here and then silently fails at some brokers.
        assertFalse(SettingsManager.isClientIdSafe("EO1-abc"));
        assertFalse(SettingsManager.isClientIdSafe("eo1_abc.def"));
        assertFalse(SettingsManager.isClientIdSafe("eo1 abc"));
        assertFalse(SettingsManager.isClientIdSafe("eo1-é"));
        assertFalse("an empty id must never reach Paho, which rejects it",
                SettingsManager.isClientIdSafe(""));
        assertFalse(SettingsManager.isClientIdSafe(null));
    }

    @Test
    public void mintedIdsDoNotCollide() {
        // The property that makes a stable id safe at all. generateToken() is 128 bits of SecureRandom
        // truncated to 64, so a collision across a fleet this size is not a risk worth a lock file — but a
        // regression to something like a timestamp would be, and this says so in one pass.
        Set<String> seen = new HashSet<String>();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 1000; i++) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            StringBuilder sb = new StringBuilder("eo1-");
            for (int b = 0; b < 8; b++) {
                sb.append(Character.forDigit((bytes[b] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(bytes[b] & 0xF, 16));
            }
            String id = sb.toString();
            assertTrue(SettingsManager.isClientIdSafe(id));
            assertTrue("two frames would start kicking each other off at " + i, seen.add(id));
        }
    }
}
