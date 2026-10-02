# EO2 Capabilities Investigation

- **Date:** 2026-10-01
- **Device:** Electric Objects EO2 (adb serial `20061229`), Android 4.4.2 (API 19), Amlogic meson8, 1920×1080 @ 240 dpi, ~814 MB RAM
- **App under test:** `com.aphex3k.eo1` v1.3.0.1 (debug build, HOME role, TsPlayer active)
- **Methods:** app source analysis, `getprop`/`getevent -lp`, `dumpsys` (window/input/sensorservice/power), `logcat`, `screencap`, `adb shell input keyevent`, LAN web control (via `adb forward` to localhost:8080)

## 1. Input device inventory (`getevent -lp`)

| Node | Name | Advertised keys |
|------|------|-----------------|
| `/dev/input/event0` | `aml_keypad` | Full HID keyboard: A–Z, 0–9, F1–F12, arrows, media keys |
| `/dev/input/event1` | `adc_keypad` | `KEY_POWER` only |
| `/dev/input/event2` | `gpio_keypad` | `KEY_POWER` only — **the physical button** |
| `/dev/input/event3` | `cec_input` | HDMI-CEC remote keys (power, mute, vol±, D-pad, media, colors) |

No touchscreen: no `ABS_X`/`ABS_Y`/`ABS_MT_*` on any device.

## 2. Physical button (the "soft touch" equivalent)

**Result: it is a plain power key, intercepted by the OS — the app never sees it.**

- Live `getevent` capture while the user pressed the button showed `EV_KEY KEY_POWER down/up` on `event2` (`gpio_keypad`).
- `logcat` shows the interception: `PhoneWindowManager.interceptKeyBeforeQueueing` → `PowerManagerService: "Going to sleep by user request"` / `"Waking up from sleep"`.
- Consequence: a short press toggles the display at OS level. The app's `EventManager` (which expects `KEYCODE_F2`/`KEYCODE_F4` for the EO1 soft-touch buttons) never receives the event, so **all EO1 soft-button code paths are dead on EO2 hardware**.
- **Desync consequence:** the app's internal `shouldTheScreenBeOn` state is not synchronized with the real display state. If quiet hours turn the app state off and the user then wakes the panel with the physical button, `showNextImage()` (gated at `MainActivity:599`) stops advancing → stale frame on a lit panel. Conversely, OS-sleep with app state "on" keeps the rotation timer doing work for a dark screen.
- Long-press behavior (reboot/power-off): not tested (would interrupt the live device).

## 3. Ambient light sensor

**Result: none. The light-sensor brightness path is dead.**

- `dumpsys sensorservice`: empty sensor list, 0 active listener registrations.
- No sensor HAL in `/system/lib/hw/`.
- `BrightnessManager`'s `TYPE_LIGHT` listener never fires. Note: `"Reported Light Value: 0.175"` lines in logcat are **not** sensor readings — 0.175 is the `Lux.FULL_MOON` (`minLux`) constant fed through `setShouldTheScreenBeOn(true)`; `0.0` is the off-state constant.
- Effective brightness is therefore the static `minBrightness` (default 0.3), raisable only via the back-button action / web `brightness` action.

## 4. Key handling reachable on EO2 (verified via `input keyevent`)

| Keycode | Key | App handler | Result |
|---|---|---|---|
| 132 | F2 | `toggleScreenOn()` | ✅ Works both directions: `screenOffWakeLock` released/acquired, TsPlayer `surfaceDestroyed`/`surfaceCreated`, `/state` `screenOn` flips True↔False |
| 134 | F4 | `adjustMinimumBrightness()` | ✅ Works (silent by design); `Min Brightness`/`Calculated Brightness Value` logs show 0.3→0.5 after two presses. In-memory only — resets to 0.3 on app restart |
| 62 | SPACE | `showNextImage()` | ✅ Works — advanced through the pool (`displayVideo`/`displayPictures` logs, new assets downloaded) |
| 31 | C | `showConfigurationUI()` | ✅ Works — in-app config dialog opens (MEDIA/MQTT tabs: backends, time zone, quiet-hours cron, slideshow interval, self-update URL/interval, Save) |
| 47 | S | `openSystemSettings()` | ✅ Works — the EO2 **does ship an AOSP Settings app** (full UI: Wi-Fi, Display, Apps, Developer options, …) |
| 49 | U | `openUpdateWebsite()` | ❌ **Crashes** — `ActivityNotFoundException` (no browser handles `ACTION_VIEW` https on the device), uncaught in `openSystemSettings`-style `startActivity` → `System.exit(2)` → auto-restart as HOME. Reproduced (pid 739→2084) |

Implication: a **USB keyboard** plugged into the EO2 reaches every keyboard-based handler (F2/F4/SPACE/C/S all reachable). PS4/gamepad `BTN_*` mappings exist in code but were not tested (no controller attached).

## 5. LAN web server (port 8080)

Port 80 bind fails with `EACCES` (no root) → the designed fallback to **8080** is in effect. Verified via `adb forward tcp:8080 tcp:8080`:

- `GET /state` → full JSON: device/app identity, config (backends, interval, quiet hours, timezone), network, `media.rotationAssets`/`media.screenOn`, update state (`IDLE`, `installMode: intent-fallback`), telemetry (battery all zeros — wall-powered, no battery), TsPlayer diagnostics, debug overlay entries.
- `GET /control?action=screen` → `{"ok":true,"fired":true}`, `/state` `screenOn` flips. ✅
- `GET /control?action=next` → `{"ok":true,"fired":true}`, asset advanced. ✅
- Other available actions: `brightness`, `config`, `settings`, `update-site`, `check-updates`, `install-staged`, `update-reset`.

**The web control surface is a complete functional replacement for the missing physical/soft buttons** (screen, brightness, next, config, update check), and it is already fully implemented.

## 6. HDMI-CEC (bonus, untested end-to-end)

- `cec_input` (`/dev/input/event3`) is registered and `dmesg` shows the `hdmitx` CEC controller cycling `cec_late_resume`/`cec_early_suspend`.
- CEC keypresses would arrive as ordinary key events to the focused app; the app currently maps no media/CEC keycodes, so a TV remote would be inert without an `EventManager` mapping. Untested with a real CEC source (needs a CEC transmitter on the HDMI output).

## 7. Bonus findings (bugs observed on device)

1. **U key / web `update-site` action crashes the app** (no browser on device). Fix: `try/catch ActivityNotFoundException` around `startActivity` in `openUpdateWebsite()` (`MainActivity.java:611-614`).
2. **`ImmichClientV3` threw on *every* successful response**: `response.raw().close()` at `ImmichClientV3.java:110` (login) threw `IllegalStateException: Cannot read raw response body of a converted body` (seen in logcat after Gson conversion). Retrofit 2.6's `OkHttpCall.parseResponse` drains the raw body on success (or buffers and closes it on error) and then swaps in a `NoContentResponseBody` whose `close()` throws; `retrofit2.Response` in 2.6 has no `close()` at all. Four `raw().close()` sites were affected (login, version probe, download-error path, 401 re-login path). Every login threw, `MediaManager.rebuildPool` (`MediaManager.java:255`) swallowed it, and a fresh process could never build a pool ("No Media found", `rotationAssets=0`). **Fixed:** the four `raw().close()` calls were removed — after `execute()` nothing is left to release (verified via bytecode of the pinned retrofit-2.6.0 / okhttp-3.12.13 jars). Regression test: `app/src/test/java/com/aphex3k/media/immich/ImmichClientV3Test.java`.
3. App is crash-resilient in practice: as the HOME app, `System.exit(2)` from the global `UncaughtExceptionHandler` is followed by an automatic process restart (observed pids 739 → 2084 → 2483 across the session).

## 8. EO1 → EO2 parity summary

| EO1 feature | EO2 status | Equivalent on EO2 |
|---|---|---|
| Top soft button → screen toggle (F2) | ❌ no hardware source for F2 | USB keyboard F2 ✅; web `/control?action=screen` ✅; physical button works at OS level only (desyncs app state) |
| Back soft button → raise min brightness (F4) | ❌ no hardware source for F4 | USB keyboard F4 ✅; web `/control?action=brightness` ✅ |
| F2+F4 within 250 ms → check updates | ❌ not reachable in hardware | web `/control?action=check-updates` ✅ |
| Ambient-light auto brightness | ❌ no light sensor exposed | static min brightness only |
| Next asset (SPACE / PS4 circle) | ❌ no on-device source | USB keyboard SPACE ✅; web `next` ✅; CEC remote possible (unmapped) |
| Config UI (C) | ❌ no on-device source | USB keyboard C ✅; web `config` ✅ |
| System settings (S) | ❌ no on-device source | USB keyboard S ✅ (Settings app exists) |
| Update website (U) | ❌ no browser → crash | — (fix: guard the intent) |

### Recommendations to reach parity

1. **Sync app screen state with the physical power button** — **implemented** (`MainActivity` registers an `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` receiver; `reconcileScreenState()` flips `shouldTheScreenBeOn` and runs the same `turnScreenOn()`/`turnScreenOff()` path as the F2 toggle whenever the panel state diverges from app state). Makes the physical button behave like the EO1 top button, including quiet-hours edge cases.
2. **Fix `openUpdateWebsite()`** with `try/catch ActivityNotFoundException` (EO2 has no browser).
3. **Fix `ImmichClientV3`'s broken response closes** — **implemented** (removed all four `response.raw().close()` calls; Retrofit 2.6's `parseResponse` already released the connection, and `retrofit2.Response` has no `close()` in 2.6. Also replaced `java.util.function.Supplier` — absent on API 19 — with an app-defined `CallSupplier`. Regression test: `app/src/test/java/com/aphex3k/media/immich/ImmichClientV3Test.java`).
4. **Treat the LAN web UI (:8080) as the primary remote control** for the EO2 (already complete).
5. Optional bonus: map CEC/media keycodes (e.g. `KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_FAST_FORWARD`) in `EventManager` so a TV remote on the HDMI-CEC link can drive next/screen.

## 9. Open items (not verified)

- Physical button **long-press** behavior (reboot/shutdown?) — not exercised to avoid interrupting the live device.
- CEC end-to-end with a real CEC source.
- PS4/gamepad `BTN_*` keys (no controller attached).
- Note on observation method: TsPlayer renders on the Amlogic native video plane, so `screencap` shows the video area black even while playing — video state must be verified via logcat (`TsPlayer status=…`), not screenshots.
