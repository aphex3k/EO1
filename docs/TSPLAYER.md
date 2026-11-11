# TsPlayer playback — EO1 / EO2

Amlogic/Geniatech **TsPlayer** (`libTsPlayer-jni.so`) is the original Electric Objects video path. This APK prefers it **on the EO2 only** — the EO1's silicon is different and has no vendor player at all, see [TsPlayer on the EO1: closed](#tsplayer-on-the-eo1-closed) — and falls back to platform `MediaPlayer` via `TextureVideoView`.

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

That middle rung is a second *engine*, not a second fetch: the bytes are already cached, and it exists only when three things are true — the frame owns two engines (`preferTsPlayer`, i.e. `BuildConfig.USE_TSPLAYER` and `TsPlayerNative.isAvailable()`), **routing actually picked TsPlayer for this clip**, and that engine has fewer than `MAX_ENGINE_ATTEMPTS` attempts on these bytes. Where the native library never loaded there is only one engine, so the rung is skipped and the cycle goes straight to the degraded source.

The middle condition is the one that was missing, and it was not a theoretical gap: `displayVideo` re-derives the engine from the file's own header on every entry, so a clip that routing had already handed to MediaPlayer was replayed by the rung *onto MediaPlayer* — an entire wasted attempt (watchdog arming, prepare, 12 s paint deadline) before the ladder moved on. The rung tested whether the *frame* had a second engine rather than whether this *clip* had one, and once every non-native aspect is MediaPlayer material that is the common case rather than the exception. Note this also retires an older oddity: a clip that failed on TsPlayer, was swapped to MediaPlayer, and failed there used to be sent back through TsPlayer by this rung regardless.

A video with a coded side above 1920px skips straight to the last rung, because that decoder garbles such video without raising an error. So does one whose macroblock throughput, or whose sustained video-track bitrate (15 Mbit/s), exceeds a hardware ceiling — from its second appearance onward, since those verdicts need the `moov` and so the first appearance pays one original fetch before descending. So, too, does video whose *displayed* aspect is not the panel's own shape — also from its second appearance onward, because that verdict needs the `tkhd` matrix as well as the coded size. The angle is not the trigger; the displayed aspect is, and the matrix enters only as the transform that produces it (an unreadable one leaves the gate dark, and a clip the matrix has already turned to match the panel needs no crop beyond 1:1, so routing it would be a quality loss with nothing to show for it). This gate used to be limited to 1080p, and it still is on a frame with one engine — see the scoping note below, because the two halves of that sentence have different reasons. Be precise about what descending buys, because the arithmetic here is easy to overstate: a center-crop's factors depend on the clip's *shape* and the viewport, so `cropScaleFactors(1080, 1920, 1920, 1080)` and `(1080, 1920, 1280, 720)` are both `(3.16, 1.0)` — the fallback is composited over the same region and only the decode and the source read get cheaper. If a routed clip still runs slow, the cost is the composite, and the lever is fill-versus-letterbox in `TextureVideoView`, not the source. All four gates are decided while choosing a *source*, so they apply to files fetched from a remote backend: an uploaded file is never **source**-routed and is displayed at whatever size, frame rate, bitrate or orientation it arrived, because there is no degraded source to route it *to* (`MediaManager.originalUsable` returns true the moment `localPath` is set). Say it that way rather than "never routed", because it is not true of the engine: an uploaded square clip on an EO2 is very much routed, and now goes to MediaPlayer rather than being letterboxed by TsPlayer. One guard makes the widened source gate sound at the small end: the `/video/playback` probe answers with its own size, and a "degraded" source that is **not smaller** than the original is refused, so a 480p clip is never sent away from its file in order to download a bigger 720p one. Equality alone used to be the test, which was right while the gate only fired at 1080p and would have been silently negative just below the server's transcode ladder.

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

Runtime still requires the **resolved** gate, `VideoSilicon.allowsTsPlayer(silicon,
capabilities.supportsTsPlayer())`, **and** `TsPlayerNative.isAvailable()` — not the model flag on its
own. The two axes are asymmetric on purpose: confirmed Amlogic silicon permits the engine even when the
model profile says no, and nothing but a denial from both can forbid it. Requiring both flags here would
erase the override this file documents.

MediaPlayer only: an EO1 (model denies, no Amlogic marker), an emulator, arm64, a build with
`USE_TSPLAYER=false`, or a board missing the system Amlogic libraries. What decides, on any given frame,
is printed as `video.tsGate.decidedBy` in `/state` rather than left to be inferred.

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
MediaPlayer if TsPlayer was serving it, and otherwise descends one rung. It also owns the loop seam: a
lap's end is detected here against both the duration the player reports and the file's own length, under
three reasons — `parked`, `creeping`, and `rewound` (at or past the player's duration but below the
previous reading) — because nothing raises
`onCompletion`. It cannot see a decoder that advances and paints nothing — that is the whole reason the
paint deadline exists. Unlike the paint poll it **cancels** rather than parks when the screen is off or
the video view is not visible, and is re-armed by the next display attempt.

## Scaling & orientation (routing)

TsPlayer's `.so` renders with a **fixed scale of its own choosing** and its JNI surface exposes
only `createPlayer/setSurface/start/stop/pause/resume/deletePlayer/getStatus/getCurrentTime`
— no scale-mode or rotation control is reachable from Java. Two defects follow:

- **Rotated video plays upside-down/wrong-way** (the `.so` ignores display-matrix metadata).
- **A clip whose shape is not the panel's is not filled** — it is letterboxed or squished rather
  than aspect-filled and cropped on the long axis.

Be precise about the second one, because the wording here was wrong for a long time and it misleads
anyone reasoning about the fix. Two things were established from the shipped binary rather than from
prose. The `.so` links no `libandroid` and imports no `ANativeWindow` symbol, so the `Surface` handed
to `setSurface` is not a drawing target at all — pixels go to `/dev/amstream_*` and the Amlogic global
video layer composites behind the framebuffer, with the app's `SurfaceView` a hole in front of it. And
its entire geometry toolbox (`amvideo_utils_set_virtual_position`, `amvideo_setscreenmode`,
`amdisplay_utils_set_scale_mode`, the `amvecm` screen-mode `ioctl`) is **unreachable**: a whole-`.text`
scan finds the one function that would apply a per-clip rectangle has zero call sites. So TsPlayer
makes no per-clip aspect decision — it neither stretches nor letterboxes *by choice*. What draws is the
ROM's own video-layer rectangle, written once at boot by `set_display_mode.sh`, and whether that
stretches or bars a non-native stream has never been observed on a frame.

That is a statement about severity, not about the rule. The rule holds either way: an engine with no
aspect control can only be correct when there is nothing to correct, which is when the clip's shape
already equals the panel's. Everything else goes to the platform player, which center-crops to fill.

So `MainActivity.displayVideo` routes each video before choosing a player, through
`VideoRoutingRule.allowsTsPlayer` — a pure function, separate from `MainActivity` precisely so it could
be tested: routing itself never had a test, and a change that moved most of a library from one engine to
the other left the whole suite green. Inputs:

- **Video rotation** — a bounded pure-Java ISOBMFF header probe (`VideoHeaderProbe`, no
  `MediaMetadataRetriever`, which would EBUSY-starve a live TsPlayer on `/dev/amstream_vbuf`):
  the `tkhd` 16.16 display matrix (v0/v1), overridden by an ISO 23001-8 `rot ` box when
  present; coded width/height from the first `stsd` video sample entry.
- **Video coded dimensions** — probe first; the `MediaAsset` exif dimensions when the probe
  yields 0.
- **Panel shape** — the root view's live `getWidth()/getHeight()`, read once in
  `MainActivity.currentPanelShape()` and handed to `MediaManager.publishPanel` at launch, from
  `onConfigurationChanged`, from `onWindowFocusChanged`, and again before every display attempt.
  This replaced a `Configuration.orientation` boolean, and the reason matters: an orientation flag
  cannot distinguish a 16:9 panel from a 16:10 one, which is the whole question the rule now asks.
  Read the root and not a video view because a `GONE` view is never measured, and `onCreate` hides
  whichever view is idle — so a video view would report 0 on exactly the frames this rule exists for.
  Verify on the EO2 that physically turning the frame actually reaches `onConfigurationChanged`; the
  manifest claims `orientation` in `configChanges`, so nothing else follows the wall. A panel that
  cannot be measured yet leaves both decisions dark, which is the conservative direction: the clip
  keeps TsPlayer and its original. Read the value back from `/state`'s `video.panelShape` (the pixels
  the rule used) and `video.frameOrientation` (the same number as a word, derived rather than
  separately measured, so the two cannot disagree).
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
   or effective aspect differs from the panel's own shape by more than
       MediaCompatibility.VIDEO_NATIVE_ASPECT_TOLERANCE_BP (3%)
```

where effective dimensions are the coded dimensions swapped for 90°/270° rotations. Unknown
dimensions and an unmeasurable panel keep the TsPlayer default; unknown rotation is judged from the
coded dimensions by the aspect term, which cannot refuse it, while the rotation term simply cannot
fire.

The second line replaced a test for the aspect being merely **opposite** the panel's, which compared
the clip's two sides against each other and never looked at the wall. That predicate could only be
answered by a clip with a long axis, so a square fell through to TsPlayer and reached the panel
letterboxed — the reported bug — and because "opposite" was really a hidden threshold at aspect 1.0,
a 1088x1080 clip flipped engines while a 1080x1080 and a 1080x1088 did not. Comparing against the
panel fixes both: "opposite" is just the largest case of "differs", so every clip the old rule caught
is still caught, and 3% is the tolerance that keeps H.264's 1088-padding on the right side of the
line (0.74% off a 1920x1080 panel). The nearest common video ratio to 16:9 is 16:10, 10% away, so
any value between 2% and 5% picks the same set of clips — this is a margin, not a measurement.

The aspect term is `MediaCompatibility.videoAspectDiffersFromPanel`, which the source gate calls too —
deliberately one function, so the two paths cannot drift into two definitions of "native". They still
differ in *coverage*, and that gap is real: routing falls back to the `MediaAsset`'s catalog dimensions
when the file's header gives none, while the gate refuses to decide on anything but probed bytes — so a
container the probe cannot read (an `.mkv`, whose angle lives in a Matroska element) is MediaPlayer
material by the engine's reading and simply unjudgeable by the fetcher's. The rotation term stays local
to routing: it asks whether a player can turn the picture, which is not the question a source decision
asks. The decision is
returned as a `VideoRoutingDecision` rather than stored — and reaches the log through `logDurable()` (the
board's `videoRouting` row is derived from the record, so it retires with the clip and a derived row never
reaches `AppLogger`) and the `/state` `video` block
(`rotation`, `width`, `height`, `codec` and the rest of the measurement). `engine` names the effective
player — it was called `active` until this release, which is why nothing in the block could say whether a
clip was on screen at all. Those fields are present only while a clip *is* the thing on the panel: they
describe the file, so a frame displaying a photograph reports none of them. `frameOrientation` and
`panelShape` are the exception, because they describe the wall, and `panelShape: "unmeasured"` is the only
way to tell a dark aspect rule from a working one.

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

Steps 1-3 and 6 assume the EO2. On an EO1, do step 7 first — the gate — and then 8 and 9, which matter
**more** on a frame that permanently has one engine; steps 2, 3 and 6 describe behaviour it does not have.

1. Startup logs one line, and it is the frame's **default** engine — what it will use for a clip nothing
   routes away — so it answers "does this frame have TsPlayer" and nothing else:
   - EO2: `EO1: video player: TsPlayer (USE_TSPLAYER=true, loaded, gate=amlogic, silicon=amlogic, profile=EO2)`
   - EO1: `EO1: video player: MediaPlayer (USE_TSPLAYER=true, not available on this board's silicon, gate=model:denied, silicon=unknown, profile=EO1)`

   Note what is **absent** from the EO1 line: no `failed:` and no `UnsatisfiedLinkError` anywhere in it.
   The gate denies the engine before the class is initialized, so there is no load attempt to report.
   An EO1 that logs a linker error has a broken gate, not a missing library — that is step 7.
   The other engine line is per-clip: `displayVideo routing: … player=` names what will composite *that*
   file. The two disagree on a healthy EO2 — launch says `TsPlayer`, a rotated or wrong-aspect clip says
   `MediaPlayer` — and neither is more correct than the other.
2. Play a known-good local H.264 from Immich cache full-screen
3. Loop ≥2 minutes without surface loss **and without black flicker between passes** — the durable log's `TsPlayer createPlayer` line shows no Java recreate and `loopPerf.restartMethod=native-loop`
4. Image ↔ video handoff and screen off/on
5. HEVC (or other incompatible) plays via TsPlayer/MediaPlayer; on failure it falls back to Immich /video/playback (images: preview thumbnail)
6. Force Ts failure (corrupt file) → log `TsPlayer → MediaPlayer fallback` then thumbnail/next asset path

Steps 7–9 are the parts of this branch that no JVM test can reach, because they are the wiring around a
live Activity rather than the helpers:

7. **The engine gate, on both frames.** `/state` → `video.tsGate` reports every input to the decision
   plus `decidedBy`, and `device.buildHardware` / `buildBoard` / `boardPlatform` / `cpu` say what the
   machine actually is. Expected: EO2 → `silicon: "amlogic"`, `decidedBy: "amlogic"`,
   `tsSupported: true`; EO1 → `silicon: "unknown"`, `decidedBy: "model:denied"`,
   `tsSupported: false`. On an EO1 there must be **no** `tsLoadError` field and no
   `UnsatisfiedLinkError` in the durable log — a linker error on a frame whose gate resolved to false
   means detection went wrong, and the fix is a property read, not a missing library.
   `decidedBy: "model:allowed"` with `silicon: "unknown"` is the correct answer for a box this repo has
   never seen: an unidentified frame keeps its engine, because nothing has established it lacks one.
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
- **Not on an EO1, and this is a hardware fact.** Until 2026-11 the EO1's absence of TsPlayer was
  recorded as a linker miss — `libTsPlayer-jni.so` has a `DT_NEEDED` on `libsystemwriteservice.so`,
  exactly one undefined symbol resolves only to it
  (`android::ISystemWriteService::asInterface(const sp<IBinder>&)`), and that file is not in the EO1's
  ROM. All true, and all it means now is that the loader was never the interesting part: the two
  frames' silicon differs, and the gate is `supportsTsPlayer()`. See the closed section below.
- On the EO2 the vendor service is registered but **deaf** — `readSysfs could not contact remote` about
  ten times a second, which is *why* the app keeps a durable on-disk log
  ([WEBSERVER.md](WEBSERVER.md#logs--past-state)) — and video still paints, so the sysfs path is not
  load-bearing for playback.
- A live 2026-11 EO2 dump shows a **second** state of that service, and it is the opposite one: the
  service answers and denies itself. `E/SystemWriteService(  589): java.io.FileNotFoundException:
  /sys/class/tv/policy_fr_auto: open failed: EACCES` from `SystemWriteService.writeSysfs`, four times
  per video lap, clustered at player lifecycle edges (`play()`, a `seekTo(0)`, `disconnect`,
  `release`) rather than per frame, and always in system_server's pid.
  **This app is not the client.** That node's string is in the ROM's own `libamplayer.so`, reached via
  `libamavutils.so`'s `amSystemWriteWriteSysfs`; the shipped `libTsPlayer-jni.so` contains no `policy`
  substring and no `/sys/class/tv` path among its 43 `/sys/*` strings (it does import
  `amSystemWriteWriteSysfs` for other nodes, so the exoneration is path-specific, not blanket), and
  `EO2/data/backup/init.amlogic.board.rc:45` writes `policy_fr_auto` as root at boot while no `.rc` in
  the dump grants anyone else write.
  It is therefore benign and unreachable from here — a denied `open()` changes no per-frame work — but
  it is the line an operator will point at when a stutter report says "the log files show exceptions",
  and it is not the stutter. What the loop seam (`VideoLoopSeamRule`) was doing on that frame is
  documented in the code and in PR #106: across the one lap ever sampled at its tail, about 5.9 s of wall
  passed in which the media clock advanced 255 ms — roughly 0.04x, against 1.00x through the body of the
  same lap. **Whether the panel held a frozen picture during that interval has never been observed**, and
  the `rate=1.00x` printed beside it is not the media clock but `MainActivity.measurePlaybackRate`'s 5 s
  window, which re-anchors on every backwards position step. Do not revive the closed section below over
  it, and do not describe that interval as a frozen frame without new evidence.

## TsPlayer on the EO1: closed

**The EO1 has no TsPlayer, and never will.** The two frames' silicon turned out to be significantly
different — the EO1 is an NXP i.MX6Dual (`MCIMX6D5EYM10AC`, from the die marking in
`_img/eo1-processor-fullwidth.jpg`), the EO2 is Amlogic (`ro.hardware=amlogic`, `ro.product.board=stvm8b`,
`ro.board.platform=meson8`, from the prop dump in `EO2/`) — and the Amlogic vendor player belongs to the
EO2's board. Full sourcing in [docs/RESEARCH.md](RESEARCH.md) §1. Every video on an EO1 runs on platform `MediaPlayer`, by
hardware. `HardwareCapabilities.supportsTsPlayer()` is where that is stated in code, and
`MainActivity` consults it before it ever touches `TsPlayerNative`, so a correctly-detected EO1 does
not attempt the load and does not log a linker error.

What is closed, and should not be reopened:

- **Shipping `libsystemwriteservice.so`.** `libTsPlayer-jni.so` carries a `DT_NEEDED` on it, the EO1
  firmware never shipped it, and it is sitting in the EO2 dump at
  `EO2/data/backup/system/lib/libsystemwriteservice.so` — 13 KB, one symbol wide
  (`android::ISystemWriteService::asInterface`). That made "copy it in" look like a one-line fix. It is
  not one, and now not even a possible one: the file is a dependency of a player this board cannot run.
- **Loading it conditionally by absolute path**, with or without a runtime check for the ROM's copy.
- **Patching the vendor blob** to bound the wait below.

The wait is still worth knowing about, because it is the reason the naive fix is worse than doing
nothing — the reasoning outlives the decision:

```
getSystemWriteService()   @ libTsPlayer-jni.so 0x787008-0x787118
  pthread_mutex_lock
    loop: defaultServiceManager()->getService("system_write")
          if (result != null) break
          __android_log_print(5, ..., "SystemWriteService not published, waiting...")
          usleep(500000)
          goto loop                       <- no counter, no timeout
  pthread_mutex_unlock
```

Reached from `TsVideoView.startOrRestartPlayer` → `createPlayer` (`TsVideoView.java:330`) on a handler
bound to the main looper (`TsVideoView.java:42`). On a ROM that never registers the service, supplying
the client converts a frame that plays video into a frame with a dead UI needing a power cable. A
self-written stub cannot rescue that either: the wait is upstream of the one symbol a stub could
supply, and `defaultServiceManager` lives in the system's `libbinder`.

For contrast, a *registered but deaf* service is harmless and is the normal state — the EO2's answers
`readSysfs could not contact remote` about ten times a second ([WEBSERVER.md](WEBSERVER.md#logs--past-state)
is why) while its video paints fine. So the sysfs path is not load-bearing for playback; the
unregistered case was the only dangerous one, and on EO1 it is moot.

Note that "deaf" is not the only non-fatal answer on offer. The same service on a 2026-11 EO2 is
registered, reachable, and **self-denying** — it accepts the Binder call and then cannot open
`/sys/class/tv/policy_fr_auto` for itself, printing a full Java stack trace from `writeSysfs` in
system_server several times a lap. That string belongs to the ROM's `libamplayer.so`, not to this
app's blob (see the inventory above), and it is a third state to recognise rather than a new failure to
fix: `readSysfs could not contact remote` is the *client-side* complaint and appears zero times in that
dump, so reading one log line as proof of the other's state is how a frame gets misdiagnosed.

## Two axes, because a model name cannot answer a silicon question

`docs/RESEARCH.md` §1.3 put it plainly: keying the engine on the model is "right for buttons and wrong
for video". So the gate is `VideoSilicon.allowsTsPlayer(silicon, modelProfile.supportsTsPlayer())`:

- **Silicon can only permit.** A confirmed Amlogic board (`Build.HARDWARE` contains `amlogic`,
  `Build.BOARD` contains `stvm`, or `ro.board.platform` contains `meson` — all three read out of the
  EO2's own prop dump) gets the engine whatever its model string says. That is the fix for real
  hardware that does not identify itself: `stvm8b` / "Quad-Core Enjoy TV Box" *is* an EO2, from an EO2's
  recovery log, and a model-only test hands it the wrong answer.
- **Silicon never forbids.** There is no list of "known not-Amlogic" strings, because the EO1's i.MX6
  identification came from a die photograph and a teardown, not from that frame's properties. A
  classification invented from a photo would fail closed on hardware nobody has measured, so an
  unrecognised board defers to the model profile — and the EO1's model already says no.
- **Gate before the class, not only before the call.** Java evaluates a method's arguments first, so
  writing `TsPlayerNative.isAvailable()` inside a log line or a `/state` field initializes the class
  and runs `System.loadLibrary` — on a denied frame that puts straight back the linker warning the
  ordering was added to keep off the log. The native answers are read once, inside `if (tsAllowed)`,
  and everything downstream reads the captured fields. No JVM test can tell "loaded and failed" from
  "never attempted" — on a host with no ARM library both answer `false` — so the guarantee is carried by
  structure: one startup/diagnostics call site in `MainActivity`, and `TsVideoView`'s own calls behind a
  controller that is never selected when the gate denies. A third diagnostic read added casually is the
  regression to watch for, and no test will catch it.
- **Both inputs are reported.** `video.tsGate` carries `silicon`, `modelProfile`, `modelAllows`,
  `decidedBy`, `buildFlag` and `libraryLoaded`, and `device` carries `buildHardware`, `buildBoard`,
  `boardPlatform` and one line of `cpu`. A gate nobody can reconstruct from a report is a gate that
  gets debugged by guessing.

`/state` on an EO1 says `tsSupported: false`, `decidedBy: "model:denied"`, `preferTsPlayer: false`, and
no `tsLoadError` — that combination is the hardware answer, and it is complete. An
`UnsatisfiedLinkError` on a frame where it is not reached would mean detection went wrong; check
`device.platform` and `HardwareCapabilitiesFactory` before looking for a missing library.

### What being permanently single-engine costs an EO1

One thing, and it is why the ladder is written the way it is: `ORIGINAL_REPLAY` — the rung that
replays a cached original on the *other* engine — is gated on `preferTsPlayer` and never runs here
(`MainActivity.java`, `playbackFallback`). A frame with one engine has one attempt at the original and
then the degraded source, so the display-path guards carry more weight on an EO1 than on an EO2: a
server with nothing transcoded answering `/video/playback` with the original's own bytes is a second
copy of the same failure, and the render-silent verdict must never become a server-side tag. Those are
checked in `MediaManagerFallbackSourceTest`; steps 8-9 of the validation checklist are the half no JVM
test can reach.
