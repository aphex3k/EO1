package com.aphex3k.media.immich;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import com.aphex3k.eo1.AuthenticationFailedException;
import com.aphex3k.eo1.AuthenticationUnavailableException;
import com.aphex3k.eo1.InvalidCredentialsException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The process's Immich authentication sessions: one per {@code host + userid}, each holding the
 * bearer token the app last obtained, when it expires, and a cooldown after a failed login.
 *
 * <p>Deliberately wider than a client instance. {@link com.aphex3k.eo1.MediaManager} builds brand
 * new backends on every pool rebuild, so a session owned by a client would die with it and a
 * failing pool — which rebuilds every rotation tick — would log in every tick. Living here means a
 * rebuild reuses the live token and credentials are spent only when a token actually needs
 * acquiring. The shape (installed from {@code Eo1Application}, fetched with {@link #get()}) is
 * {@link com.aphex3k.eo1.CrashReporter}'s.
 *
 * <p>Tokens are mirrored into default SharedPreferences so a restart does not spend a login. The
 * mirror is an optimisation and nothing depends on it: an entry is written only when its expiry is
 * known and still ahead, keyed by a hash of {@code host + userid} so no credential or address is
 * duplicated on disk, and an entry whose password hash no longer matches is discarded the moment it
 * is touched — which is what makes editing a password take effect on the next tick rather than
 * replaying a token the new password cannot mint. Never reachable from the LAN web server: no
 * route reads preferences, and the token appears in no {@code /state} or {@code /config} document.
 *
 * <p>Immich's 3.x API has no refresh endpoint, so renewing is re-running the login with the
 * configured credentials. Credentials stay in {@code configuration.json} exactly as before; this
 * class is where they stop being needed.
 */
public final class ImmichSessionStore {

    private static final String TAG = "EO1";

    /** Renew this much before the stated expiry, so a call never races the token's last moment. */
    private static final long REFRESH_MARGIN_MS = 60_000L;

    /** After a login fails, do not try again for this long: a wrong password must not be retried
     *  once per rotation tick while the pool keeps rebuilding. */
    private static final long FAILURE_COOLDOWN_MS = 60_000L;

    /** Default-preferences key holding the token mirror. */
    private static final String PREF_KEY = "immich_sessions";

    /**
     * Acquires a fresh token by running the actual login.
     *
     * <p>The clause is the app's auth failure set rather than {@code Exception} so that callers
     * like {@link ImmichClientV3#login()} keep reporting <em>why</em> authentication failed — a
     * wrong password and an unreachable auth endpoint have different remedies, and a toast that
     * says only "login threw" loses that.
     */
    public interface LoginRunner {
        /**
         * @return the access token, or null when the server authenticated the user but handed back
         *         nothing usable
         */
        String run() throws AuthenticationFailedException, AuthenticationUnavailableException,
                InvalidCredentialsException, IOException;
    }

    private static volatile ImmichSessionStore instance = new ImmichSessionStore(null);

    @Nullable private final SharedPreferences prefs;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean hydrated;

    private ImmichSessionStore(@Nullable SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * Points the store at real preferences. Safe to call before any Activity exists; a failure
     * leaves the process running on the in-memory store it started with.
     */
    public static void install(Context app) {
        try {
            Context owner = app.getApplicationContext() != null ? app.getApplicationContext() : app;
            // Context.getDefaultSharedPreferences() does not exist at API 19; same entry point
            // SettingsManager uses.
            instance = new ImmichSessionStore(
                    android.preference.PreferenceManager.getDefaultSharedPreferences(owner));
        } catch (Throwable t) {
            Log.w(TAG, "ImmichSessionStore: token mirror unavailable, sessions stay in memory", t);
        }
    }

    /** The process's store; never null — before {@link #install} it is memory-only. */
    public static ImmichSessionStore get() {
        return instance;
    }

    /** Drops every session, including anything read from the mirror. Test seam. */
    public static void clearForTests() {
        instance = new ImmichSessionStore(null);
    }

    /**
     * The session for these credentials, creating or replacing it as needed.
     *
     * <p>A stored password hash that no longer matches yields a brand new session rather than a
     * patched one: the old token was minted by the old password and the cooldown belongs to the old
     * failure, so neither should be carried into the new configuration.
     */
    public Session attach(String host, String userid, String password) {
        hydrate();
        String id = sessionId(host, userid);
        String pwHash = hash(password);
        Session existing = sessions.get(id);
        if (existing != null && pwHash.equals(existing.pwHash)) {
            return existing;
        }
        Session fresh = new Session(this, pwHash);
        sessions.put(id, fresh);
        return fresh;
    }

    private void hydrate() {
        if (hydrated || prefs == null) {
            hydrated = true;
            return;
        }
        synchronized (this) {
            if (hydrated) {
                return;
            }
            try {
                String json = prefs.getString(PREF_KEY, null);
                if (json != null) {
                    JsonElement parsed = JsonParser.parseString(json);
                    long now = System.currentTimeMillis();
                    if (parsed != null && parsed.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                            if (!e.getValue().isJsonObject()) {
                                continue;
                            }
                            JsonObject v = e.getValue().getAsJsonObject();
                            String pw = str(v, "pw");
                            String token = str(v, "token");
                            long exp = num(v, "exp");
                            // Nothing can have attached a session for this key yet: attach()
                            // hydrates under the same monitor before it reads the map.
                            if (pw != null && token != null && exp > now) {
                                sessions.put(e.getKey(),
                                        new Session(ImmichSessionStore.this, pw, token, exp));
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "ImmichSessionStore: could not read the token mirror", t);
            }
            hydrated = true;
        }
    }

    private void persist() {
        if (prefs == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Session> e : sessions.entrySet()) {
                Session s = e.getValue();
                String token = s.token;
                long exp = s.expiresAtMillis;
                // An unknown expiry is worthless across a restart — nothing would renew it in
                // time — so such a session is kept in memory only.
                if (token == null || exp <= now) {
                    continue;
                }
                JsonObject v = new JsonObject();
                v.addProperty("pw", s.pwHash);
                v.addProperty("token", token);
                v.addProperty("exp", exp);
                root.add(e.getKey(), v);
            }
            // commit(), not apply(): a frame may be power-cycled moments after a rotation, and a
            // token that never reached the file is a login spent again on the next boot.
            prefs.edit().putString(PREF_KEY, root.toString()).commit();
        } catch (Throwable t) {
            Log.w(TAG, "ImmichSessionStore: could not write the token mirror", t);
        }
    }

    @Nullable
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0L;
        } catch (Exception ex) {
            return 0L;
        }
    }

    private static String sessionId(String host, String userid) {
        return hash(String.valueOf(host) + '\n' + String.valueOf(userid));
    }

    private static String hash(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 and UTF-8 are both guaranteed; a throw here means a broken platform provider,
            // and the session still works — an identity hash of the plain text is fine as a fallback.
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * One backend's authentication state. All token transitions happen under {@link #lock} so the
     * rotation worker, the prefetch thread and the tagging thread cannot interleave two logins —
     * each of which would add a session row on the server.
     */
    public static final class Session {

        private final ImmichSessionStore owner;
        private final String pwHash;
        private final Object lock = new Object();

        @Nullable volatile String token;
        volatile long expiresAtMillis;
        volatile long nextLoginAllowedAt;

        private Session(ImmichSessionStore owner, String pwHash) {
            this.owner = owner;
            this.pwHash = pwHash;
        }

        private Session(ImmichSessionStore owner, String pwHash, String token,
                        long expiresAtMillis) {
            this(owner, pwHash);
            this.token = token;
            this.expiresAtMillis = expiresAtMillis;
        }

        /**
         * A token to authenticate with now, acquiring one if there is none or the cached one is
         * spent. A still-valid token is returned without touching the network.
         *
         * @throws AuthenticationFailedException       the server refused the credentials (401), or
         *         authenticated the user without handing back a token
         * @throws AuthenticationUnavailableException  the auth endpoint answered 404 or no body
         * @throws InvalidCredentialsException        the account is not usable
         * @throws IOException                        a wire failure, or a retry refused while
         *         cooling down after an attempt that failed
         */
        public String token(LoginRunner runner)
                throws AuthenticationFailedException, AuthenticationUnavailableException,
                InvalidCredentialsException, IOException {
            synchronized (lock) {
                if (usable(System.currentTimeMillis())) {
                    return token;
                }
                return loginLocked(runner, false);
            }
        }

        /**
         * Establishes a new token regardless of what is cached — the server answered 401, so
         * whatever was held is spent even if its own claim says otherwise.
         *
         * @throws IOException a wire failure, or a retry refused while cooling down after an
         *         attempt that failed; the other failures are the ones {@link #token} documents
         */
        public String reauthenticate(LoginRunner runner)
                throws AuthenticationFailedException, AuthenticationUnavailableException,
                InvalidCredentialsException, IOException {
            synchronized (lock) {
                return loginLocked(runner, true);
            }
        }

        /**
         * Renews a held token that its own expiry says is spent, and does nothing otherwise —
         * including when no token is held, which stays {@link ImmichClientV3#login()}'s problem
         * rather than becoming a surprise login in the middle of a download. A request that goes
         * out with nothing to authenticate it is answered by the 401 path.
         *
         */
        public void renewIfSpent(LoginRunner runner)
                throws AuthenticationFailedException, AuthenticationUnavailableException,
                InvalidCredentialsException, IOException {
            synchronized (lock) {
                if (token == null || usable(System.currentTimeMillis())) {
                    return;
                }
                loginLocked(runner, true);
            }
        }

        /** The token to put on a request, or null when this session has none. Never blocks. */
        @Nullable
        String peek() {
            return token;
        }

        boolean hasToken() {
            return token != null;
        }

        /**
         * @param force true to ignore a cached token and log in again
         */
        private String loginLocked(LoginRunner runner, boolean force)
                throws AuthenticationFailedException, AuthenticationUnavailableException,
                InvalidCredentialsException, IOException {
            long now = System.currentTimeMillis();
            if (!force && usable(now)) {
                return token;
            }
            if (nextLoginAllowedAt > now) {
                throw new IOException("Immich login is cooling down after a failed attempt;"
                        + " next try in " + ((nextLoginAllowedAt - now) / 1000L) + "s");
            }
            String fresh;
            try {
                fresh = runner.run();
            } catch (Exception e) {
                // A refusal means the credential is spent, so hold nothing; a wire failure says
                // nothing about the token we already have, and dropping it would turn a network
                // blip into a forced re-login for every request once the link comes back.
                if (e instanceof AuthenticationFailedException) {
                    token = null;
                    expiresAtMillis = 0L;
                }
                nextLoginAllowedAt = System.currentTimeMillis() + FAILURE_COOLDOWN_MS;
                throw e;
            }
            if (fresh == null || fresh.isEmpty()) {
                token = null;
                expiresAtMillis = 0L;
                nextLoginAllowedAt = System.currentTimeMillis() + FAILURE_COOLDOWN_MS;
                throw new AuthenticationFailedException(-1,
                        "the Immich login authenticated the user but returned no access token");
            }
            token = fresh;
            expiresAtMillis = JwtExpiry.expiresAtMillis(fresh);
            nextLoginAllowedAt = 0L;
            owner.persist();
            return token;
        }

        private boolean usable(long now) {
            if (token == null) {
                return false;
            }
            // expiresAtMillis 0 means "unknown": keep the token and let a 401 decide.
            return expiresAtMillis == 0L || now < expiresAtMillis - REFRESH_MARGIN_MS;
        }
    }
}
