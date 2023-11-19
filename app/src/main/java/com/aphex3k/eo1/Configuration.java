package com.aphex3k.eo1;

import androidx.annotation.Keep;

import javax.annotation.Nullable;

@Keep
public class Configuration {
    @Nullable
    public String host;
    @Nullable
    public String userid;
    @Nullable
    public String password;
    @Nullable
    public boolean autoBrightness;
    @Nullable
    public String selectedTimeZoneId;
    @Nullable
    public int startQuietHour;
    @Nullable
    public int endQuietHour;
    @Nullable
    public int interval;
    @Nullable
    public float brightnessLevel;
}
