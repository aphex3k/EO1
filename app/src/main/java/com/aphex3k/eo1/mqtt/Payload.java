// Payload.java

package com.aphex3k.eo1.mqtt;

import androidx.annotation.Keep;

@Keep
public class Payload {
    public String uuid;
    public String model;
    public String name;
    public String groupName;
    public String coordinatorUUID;
    public Volume volume;
    public Mute mute;
    public Long bass;
    public Long treble;
    public Long ts;
    public TTrack currentTrack;
    public EnqueuedMetadata enqueuedMetadata;
    public TTrack nextTrack;
    public String playmode;
    public String transportState;

    public Payload() {}

}

// @Keep above is not decoration. The field names here ARE the wire names - none of
// them carries @SerializedName, because the coordinator's own keys are the same
// lowercase camel case - so this class is reflection-dependent twice over: Gson
// instantiates it and matches its fields by name, and no Java statement tells R8
// either fact. Without @Keep, R8 merged this class into an abstract, field-less
// shell and release frames dropped every state message; the whole story is in
// app/proguard-rules.pro.
