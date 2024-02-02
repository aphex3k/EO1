package com.aphex3k.eo1;

import androidx.annotation.Keep;

import javax.annotation.Nullable;

@Keep
public class Configuration {
    @Nullable
    public String host = "";
    @Nullable
    public String userid = "";
    @Nullable
    public String password = "";
    @Nullable
    public String selectedTimeZoneId = "";
    public int startQuietHour = -1;
    public int endQuietHour = -1;
    public int interval = 5;
}
