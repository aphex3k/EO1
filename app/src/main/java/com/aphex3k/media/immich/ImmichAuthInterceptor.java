package com.aphex3k.media.immich;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.List;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Puts this session's bearer token on every Immich request that needs one.
 *
 * <p>It only ever attaches a token the session already holds — it never logs in. An interceptor
 * cannot throw the app's checked auth exceptions and cannot re-issue a request whose streamed body
 * is already consumed, so acquiring and renewing stays in
 * {@link ImmichClientV3}'s {@code withSession}, where both are answerable. A request that goes out
 * with no token is therefore a caller that skipped that step, not a silent failure here.
 */
public final class ImmichAuthInterceptor implements Interceptor {

    /**
     * Paths that must go out bare. {@code /api/auth/login} is what mints the token.
     * {@code /api/server/version} matters for a subtler reason: the version probe runs before the
     * first login in {@link ImmichMediaBackend#initialize()}, so attaching a cached token there
     * would let a spent one 401 the probe and raise a "could not determine the Immich server
     * version" warning that has nothing to do with the server's version.
     */
    private static final List<String> PUBLIC_PATHS = java.util.Arrays.asList(
            "/api/auth/login", "/api/server/version");

    private final ImmichSessionStore.Session session;

    public ImmichAuthInterceptor(ImmichSessionStore.Session session) {
        this.session = session;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        String token = session.peek();
        if (token != null && !isPublic(request.url().encodedPath())) {
            request = request.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .build();
        }
        return chain.proceed(request);
    }

    private static boolean isPublic(String path) {
        for (String publicPath : PUBLIC_PATHS) {
            if (path.equals(publicPath) || path.endsWith(publicPath)) {
                return true;
            }
        }
        return false;
    }
}
