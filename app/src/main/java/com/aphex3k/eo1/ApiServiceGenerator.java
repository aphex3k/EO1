package com.aphex3k.eo1;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

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
        return new Retrofit.Builder()
                .baseUrl(host)
                .addConverterFactory(GsonConverterFactory.create())
                .client(getNewHttpClient(context, progressListener))
                .build()
                .create(serviceClass);
    }

    protected static OkHttpClient getNewHttpClient(@Nullable Context context, ProgressListener progressListener) {

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();

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
                .cookieJar(new SessionCookieJar())
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

        return enableTls12OnPreLollipop(client).build();
    }

    // https://github.com/square/okhttp/issues/2372
    public static OkHttpClient.Builder enableTls12OnPreLollipop(OkHttpClient.Builder client) {
        try {
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, null, null);
            client.sslSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));

            ConnectionSpec cs = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build();

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
