// Payload.java

package com.aphex3k.eo1.mqtt;

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

// No changes needed; mapping handled by custom TypeAdapterFactory
