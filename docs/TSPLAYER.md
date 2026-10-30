# TsPlayer playback — EO1 / EO2

Amlogic/Geniatech **TsPlayer** (`libTsPlayer-jni.so`) is the original Electric Objects video path. This APK prefers it on matching hardware and falls back to platform `MediaPlayer` via `TextureVideoView`.

## Scope

| In scope | Out of scope |
|----------|----------------|
| **Original** files via TsPlayer — fetched from Immich or uploaded to the frame itself (no client re-encode) | Client video re-encode (removed 2026-09; EO CPU ~1 fps on libx264) |
| `TsVideoView` (`SurfaceView`) → `TsPlayerNative` → `libTsPlayer-jni.so` | ExoPlayer / Media3 |
| Automatic MediaPlayer fallback on load/play/surface failure | Non-Amlogic primary playback |
| Native `.so` loop — create the player once per asset, no Java teardown loop | App-level video filters / ppmgr FX |

There is no client-side transcoding or conversion — the original is used as-is, whether it was downloaded from Immich or uploaded to the frame's own web page. An upload is not a different playback path: it is simply an original that is already on disk (`MediaAsset.localPath`), and every rung, watchdog and routing rule below applies to it unchanged. Nothing is filtered out by file name.

The ladder a display failure descends is per media type, not one shared sequence — the rungs are `DisplayStage` in `MainActivity.playbackFallback`:

- **Still:** original → the backend's degraded source (`/thumbnail?size=preview`) → verdict.
- **Video:** original → **the same original on the other engine** → the backend's degraded source (`/video/playback`) → verdict.

That middle rung is a second *engine*, not a second fetch: the bytes are already cached, and it exists only while `preferTsPlayer` is true (`BuildConfig.USE_TSPLAYER` and `TsPlayerNative.isAvailable()`). Where the native library never loaded there is only one engine, so the rung is skipped and the cycle goes straight to the degraded source — and a clip whose display rotation is non-identity or whose aspect is opposite the frame's never reached TsPlayer in the first place, because routing decides the engine before the first play (see [Scaling & orientation](#scaling--orientation-routing)); replaying it would just re-select the engine that failed.

A video with a coded side above 1920px skips straight to the last rung, because that decoder garbles such video without raising an error. So does one whose macroblock throughput, or whose sustained video-track bitrate (15 Mbit/s), exceeds a hardware ceiling — from its second appearance onward, since those verdicts need the `moov` and so the first appearance pays one original fetch before descending. So, too, does **1080p** video whose *displayed* aspect is opposite the panel's — also from its second appearance onward, because that verdict needs the `tkhd` matrix as well as the coded size. The angle is not the trigger; the displayed aspect is, and the matrix enters only as the transform that produces it (an unreadable one leaves the gate dark, and a clip the matrix has already turned to match the panel needs no crop beyond 1:1, so routing it would be a quality loss with nothing to show for it). Be precise about what descending buys, because the arithmetic here is easy to overstate: a center-crop's factors depend on the clip's *shape* and the viewport, so `cropScaleFactors(1080, 1920, 1920, 1080)` and `(1080, 1920, 1280, 720)` are both `(3.16, 1.0)` — the fallback is composited over the same region and only the decode and the source read get cheaper. If a routed clip still runs slow, the cost is the composite, and the lever is fill-versus-letterbox in `TextureVideoView`, not the source. All four gates are decided while choosing a *source*, so they apply to files fetched from a remote backend: an uploaded file is never routed at all and is displayed at whatever size, frame rate, bitrate or orientation it arrived, because there is no degraded source to route it *to* (`MediaManager.originalUsable` returns true the moment `localPath` is set).

What the original and the fallback both fail on is tagged `EO1_INCOMPATIBLE` — for the operator to fix in Immich: regenerate thumbnails, re-encode, or remove it from the frame's albums. That terminal verdict is **Immich-only**: the tag is written by `MediaBackend.markIncompatible`, which `LocalMediaBackend` does not implement, so the interface's default no-op runs — and the "no fallback for this backend" log line it follows is `Log.i`, which reaches logcat (≈13 minutes) but not `/logs` or `eo1-app.log`. An uploaded file that fails to display therefore leaves no durable trace and is retried on the next cycle. See AGENTS.md, "No client-side transcoding", for how a local failure is made durable (only at scan time, via the `<name>.incompat.txt` sidecar).

## Source map

| Path | Role |
|------|------|
| [`app/src/main/jniLibs/armeabi-v7a/libTsPlayer-jni.so`](../app/src/main/jniLibs/armeabi-v7a/libTsPlayer-jni.so) | Git LFS native player (from EO APK) |
| [`com.example.tsplayer.TsPlayerNative`](../app/src/main/java/com/example/tsplayer/TsPlayerNative.java) | JNI wrapper + `isAvailable()` |
| [`TsVideoView`](../app/src/main/java/com/aphex3k/eo1/TsVideoView.java) | SurfaceHolder lifecycle |
| [`VideoPlayerController`](../app/src/main/java/com/aphex3k/eo1/VideoPlayerController.java) | Shared API for Ts + MediaPlayer |
| [`MainActivity`](../app/src/main/java/com/aphex3k/eo1/MainActivity.java) | Prefer TsPlayer; fallback; watchdog |

## Feature flag

`BuildConfig.USE_TSPLAYER` (default `true` in [`app/build.gradle`](../app/build.gradle)).

Runtime still requires `TsPlayerNative.isAvailable()` (`System.loadLibrary("TsPlayer-jni")` success). Emulator / arm64 / missing system Amlogic libs → MediaPlayer only.

## Playback flow

```
obtain the original (download from Immich, or the uploaded file already in filesDir/uploaded)
  → no client re-encode — the original is played as it arrived
  → displayVideo → TsVideoView (createPlayer → setSurface → start, once per asset)
  → loop: the .so loops the file natively (restarts at EOF; no Java intervention)
  → deletePlayer + createPlayer only on asset handoff / stop() / surface destroy
  → on Ts error/surface-timeout: same original → MediaPlayer
  → on MediaPlayer failure: the backend's degraded source (not client re-encode) —
    Immich /video/playback, or the preview thumbnail; a local upload has neither
```

Note: on-device `getStatus()` often stays `IDLE (0)` while frames play (OEM only logged status, never branched on it). Do not use status for loop control.

`SurfaceView` does **not** create a surface while `INVISIBLE`/`GONE`. The layout keeps `ImageView` above `TsVideoView` so the wallpaper covers video until `onVideoPrepared` hides the image.

## Watchdogs

Two timers watch a video attempt, and they watch different failures. Both run on the UI handler, both
are cancelled on asset handoff, and a failure confirmed by either moves the asset **one** rung down
the ladder above — never two, never a dropped asset.

**Paint deadline — `MainActivity.VIDEO_PAINT_TIMEOUT_MS` (12 000 ms), polled every
`VIDEO_PAINT_POLL_MS` (500 ms).** Armed by `startVideoPaintWatchdog` *before* any callback fires, so an
attempt whose `prepareAsync()` never returns is still watched; the position watchdog cannot cover that
case, because it is armed *from* `onVideoPrepared`. It asks one question — has this engine composited a
frame yet? — and the answer depends on `VideoPlayerController.supportsPaintSignal()`. The MediaPlayer
path supplies a real signal (`TextureVideoView.onSurfaceTextureUpdated` stamps the last composited
frame). **TsPlayer supplies none** — its `.so` offers nothing per-frame and reports wall-clock
position — so on that engine the check is armed and at once satisfied rather than judging the clip, and
the photo is stepped aside at prepare. On the MediaPlayer path the photo is stepped aside only once a
frame is proven to exist (`revealVideoSurface`), never at prepare. No frame within 12 s means one rung
down.

While the panel is not ours to paint — quiet hours, `onPause`, the screen toggle — that poll **parks**
(`pauseVideoPaintPoll`) instead of cancelling or spending its deadline, and `onResume` / `turnScreenOn`
re-arm it with the parked time deducted from the budget (`resumeVideoPaintPollIfPending`). A
`TextureView` that is `INVISIBLE` composites nothing, so an un-parked clock would sentence a healthy
clip for the life of the process on the strength of the app's own visibility bookkeeping.

**Position watchdog — `MainActivity.VIDEO_WATCHDOG_POLL_MS` (2000 ms) with
`VIDEO_STALL_THRESHOLD_MS` (8000 ms) without headway.** Watches the timeline rather than the pixels.
Each poll reads `getCurrentPosition()`; any movement resets the stall clock, so 8 s of a position frozen
at one value buys one recovery `seekTo` (to 0 when the read sits within a second of the reported end,
otherwise to where it stalled) and a fresh threshold. A second 8 s of nothing hands the same file to
MediaPlayer if TsPlayer was serving it, and otherwise descends one rung. It also owns the loop seam: last frames
past the duration the player reports are detected here (`parked`/`creeping`), because nothing raises
`onCompletion`. It cannot see a decoder that advances and paints nothing — that is the whole reason the
paint deadline exists. Unlike the paint poll it **cancels** rather than parks when the screen is off or
the video view is not visible, and is re-armed by the next display attempt.

## Scaling & orientation (routing)

TsPlayer's `.so` renders with a **fixed fit/letterbox scale** and its JNI surface exposes
only `createPlayer/setSurface/start/stop/pause/resume/deletePlayer/getStatus/getCurrentTime`
— no scale-mode or rotation control is reachable from Java. Two defects follow:

- **Rotated video plays upside-down/wrong-way** (the `.so` ignores display-matrix metadata).
- **Landscape clips do not fill a portrait viewport** — they are squished/letterboxed instead
  of aspect-filling and cropping the sides.

So `MainActivity.displayVideo` routes each video before choosing a player. Inputs:

- **Video rotation** — a bounded pure-Java ISOBMFF header probe (`VideoHeaderProbe`, no
  `MediaMetadataRetriever`, which would EBUSY-starve a live TsPlayer on `/dev/amstream_vbuf`):
  the `tkhd` 16.16 display matrix (v0/v1), overridden by an ISO 23001-8 `rot ` box when
  present; coded width/height from the first `stsd` video sample entry.
- **Video coded dimensions** — probe first; the `MediaAsset` exif dimensions when the probe
  yields 0.
- **Frame orientation** — the display's `Configuration.orientation`, read once in
  `MainActivity.frameIsPortrait()` and handed to `MediaManager.setFramePortrait` at launch and from
  `onConfigurationChanged` (verify on the EO2 that physically turning the frame actually flips it;
  only a sensor path is warranted if not). This used to be only an engine question; since the 1080p
  orientation gate it also decides whether an original is fetched at all, so a value that does not
  follow the wall mount now retires half a correctly-encoded library instead of picking the wrong
  player. Read it back from `/state`'s `video.frameOrientation`.
- **Frame rate** — also from the probe, via `mdhd` (timescale and duration) and the `stsz` sample
  count. This one is *not* an input to the player choice above; nothing about TsPlayer vs
  MediaPlayer depends on it. It selects the **source** instead: a clip whose coded size x frame rate
  exceeds `MediaCompatibility.MAX_VIDEO_MACROBLOCKS_PER_SECOND` decodes too slowly to watch but
  raises no error, so `MediaManager` starts it at the backend's `/video/playback` fallback. See
  AGENTS.md, "No client-side transcoding", for why that verdict is taken on the bytes rather than
  from catalog metadata.

Routing rule — force the MediaPlayer/`TextureVideoView` fallback when:

```
rotation ∈ {90, 180, 270}
   or (effective W > effective H and frame is portrait)   // landscape video, portrait frame
   or (effective H > effective W and frame is landscape)  // portrait video, landscape frame
```

where effective dimensions are the coded dimensions swapped for 90°/270° rotations.
Square or unknown aspect, and unknown rotation, keep the TsPlayer default. The two aspect lines are
`MediaCompatibility.videoAspectOppositeFrame`, which the 1080p source gate calls too — deliberately
one function, so the two paths cannot drift into two definitions of "opposite". They still differ in
*coverage*, and that gap is real: routing falls back to the `MediaAsset`'s catalog dimensions when the
file's header gives none, while the gate refuses to decide on anything but probed bytes — so a container
the probe cannot read (an `.mkv`, whose angle lives in a Matroska element) is MediaPlayer material by the
engine's reading and simply unjudgeable by the fetcher's. The rotation line stays local to routing: it
asks whether a player can turn the picture, which is not the question a source decision asks. The decision is
logged and reported via `debugInformationProvided` plus the debug `/state` `video` block
(`rotation`, `width`, `height`, `frameOrientation`; `active` shows the effective player).

The fallback view performs **center-crop (aspect-fill)**: `TextureVideoView.updateTextureViewSize`
compensates the TextureView's buffer-stretch mapping with per-axis factors (`cropScaleFactors`), so
video fills the viewport, is cropped on the long axis, and is neither stretched nor over-zoomed.

Rotation is deliberately **not** an input to that arithmetic. The platform MediaPlayer on these
frames applies the display rotation while decoding, so `onVideoSizeChanged` reports the buffer
*already turned*: a 1920x1080 clip with a −90° matrix arrives as `video=1080x1920` into a
`view=1080x1920` view, and the transform it wants is the identity. `cropScaleFactors` used to swap
the axes a second time for 90°/270° content, which made that case a uniform `1.7778` magnification —
a native-resolution portrait clip composited at 3.2× the pixels, sampled from a region about
1920x3413 wide. The old comment claimed the un-swapped denominators rendered a rotated clip "~3:1
squished"; that premise was the coded dimensions, which is not what this view is handed. On screen
the over-zoom looked like video playing below realtime rather than like a geometry bug, and it cost
an investigation two wrong hypotheses before `TextureVideoView`'s own debug line
(`view=… video=… rotation=… scale=(…)`) was read. It is the reason the same file plays at 1.00×
interior and still cannot hold framerate.

For the same reason the fallback applies **no** `postRotate` of its own — the decoder's rotation
would make it double up. The probe's **clockwise** value therefore drives the routing decision and
this view's debug log line, and nothing else. It is handed down per asset through
`VideoPlayerController.setDisplayRotation` → `TextureVideoView.setDisplayRotation` — the platform
`MediaMetadataRetriever` read inside `TextureVideoView` is unreliable on the EO frames (returns 0 for
files whose `tkhd` matrix is non-identity), so it is only consulted when the probe yields "unknown"
(-1).

## Looping (native)

**The `.so` loops a media file natively.** After reaching EOF, `libTsPlayer-jni.so`
restarts playback from the beginning on its own — no surface clear, no black frame, no
Java callback. We therefore create the player **once per asset**
(`createPlayer` → `setSurface` → `start`) and run **no** per-duration Java teardown loop.

Full teardown (`deletePlayer` + `createPlayer`) happens only:

- on asset handoff (new `setDataSource`),
- on `stop()`,
- on `surfaceDestroyed`.

This is the OEM's own mechanism: the stock `EoVideoView` also creates the player once and
lets it loop, tearing down only when the cloud/UI swaps artwork (see
[Forensic findings](#forensic-findings-original-eo-apk)).

On-device confirmed (EO2, 2026-09-26): seamless looping, no wedge, no black flash between
passes — the black-flash/wedge behavior was caused by a *Java* per-duration full-recreate
loop, which has been removed.

Do **not** reintroduce a per-duration Java loop, `start()`-at-EOS, or create-without-delete
looping — see [rejected approaches](#looping-rejected-approaches-do-not-revive).

## Looping: rejected approaches (do not revive)

The native loop is the design. Everything below was tried to make *Java* loop the same file
and failed on EO1/EO2 — do not revive them.

### Per-duration Java full-recreate loop (`deletePlayer` + `createPlayer` on a timer)

**Rejected — this was the original bug.** Firing a `deletePlayer` + `createPlayer` every
`duration` (the per-duration teardown loop) cleared the Amlogic video plane on each
`createPlayer` (~200 ms) → a visible **black flash on every pass**. Removed; replaced by
native looping. `deletePlayer` + `createPlayer` is correct only on asset handoff / `stop` /
surface destroy, never on a per-duration timer.

### `start()`-alone at end-of-stream

**Rejected** (verified on device 2026-09-26). Calling `start()` on a player parked at EOF
re-inits the codec (`codec_video_es_init`), which returns `EBUSY` on `/dev/amstream_vbuf`
(still held by the live player) and leaves the video frozen, spamming `amcodec` every loop.

### Surface GONE→VISIBLE / setFormat “hard recreate”

**Rejected.** Destroying `SurfaceHolder` between generations blanks the video plane (black flicker). Agents must **not** suggest or reintroduce periodic/fallback surface visibility pulses or `setFormat` cycles to unstick the decoder for looping.

`GONE`/`VISIBLE` is only valid for normal show/hide of the video view (asset handoff, stop), never as a loop strategy.

### Rolling createPlayer window (A→B→delete A→C→delete B…)

**Not viable with this JNI.** `TsPlayerNative` exposes process-global statics only:

- `boolean createPlayer(String path)` — no instance handle returned
- `void deletePlayer()` — no argument; deletes the one global player
- `void setSurface(Surface)` — binds that single player

Native strings also imply exclusive Amlogic resources (`/dev/amstream_*`, “codec is busy”, “Existing an audio dec instance!”). A second live player cannot be held and cross-faded.

### Repeated `createPlayer` without `deletePlayer` (many generations)

**May wedge** after ~12 gens (JNI success, frozen last frame). Do **not** use as a loop strategy. If an asset ever wedges under native looping, recover by moving to the next asset (asset handoff performs the full teardown) or falling back to MediaPlayer — never by adding a Java loop.

## Forensic findings (original EO APK)

Source: `EoVideoView` / `TsPlayerNative` in `.electric-objects/apk/_forensic_extract/classes.dexdump.txt` and `libTsPlayer-jni.strings.txt`.

### API shape

Same static natives we wrap today. `getCurrentTime` and `resume` exist on the JNI class but **are never invoked** anywhere in the EO dex — only `getStatus`, `pause`, `stop`, `deletePlayer`, `createPlayer`, `setSurface`, `start`.

### OEM does not loop inside the view

`EoVideoView` has **no** duration timer and **no** end-of-stream poll. The only recreate path is `restartPlayer(artwork)`, called from:

- `setVideoArtwork(...)` (new/changed artwork)
- `setupView` if `mArtwork` already set

So between artwork changes the player is created **once** and left to loop the file
**natively** in the `.so` — exactly the mechanism this APK now uses. The stock app only tore
the player down when the cloud/UI swapped artwork, never on a per-clip timer.

### OEM same-surface restart sequence (artwork change — not tight loop)

`restartPlayer` posts to the main looper a runnable that logs *“Stopping player and invalidating surface…”* but **does not** destroy `SurfaceHolder`. Actual order:

1. `isPlaying` CAS true→false (bail if already stopped)
2. `pause()`
3. `stop()` then `stop()` again (logged twice)
4. `deletePlayer()`
5. `createPlayer(path)`
6. `setSurface(getHolder().getSurface())` — **same** surface
7. `start()`
8. `isPlaying = true`

That full delete path is appropriate for **asset change**. Running it on a per-duration
timer is what introduced the black frames on EO1/EO2.

`surfaceCreated` only logs. `surfaceChanged` starts the player only if `isPlaying` CAS false→true. `surfaceDestroyed` does `stop` + `deletePlayer` if it was playing.

### Implications for EO1

| Idea | Verdict |
|------|---------|
| Native `.so` loop; full teardown only on asset handoff | **Current design** |
| Rolling A/B players | Impossible — no instance handles; exclusive amstream |
| Surface GONE→VISIBLE between loops | Rejected — black flicker |
| Mid-loop delete + create (per-duration timer) | Rejected — black frame each pass |
| `start()`-alone at EOS | Rejected — EBUSY on amstream_vbuf, frozen |
| Rely on `getStatus` / `getCurrentTime` for EOS | Unreliable / unused by OEM |

## Looping status

Native looping is the shipped design and is confirmed on device (EO2, 2026-09-26): seamless
passes, no wedge, no black flash. Watch long-term stability on real rotation (many assets,
mixed durations). If an asset ever wedges under native looping, recover by moving to the next
asset (asset handoff performs the full `deletePlayer` + `createPlayer`) or falling back to
MediaPlayer — do **not** reintroduce a per-duration Java loop.

## Hard constraints (no client re-encode)

Client-side FFmpeg **re-encode is not used at all** (removed 2026-09). The EO CPU manages ~1 fps on libx264 — minutes per second of source. TsPlayer plays originals directly, Immich or uploaded; if playback fails, the asset falls back to the backend's degraded source — Immich `/video/playback`, or the preview thumbnail for images.

## On-device validation checklist

On a physical EO2, and on an EO1 only if [Getting TsPlayer onto an EO1](#getting-tsplayer-onto-an-eo1)
has landed — an EO1 on the stock ROM runs every video on the platform MediaPlayer and should:

1. Logcat `EO1: video player: TsPlayer (USE_TSPLAYER=true native=true)`. On an EO1 expect
   `native=failed`. Two lines name the engine and they answer two different questions, so read the one
   that matches the question: the durable `video engine:` line, once per launch, is the frame's
   **default** — the engine it will use for a clip nothing routes away, which is the only thing a
   "does this frame have TsPlayer" check can mean. The per-clip `displayVideo routing: … player=` line
   is the engine that will actually composite *that* file, and on a healthy EO2 a rotated or
   wrong-aspect clip correctly says `MediaPlayer` there while the launch line says `TsPlayer`. Neither
   line is more right than the other; a frame that says one thing at launch and the other per clip is
   working, not contradicting itself.
2. Play a known-good local H.264 from Immich cache full-screen
3. Loop ≥2 minutes without surface loss **and without black flicker between passes** — `/state` shows `loopGen=0` and `lastRestartMethod=native-loop` (no Java recreate)
4. Image ↔ video handoff and screen off/on
5. HEVC (or other incompatible) plays via TsPlayer/MediaPlayer; on failure it falls back to Immich /video/playback (images: preview thumbnail)
6. Force Ts failure (corrupt file) → log `TsPlayer → MediaPlayer fallback` then thumbnail/next asset path

Steps 7–9 are the parts of this branch that no JVM test can reach, because they are the wiring around a
live Activity rather than the helpers:

7. **The probe, on both frames.** `/state` → `video.systemWrite` must report `registrant: present` and a
   `clientPath` on the EO2. On the EO1 either value is a finding in its own right — that field is what
   Phase B of the table below waits on. `pending` after a minute means the reflective lookup did not
   settle; `unreadable` means it answered a question about the framework, not about the service.
8. **A clip the frame cannot draw** (render-silent, not corrupt). Play something decodes-but-does-not-
   paint and confirm: `/state` shows `renderSilent: true`, the still preview appears on the *next*
   rotation, `eo1-app.log` says `noteRenderSilent`, and the Immich asset carries **no**
   `EO1_INCOMPATIBLE` tag. Then confirm the opposite for a genuinely broken file — that one must still
   be tagged. A build that tags the silent clip has regressed the one verdict this app must never
   write to the user's library.
9. **The no-transcode dead end on the single-engine frame.** On the EO1, against an Immich with no
   transcodes, a video that fails to display must log `has no degraded source distinct from its
   original`, must not download a second copy (watch the cache: one `<id>_fb.*`, never two names for
   the same bytes), and must still reach its terminal outcome — tag if it was refused, show the still if
   it merely painted nothing. Repeat after a restart, since both verdicts are per-process.

To force MediaPlayer only: set `buildConfigField "boolean", "USE_TSPLAYER", "false"` and rebuild.

## Packaging notes

- ABI: **armeabi-v7a** only (matches release `abiFilters`)
- ~9.7 MB LFS `.so` (same blob as EO2 `/system/lib/libTsPlayer-jni.so`)
- ProGuard keeps `com.example.tsplayer.TsPlayerNative`
- Depends on Amlogic system services (`libmedia`, `libsystemwriteservice`, `/dev/amstream*`) — will not work as primary on generic Android
- **`libsystemwriteservice.so` is a `DT_NEEDED` of the `.so`, and it is not in an EO1's ROM.** Measured:
  exactly one undefined symbol in `libTsPlayer-jni.so` resolves only to it
  (`android::ISystemWriteService::asInterface(const sp<IBinder>&)`), so on an EO1 the whole player is
  lost to one linker miss — `dlopen` refuses and `TsPlayerNative` correctly reports unavailable.
- What is measured and what is not: the linker proves a soname is off the path. It does **not** say
  whether this ROM registers the `system_write` service, and that is the fact that decides whether
  supplying the soname fixes the frame or freezes it. `/state`'s `video.systemWrite` block answers it
  (`SystemWriteServiceProbe`, read through the framework, never by loading the library that hangs), and
  on the EO2 — where the client loads fine — the service is registered but deaf: `readSysfs could not
  contact remote` about ten times a second, which is *why* the app keeps a durable on-disk log
  ([WEBSERVER.md](WEBSERVER.md#logs--past-state)).

## Getting TsPlayer onto an EO1

TsPlayer is the better player on this hardware whenever the aspect ratio matches, and an EO1 does not
get it: one linker miss costs the whole engine. The fix is not obviously "ship the missing file", and
the reason is worth having written down before someone retries it.

`getSystemWriteService()` inside the vendor `.so` (ARM, `0x787008`–`0x787118`) does this:

```
pthread_mutex_lock
  loop: defaultServiceManager()->getService("system_write")   @plt, vtable slot 16
        if (result != null) break
        __android_log_print(5, ..., "SystemWriteService not published, waiting...")
        usleep(500000)                                        @ 0x7a120 us
        goto loop
pthread_mutex_unlock
```

No retry counter, no timeout, and the mutex is a process-global held across the sleep. The call is
reached from `TsVideoView.startOrRestartPlayer` → `TsPlayerNative.createPlayer`
(`TsVideoView.java:330`) on a handler bound to the **main looper** (`TsVideoView.java:42`).

So the outcome of supplying `libsystemwriteservice.so` to a frame depends entirely on one fact the
linker error cannot tell you — and getting it wrong turns a frame that plays video into a frame with a
dead UI that needs a power cable.

| `video.systemWrite.registrant` | ROM client | What to do |
|---|---|---|
| `present` | absent | Ship the client. Put it in `app/src/main/jniLibs/armeabi-v7a/` as a **regular git object** (`.gitattributes`: `-filter -diff -merge -text`, following the `gradle-wrapper.jar` exemption — a build-required file must not depend on a fork's ability to serve LFS). Then, **in every build that bundles it**, resolve the client before touching TsPlayer: `System.load` the ROM path when the ROM has one, and the bundled path only when the ROM is confirmed not to — and `System.loadLibrary("TsPlayer-jni")` after that. `BIND_NOW` means the soname must be resolvable at load, and a library already loaded by absolute path satisfies the later `DT_NEEDED` lookup by basename, so whichever one is loaded first is the one TsPlayer talks through. Skipping the load when a ROM client exists does **not** achieve that on its own: the `DT_NEEDED` lookup still searches the bundled copy's directory, so the *choice* has to be made by loading, not by declining to. |
| `present` | present | Nothing to ship: the linker would have found it. A miss with the file on disk is a search-path problem, so preload the path `video.systemWrite.clientPath` names. No blob in the repo. |
| `absent` | absent | **Do not ship the client.** This is the row where loading it hangs the frame. See the bounded-wait option below. |
| `unreadable` / `pending` | — | The probe did not answer. Re-measure over a cable; do not decide from this row. |

**Why the existence check is runtime, not build-time — and why it must load rather than merely skip.**
A client shipped inside the APK can answer the `DT_NEEDED` lookup before the ROM's does, which would
replace the framework-matched client **on the EO2 — the one frame where TsPlayer paints today** with
ours. Whether it does depends on the linker's search order for a library loaded by a library, which
varies across AOSP lines and **this repository has not measured it** — an earlier draft of this table
asserted that the app's `nativeLibraryDir` precedes `/system/lib` on API 19 as though it were a fact,
and that is exactly the kind of claim that has already cost this branch a correction once. The recipe
must not depend on the answer: load the ROM client by absolute path when it exists, so the soname is
already resolved by name before TsPlayer is dlopened, and load the bundled one only when the ROM has
nothing. Whichever precedence the linker implements, the first loaded wins by lookup order, and the
frame says which one it chose.

If the two clients differ in transaction codes and ours wins anyway, EO2's sysfs pokes start returning
defaults to a player that keeps drawing: a silent regression on the working frame, invisible to every
signal the app has, and not the kind of thing the 10-minute logcat window would show. A SHA-256 of the
in-tree dump against the APK proves nothing about EO2's live ROM copy. So the guard is a check the
running frame makes, and the check is expressed as a load.

**And the gate has to be in the build, not in the operator's judgment.** EO1 has no wireless adb and its
ROM's adbd does not listen on TCP 5555, so a wrong guess there is a wall-mount trip with a keyboard, and
rollback is a browser sideload of the previous APK.

**If the answer is `absent`** — which a ROM that dropped the client library very probably is — the
remaining route is to bound the wait in the vendor blob: change the loop-back branch at `0x787118`
(`0affffd6  beq 787078`) so a null registrant gives up after a bounded number of tries and returns null
rather than looping forever holding the mutex. Plausible, because the EO2 shows playback is not blocked
on that service answering: its client loads, its service is deaf, and video still paints. But it means
shipping a **modified** 9.7 MB vendor binary that no longer matches `/system/lib/libTsPlayer-jni.so` on
either frame, one wrong byte from broken video everywhere, and it is only testable on hardware. Treat it
as a deliberate escalation: patch the `armeabi-v7a` blob only, record the exact offset and instruction
here, keep the pristine blob recoverable, and gate the release on both `.so` files being present in the
APK's `lib/armeabi-v7a/`.

**What is not true about any of this**, and should not be repeated: that the `.so` needs a *working*
service to decode (it does not — the EO2's does not work), that a self-written stub could replace it (the
wait is upstream of the one symbol a stub could supply, and `defaultServiceManager` lives in the system's
`libbinder`), or that the current absence of TsPlayer on an EO1 is a bug in this app. It is a ROM that
shipped a player without shipping one of its dependencies.
