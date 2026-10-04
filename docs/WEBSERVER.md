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
| `/state` | GET | JSON: device/app info (incl. `app.trustedNetwork`; device adds detected `platform`; a top-level `capabilities` block reports `lightSensor`, `screenBrightness`, `brightnessButton`, `powerButton`, `screenToggleKeyCode`), config (`config.backends[]` with `id`/`type`/`host`/`apiVersion`/`valid` — no secrets — plus a deprecated `host` alias, `intervalMinutes`, `quietHours` (array of cron expressions, empty when unset), `timezone`), network/Wi-Fi, rotation stats, an `update` block (`state`, `installedVersionCode`, `expectedVersionCode`, `expectedVersionName`, `manifestUrl`, `lastCheckedMs`, `lastError`, `attempts`, `stagedSha256` (SHA-256 of the staged APK, empty when none), `stagedApk{present,bytes}`, `installPermissionHeld`, `installMode`), battery/memory/uptime telemetry |
| `/logs` | GET | HTML page that live-polls `/log.json` every 3 s |
| `/log.json?lines=N` | GET | JSON array of in-memory log events (ring buffer, up to 500 retained) |
| `/log/file?lines=N` | GET | Tail of the on-disk rolling log (`filesDir/eo1-app.log`, rotated at 256 KB) |
| `/files` | GET | JSON list of uploaded files (`name`, `size`, `lastModified`) |
| `/files/<name>` | GET | Streams an uploaded file |
| `/files/<name>/delete` | DELETE / POST | Deletes an uploaded file |
| `/upload` | POST | `multipart/form-data` upload, 512 MB cap per file, 2 GB cap per request, max 32 parts; stored in `filesDir/uploaded/` |
| `/update?token=<t>` | POST | **Trusted network + configuration token + same origin.** `multipart/form-data` upload of a single APK file (100 MB cap — `UpdateManifest.MAX_APK_BYTES`), staged as a self-update instead of added to the rotation pool. The APK is verified with the same gates as the self-update download path: SHA-256, JAR signature against the installed app's signing certificate (pure-Java `ApkSignatureVerifier`), and a strictly newer `versionCode` read from the APK's binary manifest. On success it lands in `filesDir/updates/` as the staged update (state `STAGED`) — **never auto-installed**. The JSON response carries the computed `sha256` so the user can check it against the published release before firing `install-staged`; the index page shows it and offers **Install staged** / **Discard staged** (a page reload re-shows it from `/state`) |
| `/control?action=<a>[&code=<n>]` | GET / POST | Fires a hardware-key action (below); `keyevent` (debug builds only) additionally requires POST and reads the `code` param |
| `/config?token=<t>` | GET | **Trusted network + configuration token.** Full device configuration JSON, credentials included |
| `/config?token=<t>` | POST | **Trusted network + configuration token + same origin.** Applies the request body (≤ 512 KB JSON) as the new device configuration: parsed, normalized, written to `filesDir/configuration.json`, live config replaced, time zone re-applied, rotation restarted. `{"ok":false,"msg":...}` on validation failure (config unchanged) |
| `/config/download?token=<t>` | GET | **Trusted network + configuration token.** Streams the configuration as a `configuration.json` attachment (same document as `POST /config` writes, legacy mirror fields included) |
| `/config/import?token=<t>` | POST | **Trusted network + configuration token + same origin.** Same as `POST /config`; the web UI's file-picker import path |
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
| `keyevent?code=<n>` | — (admin/debug; **debug builds only**, POST only) | inject an arbitrary key press via `Instrumentation.sendKeyDownUpSync`; `code` must parse to an int in 0…65535. Lets you try keycodes (e.g. `26` POWER, `223` SLEEP, `132` F2) on a device without ADB. The index page shows a "Send key" form with quick-fill buttons in debug builds. |

All actions are posted to the UI thread — except `keyevent`, which is injected directly on the
web worker thread (`sendKeyDownUpSync` posts to the UI looper and would deadlock if posted
to it). `keyevent` is a debug-build-only admin action that additionally requires POST: a
release build refuses it entirely, and the method gate blocks the cross-origin `GET`
drive-by. POST-only is **not** CSRF protection by itself — `/control` reads its parameters
from the URL query string, so a page the user visits could still auto-submit a plain `POST`
form and reach the action on a debug build; real CSRF resistance would need additional
checks (an `Origin`/`Referer` validation, a non-simple content type, or a per-session
token). The server deliberately has none of those — it remains credential-free plain HTTP
by design (trusted LAN only) — so the security boundary is the trusted-LAN assumption plus
the debug-build gate, not hardening against hostile pages. `keyevent` on a release build or
via GET, an unknown action, or a malformed `code` returns `{"ok":false,"fired":false}`.

## Trusted network

The configuration endpoints (`/config*`) and the APK update upload (`POST /update`) sit
behind two gates, on top of the trusted-LAN
assumption:

1. **Trusted-network flag** — **deliberately not part of `configuration.json`**: it lives in the
   app's default SharedPreferences under the key `trusted_network`, defaults to **off** on a
   fresh install, and is toggled by the *Trusted Network* checkbox in the on-device options
   dialog (Media tab) — the toggle persists immediately, it is not saved through the dialog's
   Save button.
2. **Per-device configuration token** — a 32-hex-character secret generated with
   `java.security.SecureRandom` on first use and persisted in the same SharedPreferences under
   the key `trusted_network_token`. Every `/config*` request must send it as the `token` query
   parameter, as does `POST /update`; the server compares it in constant time
   (`MessageDigest.isEqual`) and fails closed (403) when the token is missing, wrong, or
   unavailable. It is displayed in exactly one
   place: the on-device options dialog, below the checkbox, while the flag is on. It is not
   part of the configuration document or its export, of `/state`, or of the app logs (the
   logs are themselves readable via `/log.json`).

**Same-origin (CSRF) check.** `POST /config`, `POST /config/import` and `POST /update`
additionally reject
requests whose `Origin` header (falling back to `Referer`) does not match the request's
`Host` header, so a cross-origin page can no longer drive-by-write the configuration or
stage an update. The
expected port is taken from the `Host` header when it carries one, otherwise from the bound
port; a URL port defaults to 80 when omitted; the host comparison is case-insensitive; and the
check fails closed on any unparseable piece. Clients that send no provenance header at all
(curl, scripts) pass this check — they remain fully token-gated.

While the flag is off, the four config endpoints answer `403`, `POST /update` is refused the
same way, and the index page shows a note instead of the configuration editor (and instead of
the APK upload form). While on, the index page shows a token input with a
**Load** button; on a successful `GET /config?token=...` the editor (backends with
credentials, add/remove, interval, time zone, quiet-hour cron rows, self-update, MQTT, Save)
becomes visible, together with **Export configuration** (`GET /config/download?token=...`,
a `configuration.json` attachment) and **Import configuration** (file picker →
`POST /config/import?token=...`). The index page no longer embeds the configuration in its
HTML — the editor stays hidden until the load with the token succeeds — and remembers the
token in `localStorage` (`eo1.cfg.token`) so subsequent visits reload without re-entry.
A successful apply mirrors the on-device Save flow: configuration written + live config
replaced, time zone re-applied, and the rotation core loop restarted.

Because the LAN server is unsecured plain-HTTP by design, the token moves the threat model
from "anyone on the LAN" to "someone who can read the token off the device's screen": with
the flag on, keep the frame out of reach of people who could watch the options dialog, and
keep the flag off on untrusted networks entirely. The flag is reported in `/state` as
`app.trustedNetwork`; the token is not.

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

APK uploads via `POST /update` are the one exception: they land in `filesDir/updates/`
(the self-update staging area), are verified before anything touches them, and never
join the rotation pool.

## Logs / past state

Raw system logcat is not readable by a normal (non-root) Android app, so the log feature
captures the app's own curated event stream instead: `MainActivity.debugInformationProvided`
(and uncaught exceptions) feed `AppLogger`, which keeps a 500-event in-memory ring buffer
**and** a 256 KB rolling on-disk file. Device telemetry (battery, memory, Wi-Fi, uptime) is
collected on demand by `DeviceTelemetry` for `/state`.
