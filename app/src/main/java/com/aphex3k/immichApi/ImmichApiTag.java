package com.aphex3k.immichApi;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.google.gson.annotations.SerializedName;

@Keep
public class ImmichApiTag implements Comparable<ImmichApiTag> {

    @SerializedName("name")
    private String name;

    @SerializedName("type")
    private ImmichTagType type;

    public ImmichApiTag(String name, ImmichTagType type) {
        this.name = name;
        this.type = type;
    }

    public String getName() { return name; }

    public ImmichTagType getType() { return type; }

    @Override
    public int compareTo(@NonNull ImmichApiTag immichApiTag) {
        return this.name.compareTo(immichApiTag.getName()) + this.type.compareTo(immichApiTag.getType());
    }
}
