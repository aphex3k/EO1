| `/` | GET | One-page UI: upload form, file table (download/delete), control buttons. The header leads with this frame's device name when one is set, and names the running build (version, variant, source commit, date, signing-key verdict) so a frame can be identified without touching it |
| `/state` | GET | JSON: device/app info (incl. `app.trustedNetwork`; `app.version`/`versionName`/`buildNumber`/`variant`/`buildDate`/`gitSha`/`gitDirty` and an `app.signer` block (`signed`, `fingerprint` — SHA-256 of the installed certificate's DER, `subject`, `serial`, `validFrom`/`validTo`, `validity`, `algorithm`, `keyVerdict`) report which build is running and who signed it, the same facts the options dialog's **About** tab shows, and `app.createCount`/`app.processStartMs` — how many times the Activity has been built in this process and when the process started, which is how a report of "it reset itself" tells an Activity re-creation apart from a restart; device adds detected `platform`, plus `name` — the per-device label, present only when the frame has been named, so an unnamed frame's `device` block reads exactly as it did before the field existed; a top-level `capabilities` block reports `lightSensor`, `screenBrightness`, `brightnessButton`, `powerButton`, `screenToggleKeyCode`), config (`config.backends[]` with `id`/`type`/`host`/`apiVersion`/`enabled`/`valid` — no secrets — plus a deprecated `host` alias, `intervalMinutes`, `autoReboot` (the watchdog's reboot permission — a configuration field, so the config editor below can change it), `quietHours` (array of cron expressions, empty when unset), `timezone`), network/Wi-Fi, a `media` block (`rotationAssets`, `screenOn`, and — once an asset is on screen — `current` with `key`, `type` (`image`/`video`/`web`, plus `url` for a page), `backendId`, `width`, `height` (0 when unknown), `durationMs`, `local` (bool), plus `originalFileName`/`sizeBytes` when the backend reports them), a `brightness` block (`supported` — false on EO2, where nothing here changes; `step` and `floor` — the backlight level the user's BACK taps put, as an index and as the brightness it means, `persistedStep` — the same number read back out of SharedPreferences, so a `step` that disagrees with `persistedStep` means the level was raised by something that never wrote it down; `lastLightValue` — the last reading the light-sensor curve used, `applied` — what is on the panel right now (-1 before the first apply), and `screenOn`), an `input` block (`windowFocused` — whether this window holds input focus, the precondition for every keyboard binding; `activityPaused`; `focusedView` — simple class name of the view holding focus, or `none`, and it reads as a text editor if an editor ever keeps focus after the options dialog closes, which is what silences keys while everything else looks healthy; `lastKeyCode`/`lastKeyAtMs` — the last key the window was actually offered, so a keyboard delivering nothing shows as a timestamp that never moves), an `update` block (`state`, `installedVersionCode`, `expectedVersionCode`, `expectedVersionName`, `manifestUrl`, `lastCheckedMs`, `lastError`, `attempts`, `stagedSha256` (SHA-256 of the staged APK, empty when none), `stagedApk{present,bytes}`, `installPermissionHeld` (bool — whether the frame holds `INSTALL_PACKAGES`), `installMode` (path the next install dispatch will take: `headless` when that permission is held, else `adbd` when the ROM's adbd is reachable, else `defer-to-human` — nothing on this frame can install by itself, so an automatic cycle shows nothing and waits), `lastInstallAttempt` (`none`/`headless`/`adbd`/`intent` — which path the most recent install dispatch used), `adbdReachable` (bool, on-demand probe of the ROM's adbd on 127.0.0.1:5555 — the headless-install precondition), `loopbackAdbAllowed` (bool — both *Trusted Network* and *Allow the frame to start its own adbd* are on, i.e. whether the app is permitted to open that port at all), `tcpAdbArmedByApp` (bool — adbd is listening because this frame started it; the teardown that closes it runs when the action ends, and a `true` that outlives an install means the next launch will close it), `needsHumanConfirmation` (bool — the staged update is retryable but every silent transport failed, so the index page carries a standing notice asking for a click at the frame), `installFiredAtMs` (when an install was last dispatched, 0 when nothing is pending — persisted precisely so a pending install can settle in a process that never fired it)), a `watchdog` block (`staleMs`/`staleLimitMs` — how long nothing has been on screen and when that counts as wedged; `level`/`lastAction`/`lastActionAtMs` — how far down the recovery ladder this wedge has already gone; `repairAttempts`, `probeMisses`, `ticks`; `paused`, `mainLoopAlive`/`mainLoopStallMs`, `downloadInFlightMs`, `offlineMs`, `autoReboot`), a `crash` block (`store`: `count`, `lastAtMs`, `lastVersionCode` — the build whose `mapping-<versionCode>.txt.gz` retraces the newest report — and `totalBytes`; `sources`: per-source startup-harvest outcomes, each `copied` / `empty` / `unchanged` / `budget-exhausted` / `unavailable`, plus `readLogs` `granted`/`denied`), battery/memory/uptime telemetry; debug builds add a `video` block (active player, TsPlayer loop telemetry, and the last video routing inputs: `codec` (the `stsd` fourcc the header probe read, absent when it could not identify one), `rotation`, `width`, `height`, `frameOrientation`) |
| `/logs` | GET | HTML page that live-polls `/log.json` every 3 s |
| `/log.json?lines=N` | GET | JSON array of in-memory log events (ring buffer, up to 500 retained) |
| `/log/file?lines=N` | GET | Tail of the on-disk rolling log (`filesDir/eo1-app.log`, rotated at 256 KB) |
| `/crashes` | GET | HTML table of stored crash reports (below) — download and delete each one |
| `/crash.json` | GET | JSON array of the same listing (`name`, `kind`, `epochMillis`, `versionCode`, `exception`, `size`, `lastModified`) |
| `/crash/<name>` | GET | Streams one crash report as plain text |
| `/crash/<name>/delete` | DELETE / POST | Deletes one crash report (retention pruning is separate and automatic) |
| `/files` | GET | JSON list of uploaded files (`name`, `size`, `lastModified`) |
| `/files/<name>` | GET | Streams an uploaded file |
| `/files/<name>/delete` | DELETE / POST | Deletes an uploaded file |
| `/upload` | POST | `multipart/form-data` upload, 512 MB cap per file, 2 GB cap per request, max 32 parts; stored in `filesDir/uploaded/` |
| `/update?token=<t>` | POST | **Trusted network + configuration token + same origin.** `multipart/form-data` upload of a single APK file (100 MB cap — `UpdateManifest.MAX_APK_BYTES`), staged as a self-update instead of added to the rotation pool. The APK is verified with the same gates as the self-update download path: SHA-256, JAR signature against the installed app's signing certificate (pure-Java `ApkSignatureVerifier`), and a strictly newer `versionCode` read from the APK's binary manifest. On success it lands in `filesDir/updates/` as the staged update (state `STAGED`) — **never auto-installed**. The JSON response carries the computed `sha256` so the user can check it against the published release before firing `install-staged`; the index page shows it and offers **Install staged** / **Discard staged** (a page reload re-shows it from `/state`) |
| `/control?action=<a>[&code=<n>][&asset=<key>]` | GET / POST | Fires a hardware-key action (below); `keyevent` (debug builds only) additionally requires POST and reads the `code` param, and `show` (debug builds only) reads `asset` |
| `/config?token=<t>` | GET | **Trusted network + configuration token.** Full device configuration JSON, credentials included |
| `/config?token=<t>` | POST | **Trusted network + configuration token + same origin.** Applies the request body (≤ 512 KB JSON) as the new device configuration: parsed, normalized, written to `filesDir/configuration.json`, live config replaced, time zone re-applied, rotation restarted. `{"ok":false,"msg":...}` on validation failure (config unchanged) |
| `/config/download?token=<t>` | GET | **Trusted network + configuration token.** Streams the configuration as a `configuration.json` attachment (same document as `POST /config` writes, legacy mirror fields included) |
| `/config/import?token=<t>` | POST | **Trusted network + configuration token + same origin.** Same as `POST /config`; the web UI's file-picker import path |
| `/devicename?token=<t>` | POST | **Trusted network + configuration token + same origin.** Replaces this frame's **device name** — the operator's free-form label ("Kitchen") — from the raw request body, so the name never appears in a query string or a logged request line. Capped at 512 bytes, then sanitised (control characters dropped, trimmed, ≤ 64 chars / ≤ 128 UTF-8 bytes); an empty body clears it. The JSON response echoes the name actually stored, so a shortening is visible instead of silent. Stored in the app's default SharedPreferences under `device_name`, **not** in `configuration.json`: exporting, importing or hand-editing a configuration cannot relabel another frame, and this write leaves the running rotation and schedule alone. Surfaced in the page header and `<title>`, in the frame's debug overlay, and as `/state`'s `device.name` |
| `/health` | GET | `ok` |

`/control` actions mirror the hardware buttons (`EventManager`):

| action | hardware equivalent | effect |
|---|---|---|
| `next` | space / PS4 circle | show next asset in the rotation |
| `show?asset=<backendId>:<assetId>` | — (**debug builds only**) | Display **one chosen rotation asset now** instead of hoping the shuffle lands on it: the named record is swapped to the head of the pool and the next tick displays it, so it goes down the ordinary rotation path with the whole ladder applied — original bytes, the other player engine, backend fallback, incompatible tagging — exactly as an unprompted rotation of that asset would. `<backendId>` is one of `config.backends[].id` (`immich-1`, `local`, `web-1`); `<assetId>` is the id that backend reports for the asset (Immich's own asset UUID; for `local` and `web`, a deterministic UUID derived from the file name or page URL), so the dependable way to get a key is to read `/state`'s `media.current.key` off a frame that is already showing the asset in question. An empty key, or one this generation's pool does not hold, answers `fired:false`; before the first rotation there is no pool to check against, so such a request is accepted and the miss is reported in `/logs` instead. Like `next`, nothing is displayed while quiet hours have the panel off — turn it on with `?action=screen` first, and a request made while the panel is dark is dropped rather than left pending until it wakes |
| `screen` | top button | toggle screen on/off |
| `brightness` | back button | raise the backlight level one step (EO1). Eight steps from 0.3 to 1.0; past the top it wraps to 0.3. The level is the *floor* the ambient-light curve lifts from, and it is persisted, so it survives a restart, an update install and a reboot |
| `config` | C | open the app configuration dialog |
| `settings` | S | open system settings |
| `update-site` | U | open the GitHub releases page |
| `check-updates` | top + back | check the self-update manifest for a new APK |
| `install-staged` | — | install a staged self-update. This is the only thing that puts the confirmation dialog on the frame's screen: it is an explicit request, so it turns the panel on first and no relaunch alarm is armed over it. With a working silent transport it just installs, dialog-free |
| `update-reset` | — | drop the staged update files and reset the update state |
| `keyevent?code=<n>` | — (admin/debug; **debug builds only**, POST only) | inject an arbitrary key press via `Instrumentation.sendKeyDownUpSync`; `code` must parse to an int in 0…65535. Lets you try keycodes (e.g. `26` POWER, `223` SLEEP, `132` F2) on a device without ADB. The index page shows a "Send key" form with quick-fill buttons in debug builds. |
| `watchdog?level=<1..5>` | — (**debug builds only**, POST only) | run one rung of the kiosk watchdog ladder immediately, as if its threshold had been reached: 1 force a rotation, 2 redraw the current asset, 3 Wi-Fi repair through adbd, 4 restart the app process, 5 reboot the device (rung 5 still respects the *Reboot the frame* option and only reboots when it is on). The way to prove a rung works without waiting for a wedge. |

All actions are posted to the UI thread — except `keyevent`, which is injected directly on the
web worker thread (`sendKeyDownUpSync` posts to the UI looper and would deadlock if posted
to it), and `watchdog`, which runs on the web worker thread precisely so it can still act when
the UI thread is the thing that is stuck (each rung dispatches to whatever thread owns the work).
`show` is posted like the rest: it only parks an asset key on the media layer and then drives the
same rotation tick `next` does, which is why it adds no display path of its own.
`keyevent` and `watchdog` are debug-build-only admin actions that additionally require POST: a
release build refuses them entirely, and the method gate blocks the cross-origin `GET`
drive-by. POST-only is **not** CSRF protection by itself — `/control` reads its parameters
from the URL query string, so a page the user visits could still auto-submit a plain `POST`
form and reach the action on a debug build; real CSRF resistance would need additional
checks (an `Origin`/`Referer` validation, a non-simple content type, or a per-session
token). The server deliberately has none of those — it remains credential-free plain HTTP
by design (trusted LAN only) — so the security boundary is the trusted-LAN assumption plus
the debug-build gate, not hardening against hostile pages. `show` is debug-build-only as well, but
stays `GET`-able, because naming which of its own rotation assets a frame displays adds no power a
drive-by `?action=next` does not already have. `keyevent`/`watchdog` on a release
build or via GET, `show` on a release build or with a key the current pool does not hold, an
unknown action, or a malformed `code`/`level` returns
`{"ok":false,"fired":false}`.

## Trusted network

The configuration endpoints (`/config*`), the APK update upload (`POST /update`) and the device-name
write (`POST /devicename`) sit behind two gates, on top of the trusted-LAN
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

**Same-origin (CSRF) check.** `POST /config`, `POST /config/import`, `POST /update` and
`POST /devicename` additionally reject
requests whose `Origin` header (falling back to `Referer`) does not match the request's
`Host` header, so a cross-origin page can no longer drive-by-write the configuration, stage an
update, or rename the frame. The
expected port is taken from the `Host` header when it carries one, otherwise from the bound
port; a URL port defaults to 80 when omitted; the host comparison is case-insensitive; and the
check fails closed on any unparseable piece. Clients that send no provenance header at all
(curl, scripts) pass this check — they remain fully token-gated.

While the flag is off, the four config endpoints answer `403`, and `POST /update` and
`POST /devicename` are refused the same way; the index page then shows a note instead of the
configuration editor (and instead of the APK upload form and the device-name editor), and
`/devicename` can only be reached from the frame itself — the **About** tab of the on-device
options dialog. While on, the index page shows a token input with a
**Load** button; on a successful `GET /config?token=...` the editor (backends with
credentials, add/remove (**+ Add Immich backend** / **+ Add local uploads** / **+ Add website** —
a website row's only field is its page URL), a per-backend **Enabled** toggle for non-local
backends, interval, time
zone, quiet-hour cron rows, self-update, MQTT, Save)
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
(including local uploads) keep rotating. A backend running against a server newer than any
client this build knows is a different case: it stays in the rotation and raises a one-shot
compatibility warning (a toast, plus a `compatibility:` line in `/logs`) rather than being
disabled. A backend can also be turned off entirely with the
**Enabled** toggle in the configuration editor: the entry (and its credentials) stay in
`configuration.json`, but `MediaManager` skips it when building the rotation pool — the pool
is rebuilt on the next tick whenever an `enabled` flag changes.

#### Compatibility sidecar reports

When a local upload fails a device compatibility check on its first scan (HEIF/AV1 still,
h265/hevc/av1 video — `MediaCompatibility`), `LocalMediaBackend` writes a plaintext report
next to the file: `<name>.incompat.txt` (`CompatibilitySidecar`). The report contains the
exact failure reason, a user-actionable fix (convert / re-encode and re-upload), and a JSON
diagnostic block (asset name/size/dimensions, failing check, reason, UTC timestamp, device
model/Android/app version).

- Sidecars are **never part of the rotation** (`.txt` is not a media extension) and are
  written only once — the first failure is authoritative, the app never rewrites or deletes
  them.
- While `<name>.incompat.txt` exists, `<name>` is excluded from the rotation pool. Deleting
  the sidecar (Files table → **Delete**, or `POST /files/<name>.incompat.txt/delete`) re-checks
  the asset on the next rotation tick; placing a sidecar manually is a supported way to keep
  an asset out of the rotation.
- Like any uploaded file, a sidecar is listed in `GET /files` and the index page's file table,
  and is served by `GET /files/<name>.incompat.txt` as `text/plain`.
- The app never deletes files from `filesDir/uploaded` — neither media nor sidecars. Removal
  happens only through an explicit user action (the web UI's Delete button or on disk).

APK uploads via `POST /update` are the one exception: they land in `filesDir/updates/`
(the self-update staging area), are verified before anything touches them, and never
join the rotation pool.

## Self-recovery (kiosk watchdog)

`KioskWatchdog` guards one invariant: **while the screen should be on, the frame keeps putting new
content on it.** It ticks once a minute on its own `Timer` thread — deliberately not the activity's
`Handler`, because the failure it was written for blocked the main thread for 15 minutes in a binder
call into a deadlocked WindowManager, and a handler-based watchdog would have been blind exactly then.

Staleness is the age of the last display the loop committed to — refreshed by a rotation tick that
reached `displayPicture`/`displayVideo`, by a download that has stopped making progress, and started
at process launch so a frame that has never gotten anything on screen ages out too. (It judges loop
liveness, not asset decodability: a single long video looping natively is healthy, not stale.)
Nothing is judged during quiet hours, while another activity is in front
(`/state: watchdog.paused`, `PAUSED` in the heartbeat), or before the first asset has ever shown.
The limit is `max(20 min, 3 × rotation interval)`, and past it the ladder escalates — each rung at
most once per wedge, one rung per tick at most, and the counter resets as soon as the frame is
healthy again. The "at stale ≥" column is therefore what a rung *requires*, not when it fires: a
wedge that already looks hours old on the watchdog's first look still walks rungs 1→2→3→4 before it
reaches 5, because rung 5 is the one an unprivileged frame is not allowed to pull and must not be
arrived at by skipping the rung that would have worked:

| rung | at stale ≥ | what it does |
|---|---|---|
| 1 force rotation | limit | re-drives `showNextImage` (a lost tick or a skipped rotation) |
| 2 recover playback | limit + 5 min | forces the full player teardown an asset handoff would do (clears the same-asset skip, releases the player, uncovers the wallpaper) and then rotates — the documented TsPlayer recovery, never a per-duration recreate |
| 3 network repair | limit + 10 min, and only after 10 min offline | `svc wifi disable` → 3 s → `svc wifi enable` through the ROM's adbd (fixed constants only), which restarts the vendor Wi-Fi stack via WifiService — the ROM's supplicant is registered as `init.svc.p2p_supplicant`, so poking `ctl.start wpa_supplicant` by the AOSP name would be a guess. Effect is judged by connectivity on the next tick. Up to 3 attempts, ≥10 min apart; bails out at once if adbd cannot be reached. A rung that is actually
running may start that daemon for the seconds it needs it — but only if *Trusted Network* **and**
*Allow loopback ADB for update installs* are both on, and it shuts it down again in the same `finally`
when the rung ends, so adbd is never left listening between rungs and is never started by the ladder
deciding nothing yet. With the flags off (the default), on an EO1 that means this rung logs a skip
instead of running. Caveat measured on the EO2: while a host `adb` client is attached, adbd accepts the loopback connection but never answers — so this rung was verified to fail fast, not to restore Wi-Fi. Test it on a frame that is not plugged into a dev machine (and note that the re-arm restarts adbd, which drops that host session) |
| 4 self-restart | two un-acked main-thread probes, or the climb arriving at it | schedules a relaunch and kills our own process — the only rung that works when the UI thread is the wedged part |
| 5 reboot | limit + 30 min | `reboot` via adbd. **Opt-in** via `config.autoReboot` — the *Reboot the frame if nothing else recovers it* checkbox in the options dialog, or the same field in the config editor below (trusted network + token); when off, the ladder logs that it stopped here. While there is no network it waits for rung 3's attempts to be spent first — an ISP outage is not fixed by rebooting the frame every day |

The 2026-10-13 incident is the reason rungs 3 and 5 exist: a `SurfaceFlinger` hang put
`system_server`'s WindowManager monitor under the system Watchdog, the Watchdog restarted the
framework, and the ROM never started `wpa_supplicant` afterwards — so the frame sat "waiting for
network" for 13 hours while every part of the app was correctly reporting the situation.

Force any rung on a debug build with `POST /control?action=watchdog&level=<1..5>`.

### Vendor OTA pollers

Not a rung — this runs once from `MainActivity.onCreate`, on its own thread, and never blocks or
restarts anything. The ROM ships two pollers the frame must not answer to: `com.geniatech.upgrade`
(`/system/app/Update.apk`, an hourly alarm plus BOOT_COMPLETED and every Wi-Fi/ethernet state change,
pointed at the dead `www.geniatech.net`) and `com.electricobjects.updater` (a 15-minute repeating
alarm pointed at Electric Objects' cloud). Both are `android.uid.system`.

At launch the app asks **the framework** whether they are already excluded from intent resolution
(`VendorPollers`, through `getApplicationEnabledSetting` / `getComponentEnabledSetting`, which API 19
gates on nothing but the cross-user check) and reaches for loopback adbd only when the answer says
work remains. Read the line in `/logs` accordingly:

| line | meaning |
|------|---------|
| `vendor updater: silenced, verified without adbd: …` | the framework says the wake paths are closed. Nothing was written this launch, and nothing needed writing |
| `vendor updater: after pm disable …=ok -> …=quiet(…)` | the write landed and was confirmed by re-reading |
| `vendor updater: after adbd is not listening, nothing written -> …=armed(…) \| needs human: adb shell pm disable com.geniatech.upgrade …` | this frame cannot silence itself. Type the commands the line carries, over USB root adb — the setting persists across reboots |

A frame with a host `adb` client attached on USB is in that last group by design: its loopback adbd
accepts the connection and never reads a byte, which is what used to make every bench run log
`unreachable (SocketTimeoutException)` on a device that had been silent for days. The read-back is
what separates that from a genuinely armed frame.

Two things the read is not: a `dumpsys package` **Resolver Table entry is not evidence a component is
enabled** — KitKat lists disabled components there regardless of state, which is why it must never be
used as the check. Read `User 0: … enabled=N` (0 DEFAULT, 1 ENABLED, 2 DISABLED, 3 DISABLED_USER, 4
DISABLED_UNTIL_USED), `pm list packages -d`, or `/data/system/users/0/package-restrictions.xml`. And a
disable does not dequeue an alarm that is already armed — `AlarmManagerService` cancels alarms for
removals, not for enable-state changes — so the hourly wake keeps firing, delivering nothing, until the
next boot, after which nothing re-arms it.

Rolling one back takes *both* forms, because `pm enable <package>` does not clear a leftover
`<disabled-components>` entry:

```bash
adb shell pm enable com.geniatech.upgrade && adb shell pm enable com.geniatech.upgrade/.UpdateReceiver
adb shell pm enable com.electricobjects.updater/.CheckUpdateReceiver && adb shell pm enable com.electricobjects.updater/.BootCompletedReceiver
```

## Logs / past state

Raw system logcat is not readable by a normal (non-root) Android app, so the log feature
captures the app's own curated event stream instead: `MainActivity.debugInformationProvided`
(and uncaught exceptions) feed `AppLogger`, which keeps a 500-event in-memory ring buffer
**and** a 256 KB rolling on-disk file. Device telemetry (battery, memory, Wi-Fi, uptime) is
collected on demand by `DeviceTelemetry` for `/state`.

The rolling file log is a *general* post-mortem source, and it exists because on the EO2 the vendor
`ISystemWriteService` writes ~10 lines/second ("readSysfs could not contact remote"), so a
`logcat -d` ring holds roughly 13 minutes and any overnight incident is already gone by the time
anyone can plug in adb. The watchdog therefore writes a `heartbeat` line every 10 minutes even when
nothing is wrong (`tick`, `stale`, `limit`, `level`, `action`, `network`/`offlineFor`, `screen`,
`PAUSED`, `UNACKED_PROBES`) — about two weeks of history inside the rolling cap, and enough to tell
"loop dead", "offline" and "blocked main thread" apart after the fact. A *crash* is no longer
something you have to catch in that window: it gets its own durable report, below.

## Crash dumps

`CrashReporter` is the process's only `Thread.UncaughtExceptionHandler`, installed from
`Eo1Application.attachBaseContext` — before any Activity exists, because the crashes worth reading
are often the ones that happen during `MainActivity.onCreate`. Each one writes a plaintext report
into `filesDir/crashes`, then preserves the contract the app has always had: a +120 s HOME relaunch
alarm and `System.exit(2)`. It never chains to the framework handler (that would put a crash dialog
on an unattended screen) and the alarm/exit live in a `finally`, so a report that fails to write
still restarts the frame.

**One file per event**, named so that a listing needs no body reads:

```
crash-<epochMillis>-<seq>-<kind>-<versionCode>.txt     kind = java | logcat | dropbox | anr
```

The body is a `Key: value` header (time, version, device, thread, exception, memory, free disk,
uptime, and what each optional source found), the full trace with its `Caused by` chain, the last
50 `AppLogger` ring lines as the lead-up, and a JSON block for tooling. Traces are written by an
*iterative* cause walk, not `printStackTrace`: that recurses per cause and would raise
`StackOverflowError` on a dying thread's stack, losing the whole report.

**Retention**, enforced on write and at startup: 14 days, and a 4 MB total past which the oldest
reports go. The newest report is never evicted.

### What is captured, and what you may not get

| Source | When | Honest expectation |
|---|---|---|
| `java` | the instant it happens, from the handler | Always available. This is the one that matters. |
| `logcat` | `logcat -d -b system` at startup, filtered down to debuggerd's native-crash lines | Needs `READ_LOGS`. There is **no `crash` buffer on API 19** (`LOG_ID_CRASH` arrived in Android 7.0; this ROM's `logcat` knows only `main`/`system`/`radio`), so the `system` buffer is where a segfault lands. A denied or failing `logcat` is reported `unavailable`, never stored as a report — the child's exit status is checked, because its error text would otherwise look like output. |
| `dropbox` | a copy of the crash-named artifacts in `/data/system/dropbox` at startup | Most likely `unavailable`: a non-system uid cannot traverse it on API 19. Only `*crash*`/`*tombstone*`/`*anr*` `.txt`/`.txt.gz` files are copied (a real device's directory is ~159 `event_data@*.txt` per tombstone), and `.gz` is decompressed on the way in. |
| `anr` | the same, from `/data/anr` | Same. |

`/state`'s `crash.sources` distinguishes *"nothing crashed"* (`empty`) from *"we could not look"*
(`unavailable`) — and from `unchanged`, meaning a scrape found nothing new since the last boot, which
is what stops a night of boot loops from spending the whole 4 MB cap re-copying one ANR trace.
Copying is capped per boot, and a copied artifact is **copied into `filesDir/crashes`** — no crash
route reads anywhere else, so nothing reaches `/data` in place. Harvesting the two framework
directories through the device's adbd would work, but it would mean opening TCP adb as root to
satisfy a diagnostics read, and the app does not do that for anything other than an install.

### Decoding a release trace

Release is R8-minified, and `proguard-rules.pro` keeps `SourceFile,LineNumberTable` — so a frame
reads `a.b.c(SourceFile:17)` and means nothing without the mapping the build produced. Every publish
archives it beside the APK as `mapping-<versionCode>.txt.gz`, and the `<versionCode>` in a report's
name is exactly the key that selects it:

```bash
curl -o dump.txt 'http://<frame>:8080/crash/<name>'   # 1. the report
gunzip -k mapping-<versionCode>.txt.gz                # 2. the build's mapping, from the update dir
"$ANDROID_HOME/cmdline-tools/latest/bin/retrace" mapping-<versionCode>.txt dump.txt   # 3. readable
```

`/crashes` shows the needed file name per row, and the index page carries a line whenever the store
is non-empty. These pages are ungated, like `/logs` and `/files`: a report is the thing you need on
the frame that is currently dead, and the flag that would gate it can only be set at that frame's
own screen. Reports hold absolute paths, versions and exception text — never the configuration, the
token, or SharedPreferences, which the reporter does not read.
