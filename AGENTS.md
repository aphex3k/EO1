# AGENTS.md — EO1 / EO2 Immich frame APK

## Goal

Android home-screen replacement APK for Electric Objects **EO1** and **EO2** digital frames. It pulls photos/videos from a self-hosted [Immich](https://immich.app/) server and rotates them on an interval so display never depends on Electric Objects’ cloud.

- Package ID: `com.aphex3k.eo1`
- Product source: `app/` + `eo1-ffmpeg/` library module
- Human install/ops guide: [README.md](README.md)
- Hardware notes: [EO1-specs.md](EO1-specs.md)
- FFmpeg / transcoding: [docs/FFMPEG.md](docs/FFMPEG.md)

## Source map

| Path | Treat as |
|------|----------|
| `app/` | **App product source** (Java Android module) |
| `eo1-ffmpeg/` | **FFmpeg transcode library** (Java API + Git LFS prebuilt native AAR) |
| Root Gradle files, `Jenkinsfile` | Build & CI |
| `docs/FFMPEG.md` | FFmpeg integration reference for agents |
| `configuration_example.json` | Config template (may drift; see below) |
| `EO2/`, `ffmpeg/`, `.electric-objects/` | Local dumps / vendor reference — not app logic |
| `_img/` | README assets only |

Gitignored (do not commit): `configuration.json`, `ffmpeg/`, large parts of `EO2/`.

Git LFS (tracked): `eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar` (ffmpeg-kit LTS GPL prebuilt; armeabi-v7a + arm64-v8a).

## Tech stack & hard constraints

- **Language:** Java only — no Kotlin
- **SDK:** `minSdk` / `targetSdk` / `maxSdk` = **19** (KitKat 4.4.2); `compileSdk` 34
- **Build:** Gradle + Android Gradle Plugin; CI via Jenkins (build, tests, signed APKs)
- **Networking:** Retrofit + OkHttp + Gson; hand-rolled Immich client; Gitea client for OTA
- **Immich server:** supported range **3.0.0–3.1.0** (tested against 3.1.0). Bounds live in `MainActivity.IMMICH_MIN_VERSION` / `IMMICH_MAX_VERSION`. Immich’s API is not stable across releases — when bumping support, re-check OpenAPI and update types under `immichApi/`.
- **TLS:** EO1 needs TLS 1.2 and weaker ciphers — see `Tls12SocketFactory.java` and `ApiServiceGenerator.java`
- **Video:** Platform `MediaPlayer` via `com.dd.crop.TextureVideoView`. **Do not use ExoPlayer** (including 2.19.x / Media3) on Geniatech EO1/EO2 — it triggers MediaCodec/GPU driver lockups that hang the whole device (ADB dies; requires power-cycle). Prefer MediaPlayer or other paths that avoid aggressive MediaCodec usage on API 19 Geniatech boards.
- **Video transcoding:** FFmpeg (via `:eo1-ffmpeg`) transcodes incompatible sources (HEVC/VP9/AV1) and oversized videos (longest axis > 1920px) to H.264 MP4 **before** `MediaPlayer` playback. FFmpeg is transcode-only — never used for playback. Immich `/video/playback` remains the last-resort fallback. See [docs/FFMPEG.md](docs/FFMPEG.md).
- **CI:** Jenkins uses the committed LFS AAR by default. Set `REBUILD_FFMPEG_NATIVE=1` on a Jenkins build to rebuild ffmpeg-kit from source (see [docs/FFMPEG.md](docs/FFMPEG.md)).
- **Secrets:** Immich password is stored cleartext in device `configuration.json` — never commit a real config

Do not casually bump SDK levels or modernize AndroidX / OkHttp / Retrofit; pins exist for API 19.

## Architecture / entry points

```
MainActivity
  ├── SettingsManager       → configuration.json (Configuration.java)
  ├── MediaManager          → com.aphex3k.immichApi → Immich server
  │     └── VideoTranscodeManager → eo1-ffmpeg (probe / transcode)
  ├── BrightnessManager / BrightnessSensorManager
  └── UpdateManager         → com.aphex3k.giteaApi → Gitea releases
```

Open these first:

| Role | Path |
|------|------|
| Launcher / orchestration | `app/src/main/java/com/aphex3k/eo1/MainActivity.java` |
| Album fetch / rotation | `app/src/main/java/com/aphex3k/eo1/MediaManager.java` |
| Video transcode orchestration | `app/src/main/java/com/aphex3k/eo1/VideoTranscodeManager.java` |
| FFmpeg probe / transcode API | `eo1-ffmpeg/src/main/java/com/aphex3k/eo1/ffmpeg/` |
| Settings I/O | `app/src/main/java/com/aphex3k/eo1/SettingsManager.java` |
| Config model (source of truth) | `app/src/main/java/com/aphex3k/eo1/Configuration.java` |
| Immich HTTP API | `app/src/main/java/com/aphex3k/immichApi/ImmichApiService.java` |
| HTTP / TLS / cookies | `app/src/main/java/com/aphex3k/eo1/ApiServiceGenerator.java`, `Tls12SocketFactory.java` |
| OTA updates | `app/src/main/java/com/aphex3k/eo1/UpdateManager.java` |
| HOME launcher role | `app/src/main/AndroidManifest.xml` |
| Unit tests | `app/src/test/java/` |

`com.aphex3k.sonos` is an empty package — do not assume Sonos features exist.

## Config

Device config lives at `/data/data/com.aphex3k.eo1/files/configuration.json`.

- Prefer **`Configuration.java`** as the source of truth for fields.
- `configuration_example.json` is a template and may include fields the model no longer has (e.g. `autoBrightness`).

Current model fields: `host`, `userid`, `password`, `selectedTimeZoneId`, `startQuietHour`, `endQuietHour`, `interval`.

## Common workflows

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew test
```

Device install (EO1 browser sideload vs EO2 `adb`) is documented in [README.md](README.md) — not required for most code changes.

## Do / don’t

**Do**

- Keep API 19 compatibility and existing Retrofit/OkHttp pin strategy
- Extend types under `immichApi/` when Immich API fields change
- Treat `app/src/main/java/com/aphex3k/eo1/` as the app core

**Don’t**

- Commit `configuration.json`, `ffmpeg/`, or bulk `EO2/` dumps
- Introduce Kotlin or raise `minSdk` / `maxSdk` without an explicit product decision
- Add ExoPlayer / Media3 for video playback (known Geniatech API 19 full-system hang)
- Treat README marketing or hardware setup prose as build requirements for code changes
- Edit or rely on `EO2/`, `ffmpeg/`, or `.electric-objects/` as product source
