# TsPlayer playback — EO1 / EO2

Amlogic/Geniatech **TsPlayer** (`libTsPlayer-jni.so`) is the original Electric Objects video path. This APK prefers it on matching hardware and falls back to platform `MediaPlayer` via `TextureVideoView`.

## Scope

| In scope | Out of scope |
|----------|----------------|
| Immich **original** files via TsPlayer (no client re-encode when Ts available) | Client FFmpeg video re-encode while TsPlayer is preferred (~1 fps on EO CPU) |
| `TsVideoView` (`SurfaceView`) → `TsPlayerNative` → `libTsPlayer-jni.so` | ExoPlayer / Media3 |
| Automatic MediaPlayer fallback on load/play/surface failure | Non-Amlogic primary playback |
| Loop via file duration timer (+ optional getCurrentTime), not getStatus | App-level video filters / ppmgr FX |

When TsPlayer is available, client FFmpeg **video** prepare/reactive convert is skipped. Image convert is unchanged. See [FFMPEG.md](FFMPEG.md).

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
  → prepareVideo skipped when TsPlayer available (no client libx264)
  → displayVideo → TsVideoView
  → loop: MediaMetadataRetriever duration timer → recreate player
  → on Ts error/surface-timeout: same original → MediaPlayer
  → on MediaPlayer failure: Immich /video/playback or thumbnail (not client re-encode)
```

Note: on-device `getStatus()` often stays `IDLE (0)` while frames play (OEM only logged status, never branched on it). Do not use status for loop control.

`SurfaceView` does **not** create a surface while `INVISIBLE`/`GONE`. The layout keeps `ImageView` above `TsVideoView` so the wallpaper covers video until `onVideoPrepared` hides the image.

## Hard constraints (video convert)

Client-side FFmpeg **re-encode is not used when TsPlayer is available**. EO CPU manages ~1 fps libx264 — minutes per second of source. TsPlayer plays Immich originals directly; Immich `/video/playback` remains the download fallback if playback fails.## On-device validation checklist

On a physical EO1/EO2:

1. Logcat `EO1: video player: TsPlayer (USE_TSPLAYER=true native=true)`
2. Play a known-good local H.264 from Immich cache full-screen
3. Loop ≥2 minutes without surface loss
4. Image ↔ video handoff and screen off/on
5. HEVC (or other incompatible) still converts via FFmpeg then plays
6. Force Ts failure (corrupt file) → log `TsPlayer → MediaPlayer fallback` then thumbnail/next asset path

To force MediaPlayer only: set `buildConfigField "boolean", "USE_TSPLAYER", "false"` and rebuild.

## Packaging notes

- ABI: **armeabi-v7a** only (matches release `abiFilters`)
- ~9.7 MB LFS `.so` (same blob as EO2 `/system/lib/libTsPlayer-jni.so`)
- ProGuard keeps `com.example.tsplayer.TsPlayerNative`
- Depends on Amlogic system services (`libmedia`, `libsystemwriteservice`, `/dev/amstream*`) — will not work as primary on generic Android
