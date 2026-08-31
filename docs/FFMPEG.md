# FFmpeg integration — EO1 / EO2

Agent-oriented reference for client-side video transcoding. FFmpeg is used **only to transcode** incompatible videos before playback. Playback stays on platform `MediaPlayer` via `TextureVideoView`.

## Goal and scope

- Convert Immich video originals that EO1/EO2 cannot play (especially **HEVC/H.265**, VP9, AV1) or that exceed **1920px on the longest axis** into **H.264 MP4** files sized for the frame display and `MediaPlayer` on API 19.
- **Not in scope:** FFmpeg-based playback, ExoPlayer/Media3, or raising `minSdk`.

## Source map

| Path | Role |
|------|------|
| [`eo1-ffmpeg/`](../eo1-ffmpeg/) | Android library module — `VideoProbe`, `VideoTranscoder`, FFmpeg command runner |
| [`eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar`](../eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar) | Git LFS prebuilt ffmpeg-kit LTS GPL AAR (armeabi-v7a + arm64-v8a) |
| [`app/.../VideoTranscodeManager.java`](../app/src/main/java/com/aphex3k/eo1/VideoTranscodeManager.java) | App orchestration — cache, timeouts, disk guards |
| [`app/.../MediaManager.java`](../app/src/main/java/com/aphex3k/eo1/MediaManager.java) | Proactive transcode after video download |
| [`app/.../MainActivity.java`](../app/src/main/java/com/aphex3k/eo1/MainActivity.java) | Reactive transcode on `MediaPlayer` failure |
| [`Jenkinsfile`](../Jenkinsfile) | Optional `Build FFmpeg Native` stage (`REBUILD_FFMPEG_NATIVE=1`) |
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
  → if incompatible codec (HEVC/VP9/AV1), longest axis > 1920, or audio present: VideoTranscoder → {uuid}_eo1.mp4
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

When video must be re-encoded (incompatible codec or oversized):

```
-i <input>
-c:v libx264 -profile:v baseline -level 3.1 -pix_fmt yuv420p
-vf scale='min(1920,iw)':'min(1920,ih)':force_original_aspect_ratio=decrease
-an -movflags +faststart -threads 2
<output>
```

When video is already EO1-compatible but contains audio (remux only):

```
-i <input>
-c:v copy -an -movflags +faststart
<output>
```

- Scale filter fits video inside a 1920×1920 box so the **longest side is at most 1920** while preserving aspect ratio.

- `-an`: strip all audio tracks (EO1 has no speakers).
- `-threads 2`: matches EO1 dual-core CPU.
- Output cache name: `{assetId}_eo1.mp4`.

## Build workflow (native AAR)

### Prebuilt AAR (default)

The repo ships [`eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar`](../eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar) via **Git LFS** (~22 MB; `armeabi-v7a` for EO1 hardware, `arm64-v8a` for Apple Silicon emulator debug). After clone:

```bash
git lfs pull
./gradlew test assembleDebug assembleRelease
```

Release APKs still package **armeabi-v7a** natives only (`app/build.gradle` release `abiFilters`). Debug can include arm64 when `-PdebugAbiArm64=true` (set automatically by `./debug.sh` when the AAR contains arm64).

### CI (Jenkins)

**Default:** verify the committed AAR exists after checkout; skip native build.

**Opt-in rebuild:** set `REBUILD_FFMPEG_NATIVE=1` on the Jenkins job to clone ffmpeg-kit and run:

```bash
./android.sh --lts --enable-gpl --enable-x264 --disable-x86 --disable-x86-64
cp prebuilt/bundle-android-aar-lts/ffmpeg-kit/ffmpeg-kit.aar ../eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar
```

Use NDK **r22b** (`ANDROID_NDK_ROOT=/var/android-sdk/ndk/22.1.7171670`). Rebuilt AARs are workspace-only unless manually committed back to Git LFS.

### Local dev without rebuilding natives

- `./gradlew test` works — unit tests mock `FfmpegCommandRunner`.
- `./gradlew assembleDebug` / `assembleRelease` use the LFS AAR when present.
- Without native libs at runtime, transcoding is skipped and the app falls back to Immich `/video/playback`.

### Local native rebuild (optional)

Use the gitignored [`ffmpeg/ffmpeg-kit/`](../ffmpeg/ffmpeg-kit/) fork or clone fresh, or run `./debug.sh` (builds when AAR is missing or `FORCE_FFMPEG_BUILD=1`).

```bash
./android.sh --lts --enable-gpl --enable-x264 --disable-x86 --disable-x86-64
cp prebuilt/bundle-android-aar-lts/ffmpeg-kit/ffmpeg-kit.aar ../eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar
```

**macOS note:** ffmpeg-kit LTS targets **API 16**, so NDK **r24+** cannot be used (`armv7a-linux-androideabi16-clang` is missing). `./debug.sh` uses NDK **r23** when installed, otherwise **r22** with llvm `ar` shims for both `arm-linux-androideabi-*` and `aarch64-linux-android-*` (GNU `ar` aborts on current macOS). On macOS it uses a Homebrew CMake 4.x compatibility shim (`CMAKE_POLICY_VERSION_MINIMUM=3.5`, cpu-features tests disabled) and bumps ffmpeg-kit's Gradle wrapper to **8.5** for Java 21. Set `FFMPEG_INSTALL_SDK_CMAKE=1` to install SDK `cmake;3.22.1` instead. On **Apple Silicon**, `./debug.sh` builds **arm64-v8a** in addition to armeabi-v7a when rebuilding locally.

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
