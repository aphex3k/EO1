// Payload.java

package com.aphex3k.eo1.mqtt;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

/**
 * One coordinator state document.
 *
 * <p>The keys that decide anything — which callback fires, what the banner is about, what names the
 * room — carry an {@code alternate} spelling as well as the one read off a live frame. This is the
 * asymmetry that made {@link TTrack} a hand-written reader: Gson's field match is case-sensitive, so
 * a bridge that capitalises a key does not fail, it <em>says nothing</em>, and a frame with nothing to
 * say is indistinguishable from a house with no music. {@code transportState} is the sharpest case,
 * because a document whose state key this app cannot read reaches {@link MqttManager}'s dispatch and
 * then reaches <b>no callback at all</b> — the whole feature goes quiet with no error anywhere, and
 * not one line in {@code files/eo1-app.log} naming it.
 *
 * <p>The keys nothing in the app reads ({@code model}, {@code volume}, {@code mute}, {@code bass},
 * {@code treble}, {@code ts}, {@code playmode}) deliberately carry no annotation: an alternate
 * spelling for a field nobody consumes is a name claim nobody can check, and {@code Volume} and
 * {@code Mute} are already recorded in AGENTS.md as shapes this app has never verified.
 */
@Keep
public class Payload {
    // Read by NowPlaying.identityOf only as a last resort behind coordinatorUuid, but that fallback is
    // load-bearing on a single-player household, so its name is a wire fact like the rest.
    @SerializedName(value = "uuid", alternate = {"Uuid", "UUID"})
    public String uuid;
    public String model;
    @SerializedName(value = "name", alternate = {"Name"})
    public String name;
    @SerializedName(value = "groupName", alternate = {"GroupName"})
    public String groupName;
    // coordinatorUuid, as the coordinator writes it — this field used to be spelled
    // coordinatorUUID, and since Payload carried no @SerializedName at all, Gson's exact-name
    // match silently left it null. Key names here are wire facts, read off a live frame on
    // 2026-11-14; TTrack explains the nested documents, whose casing is not this consistent. The
    // alternates below are not that measurement, they are the tolerance for the drift it predicts.
    @SerializedName(value = "coordinatorUuid", alternate = {"CoordinatorUuid", "coordinatorUUID"})
    public String coordinatorUuid;
    public Volume volume;
    public Mute mute;
    public Long bass;
    public Long treble;
    public Long ts;
    @SerializedName(value = "currentTrack", alternate = {"CurrentTrack"})
    public TTrack currentTrack;
    @SerializedName(value = "enqueuedMetadata", alternate = {"EnqueuedMetadata"})
    public EnqueuedMetadata enqueuedMetadata;
    @SerializedName(value = "nextTrack", alternate = {"NextTrack"})
    public TTrack nextTrack;
    public String playmode;
    @SerializedName(value = "transportState", alternate = {"TransportState"})
    public String transportState;

    public Payload() {}

}

// @Keep above is not decoration. Most of the field names here ARE the wire names, so this class is
// reflection-dependent twice over: Gson instantiates it and matches its fields by name, and no Java
// statement tells R8 either fact. Without @Keep, R8 merged this class into an abstract, field-less
// shell and release frames dropped every state message; the whole story is in
// app/proguard-rules.pro. The @SerializedName annotations are what the gate in
// check-release-reflection.sh reads as "this Java name may be renamed harmlessly", which is true and
// is the narrower claim the unannotated fields cannot make.

// @Keep above is not decoration. The field names here ARE the wire names - none of
// them carries @SerializedName, because the coordinator's own keys are the same
// lowercase camel case - so this class is reflection-dependent twice over: Gson
// instantiates it and matches its fields by name, and no Java statement tells R8
// either fact. Without @Keep, R8 merged this class into an abstract, field-less
// shell and release frames dropped every state message; the whole story is in
// app/proguard-rules.pro.
