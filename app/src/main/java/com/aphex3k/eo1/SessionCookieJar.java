package com.aphex3k.eo1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

public class SessionCookieJar implements CookieJar {

    private List<Cookie> cookies;

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        if (url.encodedPath().endsWith("login")) {
            this.cookies = new ArrayList<>(cookies);
        }
    }


    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        if (!url.encodedPath().endsWith("login") && cookies != null) {
            return cookies;
        }
        return Collections.emptyList();
    }
}