package com.aphex3k.eo1;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aphex3k.eo1.trust.MergedTrust;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import okhttp3.CipherSuite;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

@Keep
public class ApiServiceGenerator {

    public static <S> S createService(Class<S> serviceClass, String host, @Nullable Context context, @Nullable ProgressListener progressListener) {
        return createService(serviceClass, host, context, progressListener, null);
    }

    /**
     * @param authInterceptor supplies credentials per request, or {@code null} for a client that
     *        authenticates some other way. There is no cookie jar on this path: an Immich session
     *        is a bearer token the app holds and can expire, not a string it happens to be sent.
     */
    public static <S> S createService(Class<S> serviceClass, String host, @Nullable Context context,
                                      @Nullable ProgressListener progressListener,
                                      @Nullable Interceptor authInterceptor) {
        return new Retrofit.Builder()
                .baseUrl(host)
                .addConverterFactory(GsonConverterFactory.create())
                .client(getNewHttpClient(context, progressListener, authInterceptor))
                .build()
                .create(serviceClass);
    }

    protected static OkHttpClient getNewHttpClient(@Nullable Context context,
                                                   ProgressListener progressListener,
                                                   @Nullable Interceptor authInterceptor) {

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(new RedactingLogger());

        if (BuildConfig.DEBUG) {
            logging.setLevel(HttpLoggingInterceptor.Level.HEADERS);
        }
        else {
            logging.setLevel(HttpLoggingInterceptor.Level.NONE);
        }

        OkHttpClient.Builder client = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .addInterceptor(new Interceptor() {
                    @NonNull
                    @Override public Response intercept(@NonNull Chain chain) throws IOException {
                        final int max = 60 * 60 * 24 * 28;

                        Request request = chain.request();

                        request = request.newBuilder().header("Cache-Control",
                                    "immutable, max-age=" + max).build();

                        return chain.proceed(request);
                    }
                });

        if (authInterceptor != null) {
            // Added before the logging interceptor, so logging is innermost and sees what actually
            // goes out — which is why it has to redact.
            client.addInterceptor(authInterceptor);
        }

        if (progressListener != null) {
            client.addNetworkInterceptor(chain -> {
                Response originalResponse = chain.proceed(chain.request());
                return originalResponse.newBuilder()
                        .body(new ProgressResponseBody(originalResponse.body(), chain.request(), progressListener))
                        .build();
            });
        }

        if (BuildConfig.DEBUG) {
            client.addInterceptor(logging);
        }

        return enableTls12OnPreLollipop(client, context).build();
    }

    /**
     * The frame's one TLS choke point: both the Immich client and {@link UpdateManager}'s download
     * client reach the network through here, so this is the whole surface an app-local trust decision
     * can cover.
     *
     * <p>https://github.com/square/okhttp/issues/2372 is why the method exists at all — API 19 supports
     * TLS 1.2 but does not enable it by default. What it now also does is install
     * {@link MergedTrust}'s anchor set, because the ROM's store cannot validate a current certificate:
     * the shipped {@code SSLContext} used to be initialised with {@code null} trust managers, which
     * means "whatever the device was shipped with in 2013".
     *
     * @param context used only to read the bundle on/off flag; {@code null} keeps the bundle on, which
     *        is what a caller without a context (and a frame that has never had the flag touched)
     *        should get.
     */
    public static OkHttpClient.Builder enableTls12OnPreLollipop(OkHttpClient.Builder client,
                                                                @Nullable Context context) {
        try {
            boolean bundleEnabled = trustBundleEnabled(context);
            MergedTrust.Result trust = MergedTrust.create(bundleEnabled);

            SSLContext sc;
            if (trust.trustManager != null) {
                sc = SSLContext.getInstance("TLSv1.2");
                sc.init(null, new TrustManager[]{trust.trustManager}, null);
                // The two-argument form, so OkHttp verifies the name in the certificate against the
                // host it is connecting to using the same anchors that decided the chain was valid.
                // Handing it a socket factory built over a different trust manager than the one
                // OkHttp checks would be how a trust layer ends up validating one set and trusting
                // another.
                client.sslSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()), trust.trustManager);
            } else {
                // Platform default, exactly as before: no merged manager was safe to install.
                sc = SSLContext.getInstance("TLSv1.2");
                sc.init(null, null, null);
                client.sslSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));
                if (bundleEnabled) {
                    // Wanted the bundle, could not install it safely. This is the one line that tells
                    // an operator why a frame is still failing after an update that added roots.
                    Log.w("EO1-trust", "root bundle not installed: " + trust.detail);
                }
            }

            // OkHttp's ECDSA suites are all AEAD, and this platform's OpenSSL has none — so a server
            // presenting an ECDSA chain has no suite in common with the frame unless the CBC ones are
            // named, and roots alone cannot open that gate (docs/TRUST.md).
            //
            // Additive, deliberately. The obvious spelling — .cipherSuites(ECDSA_CBC_ON_TLS12) — makes
            // the list exactly those four and silently deletes every AEAD suite the spec inherited, so
            // a client on any platform newer than the frames loses TLS 1.2 GCM, which is what the
            // Immich live-tests exercised. Naming suites is a union operation here, never a
            // replacement, for the same reason MergedTrust refuses to install a smaller anchor set.
            ConnectionSpec.Builder specBuilder = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2);
            List<CipherSuite> suites = ConnectionSpec.MODERN_TLS.cipherSuites();
            if (suites != null) {
                List<CipherSuite> widened = new ArrayList<CipherSuite>(suites);
                for (int i = 0; i < ECDSA_CBC_ON_TLS12.length; i++) {
                    if (!widened.contains(ECDSA_CBC_ON_TLS12[i])) {
                        widened.add(ECDSA_CBC_ON_TLS12[i]);
                    }
                }
                specBuilder.cipherSuites(widened.toArray(new CipherSuite[widened.size()]));
            }
            ConnectionSpec cs = specBuilder.build();

            List<ConnectionSpec> specs = new ArrayList<>();
            specs.add(cs);
            specs.add(ConnectionSpec.COMPATIBLE_TLS);
            specs.add(ConnectionSpec.CLEARTEXT);

            client.connectionSpecs(specs);
        } catch (Exception exc) {
            Log.e("OkHttpTLSCompat", "Error while setting TLS 1.2", exc);
        }

        return client;
    }

    /**
     * The two ECDSA CBC suites a KitKat handshake can use and {@code MODERN_TLS} does not list. The
     * RSA CBC suites are already there; GCM/CCM entries are unusable on this platform, which is why
     * they are absent from the frame's offered set and present in its inherited one — the union keeps
     * them for every newer platform this same code runs on.
     */
    private static final CipherSuite[] ECDSA_CBC_ON_TLS12 = {
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
    };

    /**
     * A socket factory trusting the same anchors as every OkHttp client this app builds, for the one
     * consumer that cannot take an {@code OkHttpClient}: Paho speaks its own TLS.
     *
     * <p>Without this, an {@code ssl://} broker keeps using the ROM store and fails for the reason
     * this whole feature exists, while every other connection on the frame works — which reads as a
     * broker problem and never as a trust problem.
     */
    public static javax.net.ssl.SSLSocketFactory trustAnchoredSocketFactory(@Nullable Context context)
            throws Exception {
        MergedTrust.Result trust = MergedTrust.create(trustBundleEnabled(context));
        SSLContext sc = SSLContext.getInstance("TLSv1.2");
        if (trust.trustManager != null) {
            sc.init(null, new TrustManager[]{trust.trustManager}, null);
        } else {
            sc.init(null, null, null);
        }
        return new Tls12SocketFactory(sc.getSocketFactory());
    }

    private static boolean trustBundleEnabled(@Nullable Context context) {
        if (context == null) {
            return true;
        }
        try {
            // Context.getDefaultSharedPreferences() postdates API 19; same entry point as MainActivity.
            return android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean(SettingsManager.PREF_TRUST_BUNDLE, true);
        } catch (Exception e) {
            // A store that will not answer gets the safe reading: on, because a frame whose Immich
            // server cannot be validated is not served by silently trusting an old set of anchors.
            return true;
        }
    }

    /**
     * Debug header logging that keeps the session out of logcat.
     *
     * <p>{@code HttpLoggingInterceptor.redactHeader} postdates this project's pinned
     * logging-interceptor, so redaction happens at the sink instead. A live bearer token in logcat
     * is replayable by anything that can read the log, and unlike the password it needs nothing
     * else to work.
     */
    private static class RedactingLogger implements HttpLoggingInterceptor.Logger {

        private static final String TAG = "EO1-http";

        @Override
        public void log(String message) {
            if (message == null) {
                return;
            }
            int colon = message.indexOf(':');
            if (colon > 0) {
                String name = message.substring(0, colon).trim().toLowerCase(java.util.Locale.US);
                if (name.equals("authorization") || name.equals("cookie")
                        || name.equals("set-cookie")) {
                    Log.d(TAG, name + ": [redacted]");
                    return;
                }
            }
            Log.d(TAG, message);
        }
    }

    private static class ProgressResponseBody extends ResponseBody {

        private final ResponseBody responseBody;
        private final ProgressListener progressListener;
        private BufferedSource bufferedSource;
        private final WeakReference<Request> request;

        ProgressResponseBody(ResponseBody responseBody, Request request, ProgressListener progressListener) {
            this.responseBody = responseBody;
            this.progressListener = progressListener;
            this.request = new WeakReference<>(request);
        }

        @Override public MediaType contentType() {
            return responseBody.contentType();
        }

        @Override public long contentLength() {
            return responseBody.contentLength();
        }

        @Override public BufferedSource source() {
            if (bufferedSource == null) {
                bufferedSource = Okio.buffer(source(responseBody.source()));
            }
            return bufferedSource;
        }

        private Source source(Source source) {
            return new ForwardingSource(source) {
                long totalBytesRead = 0L;

                @Override public long read(Buffer sink, long byteCount) throws IOException {
                    long bytesRead = super.read(sink, byteCount);
                    // read() returns the number of bytes read, or -1 if this source is exhausted.
                    totalBytesRead += bytesRead != -1 ? bytesRead : 0;
                    HttpUrl url = null;
                    Request r = request.get();
                    if (r != null) {
                        url = r.url();
                    }
                    progressListener.clientProgressUpdates(totalBytesRead, responseBody.contentLength(), bytesRead == -1, url);
                    return bytesRead;
                }
            };
        }
    }

    public interface ProgressListener {
        void clientProgressUpdates(long bytesRead, long contentLength, boolean done, @Nullable HttpUrl url);
    }
}
