# AGENTS.md — EO1 / EO2 Immich frame APK

## Goal

Android home-screen replacement APK for Electric Objects **EO1** and **EO2** digital frames. It pulls photos/videos from a self-hosted [Immich](https://immich.app/) server and rotates them on an interval so display never depends on Electric Objects’ cloud.

- Package ID: `com.aphex3k.eo1`
- Product source: `app/` only
- Human install/ops guide: [README.md](README.md)
- Hardware notes: [EO1-specs.md](EO1-specs.md)

## Source map

| Path | Treat as |
|------|----------|
| `app/` | **Only product source** (Java Android module) |
| Root Gradle files, `Jenkinsfile` | Build & CI |
| `configuration_example.json` | Config template (may drift; see below) |
| `EO2/`, `ffmpeg/`, `.electric-objects/` | Local dumps / binaries / vendor reference — **not** app logic |
| `_img/` | README assets only |

Gitignored (do not commit): `configuration.json`, `ffmpeg/`, large parts of `EO2/`.

## Tech stack & hard constraints

- **Language:** Java only — no Kotlin
- **SDK:** `minSdk` / `targetSdk` / `maxSdk` = **19** (KitKat 4.4.2); `compileSdk` 34
- **Build:** Gradle + Android Gradle Plugin; CI via Jenkins (build, tests, Sonar, signed APKs)
- **Networking:** Retrofit + OkHttp + Gson; hand-rolled Immich client; Gitea client for OTA
- **TLS:** EO1 needs TLS 1.2 and weaker ciphers — see `Tls12SocketFactory.java` and `ApiServiceGenerator.java`
- **Secrets:** Immich password is stored cleartext in device `configuration.json` — never commit a real config

Do not casually bump SDK levels or modernize AndroidX / OkHttp / Retrofit; pins exist for API 19.

## Architecture / entry points

```
MainActivity
  ├── SettingsManager  → configuration.json (Configuration.java)
  ├── MediaManager     → com.aphex3k.immichApi → Immich server
  ├── BrightnessManager / BrightnessSensorManager
  └── UpdateManager    → com.aphex3k.giteaApi → Gitea releases
```

Open these first:

| Role | Path |
|------|------|
| Launcher / orchestration | `app/src/main/java/com/aphex3k/eo1/MainActivity.java` |
| Album fetch / rotation | `app/src/main/java/com/aphex3k/eo1/MediaManager.java` |
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
- Treat README marketing or hardware setup prose as build requirements for code changes
- Edit or rely on `EO2/`, `ffmpeg/`, or `.electric-objects/` as product source
