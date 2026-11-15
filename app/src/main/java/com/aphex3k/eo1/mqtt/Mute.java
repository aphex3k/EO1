package com.aphex3k.eo1.mqtt;

import androidx.annotation.Keep;

@Keep
public class Mute {
    public boolean value;

    public Mute() {}
}

// `value` is the coordinator's own key; see Payload for why the keep rule in
// app/proguard-rules.pro is what makes this type exist at all in a release build.
