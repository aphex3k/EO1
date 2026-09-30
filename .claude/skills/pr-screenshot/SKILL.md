---
name: pr-screenshot
description: Attach a UI screenshot to a pull request whenever its diff changes anything the user sees — the frame display (photos, video, fallback/error art, toasts, options dialog) or the LAN web pages. Use when creating or updating a PR in this repo that contains user-visible UI changes, in particular when composing the PR summary/description text.
---

# PR screenshot

Every PR with user-visible changes gets one screenshot of the changed UI, embedded in the
PR description (the summary text). The shot must show the APK **built from the PR branch** —
a screenshot of an older build proves nothing.

Purely internal diffs (Immich client, config parsing, rotation order, build/CI) need no
screenshot.

## 1. Pick the device

```bash
adb devices
```

- **Physical frame attached** (serial usually `20061229`) → use it: `adb -s <serial> …`.
- **No device attached** → use the local `EO1_API21` AVD (API 21, arm64-v8a, 1024 MB,
  1080p skin — the closest match to the ~800 MB API-19 hardware that a macOS/Apple-Silicon
  host can run; arm64 emulation starts at API 21). Launch it headless:

  ```bash
  emulator -avd EO1_API21 -no-window -no-snapshot -memory 1024 -timezone America/Los_Angeles -no-boot-anim -no-audio &
  ```

  Wait until `adb -s emulator-5554 shell getprop sys.boot_completed` prints `1`.
  If the AVD is missing, create it:

  ```bash
  avdmanager create avd --name EO1_API21 --package 'system-images;android-21;default;arm64-v8a'
  ```

## 2. Install this branch's build

```bash
./gradlew assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.aphex3k.eo1/com.aphex3k.eo1.MainActivity
```

The debug APK works on both targets and still ships the armeabi-v7a TsPlayer `.so`, so the
physical frame exercises the real video path.

## 3. Show the UI the PR changes

- **Options dialog** — opens on first launch; reopen later via the web server:
  `curl 'http://<device-ip>:8080/control?action=config'` (or press `C` on a connected keyboard).
- **Rotation display** — quiet hours may have switched the screen off; turn it back on:
  `curl 'http://<device-ip>:8080/control?action=screen'`, then `...?action=next` to step to a
  fresh asset. Wait a few seconds for the media to render.
- **LAN web pages** (`/`, `/logs`, `/files`) — no device capture needed; load the page in a
  browser and screenshot it there.

The bound web port is `app.webPort` in `GET http://<device-ip>:<port>/state` (80 when
system-installed, 8080 otherwise). For the AVD, reach it via
`adb forward tcp:8080 tcp:8080` → `http://127.0.0.1:8080`.

## 4. Capture

```bash
adb -s <serial> exec-out screencap -p > docs/screenshots/<name>.png
```

(older adb hosts: `adb shell screencap -p /sdcard/shot.png` then `adb pull`.) Choose `<name>`
as a short kebab-case description of the UI state; images go in `docs/screenshots/` (create
the directory if missing). Verify the PNG is not all-black — a black frame means the screen
was off or the media had not rendered yet; retry after `?action=screen` / `?action=next`.

## 5. Attach it to the PR when composing the summary

`gh` cannot upload binaries to a PR body, so reference the image from the PR head ref:

1. Commit `docs/screenshots/<name>.png` to the PR branch and push it.
2. In the PR description, embed it next to the summary of the change:

   ```markdown
   ![<caption>](https://raw.githubusercontent.com/aphex3k/EO1/<head-branch>/docs/screenshots/<name>.png)
   ```

If the user would rather not store screenshots in git, skip the commit: keep the PNG in the
working tree, finish the PR without the image, and hand the user the file path to drag into
the GitHub web UI.

## Caveats

- The API-21 arm64 AVD has no `libTsPlayer-jni.so` — video there runs through the
  `MediaPlayer` fallback. For PRs about video playback or TsPlayer behavior, prefer the
  physical frame; otherwise note in the PR that the shot was taken on the AVD.
- Clean up when done: kill an AVD you launched (`adb -s emulator-5554 emu kill`).
- The real frame may be busy or unreachable (EO1 has no wireless adb and its single USB port
  is shared with the OTG keyboard). If neither the device nor the AVD is usable, ask the user
  for a photo of the frame instead of guessing.
