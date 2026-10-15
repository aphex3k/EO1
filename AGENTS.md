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
- **Networking:** Retrofit + OkHttp + Gson; hand-rolled Immich client; self-update via `com.aphex3k.update` (plain-HTTP manifest + pure-Java JAR signature verification + headless install). Headless install: `installPackage` reflectively when the app holds `INSTALL_PACKAGES`, otherwise the device's own adbd on `127.0.0.1:5555` (`AdbdInstallClient` speaks the minimal adb wire protocol and runs `pm install` — the ROM starts adbd with TCP 5555 and `ro.adb.secure` unset, and sideloaded frames lack the signature-level `INSTALL_PACKAGES` permission); install-intent with confirmation UI is only the last-resort fallback when adbd is unreachable
- **Immich server:** supported band is the **whole 3.x line** (`3.0.0` up to, but excluding, `4.0.0`) — endpoints verified byte-identical through **3.3.3**. The band lives in `com.aphex3k.media.immich.ImmichClientRegistry` (the V3 client); each backend entry may pin its `apiVersion` (default `"auto"` = best-effort probe of `GET /api/server/version`). Ceilings sit on major boundaries, not on the last release someone checked: Immich marks its own stability in the spec (`x-immich-state`/`x-immich-history`) and defers removal of deprecated fields to the next major, so a 3.x minor cannot break a client that matched an earlier 3.x. A server **newer** than every known band is served best effort by the newest client and raises a one-shot compatibility warning through `MediaBackend.compatibilityWarning()` (toast + `/logs`) instead of being refused; a server **older** than the first band is still refused, because there we know the client speaks the wrong API. On a real breaking change, add a new frozen `com.aphex3k.immichApi.vN` DTO package + `ImmichClientVN` + one registry band; never edit a frozen client package. To re-verify the band cheaply, diff `open-api/immich-openapi-specs.json` in `immich-app/immich` between release tags for the operations this app calls (`ImmichApiService`) and the schemas they reference.
- **TLS:** EO1 needs TLS 1.2 and weaker ciphers — see `Tls12SocketFactory.java` and `ApiServiceGenerator.java`
- **Video:** Prefer Amlogic **TsPlayer** (`TsVideoView` + `libTsPlayer-jni.so`) on Geniatech EO1/EO2 when the native library loads; **automatic fallback** to platform `MediaPlayer` via `com.dd.crop.TextureVideoView` — and routing to the fallback is forced for videos whose display-matrix rotation is non-identity, and for videos whose display aspect is opposite the frame's display orientation (the TsPlayer `.so` has no scale/rotation control; see docs/TSPLAYER.md "Scaling & orientation (routing)"). **Do not use ExoPlayer** (including 2.19.x / Media3) — it triggers MediaCodec/GPU driver lockups that hang the whole device (ADB dies; requires power-cycle). See [docs/TSPLAYER.md](docs/TSPLAYER.md).
- **Web pages ("kiosk web"):** `com.aphex3k.media.web.WebsiteBackend` puts a configured page URL into the rotation pool as a `MediaType.WEB` asset. It has no bytes: `MediaManager` never runs it through `acquireAsset`/cache/integrity, and hands the URL to `MainActivity.displayWebsite`, which renders it in a chrome-less platform `WebView` (no extra dependency, no `@JavascriptInterface` bridge). The view is created lazily on the first web asset and inserted above the image view — a frame with no web backend must never load a WebView provider, and every other display path must hide it again. Whether it *worked* is only known asynchronously, so failures bypass the `assetFallback`/`tagAssetAsIncompatible` path entirely: `onReceivedError` or a 30s load watchdog calls `MediaManager.webDisplayFailed`, which reveals the image underneath and advances to the next pool asset (capped per cycle). A ROM with no usable WebView provider must degrade to skipping web assets, never crash into `System.exit(2)`. The engine is Android 4.4's Chromium-33 WebView: expect LAN-hosted or deliberately legacy pages over `http://`, not modern SPAs.
- **No client-side transcoding / conversion:** The downloaded Immich original is used as-is — there is no on-device FFmpeg, video re-encode, or image resize. **Nothing in `fetchCatalog` filters on decodability, and no asset is judged by its file name.** Every image and video the account can see joins the rotation pool (`ImmichMediaBackend.isDisplayable` checks media type and trash only), and each one is *displayed*: the original is always the first attempt, and a display failure moves one rung down a strict ladder — original → original on the other player engine → backend fallback (Immich `/thumbnail?size=preview` for stills, `/video/playback` for video) → flagged. Only the last rung decides incompatibility, because metadata is the one thing that can be stale: an operator who regenerates thumbnails, or a server that upgrades its HEIF handling, gets the asset back into the rotation on the next pool rebuild with no app change and no tag to clear. Two metadata gates survive, and both only *route* — they never drop an asset: originals above `MAX_ASSET_BYTES` (the ~800MB device cannot hold them) and videos with a coded side over 1920px (`MediaCompatibility.incompatibleVideoResolution`; that decoder garbles such video *without raising an error*, so waiting for a failure would show garbage forever and never reach the fallback). Their fallback artifact is cached exactly like an original — same integrity gate, same `<name>.sha1` sidecar, under a distinct `<uuid>_fb.<ext>` cache name so it can never evict-match the original it stands in for. A leading-`ftyp` byte check still runs on downloaded originals (catches HEIF content named `.jpg`); an asset whose bytes are verified undecodable is remembered for the life of the process so its original is not re-downloaded every rotation, and retried after a restart. Flagging writes the Immich `EO1_INCOMPATIBLE` tag through `MediaBackend.markIncompatible` — that tag is **operator-facing only**: the app never reads it back as a filter, and re-tags an already-tagged asset on every double failure, so a fixed asset needs no app-side unfreezing and a stale tag cannot retire one. For local uploads, the same failure is made durable: `LocalMediaBackend.fetchCatalog` writes a plaintext sidecar report `<name>.incompat.txt` next to the file on first failure (`com.aphex3k.media.local.CompatibilitySidecar` — exact reason, user-actionable fix, JSON diagnostic block); while that sidecar exists the asset is excluded from the rotation pool, sidecars are never part of the rotation themselves (`.txt` is not a media extension), and both the asset and its report are listed/served/deletable through the web server's `/files/<name>` — the only sanctioned way to re-admit an asset is to delete its sidecar.
- **Secrets:** Immich password is stored cleartext in device `configuration.json` — never commit a real config

Do not casually bump SDK levels or modernize AndroidX / OkHttp / Retrofit; pins exist for API 19.

## Architecture / entry points

```
MainActivity
  ├── SettingsManager       → configuration.json (Configuration.java, backends list)
  ├── MediaManager          → com.aphex3k.media.MediaBackend (ImmichMediaBackend → ImmichClientV3 → com.aphex3k.immichApi | LocalMediaBackend | WebsiteBackend → kiosk WebView)
  ├── Video playback        → TsPlayer (preferred) / MediaPlayer fallback
  ├── BrightnessManager / BrightnessSensorManager
  ├── UpdateManager         → com.aphex3k.update (manifest-driven self-update: SHA-256 + JAR signature + cert match; headless install via installPackage, else adbd loopback `pm install` via AdbdInstallClient)
  └── KioskWatchdog         → self-recovery ladder on its own Timer thread (force rotation → redraw → Wi-Fi repair via DeviceRepair/adbd → self-restart → opt-in reboot); see docs/WEBSERVER.md "Self-recovery"
```

Open these first:

| Role | Path |
|------|------|
| Launcher / orchestration | `app/src/main/java/com/aphex3k/eo1/MainActivity.java` |
| Video player (TsPlayer / MediaPlayer) | `TsVideoView`, `VideoPlayerController`, `com.example.tsplayer.TsPlayerNative` |
| Asset acquisition / rotation pool | `app/src/main/java/com/aphex3k/eo1/MediaManager.java` |
| Media layer (backend interface, assets, backends) | `app/src/main/java/com/aphex3k/media/` — `MediaBackend`, `MediaAsset`, `MediaSource`, `MediaType`; `media.immich` (`ImmichMediaBackend`, `ImmichClient(V3)`, `ImmichClientRegistry`); `media.local` (`LocalMediaBackend`); `media.web` (`WebsiteBackend`) |
| Settings I/O | `app/src/main/java/com/aphex3k/eo1/SettingsManager.java` |
| Platform hardware caps | `HardwareCapabilities` / `Eo1Capabilities` / `Eo2Capabilities` / `HardwareCapabilitiesFactory` in `com.aphex3k.eo1` — runtime-detected feature flags (soft-touch buttons, light sensor, brightness, power button) selected from `Build.DEVICE`/`Build.MODEL` |
| Config model (source of truth) | `app/src/main/java/com/aphex3k/eo1/Configuration.java` |
| Immich HTTP API | `app/src/main/java/com/aphex3k/immichApi/ImmichApiService.java` |
| HTTP / TLS / cookies | `app/src/main/java/com/aphex3k/eo1/ApiServiceGenerator.java`, `Tls12SocketFactory.java` |
| Self-update | `app/src/main/java/com/aphex3k/eo1/UpdateManager.java`, `AdbdInstallClient.java` (minimal adb wire-protocol client: `pm install` over the ROM's adbd on 127.0.0.1:5555, no confirmation UI), `com.aphex3k.update/` (`UpdateManifest` DTO, `UpdateState`/`UpdateStateStore` persistence, `ApkSignatureVerifier` pure-Java JAR-signature check) |
| Kiosk self-recovery | `app/src/main/java/com/aphex3k/eo1/KioskWatchdog.java` (ladder + heartbeat, runs on a `Timer` thread because the main thread is one of the things it watches), `DeviceRepair.java` (fixed adbd command vocabulary: Wi-Fi repair, reboot) |
| LAN web server | `WebServer` (HTTP on port 80→8080), `WebController` (implemented by `MainActivity`), `MultipartParser` (streaming multipart), `UploadedMedia` (persistent `filesDir/uploaded`), `AppLogger` (ring buffer + rolling file), `DeviceTelemetry` (on-demand `/state` data) — see [docs/WEBSERVER.md](docs/WEBSERVER.md) |
| HOME launcher role | `app/src/main/AndroidManifest.xml` |
| Unit tests | `app/src/test/java/` |

`com.aphex3k.sonos` is an empty package — do not assume Sonos features exist.

## Config

Device config lives at `/data/data/com.aphex3k.eo1/files/configuration.json`.

- Prefer **`Configuration.java`** as the source of truth for fields.
- `configuration_example.json` is a template for the new multi-backend shape.

Current model fields: `backends` — a list of `ConfigurationBackendEntry` (`type` "immich"|"local"|"web", `id` like "immich-1"/"local"/"web-1", `host`, `userid`, `password`, `apiVersion` = "auto" or a pinned semver; local entries carry only `type`/`id`, at most one is honored; a **web** entry carries only `type`/`id`/`host` and one entry means one page — `host` holds the page URL verbatim (`normalize` never appends the trailing "/" it adds to an immich base URL), any number of web entries is allowed, and duplicates of the same URL are dropped) — plus `selectedTimeZoneId`, `quietHours` (list of raw 5-field cron expressions, `minute hour day month weekday`; the screen is off whenever **any** expression matches the current minute in `selectedTimeZoneId`, overlaps simply OR), `interval`, and `autoReboot` (the kiosk watchdog's permission to reboot the device as its last rung; absent/false means it never reboots, and the rungs before it — re-rotate, redraw, repair Wi-Fi, restart the app — are unaffected by the flag; editable in the options dialog and in the trusted-network-gated web config editor). The flat `host`/`userid`/`password` fields are `@Deprecated` legacy: on load, `Configuration.normalize` migrates a legacy flat config into a single immich backend (and re-saves it); on save, `SettingsManager` mirrors the first immich backend back into the flat fields so older APKs can still read the file. The `@Deprecated` `startQuietHour`/`endQuietHour` pair is legacy in the same way: on load it migrates into a single cron expression in `quietHours` (parser: `CronExpression`), and on save a single mirrorable whole-hour window is written back — anything richer degrades to -1/-1 (off, never wrong hours). The **trusted-network** flag is intentionally *not* in `configuration.json`: it lives in the app's default SharedPreferences (key `trusted_network`, off by default, toggled by the *Trusted Network* checkbox in the options dialog) and gates the LAN web server's config endpoints (`/config`, `/config/download`, `/config/import`), which additionally require the per-device **configuration token** as the `token` query parameter (32-hex, lazily generated with `SecureRandom`, persisted under `trusted_network_token`, shown only in the options dialog while the flag is on) — and the two write endpoints enforce a same-origin `Origin`/`Referer` check (see [docs/WEBSERVER.md](docs/WEBSERVER.md)).

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
- Gate platform-specific hardware behavior (soft-touch buttons, light sensor, screen brightness, power button) on `HardwareCapabilities` flags — never add model-specific `if`s on `Build.MODEL`/`Build.DEVICE` elsewhere; unsupported features no-op
- Keep the LAN web server dependency-free and resource-cheap (hand-rolled `ServerSocket`, `Connection: close`); every web-server thread must be defensively wrapped — a stray exception must never reach the global `UncaughtExceptionHandler` (which calls `System.exit(2)`)
- Let `libTsPlayer-jni.so` loop a media file **natively** — create the TsPlayer once per asset and run no per-duration Java teardown loop; full `deletePlayer` + `createPlayer` only on asset handoff / `stop()` / `surfaceDestroyed` (see [docs/TSPLAYER.md](docs/TSPLAYER.md) “Looping (native)”)
- Keep every adbd command line built from fixed constants — the install path (`AdbdInstallClient`, staged public-copy path only) and the `DeviceRepair` ladder (`svc wifi disable`/`svc wifi enable`, `reboot`) alike; never interpolate user/config/network input
- Let the kiosk watchdog escalate only *down* its ladder (rotation → redraw → network repair → self-restart → opt-in reboot), one rung per wedge, with the device reboot kept behind the options-dialog permission — an unattended frame should self-heal, but not silently hard-reboot in a loop

**Don’t**

- Commit `configuration.json`, `ffmpeg/`, or bulk `EO2/` dumps
- Modify a frozen Immich client package (`com.aphex3k.immichApi`, `ImmichClientV3`) in place for a breaking API change — add the versioned package + registry band instead
- Grow the rotation pool beyond compact `MediaAsset` records; the ~800MB device cannot hold full API response objects for large libraries
- Enable GitHub’s managed “Automatic dependency submission” for this repo — the repo’s `dependency-submission.yml` workflow (user submission) is the dependency-reporting path, and user submissions take priority over managed runs
- Introduce Kotlin or raise `minSdk` / `maxSdk` without an explicit product decision
- Add ExoPlayer / Media3 for video playback (known Geniatech API 19 full-system hang)
- Loop TsPlayer video with a Java per-duration `deletePlayer`/`createPlayer` (full-recreate) loop — the `.so` already loops natively; a per-duration recreate clears the Amlogic plane → a black flash every pass and fights the native loop. Also rejected for looping: `start()`-alone at EOS (EBUSY on `amstream_vbuf`) and create-without-delete (wedges after ~12 gens). Full teardown only on asset handoff / `stop()` / surface destroy. Pulse TsPlayer `SurfaceView` GONE→VISIBLE / `setFormat` only for normal show/hide, never to fix loop stalls (see [docs/TSPLAYER.md](docs/TSPLAYER.md) “Looping (native)”)
- Add TLS/HTTPS or any new heavy dependency to the LAN web server; keep it unsecured plain-HTTP on 80→8080 for the trusted LAN only
- Automatically delete files from the local media backend (`filesDir/uploaded`): user uploads and their compatibility sidecar reports are removed **only** by explicit user action (the web UI's Delete button → `POST /files/<name>/delete`, or on disk). The app may write a sidecar report next to a failing upload but never deletes, renames, or rewrites the report or the media file
- Serve files via the web server from anywhere outside `filesDir/uploaded`
- Treat README marketing or hardware setup prose as build requirements for code changes
- Edit or rely on `EO2/`, `ffmpeg/`, or `.electric-objects/` as product source
