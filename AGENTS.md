# AGENTS.md — EO1 / EO2 Immich frame APK

## Goal

Android home-screen replacement APK for Electric Objects **EO1** and **EO2** digital frames. It pulls photos/videos from a self-hosted [Immich](https://immich.app/) server and rotates them on an interval so display never depends on Electric Objects’ cloud.

- Package ID: `com.aphex3k.eo1`
- Product source: `app/` (Java Android module)
- Human install/ops guide: [README.md](README.md)
- Hardware notes: [EO1-specs.md](EO1-specs.md)
- TsPlayer / Amlogic video: [docs/TSPLAYER.md](docs/TSPLAYER.md)
- LAN web server (debug / control / media upload): [docs/WEBSERVER.md](docs/WEBSERVER.md)

## Source map

| Path | Treat as |
|------|----------|
| `app/` | **App product source** (Java Android module) |
| Root Gradle files, `publish-update.sh` | Build (local) & release publishing |
| `docs/TSPLAYER.md` | Amlogic TsPlayer playback + MediaPlayer fallback |
| `configuration_example.json` | Config template (may drift; see below) |
| `EO2/`, `ffmpeg/`, `.electric-objects/` | Local dumps / vendor reference — not app logic |
| `_img/` | README assets only |

Gitignored (do not commit): `configuration.json`, `ffmpeg/`, large parts of `EO2/`.

Git LFS (tracked): `app/src/main/jniLibs/armeabi-v7a/libTsPlayer-jni.so` (Amlogic TsPlayer from original EO APK). `gradle/wrapper/gradle-wrapper.jar` is deliberately **not** LFS (see the `.gitattributes` override) — this repo is a GitHub fork and forks cannot serve their own LFS objects, so the wrapper must be a regular git object or Actions checkouts 404 on its LFS oid.

## Tech stack & hard constraints

- **Language:** Java only — no Kotlin
- **SDK:** `minSdk` / `targetSdk` / `maxSdk` = **19** (KitKat 4.4.2); `compileSdk` 34
- **Build:** Gradle + Android Gradle Plugin; all builds are local (the old Jenkins CI is gone — `publish-update.sh` at the repo root builds + signs release APKs via `local.properties` signing props)
- **Networking:** Retrofit + OkHttp + Gson; hand-rolled Immich client; self-update via `com.aphex3k.update` (plain-HTTP manifest + pure-Java JAR signature verification + headless `installPackage`)
- **Immich server:** supported band **3.0.0–3.2.2** (verified against the 3.2.2 OpenAPI spec — no breaking changes to the endpoints this app uses). The band lives in `com.aphex3k.media.immich.ImmichClientRegistry` (the V3 client); each backend entry may pin its `apiVersion` (default `"auto"` = best-effort probe of `GET /api/server/version`). Immich’s API is not stable across releases — on a breaking change, add a new frozen `com.aphex3k.immichApi.vN` DTO package + `ImmichClientVN` + one registry band; never edit a frozen client package.
- **TLS:** EO1 needs TLS 1.2 and weaker ciphers — see `Tls12SocketFactory.java` and `ApiServiceGenerator.java`
- **Video:** Prefer Amlogic **TsPlayer** (`TsVideoView` + `libTsPlayer-jni.so`) on Geniatech EO1/EO2 when the native library loads; **automatic fallback** to platform `MediaPlayer` via `com.dd.crop.TextureVideoView`. **Do not use ExoPlayer** (including 2.19.x / Media3) — it triggers MediaCodec/GPU driver lockups that hang the whole device (ADB dies; requires power-cycle). See [docs/TSPLAYER.md](docs/TSPLAYER.md).
- **No client-side transcoding / conversion:** The downloaded Immich original is used as-is — there is no on-device FFmpeg, video re-encode, or image resize. Assets in known-undecodable formats (HEIF/AV1 stills, h265/hevc/av1 video) are skipped *before* download by `com.aphex3k.eo1.MediaCompatibility`: a name check on backend metadata, plus a leading-`ftyp` byte check for files already local (catches HEIF content named `.jpg`); byte-verified offenders are remembered per key so they are never re-downloaded. Container-only extensions (`.mp4`/`.mov`/`.m4v`) are deliberately not flagged (they can hold H.264; h265-in-mp4 support is uncertain). Anything that slips past that gate (HEVC inside a `.mp4`, corrupt files) surfaces through the display-error path (Glide `onLoadFailed` / player error → `assetFallback` → Immich `/thumbnail?size=preview` or `/video/playback`). The ~800MB Amlogic hardware cannot reliably transcode; oversized >1920px images are loaded directly (accepted OOM risk).
- **Secrets:** Immich password is stored cleartext in device `configuration.json` — never commit a real config

Do not casually bump SDK levels or modernize AndroidX / OkHttp / Retrofit; pins exist for API 19.

## Architecture / entry points

```
MainActivity
  ├── SettingsManager       → configuration.json (Configuration.java, backends list)
  ├── MediaManager          → com.aphex3k.media.MediaBackend (ImmichMediaBackend → ImmichClientV3 → com.aphex3k.immichApi | LocalMediaBackend)
  ├── Video playback        → TsPlayer (preferred) / MediaPlayer fallback
  ├── BrightnessManager / BrightnessSensorManager
  └── UpdateManager         → com.aphex3k.update (manifest-driven self-update: SHA-256 + JAR signature + cert match, headless installPackage)
```

Open these first:

| Role | Path |
|------|------|
| Launcher / orchestration | `app/src/main/java/com/aphex3k/eo1/MainActivity.java` |
| Video player (TsPlayer / MediaPlayer) | `TsVideoView`, `VideoPlayerController`, `com.example.tsplayer.TsPlayerNative` |
| Asset acquisition / rotation pool | `app/src/main/java/com/aphex3k/eo1/MediaManager.java` |
| Media layer (backend interface, assets, backends) | `app/src/main/java/com/aphex3k/media/` — `MediaBackend`, `MediaAsset`, `MediaSource`, `MediaType`; `media.immich` (`ImmichMediaBackend`, `ImmichClient(V3)`, `ImmichClientRegistry`); `media.local` (`LocalMediaBackend`) |
| Settings I/O | `app/src/main/java/com/aphex3k/eo1/SettingsManager.java` |
| Config model (source of truth) | `app/src/main/java/com/aphex3k/eo1/Configuration.java` |
| Immich HTTP API | `app/src/main/java/com/aphex3k/immichApi/ImmichApiService.java` |
| HTTP / TLS / cookies | `app/src/main/java/com/aphex3k/eo1/ApiServiceGenerator.java`, `Tls12SocketFactory.java` |
| Self-update | `app/src/main/java/com/aphex3k/eo1/UpdateManager.java`, `com.aphex3k.update/` (`UpdateManifest` DTO, `UpdateState`/`UpdateStateStore` persistence, `ApkSignatureVerifier` pure-Java JAR-signature check) |
| LAN web server | `WebServer` (HTTP on port 80→8080), `WebController` (implemented by `MainActivity`), `MultipartParser` (streaming multipart), `UploadedMedia` (persistent `filesDir/uploaded`), `AppLogger` (ring buffer + rolling file), `DeviceTelemetry` (on-demand `/state` data) — see [docs/WEBSERVER.md](docs/WEBSERVER.md) |
| HOME launcher role | `app/src/main/AndroidManifest.xml` |
| Unit tests | `app/src/test/java/` |

`com.aphex3k.sonos` is an empty package — do not assume Sonos features exist.

## Config

Device config lives at `/data/data/com.aphex3k.eo1/files/configuration.json`.

- Prefer **`Configuration.java`** as the source of truth for fields.
- `configuration_example.json` is a template for the new multi-backend shape.

Current model fields: `backends` — a list of `ConfigurationBackendEntry` (`type` "immich"|"local", `id` like "immich-1"/"local", `host`, `userid`, `password`, `apiVersion` = "auto" or a pinned semver; local entries carry only `type`/`id`, at most one is honored) — plus `selectedTimeZoneId`, `quietHours` (list of raw 5-field cron expressions, `minute hour day month weekday`; the screen is off whenever **any** expression matches the current minute in `selectedTimeZoneId`, overlaps simply OR), and `interval`. The flat `host`/`userid`/`password` fields are `@Deprecated` legacy: on load, `Configuration.normalize` migrates a legacy flat config into a single immich backend (and re-saves it); on save, `SettingsManager` mirrors the first immich backend back into the flat fields so older APKs can still read the file. The `@Deprecated` `startQuietHour`/`endQuietHour` pair is legacy in the same way: on load it migrates into a single cron expression in `quietHours` (parser: `CronExpression`), and on save a single mirrorable whole-hour window is written back — anything richer degrades to -1/-1 (off, never wrong hours).

## Common workflows

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew test
```

### GitHub dependency reporting

`.github/workflows/dependency-submission.yml` (on push to `main` + manual) resolves the `:app` classpaths with Gradle (no tests, no packaging) and submits a dependency snapshot to the GitHub dependency graph (`POST /repos/…/dependency-graph/snapshots`). Local preview:

```bash
./gradlew :app:dependencySubmissionSnapshot   # writes app/build/dependency-submission-snapshot.json
```

The Gradle wrapper jar is deliberately a regular git object, not LFS: this repo is a GitHub fork and cannot serve its own LFS objects, so a plain checkout would leave the wrapper as an LFS pointer and `./gradlew` could not start in Actions. The blanket `*.jar` LFS rule in `.gitattributes` is overridden for it — keep it that way.

Device install (EO1 browser sideload vs EO2 `adb`) is documented in [README.md](README.md) — not required for most code changes.

## Do / don’t

**Do**

- Keep API 19 compatibility and existing Retrofit/OkHttp pin strategy
- On a breaking Immich API change, add a new frozen `com.aphex3k.immichApi.vN` DTO package + `ImmichClientVN` + a registry band in `com.aphex3k.media.immich.ImmichClientRegistry` — never edit a frozen client package
- Treat `app/src/main/java/com/aphex3k/eo1/` as the app core and `com.aphex3k.media` as the backend layer (per-backend client wiring; `MediaManager` only orchestrates)
- Keep the LAN web server dependency-free and resource-cheap (hand-rolled `ServerSocket`, `Connection: close`); every web-server thread must be defensively wrapped — a stray exception must never reach the global `UncaughtExceptionHandler` (which calls `System.exit(2)`)
- Let `libTsPlayer-jni.so` loop a media file **natively** — create the TsPlayer once per asset and run no per-duration Java teardown loop; full `deletePlayer` + `createPlayer` only on asset handoff / `stop()` / `surfaceDestroyed` (see [docs/TSPLAYER.md](docs/TSPLAYER.md) “Looping (native)”)

**Don’t**

- Commit `configuration.json`, `ffmpeg/`, or bulk `EO2/` dumps
- Modify a frozen Immich client package (`com.aphex3k.immichApi`, `ImmichClientV3`) in place for a breaking API change — add the versioned package + registry band instead
- Grow the rotation pool beyond compact `MediaAsset` records; the ~800MB device cannot hold full API response objects for large libraries
- Enable GitHub’s managed “Automatic dependency submission” for this repo — the repo’s `dependency-submission.yml` workflow (user submission) is the dependency-reporting path, and user submissions take priority over managed runs
- Introduce Kotlin or raise `minSdk` / `maxSdk` without an explicit product decision
- Add ExoPlayer / Media3 for video playback (known Geniatech API 19 full-system hang)
- Loop TsPlayer video with a Java per-duration `deletePlayer`/`createPlayer` (full-recreate) loop — the `.so` already loops natively; a per-duration recreate clears the Amlogic plane → a black flash every pass and fights the native loop. Also rejected for looping: `start()`-alone at EOS (EBUSY on `amstream_vbuf`) and create-without-delete (wedges after ~12 gens). Full teardown only on asset handoff / `stop()` / surface destroy. Pulse TsPlayer `SurfaceView` GONE→VISIBLE / `setFormat` only for normal show/hide, never to fix loop stalls (see [docs/TSPLAYER.md](docs/TSPLAYER.md) “Looping (native)”)
- Add TLS/HTTPS or any new heavy dependency to the LAN web server; keep it unsecured plain-HTTP on 80→8080 for the trusted LAN only
- Serve files via the web server from anywhere outside `filesDir/uploaded`
- Treat README marketing or hardware setup prose as build requirements for code changes
- Edit or rely on `EO2/`, `ffmpeg/`, or `.electric-objects/` as product source
