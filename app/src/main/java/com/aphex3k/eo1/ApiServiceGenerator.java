package com.aphex3k.eo1;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import okhttp3.Cache;
import okhttp3.ConnectionSpec;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.TlsVersion;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

@Keep
public class ApiServiceGenerator {

    public static <S> S createService(Class<S> serviceClass, String host, @Nullable Context context) {
        return new Retrofit.Builder()
                .baseUrl(host)
                .addConverterFactory(GsonConverterFactory.create())
                .client(getNewHttpClient(context))
                .build()
                .create(serviceClass);
    }

    protected static OkHttpClient getNewHttpClient(@Nullable Context context) {

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
}
