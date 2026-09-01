# FFmpeg integration — EO1 / EO2

Agent-oriented reference for client-side **video transcoding** and **image conversion/resampling**. FFmpeg is used **only to convert** incompatible media before display/playback. Playback stays on platform `MediaPlayer` via `TextureVideoView`; stills stay on Glide.

## Goal and scope

- Convert Immich **video** originals that EO1/EO2 cannot play (especially **HEVC/H.265**, VP9, AV1) or that exceed **1920px on the longest axis** into **H.264 MP4**.
- Convert Immich **image** originals that Glide/API 19 cannot decode (**HEIC/HEIF**) or that exceed **1920px on the longest axis** into **JPEG**.
- **GIFs** are never converted (preserve animation).
- **Not in scope:** FFmpeg-based playback, ExoPlayer/Media3, or raising `minSdk`.

## Source map

| Path | Role |
|------|------|
| [`eo1-ffmpeg/`](../eo1-ffmpeg/) | Android library — `VideoProbe`/`VideoTranscoder`, `ImageProbe`/`ImageConverter`, command runner |
| [`eo1-ffmpeg/native/`](../eo1-ffmpeg/native/) | Custom libde265/libheif build scripts applied before `android.sh` |
| [`eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar`](../eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar) | Git LFS prebuilt ffmpeg-kit LTS GPL AAR (armeabi-v7a + arm64-v8a) |
| [`app/.../VideoTranscodeManager.java`](../app/src/main/java/com/aphex3k/eo1/VideoTranscodeManager.java) | Video orchestration — cache, timeouts, disk guards |
| [`app/.../ImageConvertManager.java`](../app/src/main/java/com/aphex3k/eo1/ImageConvertManager.java) | Image orchestration — same guards; shared FFmpeg lock |
| [`app/.../FfmpegConvertLock.java`](../app/src/main/java/com/aphex3k/eo1/FfmpegConvertLock.java) | Single concurrent FFmpeg job (video + image) |
| [`app/.../MediaManager.java`](../app/src/main/java/com/aphex3k/eo1/MediaManager.java) | Proactive convert after download |
| [`app/.../MainActivity.java`](../app/src/main/java/com/aphex3k/eo1/MainActivity.java) | Reactive convert on MediaPlayer/Glide failure |
| [`Jenkinsfile`](../Jenkinsfile) | Optional `Build FFmpeg Native` stage (`REBUILD_FFMPEG_NATIVE=1`) |
| [`ffmpeg/`](../ffmpeg/) | Local dev workspace for ffmpeg-kit fork (gitignored) |

## Hard constraints

| Constraint | Reason |
|------------|--------|
| `minSdk` / `targetSdk` / `maxSdk` = **19** | EO1/EO2 ship KitKat 4.4.2 |
| ABI **armeabi-v7a** only | Geniatech EO1/EO2 hardware |
| **Software** decode/encode only | No `-hwaccel`, no FFmpeg MediaCodec — avoids Geniatech GPU lockups (see [`EO1-specs.md`](../EO1-specs.md)) |
| Convert only; `MediaPlayer` / Glide for display | ExoPlayer/Media3 hang the device on API 19 Geniatech boards |
| GPL **x264** encoder + **libheif**/**libde265** | H.264 video + HEIC stills; ship GPL notices |

## Video pipeline

```
Immich download (original)
  → VideoProbe (FFprobe)
  → if incompatible codec (HEVC/VP9/AV1), longest axis > 1920, or audio present: VideoTranscoder → {uuid}_eo1.mp4
  → displayVideo → MediaPlayer
  → on error: reactive transcode (if not already transcoded)
  → on failure: Immich /video/playback + EO1_INCOMPATIBLE tag
```

## Image pipeline

```
Immich download (original)
  → skip if GIF
  → ImageProbe (FFprobe)
  → if HEIC/HEIF or longest axis > 1920: ImageConverter → {uuid}_eo1.jpg
  → displayPicture → Glide
  → on error: reactive convert (once per asset)
  → on failure: Immich /thumbnail?size=preview (JPEG) + EO1_INCOMPATIBLE tag
```

## Public Java API (`com.aphex3k.eo1.ffmpeg`)

```java
// Video
ProbeResult VideoProbe.probe(File input);
boolean KnownIncompatibleCodecs.isKnownIncompatible(String codecName);
TranscodeResult VideoTranscoder.transcode(File input, File output, TranscodeOptions opts);

// Images
ImageProbeResult ImageProbe.probe(File input);
boolean KnownIncompatibleImageFormats.isKnownIncompatible(String codecOrFormat);
TranscodeResult ImageConverter.convert(File input, File output, ImageConvertOptions opts);
```

Inject `FfmpegCommandRunner` in tests to mock FFprobe/FFmpeg output without native libs.

## Default video transcode profile

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

## Default image convert profile

```
-i <input>
-frames:v 1
-vf scale='min(1920,iw)':'min(1920,ih)':force_original_aspect_ratio=decrease
-q:v 2
-threads 2
<output.jpg>
```

- Scale filter fits media inside a 1920×1920 box so the **longest side is at most 1920**.
- Output cache names: `{assetId}_eo1.mp4` (video), `{assetId}_eo1.jpg` (image).

## Build workflow (native AAR)

### Prebuilt AAR (default)

The repo ships [`eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar`](../eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar) via **Git LFS**. After clone:

```bash
git lfs pull
./gradlew test assembleDebug assembleRelease
```

Release APKs still package **armeabi-v7a** natives only. Debug builds include armeabi-v7a and arm64-v8a.

### HEIC support (libheif + libde265 + zlib)

Stock ffmpeg-kit LTS (FFmpeg **n6.0**) has **no** `--enable-libheif` configure option, so HEIC cannot be decoded through the current `ffmpeg` binary. App behavior:

1. Probe/convert stills with FFmpeg when possible (oversized JPEG/PNG resampling works; requires `--enable-android-zlib` for PNG).
2. **HEIC/HEIF** → on-device convert if/when the AAR can decode them; otherwise Immich **`/thumbnail?size=preview`** (JPEG) + `EO1_INCOMPATIBLE`.

Native scripts under [`eo1-ffmpeg/native/`](../eo1-ffmpeg/native/) build static **libde265** / **libheif** (for a future heif-convert/JNI path or FFmpeg upgrade). See that README for LTS limits and rebuild notes.

`--enable-android-zlib` is included in rebuild flags so PNG→JPEG resampling works.

### CI (Jenkins)

**Default:** verify the committed AAR exists after checkout; skip native build.

**Opt-in rebuild:** set `REBUILD_FFMPEG_NATIVE=1`. Jenkins clones the ffmpeg-kit fork, runs `eo1-ffmpeg/native/apply-to-ffmpeg-kit.sh`, then:

```bash
./android.sh --lts --enable-gpl --enable-x264 \
  --disable-x86 --disable-x86-64 \
  --enable-android-zlib \
  --enable-custom-library-1-name=libde265 \
  ... \
  --enable-custom-library-2-name=libheif \
  ...
```

Use NDK **r22b** (`ANDROID_NDK_ROOT=/var/android-sdk/ndk/22.1.7171670`). Rebuilt AARs are workspace-only unless manually committed back to Git LFS.

### Local native rebuild

```bash
FORCE_FFMPEG_BUILD=1 ./debug.sh
# or see eo1-ffmpeg/native/README.md
```

**macOS note:** ffmpeg-kit LTS targets **API 16**, so NDK **r24+** cannot be used. See prior NDK/CMake notes in `debug.sh`.

## App guardrails

| Guard | Value | Rationale |
|-------|-------|-----------|
| Convert timeout | 10 minutes | Avoid blocking rotation on large assets |
| Max source size | 1 GB | Same as `MediaManager.MAX_ASSET_BYTES` |
| Min free disk | 2× source + **128 MB** safety margin | Prefer evicting non-converted; preserve `_eo1.mp4` / `_eo1.jpg` |
| Concurrent FFmpeg jobs | 1 | Shared lock; 1 GB RAM budget |

## Failure modes

| Symptom | Likely cause | Action |
|---------|--------------|--------|
| `FfmpegCommandRunner.isAvailable()` false | AAR missing from APK | Rebuild with CI native stage |
| HEIC convert fails | AAR built without libheif | Rebuild with `eo1-ffmpeg/native` flags |
| Convert timeout | Large HEVC/HEIC / slow CPU | Falls back to Immich playback/preview |
| Disk full after eviction | Cache full | Falls back to Immich |
| Glide still fails after convert | Exotic still | Tag `EO1_INCOMPATIBLE`; Immich preview |
| Reactive convert loops | Repeated load error | Once per asset per rotation |

## Licensing

The x264 encoder, libde265, libheif (as linked), and GPL ffmpeg-kit package are **GPL-compatible**. Source is available from the ffmpeg-kit / strukturag projects. Ship appropriate license notices with release APKs.
