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
| `/state` | GET | JSON: device/app info, config, network/Wi-Fi, rotation stats, battery/memory/uptime telemetry |
| `/logs` | GET | HTML page that live-polls `/log.json` every 3 s |
| `/log.json?lines=N` | GET | JSON array of in-memory log events (ring buffer, up to 500 retained) |
| `/log/file?lines=N` | GET | Tail of the on-disk rolling log (`filesDir/eo1-app.log`, rotated at 256 KB) |
| `/files` | GET | JSON list of uploaded files (`name`, `size`, `lastModified`) |
| `/files/<name>` | GET | Streams an uploaded file |
| `/files/<name>/delete` | DELETE / POST | Deletes an uploaded file |
| `/upload` | POST | `multipart/form-data` upload, 512 MB cap per file, 2 GB cap per request, max 32 parts; stored in `filesDir/uploaded/` |
| `/control?action=<a>` | GET / POST | Fires a hardware-key action (below) |
| `/health` | GET | `ok` |

`/control` actions mirror the hardware buttons (`EventManager`):

| action | hardware equivalent | effect |
|---|---|---|
| `next` | space / PS4 circle | show next asset in the rotation |
| `screen` | top button | toggle screen on/off |
| `brightness` | back button | bump the minimum brightness |
| `config` | C | open the app configuration dialog |
| `settings` | S | open system settings |
| `update-site` | U | open the OTA release page |
| `check-updates` | top + back | check Gitea for a new APK |

All actions are posted to the UI thread. Unknown actions return `{"ok":false,"fired":false}`.

## Uploads and rotation

Uploaded files land in `getFilesDir()/uploaded` (`UploadedMedia`) — app-private, **never**
scanned by cache eviction (`MediaCacheManager` only touches its UUID-named cache-file pattern)
and not cleared by "clear cache". Incoming file names are sanitized to a single path
component; colliding names get a `_N` suffix. Uploads stream to disk in 8 KB chunks
(`MultipartParser`) into a per-request `incoming_<nanoTime>` temp dir, then are renamed into
the persistent dir — parallel uploads cannot collide.

On each rotation rebuild, `MediaManager.addLocalUploadedAssets()` scans that directory and
adds one synthetic asset per recognised media file (video/image extension lists in
`MediaManager`). Local assets are **fully interleaved** with Immich assets: they are appended
to the same list before it is shuffled, and they go through the **same on-device FFmpeg
pipeline** (`VideoTranscodeManager` / `ImageConvertManager`). Transcode/convert outputs are
cached in `cacheDir` as `<local-asset-uuid>_eo1.*` — those outputs are evictable, the
originals in `filesDir/uploaded` are not. When Immich is unreachable, the rotation simply
falls back to the local uploads.

## Logs / past state

Raw system logcat is not readable by a normal (non-root) Android app, so the log feature
captures the app's own curated event stream instead: `MainActivity.debugInformationProvided`
(and uncaught exceptions) feed `AppLogger`, which keeps a 500-event in-memory ring buffer
**and** a 256 KB rolling on-disk file. Device telemetry (battery, memory, Wi-Fi, uptime) is
collected on demand by `DeviceTelemetry` for `/state`.
