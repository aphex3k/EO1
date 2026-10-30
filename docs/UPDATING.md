# Updating a frame

The frame can check for, download, verify and install a new APK by itself — no adb, no OTG
peripheral, no touch screen. Updates are forward-only (the manifest's `versionCode` must be
strictly greater than the installed one) and nothing is installed before every verification gate
passes.

Self-update is **off until you turn it on**: an empty `updateManifestUrl` means the frame never
checks. A debug build never auto-checks at all, which is why a bench frame appears to ignore its own
manifest. On a release frame the poll is driven off the app's rotation timer and gated by
`updateCheckIntervalMinutes`, so a frame whose activity is paused — quiet hours, a screen you turned
off — is not checking either.

## Configuration

Two fields in `configuration.json` (also in the options dialog, Media tab, and in the web page's
config editor once *Trusted Network* is on):

- `updateManifestUrl` — plain-HTTP(S) URL of a small JSON manifest. Empty (the default)
  disables self-update entirely.
- `updateCheckIntervalMinutes` — poll interval in minutes (default 120, clamped to [5, 1440]).

The manifest (exactly the fields `publish-update.sh` writes):

```json
{
  "versionCode": 129,
  "versionName": "2.0.1",
  "apkUrl": "http://your-host/update-out/eo1-release-129.apk",
  "sha256": "<64 hex chars>",
  "sizeBytes": 12345678,
  "notes": "EO1 2.0.1"
}
```

## Verification chain

1. **Manifest gate** — `apkUrl` must be http(s), `sha256` 64 hex chars, `sizeBytes` positive
   and ≤ 100 MB, `versionCode` strictly greater than the installed one.
2. **SHA-256** — the downloaded bytes are hashed in a 32 KB streaming loop and must match the
   manifest before anything else is checked.
3. **JAR signature** — `META-INF/MANIFEST.MF` + `.SF` + `.RSA` are verified in pure Java
   (`com.aphex3k.update.ApkSignatureVerifier`): per-entry digests, the whole-manifest digest
   and the PKCS#7 signer signature.
4. **Signer-certificate match** — the leaf certificate extracted from the JAR signature must
   byte-match the certificate of the *installed* APK, i.e. the update must be signed with the
   same keystore as the current install. (An unsigned installed app has no certificate to
   match against; updates then fail with `no-installed-cert`.)

Only after all four gates does the APK get staged (private master in `filesDir/updates/` plus a
world-readable copy at `/sdcard/Download/eo1-update.apk`, because the installer runs under a
different uid) and then installed.

The same four gates run on the other way in — the page's APK upload form, `POST /update`, hashes the
file you sent, prints its `sha256`, verifies it against the installed certificate and **stages it
without ever installing it**. That is what makes a frame updatable with no manifest server anywhere:
serve nothing, upload the APK from your laptop, press *Install on the frame*. The route is itself
token-gated, so it exists only on a frame that has had *Trusted Network* on once.

## How the install runs

Install is a ladder, and a frame walks down it only as far as it can do the work *alone*:

- When the frame holds `INSTALL_PACKAGES`, the APK is installed headlessly via
  `PackageManager.installPackage`. On this ROM the permission is `signature|system`, so
  sideloaded frames never hold it — for them the app instead runs `pm install -r` as the
  shell user through the device's own adbd on `127.0.0.1:5555` (`AdbdInstallClient` speaks
  the minimal adb wire protocol; no confirmation UI).
- **The EO1 ROM never configures that adbd** (`service.adb.tcp.port` is unset, so a connect gives
  `ECONNREFUSED`), so on that board a silent install needs the frame to start adbd itself — and it
  does that **one action at a time, and only if you say so twice**: *Trusted Network* **and** *Allow
  loopback ADB for update installs* in the options dialog, both default off
  (`SettingsManager.isLoopbackAdbEnabled()`). With the pair on, `AdbdInstallClient.ensureTcpAdbd()`
  writes the property and restarts the service (at most once per 10 minutes); with either off the app
  touches nothing and falls to the next rung.
  ⚠️ Because there is no loopback-only mode, an armed EO1 answers `adb` from anywhere it can route
  to, **as root** — a wider door than the frame's unsecured web server, which is why it is opt-in and
  why the window is one action rather than one boot: `teardownTcpAdbd()` writes
  `service.adb.tcp.port=-1` and restarts adbd in a `finally` the moment that action ends (and if an
  install's own success kills the process first, the note left in the state file makes the next
  launch close it). An adbd the ROM started on its own, as on EO2, is never touched — closing a
  daemon you did not open would only interrupt it. Restarting adbd drops any host `adb` session
  attached over USB.

  **On an EO2 the opt-in is moot.** That ROM listens on TCP 5555 with `ro.adb.secure` unset from
  boot, so root-over-LAN is the board's factory state and no checkbox opened it; `ensureTcpAdbd()`
  finds the daemon already listening and changes nothing. The flag decides whether *this app* may
  start a daemon on a board that has one off — it does not decide whether the frame is reachable. If
  you own an EO2 on a network you do not control, the mitigation is the network, not the dialog.
- **It is never started to be ready.** Two places may ask for it: an install while an update is
  staged, and a watchdog rung the ladder has actually reached (Wi-Fi repair, `reboot`). Nothing is
  opened at boot — the launch-time vendor-OTA `pm disable` only *probes*, and logs a skip when
  nothing is listening, so flipping the flag does not silently widen what runs unattended.
- If neither silent transport works, **an automatic cycle puts nothing on screen.** A frame is
  mounted on a wall and dark through quiet hours; a confirmation dialog shown to nobody is not
  an install, it just looks like one for days. The update stays `STAGED` and retryable with
  `needsHumanConfirmation: true`, and the web page says so. Pressing **Install on the frame**
  is the explicit opt-in that shows the dialog (waking the panel, and no longer interrupted by
  a relaunch alarm), for a frame you can reach with an OTG keyboard or mouse.
- Before firing a *silent* install, a +120 s `AlarmManager` relaunch alarm (the same one the
  crash handler uses) is armed, so a fatal crash mid-install still gets the frame back to a
  launchable state.
- The installer kills this process on success, so success is detected by the *new* process on
  startup: the persisted expected `versionCode` is compared against
  `BuildConfig.VERSION_CODE` (`reconcileOnStartup`). A stuck or refused install rolls back to
  the retryable `STAGED` state and is retried on the next timer tick — so the retry cadence is
  your `interval` setting, and it pauses with the activity. `INSTALL_PENDING` carries the
  persisted time it was fired, so a pending install can settle in a process that never started it.
- **A staged build is not the last word.** While the host's build sits staged, the ordinary check
  interval still re-reads the manifest, and if the host now publishes a higher `versionCode` — or the
  same code built from different bytes — that published build is fetched and put through all four
  gates above, and only *then* replaces what is on disk. So a frame you left waiting on a click
  converges on the release you shipped afterwards instead of retrying the one you withdrew. A stage
  the host did not produce is never touched by this: an APK you uploaded through `POST /update` (and
  anything staged before `stagedSource` existed, which the app cannot attribute) stays exactly where
  you put it until you install it or reset it. If the re-download fails, the old stage survives —
  the state goes back to `STAGED`, not `FAILED`, so the retry keeps running — with the failure in
  `lastError` and `/logs` naming which gate or which wire it died on. Two things make that survive a
  crash rather than only a clean failure: the stage's own identity (`expectedVersionCode`,
  `stagedSha256`, `stagedSource`) is left alone while a replacement is in flight and only promoted
  when the swap commits, so a frame killed mid-download still knows which build its bytes are and
  reclaims them at the next launch; and an install that finds a sound private master behind a public
  copy it cannot re-make stays `STAGED` (`public-copy-failed`) instead of deleting the good APK over
  a defect in its copy — a full or unmounted `/sdcard` is a condition that clears, and `FAILED`
  would have stopped the retry that outlives it.

### The choice that removes every caveat above

An APK placed in `/system/app` is platform-signed, so it holds `INSTALL_PACKAGES`: silent installs,
no adbd, no opt-ins, nothing to arm. It also holds `READ_LOGS`, which is the difference between a
native player death being caught and it being invisible. That route needs a root shell once, and it is
the only install mode in which a frame truly updates itself unattended.

## Recovery (headless, via the LAN web server)

| Symptom | Action |
|---|---|
| Need to see where an update stands | `GET /state` → `update` block: `state`, `expectedVersionCode`, `lastError`, `attempts`, `stagedSource` (`manifest`/`upload`/empty — who owns the stage), `installMode` (`headless`/`adbd`/`defer-to-human`), `lastInstallAttempt` (`none`/`headless`/`adbd`/`intent`), `adbdReachable`, `loopbackAdbAllowed`, `tcpAdbArmedByApp`, `needsHumanConfirmation`, `installFiredAtMs` |
| Staged but not installed | `/logs` says which transport failed. If `needsHumanConfirmation` is set, no silent install is possible on that frame: `/control?action=install-staged` (turns the screen on and shows the confirmation dialog there) |
| Stuck / looping on a bad APK | `/control?action=update-reset` (deletes staged files and state) |
| Something staged, but you have shipped since | Nothing to do: the next check re-reads the manifest and replaces the stage once the new build verifies (`stagedSource: "manifest"` in `/state` is what makes that true). To do it now, `/control?action=check-updates`. If `stagedSource` is `upload` or empty, the frame will not displace it — press **Update reset** first |
| Force a re-check now | `/control?action=check-updates` |
| Nothing ever arrives, and `lastError` says `manifest-fetch` or `download-failed` with an `SSLHandshakeException` | If the update host is HTTPS, this is a TLS gate, not a missing release: `lastError` carries a `[TLS gate: NAME]` note naming it. An empty note means the failure was never about certificates — the host is unreachable, not untrusted. See [docs/TRUST.md](TRUST.md) |

The index page exposes **Check updates** / **Install on the frame** / **Update reset** buttons, and
prints a standing notice there when an update is staged but waiting on a person.
Recovery is forward-fix only: a failed update leaves the last good version running; the app
never downgrades or uninstalls itself.

## Publishing a new version (operator side)

All builds are local — the old Jenkins/Gitea pipeline no longer exists. Signing uses the
release keystore (alias `EO1`, the same one the old CI used) via the gitignored root
`local.properties`:

```properties
eo1.signing.store.file=/path/to/eo1-release.keystore
eo1.signing.store.password=...
eo1.signing.key.alias=EO1
eo1.signing.key.password=...
```

Then publish:

```bash
EO1_UPDATE_HOST=http://your-host/eo1 ./publish-update.sh 129 2.0.1
```

The script enforces a monotonic `versionCode` (strictly greater than the last published one),
builds with `assembleRelease`, hashes with `shasum -a 256`, writes `update-manifest.json` and stages
all three files in `./update-out` (optionally rsync'd to `EO1_UPDATE_REMOTE`): the APK, the manifest,
and `mapping-<versionCode>.txt.gz` — the build's R8 mapping, archived so a crash report from that
version can still be decoded years later ([docs/WEBSERVER.md](WEBSERVER.md#crash-dumps)). Serve
that directory from any plain-HTTP server the frames can reach and point `updateManifestUrl` at
`…/update-manifest.json`.

> The mapping is **not** listed in the manifest and no frame ever downloads it; it is only ever read
> by a person holding a crash dump. Publishing fails if the build produced no mapping — if you
> deliberately turned minification off, say so with `EO1_UPDATE_MAPPING_OPTIONAL=1`.

> The update host is reached over plain HTTP, so keep it on a trusted LAN. The frames' own HTTPS is a
> separate matter: the ROM's certificate store dates from about 2013 (156 anchors, 63 of them expired,
> none of them Let's Encrypt's), and the app now adds current roots to it for its own connections —
> see [docs/TRUST.md](TRUST.md). Neither fact weakens the other. A LAN attacker who can rewrite
> the manifest or APK is still stopped by the signer-certificate match — a new APK must be signed with
> the release keystore.

## Reading the crash after an update

Release builds are R8-minified, so a release stack trace is unreadable without that build's
`mapping.txt`, which `publish-update.sh` archives beside the APK and which no frame ever downloads.
Every crash report carries the `versionCode` that produced it, in both the file name and the header,
and the mapping is kept 1:1 with the code because the publish script refuses a non-increasing
`versionCode`. If you disable minification you must still publish, or set
`EO1_UPDATE_MAPPING_OPTIONAL=1` so the missing mapping is loud rather than silent.

## See also

- [README: Living with it](../README.md#living-with-it) — what the frame does when it wedges itself
- [docs/WEBSERVER.md](WEBSERVER.md) — every route the recovery table above uses
- [docs/TRUST.md](TRUST.md) — why an HTTPS update host was never the hard part
