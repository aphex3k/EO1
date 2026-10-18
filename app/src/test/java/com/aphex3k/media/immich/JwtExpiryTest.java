package com.aphex3k.media.immich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.Base64;

/**
 * Covers what the session store reads off an access token to decide when to renew it.
 *
 * <p>{@code 0} means "no expiry could be read", which the store treats as unknown rather than
 * spent — the distinction is what keeps an unparseable token working on the reactive 401 path.
 */
public class JwtExpiryTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static String jwt(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(UTF_8)) + "."
                + enc.encodeToString(payloadJson.getBytes(UTF_8)) + ".signature";
    }

    /** The encoder here is {@code java.util.Base64}, which the device does not have; tests do. */
    private static String jwtExpiringIn(long seconds) {
        return jwt("{\"sub\":\"user-1\",\"exp\":" + ((System.currentTimeMillis() / 1000L) + seconds) + "}");
    }

    @Test
    public void readsTheExpiryClaim() {
        long expiresAt = JwtExpiry.expiresAtMillis(jwtExpiringIn(3600));
        long expected = System.currentTimeMillis() + 3600_000L;
        assertTrue("exp must land within a second of the claim, got " + expiresAt,
                Math.abs(expiresAt - expected) < 2000L);
    }

    @Test
    public void tokenAlreadySpentAtReceiptIsReportedAsUnknown() {
        // A clock that is behind its server would otherwise mint a token the store considers dead
        // on arrival, and every call would re-login.
        assertEquals(0L, JwtExpiry.expiresAtMillis(jwtExpiringIn(-60)));
    }

    @Test
    public void implausibleLifetimeIsReportedAsUnknown() {
        assertEquals(0L, JwtExpiry.expiresAtMillis(jwtExpiringIn(365L * 24 * 3600)));
    }

    @Test
    public void opaqueTokenYieldsUnknown() {
        assertEquals("an Immich token is a JWT; anything else has no readable claim",
                0L, JwtExpiry.expiresAtMillis("at"));
        assertEquals(0L, JwtExpiry.expiresAtMillis("aaa.bbb"));
        assertEquals(0L, JwtExpiry.expiresAtMillis(""));
        assertEquals(0L, JwtExpiry.expiresAtMillis(null));
    }

    @Test
    public void payloadWithoutExpiryYieldsUnknown() {
        assertEquals(0L, JwtExpiry.expiresAtMillis(jwt("{\"sub\":\"user-1\"}")));
        assertEquals(0L, JwtExpiry.expiresAtMillis(jwt("{\"exp\":\"not a number\"}")));
        assertEquals(0L, JwtExpiry.expiresAtMillis(jwt("not json at all")));
    }

    /** A server that pads its base64url must not read as "no expiry". */
    @Test
    public void paddedPayloadStillDecodes() {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(UTF_8));
        String claims = "{\"exp\":" + ((System.currentTimeMillis() / 1000L) + 600) + "}";
        String bare = enc.encodeToString(claims.getBytes(UTF_8));
        String padded = Base64.getUrlEncoder().encodeToString(claims.getBytes(UTF_8));
        assertEquals(JwtExpiry.expiresAtMillis(header + "." + bare + ".sig"),
                JwtExpiry.expiresAtMillis(header + "." + padded + ".sig"));
    }
}
