package com.aphex3k.media.immich;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Reads the expiry out of an Immich access token, which is a JWT.
 *
 * <p>Immich's login response carries no {@code expiresIn} and the 3.x API has no refresh
 * endpoint, so the token's own {@code exp} claim is the only place lifetime is stated. Parsing
 * it here keeps that answer server-config-independent: an operator's
 * {@code ACCESS_TOKEN_LIFE_TIME} never has to be mirrored in the app.
 *
 * <p>Base64url is decoded by hand rather than with {@code android.util.Base64}: the class must
 * stay linkable in a JVM unit test, and {@code java.util.Base64} does not exist on API 19.
 */
public final class JwtExpiry {

    /** A claim further out than this is not a lifetime, it is a parse accident. */
    private static final long MAX_PLAUSIBLE_LIFETIME_MS = 48L * 60L * 60L * 1000L;

    private static final String BASE64URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private JwtExpiry() {
    }

    /**
     * @return wall-clock millis at which the token expires, or {@code 0} when no expiry could be
     *         read — which means "unknown", not "expired": the caller keeps using the token and
     *         relies on a 401 to renew it.
     */
    public static long expiresAtMillis(String jwt) {
        return expiresAtMillis(jwt, System.currentTimeMillis());
    }

    /** Overload for tests, which cannot wait for a real clock to pass a claim. */
    static long expiresAtMillis(String jwt, long nowMillis) {
        if (jwt == null) {
            return 0L;
        }
        int first = jwt.indexOf('.');
        int second = first < 0 ? -1 : jwt.indexOf('.', first + 1);
        if (first <= 0 || second <= first) {
            return 0L;
        }
        byte[] payload = decodeBase64Url(jwt.substring(first + 1, second));
        if (payload == null) {
            return 0L;
        }
        try {
            JsonElement root = JsonParser.parseString(new String(payload, UTF_8));
            if (root == null || !root.isJsonObject()) {
                return 0L;
            }
            JsonObject claims = root.getAsJsonObject();
            JsonElement exp = claims.get("exp");
            if (exp == null || !exp.isJsonPrimitive()) {
                return 0L;
            }
            long seconds = exp.getAsLong();
            if (seconds <= 0L) {
                return 0L;
            }
            long millis = seconds * 1000L;
            if (millis <= nowMillis || millis - nowMillis > MAX_PLAUSIBLE_LIFETIME_MS) {
                // Either already spent — or the device clock is badly wrong, in which case
                // trusting the claim would turn every call into a re-login.
                return 0L;
            }
            return millis;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** @return the decoded bytes, or null if the text is not base64url. */
    static byte[] decodeBase64Url(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int len = text.length();
        while (len > 0 && text.charAt(len - 1) == '=') {
            len--;
        }
        if (len == 0) {
            return null;
        }
        byte[] out = new byte[len * 3 / 4];
        int written = 0;
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < len; i++) {
            int value = BASE64URL.indexOf(text.charAt(i));
            if (value < 0) {
                return null;
            }
            acc = (acc << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (written == out.length) {
                    return null;
                }
                out[written++] = (byte) ((acc >> bits) & 0xFF);
            }
        }
        return written == out.length ? out : Arrays.copyOf(out, written);
    }
}
