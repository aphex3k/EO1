# Web server (LAN debug / control / media upload)

Every EO1 has a very slow (2.4 GHz) Wi-Fi interface used to acquire Immich media. To keep the
device debuggable and controllable without adb, the app runs an **always-on, unsecured,
plain-HTTP** web server on its LAN address:

- Tries **port 80** first (privileged; only works for a system/root install).
- Falls back to **port 8080**.

The bound port is logged to logcat (tag `EO1-web`) and exposed in the `app.webPort` field of
`/state`. There is no TLS by design: the device's TLS stack is outdated and the server is only
meant to be reached from a trusted LAN.

Implementation: `WebServer` is a hand-rolled `ServerSocket` server (one accept thread + 3
worker threads, always `Connection: close`, no keep-alive, per-request timeouts). It talks to
the app exclusively through the `WebController` interface (implemented by `MainActivity`), so
the server itself is unit-testable with a fake controller and never references the Activity.

## Endpoints

| Route | Method | Purpose |
|---|---|---|
| `/` | GET | One-page UI: upload form, file table (download/delete), control buttons |
| `/state` | GET | JSON: device/app info (incl. `app.trustedNetwork`), config (`config.backends[]` with `id`/`type`/`host`/`apiVersion`/`valid` — no secrets — plus a deprecated `host` alias, `intervalMinutes`, `quietHours` (array of cron expressions, empty when unset), `timezone`), network/Wi-Fi, rotation stats, an `update` block (`state`, `installedVersionCode`, `expectedVersionCode`, `expectedVersionName`, `manifestUrl`, `lastCheckedMs`, `lastError`, `attempts`, `stagedApk{present,bytes}`, `installPermissionHeld`, `installMode`), battery/memory/uptime telemetry |
| `/logs` | GET | HTML page that live-polls `/log.json` every 3 s |
| `/log.json?lines=N` | GET | JSON array of in-memory log events (ring buffer, up to 500 retained) |
| `/log/file?lines=N` | GET | Tail of the on-disk rolling log (`filesDir/eo1-app.log`, rotated at 256 KB) |
| `/files` | GET | JSON list of uploaded files (`name`, `size`, `lastModified`) |
| `/files/<name>` | GET | Streams an uploaded file |
| `/files/<name>/delete` | DELETE / POST | Deletes an uploaded file |
| `/upload` | POST | `multipart/form-data` upload, 512 MB cap per file, 2 GB cap per request, max 32 parts; stored in `filesDir/uploaded/` |
| `/control?action=<a>` | GET / POST | Fires a hardware-key action (below) |
| `/config` | GET | **Trusted network only.** Full device configuration JSON, credentials included |
| `/config` | POST | **Trusted network only.** Applies the request body (≤ 512 KB JSON) as the new device configuration: parsed, normalized, written to `filesDir/configuration.json`, live config replaced, time zone re-applied, rotation restarted. `{"ok":false,"msg":...}` on validation failure (config unchanged) |
| `/config/download` | GET | **Trusted network only.** Streams the configuration as a `configuration.json` attachment (same document as `POST /config` writes, legacy mirror fields included) |
| `/config/import` | POST | **Trusted network only.** Same as `POST /config`; the web UI's file-picker import path |
| `/health` | GET | `ok` |

`/control` actions mirror the hardware buttons (`EventManager`):

| action | hardware equivalent | effect |
|---|---|---|
| `next` | space / PS4 circle | show next asset in the rotation |
| `screen` | top button | toggle screen on/off |
| `brightness` | back button | bump the minimum brightness |
| `config` | C | open the app configuration dialog |
| `settings` | S | open system settings |
| `update-site` | U | open the GitHub releases page |
| `check-updates` | top + back | check the self-update manifest for a new APK |
| `install-staged` | — | install a staged self-update (turns the screen on first) |
| `update-reset` | — | drop the staged update files and reset the update state |

All actions are posted to the UI thread. Unknown actions return `{"ok":false,"fired":false}`.

## Trusted network

The configuration endpoints (`/config*`) are gated by a **trusted-network** flag that is
**deliberately not part of `configuration.json`**: it lives in the app's default
SharedPreferences under the key `trusted_network`, defaults to **off** on a fresh install, and
is toggled by the *Trusted Network* checkbox in the on-device options dialog (Media tab) — the
toggle persists immediately, it is not saved through the dialog's Save button.

While off, all four config endpoints answer `403` and the index page shows a note instead of the
configuration editor; while on, the index page renders the full editor (backends with credentials,
add/remove, interval, time zone, quiet-hour cron rows, self-update, MQTT, Save) plus
**Export configuration** (GET `/config/download`, a `configuration.json` attachment) and
**Import configuration** (file picker → `POST /config/import`). A successful apply mirrors the
on-device Save flow: configuration written + live config replaced, time zone re-applied, and the
rotation core loop restarted.

Because the LAN server is unsecured plain-HTTP by design, treat this flag as "someone on the LAN
may read and change this device's configuration, including its Immich credentials" — keep it off
on untrusted networks. The flag is reported in `/state` as `app.trustedNetwork`.

## Uploads and rotation

Uploaded files land in `getFilesDir()/uploaded` (`UploadedMedia`) — app-private, **never**
scanned by cache eviction (`MediaCacheManager` only touches its UUID-named cache-file pattern)
and not cleared by "clear cache". Incoming file names are sanitized to a single path
component; colliding names get a `_N` suffix. Uploads stream to disk in 8 KB chunks
(`MultipartParser`) into a per-request `incoming_<nanoTime>` temp dir, then are renamed into
the persistent dir — parallel uploads cannot collide.

Each rotation tick, `MediaManager` re-scans that directory through the **`local` media
backend** (`LocalMediaBackend`): newly appeared files are appended to the tail of the merged
rotation pool and the unshown tail is reshuffled, so a web upload joins the rotation on the
next interval tick — no restart, no pool rebuild, and no reference from the web server to the
media pipeline. Local assets are **fully interleaved** with Immich assets from any number of
hosts: they are peers in the same merged pool and are **played/displayed directly from
`filesDir/uploaded`** — no cache copy and no client-side FFmpeg pipeline. The originals in
`filesDir/uploaded` live outside the cache directory and are never touched by eviction (which
only touches its UUID-named cache-file pattern). Files deleted through `/files/<name>/delete`
or on disk drop out of the pool on the next tick. When an Immich backend is unreachable or
its credentials are wrong, that backend is skipped with a toast and the remaining backends
(including local uploads) keep rotating.

## Logs / past state

Raw system logcat is not readable by a normal (non-root) Android app, so the log feature
captures the app's own curated event stream instead: `MainActivity.debugInformationProvided`
(and uncaught exceptions) feed `AppLogger`, which keeps a 500-event in-memory ring buffer
**and** a 256 KB rolling on-disk file. Device telemetry (battery, memory, Wi-Fi, uptime) is
collected on demand by `DeviceTelemetry` for `/state`.
