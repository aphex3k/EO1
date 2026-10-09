# TsPlayer playback — EO1 / EO2

Amlogic/Geniatech **TsPlayer** (`libTsPlayer-jni.so`) is the original Electric Objects video path. This APK prefers it on matching hardware and falls back to platform `MediaPlayer` via `TextureVideoView`.

## Scope

| In scope | Out of scope |
|----------|----------------|
| Immich **original** files via TsPlayer (no client re-encode) | Client video re-encode (removed 2026-09; EO CPU ~1 fps on libx264) |
| `TsVideoView` (`SurfaceView`) → `TsPlayerNative` → `libTsPlayer-jni.so` | ExoPlayer / Media3 |
| Automatic MediaPlayer fallback on load/play/surface failure | Non-Amlogic primary playback |
| Native `.so` loop — create the player once per asset, no Java teardown loop; plus a one-shot boundary reset (below) | App-level video filters / ppmgr FX |

There is no client-side transcoding or conversion — the downloaded Immich original is used as-is. Undecodable images (HEIC, corrupt) and incompatible videos fall back to the Immich preview / `/video/playback` via the display-error path.

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
download Immich original
  → no client re-encode — the downloaded original is played directly
  → displayVideo → TsVideoView (createPlayer → setSurface → start, once per asset)
  → loop: the .so loops the file natively (restarts at EOF; no Java intervention)
  → one-shot boundary reset: ~2s after the first pass ends, the player is recreated once
    on the same file to clear any wedged codec state (see "One-shot boundary reset")
  → deletePlayer + createPlayer only on asset handoff / stop() / surface destroy / boundary reset
  → on Ts error/surface-timeout: same original → MediaPlayer
  → on MediaPlayer failure: Immich /video/playback or thumbnail (not client re-encode)
```

Note: on this firmware `getStatus()` always returns `0` and `getCurrentTime()` always returns
`-1` — both are hardcoded stubs in the `.so` (verified by disassembly; `pause()`/`resume()` are
also no-op stubs returning false). **Java cannot observe playback state at all.** Do not use
status or position for any control logic; the only reliable recovery is a full teardown +
recreate (asset handoff, or the one-shot boundary reset below).

`SurfaceView` does **not** create a surface while `INVISIBLE`/`GONE`. The layout keeps `ImageView` above `TsVideoView` so the wallpaper covers video until `onVideoPrepared` hides the image.

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
- **Frame orientation** — the display's `Configuration.orientation` (verify on the EO2 that
  physically turning the frame actually flips it; only a sensor path is warranted if not).

Routing rule — force the MediaPlayer/`TextureVideoView` fallback when:

```
rotation ∈ {90, 180, 270}
   or (effective W > effective H and frame is portrait)   // landscape video, portrait frame
   or (effective H > effective W and frame is landscape)  // portrait video, landscape frame
```

where effective dimensions are the coded dimensions swapped for 90°/270° rotations.
Square or unknown aspect, and unknown rotation, keep the TsPlayer default. The decision is
logged and reported via `debugInformationProvided` plus the debug `/state` `video` block
(`rotation`, `width`, `height`, `frameOrientation`; `active` shows the effective player).

The fallback view performs rotation-aware **center-crop (aspect-fill)**:
`TextureVideoView.updateTextureViewSize` compensates the TextureView's buffer-stretch mapping
with per-axis factors (`cropScaleFactors`), so video fills the viewport, is cropped on the
long axis, and is neither stretched nor over-zoomed.

The fallback also applies the display rotation via a `postRotate` on the TextureView
transform. The angle is the probe's value, handed down per asset through
`VideoPlayerController.setDisplayRotation` → `TextureVideoView.setDisplayRotation` — the
platform `MediaMetadataRetriever` read inside `TextureVideoView` is unreliable on the EO
frames (returns 0 for files whose `tkhd` matrix is non-identity), so it is only consulted
when the probe yields "unknown" (-1). This keeps the displayed rotation identical to the
rotation that drove the routing decision.

## Looping (native)

**The `.so` loops a media file natively.** After reaching EOF, `libTsPlayer-jni.so`
restarts playback from the beginning on its own — no surface clear, no black frame, no
Java callback. We therefore create the player **once per asset**
(`createPlayer` → `setSurface` → `start`) and run **no** per-duration Java teardown loop.

Full teardown (`deletePlayer` + `createPlayer`) happens only:

- on asset handoff (new `setDataSource`),
- on `stop()`,
- on `surfaceDestroyed`,
- on the one-shot boundary reset (see below).

This is the OEM's own mechanism: the stock `EoVideoView` also creates the player once and
lets it loop, tearing down only when the cloud/UI swaps artwork (see
[Forensic findings](#forensic-findings-original-eo-apk)).

On-device confirmed (EO2, 2026-09-26): seamless looping, no wedge, no black flash between
passes — the black-flash/wedge behavior was caused by a *Java* per-duration full-recreate
loop, which has been removed.

Do **not** reintroduce a per-duration Java loop, `start()`-at-EOS, or create-without-delete
looping — see [rejected approaches](#looping-rejected-approaches-do-not-revive).

## One-shot boundary reset

The native loop can fail **silently**. The `.so`'s playback thread (spawned by
`start()`, disassembled at `0x4E070`) exits without any JNI notification when
`codec_write` or `codec_get_vbuf_state` return an error other than EAGAIN (e.g. `EBUSY` on
`/dev/amstream_vbuf`); since `getStatus()`/`getCurrentTime()` are stubs, Java has no way to
detect the dead thread — the screen stays black until the next rotation asset.

Backstop in `TsVideoView`: once a pass starts successfully, a **one-shot** timer fires at
`durationHintMs + 2000ms` (≈ the end of the first pass). If the same asset is still active,
the player is recreated on the same file — the exact teardown + recreate sequence used for
asset handoff, which is the documented recovery for wedged assets. The native loop then owns
the file again for the rest of the asset's display window.

- Fires **at most once per asset** (`/state`: `video.boundaryResets`,
  `loopPerf.restartMethod = "boundary-reset"`). It is not a per-duration loop: there is no
  recurring timer, so no per-pass black flash. `TsVideoView` tracks the path the timer was
  armed for (`boundaryArmedFor`), because the reset's own recreate re-enters
  `startOrRestartPlayer` and would otherwise re-arm the timer every pass — a per-duration
  recreate loop with a black flash on every pass (observed on device, fixed 2026-10-04).
  `setDataSource` / `stop()` clear the flag, so a new display of the asset (even the same
  file) gets a fresh one-shot.
- Cost in the healthy case: one ~200 ms plane-clear per video asset when it first cycles.
- If duration is unknown (no hint, retriever failed), no timer is scheduled and the old
  behavior (recovery at next asset) applies.

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

**May wedge** after ~12 gens (JNI success, frozen last frame). Do **not** use as a loop strategy. If an asset ever wedges under native looping, recover by moving to the next asset (asset handoff performs the full teardown) or falling back to MediaPlayer — never by adding a Java loop. (The one-shot boundary reset is the sanctioned in-asset recovery; it fires once per asset, never per pass.)

## Forensic findings (original EO APK)

Source: `EoVideoView` / `TsPlayerNative` in `.electric-objects/apk/_forensic_extract/classes.dexdump.txt` and `libTsPlayer-jni.strings.txt`.

### API shape

Same static natives we wrap today. `getCurrentTime` and `resume` exist on the JNI class but **are never invoked** anywhere in the EO dex — only `getStatus`, `pause`, `stop`, `deletePlayer`, `createPlayer`, `setSurface`, `start`.

Disassembly of this `.so` (2026-10-03) confirms the query APIs are **hardcoded stubs**: `getStatus()` is `mov r0,#0` (always IDLE) and `getCurrentTime()` is `mvn r0,#0` (always −1); `pause()`/`resume()` are no-op stubs returning false. The only real work happens in `createPlayer`, `setSurface`, `start` (spawns the playback pthread), `stop` (`pthread_join` + `Demux::Close`), and `deletePlayer`. No playback state is observable from Java.

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
| Native `.so` loop; full teardown only on handoff / stop / surface destroy / one-shot boundary reset | **Current design** |
| Rolling A/B players | Impossible — no instance handles; exclusive amstream |
| Surface GONE→VISIBLE between loops | Rejected — black flicker |
| Mid-loop delete + create (per-duration timer) | Rejected — black frame each pass |
| `start()`-alone at EOS | Rejected — EBUSY on amstream_vbuf, frozen |
| Rely on `getStatus` / `getCurrentTime` for EOS | **Unusable** — hardcoded stubs in this `.so` (0 and −1 respectively) |

## Looping status

Native looping is the shipped design and is confirmed on device (EO2, 2026-09-26): seamless
passes, no wedge, no black flash. Watch long-term stability on real rotation (many assets,
mixed durations). If an asset ever wedges under native looping, recover by moving to the next
asset (asset handoff performs the full `deletePlayer` + `createPlayer`) or falling back to
MediaPlayer — do **not** reintroduce a per-duration Java loop.

Long-running rotation did surface a real failure mode: the `.so`'s playback thread can die
silently on an Amcodec error, leaving a black screen for up to the whole rotation interval.
The one-shot boundary reset ([above](#one-shot-boundary-reset)) now bounds the worst case to
one extra ~200 ms recreate per video asset.

## Hard constraints (no client re-encode)

Client-side FFmpeg **re-encode is not used at all** (removed 2026-09). The EO CPU manages ~1 fps on libx264 — minutes per second of source. TsPlayer plays Immich originals directly; if playback fails, the asset falls back to Immich `/video/playback` (or the preview thumbnail for images).

## On-device validation checklist

On a physical EO1/EO2:

1. Logcat `EO1: video player: TsPlayer (USE_TSPLAYER=true native=true)`
2. Play a known-good local H.264 from Immich cache full-screen
3. Loop ≥2 minutes without surface loss. Expect a single brief (~200 ms) recreate ~2 s after
   the first pass ends — the one-shot boundary reset (`/state`: `boundaryResets=1`,
   `restartMethod=boundary-reset`). No other flicker: `boundaryResets` stays at 1 per asset
   and no further Java recreates happen (`loopGen=0`).
4. Image ↔ video handoff and screen off/on
5. HEVC (or other incompatible) plays via TsPlayer/MediaPlayer; on failure it falls back to Immich /video/playback (images: preview thumbnail)
6. Force Ts failure (corrupt file) → log `TsPlayer → MediaPlayer fallback` then thumbnail/next asset path

To force MediaPlayer only: set `buildConfigField "boolean", "USE_TSPLAYER", "false"` and rebuild.

## Packaging notes

- ABI: **armeabi-v7a** only (matches release `abiFilters`)
- ~9.7 MB LFS `.so` (same blob as EO2 `/system/lib/libTsPlayer-jni.so`)
- ProGuard keeps `com.example.tsplayer.TsPlayerNative`
- Depends on Amlogic system services (`libmedia`, `libsystemwriteservice`, `/dev/amstream*`) — will not work as primary on generic Android
