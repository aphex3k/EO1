---
name: pr-screenshot
description: Attach a UI screenshot to a pull request whenever its diff changes anything the user sees — the frame display (photos, video, fallback/error art, toasts, options dialog) or the LAN web pages. Use when creating or updating a PR in this repo that contains user-visible UI changes, in particular when composing the PR summary/description text.
---

# PR screenshot

Every PR with user-visible changes gets a screenshot of the changed UI attached to the PR
description, composed together with the summary text. The shot must show the APK **built from
the PR branch** — a screenshot of an older build proves nothing.

Purely internal diffs (Immich client, config parsing, rotation order, build/CI, docs) need no
screenshot. No PR ships silently screenshot-free without cause: when capture is impossible, the
body says so and why.

**Screenshots never enter git.** They are staged in the gitignored `.claude/pr-screenshots/`
directory and uploaded to GitHub as user attachments. If `git status` shows a staged PNG, it is
in the wrong place.

## 1. Pick the device

`adb devices`:

- **Physical frame attached** (serial usually `20061229`) → use it: `adb -s <serial> …`.
- **No device attached** → start a headless `EO1_API21` AVD (API 21, arm64-v8a, 1024 MB, 1080p
  — the closest match to the ~800 MB API-19 hardware a macOS/Apple-Silicon host can run; arm64
  emulation starts at API 21):

  ```bash
  emulator -avd EO1_API21 -no-window -no-snapshot -memory 1024 -timezone America/Los_Angeles -no-boot-anim -no-audio &
  ```

  Wait until `adb -s emulator-5554 shell getprop sys.boot_completed` prints `1`. If the AVD is
  missing, create it:

  ```bash
  avdmanager create avd --name EO1_API21 --package 'system-images;android-21;default;arm64-v8a'
  ```

  Caveat: the AVD has no `libTsPlayer-jni.so` — video plays through the `MediaPlayer` fallback
  there. For PRs about video playback or TsPlayer behavior prefer the physical frame; otherwise
  note in the body that the shot is from the AVD.

## 2. Install the branch build and show the changed UI

```bash
./gradlew assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.aphex3k.eo1/com.aphex3k.eo1.MainActivity
```

The debug APK works on both targets and still ships the armeabi-v7a TsPlayer `.so`, so a
physical frame exercises the real video path.

- **Options dialog** — opens on first launch. Reopen it later via the web server:
  `curl 'http://<device-ip>:8080/control?action=config'` (or `C` on a connected keyboard).
- **Rotation display** — quiet hours may have the screen off: `…/control?action=screen` toggles
  it, `…/control?action=next` steps to a fresh asset. Give the media a few seconds to render.
- **LAN web pages** — no device capture needed; load the page in a browser and screenshot it
  there.

The bound port is `app.webPort` in `GET http://<device-ip>:<port>/state` (80 when installed as
a system app, 8080 otherwise). For the AVD: `adb forward tcp:8080 tcp:8080` first, then use
`http://127.0.0.1:8080`.

## 3. Capture into the staging dir

```bash
RUN=".claude/pr-screenshots/$(git rev-parse --abbrev-ref HEAD | tr -c 'A-Za-z0-9' '-')"
rm -rf "$RUN" && mkdir -p "$RUN"
adb -s <serial> exec-out screencap -p > "$RUN/<slug>.png"
```

(The `rm -rf` is scoped to this run's own tag inside this worktree, so it cannot touch anything
else's. It matters: the dir persists between runs of the same branch, and a stale PNG from an
earlier capture would otherwise upload into this PR's body.)

On older adb without `exec-out`: `adb -s <serial> shell screencap -p /sdcard/shot.png` then
`adb -s <serial> pull /sdcard/shot.png "$RUN/<slug>.png"`.

`<slug>` is a short kebab-case description of the UI state. 1080p PNGs are well under the 10 MB
attachment cap; if one ever exceeds it, downscale with `sips -Z 1600 in.png --out out.png`
(`sips` never upscales below target width, so point it at full captures only, not crops).

Verify the PNG is not all black — a black frame means the screen was off or the media had not
rendered yet; retry after `?action=screen` / `?action=next`.

## 4. Attach to the PR when composing the summary

`gh` uploads attachments natively and rewrites matching body references to the resulting asset
URL — the same canonical `github.com/user-attachments/assets/…` link the web UI's drag-and-drop
produces. Visibility inherits from the repository (in private repos GitHub serves them through
signed URLs), so no third-party hosting and no binary in git. Probe once per session:

```bash
gh pr create --help | grep -q -- --attach
```

Compose the body with an inline reference to the staged path, then create or update the PR:

```bash
# Body contains:
#   ## Screenshots
#
#   ![Options dialog with the new backend rows](.claude/pr-screenshots/<run>/options-dialog.png)
gh pr create --base main --title "…" --body-file "$BODY" \
  --attach ".claude/pr-screenshots/<run>/options-dialog.png#Options dialog with the new backend rows"
```

- Use the **exact same path string** in the body reference and the `--attach` argument; `gh`
  rewrites that reference to the uploaded asset URL. Unreferenced attachments are appended at
  the end of the body.
- Updating an existing PR: `gh pr edit <n> --body-file "$BODY" --attach …`, or
  `gh pr comment <n> --attach …` for follow-ups.
- Limits: 50 attachments per command, 10 MB per image.
- If `--attach` is missing (old `gh`), fall back to the user-attachments endpoint directly
  (repo id 686441236) and embed the returned `url` in the body:

  ```bash
  curl -sS -X POST \
    "https://uploads.github.com/user-attachments/assets?name=$(basename "$F")&content_type=image/png&repository_id=686441236" \
    -H "Authorization: Bearer $(gh auth token)" -H "Accept: application/json" \
    --data-binary @"$F" | jq -r .url
  ```

  That endpoint is undocumented and can change; use it only when the flag probe fails.

## When you cannot capture

If no device is attached and the AVD will not boot (or a state is genuinely unreachable), do
not ship a PR that implies a visual pass. Write an unchecked line in the body instead, e.g.:

```markdown
- [ ] Screenshot — not captured: no device attached, AVD failed to boot. Verified by review of
      the changed layout code and unit tests instead.
```
