package com.aphex3k.eo1;

import androidx.annotation.Keep;

@Keep
public class DebugInformation {
    private final String key;
    private final String value;

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    DebugInformation(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
