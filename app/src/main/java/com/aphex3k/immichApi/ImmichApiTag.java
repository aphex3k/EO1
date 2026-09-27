package com.aphex3k.immichApi;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.google.gson.annotations.SerializedName;

@Keep
public class ImmichApiTag implements Comparable<ImmichApiTag> {

    @SerializedName("name")
    private String name;

    public ImmichApiTag(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    @Override
    public int compareTo(@NonNull ImmichApiTag immichApiTag) {
        return this.name.compareTo(immichApiTag.getName());
    }
}
