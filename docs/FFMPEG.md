# FFmpeg integration — EO1 / EO2

Agent-oriented reference for client-side video transcoding. FFmpeg is used **only to transcode** incompatible videos before playback. Playback stays on platform `MediaPlayer` via `TextureVideoView`.

## Goal and scope

- Convert Immich video originals that EO1/EO2 cannot play (especially **HEVC/H.265**, VP9, AV1) or that exceed **1920px on the longest axis** into **H.264 MP4** files sized for the frame display and `MediaPlayer` on API 19.
- **Not in scope:** FFmpeg-based playback, ExoPlayer/Media3, or raising `minSdk`.

## Source map

| Path | Role |
|------|------|
| [`eo1-ffmpeg/`](../eo1-ffmpeg/) | Android library module — `VideoProbe`, `VideoTranscoder`, FFmpeg command runner |
| [`eo1-ffmpeg/libs/*.aar`](../eo1-ffmpeg/libs/) | CI-built ffmpeg-kit LTS GPL AAR (gitignored; not committed) |
| [`app/.../VideoTranscodeManager.java`](../app/src/main/java/com/aphex3k/eo1/VideoTranscodeManager.java) | App orchestration — cache, timeouts, disk guards |
| [`app/.../MediaManager.java`](../app/src/main/java/com/aphex3k/eo1/MediaManager.java) | Proactive transcode after video download |
| [`app/.../MainActivity.java`](../app/src/main/java/com/aphex3k/eo1/MainActivity.java) | Reactive transcode on `MediaPlayer` failure |
| [`Jenkinsfile`](../Jenkinsfile) | `Build FFmpeg Native` stage before Gradle |
| [`ffmpeg/`](../ffmpeg/) | Local dev workspace for ffmpeg-kit fork (gitignored) |

## Hard constraints

| Constraint | Reason |
|------------|--------|
| `minSdk` / `targetSdk` / `maxSdk` = **19** | EO1/EO2 ship KitKat 4.4.2 |
| ABI **armeabi-v7a** only | Geniatech EO1/EO2 hardware |
| **Software** decode/encode only | No `-hwaccel`, no FFmpeg MediaCodec — avoids Geniatech GPU lockups (see [`EO1-specs.md`](../EO1-specs.md)) |
| Transcode only; `MediaPlayer` for playback | ExoPlayer/Media3 hang the device on API 19 Geniatech boards |
| GPL **x264** encoder | Required for H.264 output; app must ship GPL license notice |

## Video pipeline

```
Immich download (original)
  → VideoProbe (FFprobe)
  → if incompatible codec (HEVC/VP9/AV1) OR longest axis > 1920: VideoTranscoder → {uuid}_eo1.mp4
  → displayVideo → MediaPlayer
  → on error: reactive transcode (if not already transcoded)
  → on failure: Immich /video/playback + EO1_INCOMPATIBLE tag
```

## Public Java API (`com.aphex3k.eo1.ffmpeg`)

```java
// Probe container/codec via FFprobe JSON
ProbeResult VideoProbe.probe(File input);

// Known incompatible codecs
boolean KnownIncompatibleCodecs.isKnownIncompatible(String codecName);

// Transcode to MediaPlayer-safe H.264 MP4
TranscodeResult VideoTranscoder.transcode(File input, File output, TranscodeOptions opts);
void VideoTranscoder.cancel();
```

Inject `FfmpegCommandRunner` in tests to mock FFprobe/FFmpeg output without native libs.

## Default transcode profile

Software-only FFmpeg arguments (via `TranscodeOptions` defaults):

```
-i <input>
-c:v libx264 -profile:v baseline -level 3.1 -pix_fmt yuv420p
-vf scale='min(1920,iw)':'min(1920,ih)':force_original_aspect_ratio=decrease
-an -movflags +faststart -threads 2
<output>
```

- Scale filter fits video inside a 1920×1920 box so the **longest side is at most 1920** while preserving aspect ratio.

- `-an`: EO1 has no speakers.
- `-threads 2`: matches EO1 dual-core CPU.
- Output cache name: `{assetId}_eo1.mp4`.

## Build workflow (native AAR)

### CI (Jenkins)

1. Clone `https://gitea.codingmerc.com/michael/ffmpeg-kit.git`
2. Set `ANDROID_SDK_ROOT=/var/android-sdk` and `ANDROID_NDK_ROOT` (NDK **r22b** recommended per ffmpeg-kit docs)
3. Build LTS GPL package for armeabi-v7a:

```bash
./android.sh --lts --enable-gpl --enable-x264 \
  --disable-arm64-v8a --disable-x86 --disable-x86_64
```

4. Copy AAR: `cp bundle-android-aar-lts/ffmpeg-kit-*-gpl*.aar ../eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar`
5. Run `./gradlew assembleRelease`

### Local dev without native libs

- `./gradlew test` works — unit tests mock `FfmpegCommandRunner`.
- `./gradlew assembleDebug` requires the AAR in `eo1-ffmpeg/libs/` (build locally or copy from CI artifact).
- Without native libs at runtime, transcoding is skipped and the app falls back to Immich `/video/playback`.

### Local native build (optional)

Use the gitignored [`ffmpeg/ffmpeg-kit/`](../ffmpeg/ffmpeg-kit/) fork or clone fresh, then run the same `android.sh` command above.

## App guardrails

| Guard | Value | Rationale |
|-------|-------|-----------|
| Transcode timeout | 10 minutes | Avoid blocking rotation on large 4K HEVC |
| Max source size | 1 GB | Same as `MediaManager.MAX_ASSET_BYTES` |
| Min free disk | 2× source file size | EO1 limited cache storage |
| Concurrent transcodes | 1 | 1 GB RAM budget |

## Failure modes

| Symptom | Likely cause | Action |
|---------|--------------|--------|
| `FfmpegCommandRunner.isAvailable()` false | AAR missing from APK | Rebuild with CI native stage |
| Transcode timeout | Large HEVC / slow CPU | Falls back to Immich playback |
| `TranscodeException` disk | Cache full | Falls back to Immich playback |
| CI native stage fails | NDK/SDK mismatch | Pin NDK r22b; check ffmpeg-kit wiki |
| Playback still fails after transcode | Exotic container/profile | Tag `EO1_INCOMPATIBLE`; Immich fallback |
| Reactive transcode loops on same asset | Repeated `onError` for one asset | Reactive transcode runs once per asset per rotation; then Immich `/video/playback` |

## Licensing

The x264 encoder and GPL ffmpeg-kit package are **GPL v3**. Source for FFmpeg and x264 is available from the ffmpeg-kit project. Ship appropriate license notices with release APKs.

## APK size

armeabi-v7a-only GPL build adds roughly **15–40 MB** to the APK.
