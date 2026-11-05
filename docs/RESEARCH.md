# External research: hardware, platform, and upstream

Research log, 2026-11-05. Everything here came from outside this repository - vendor APKs and the EO2
filesystem dump we already hold, AOSP source, the original teardown writeups, and the public code the
Electric Objects organisation left behind. It is a research note, not a specification: every claim carries
the label of how well it is established, and the doc's value is the action list at the end rather than the
prose.

Labels used below:

- **[measured]** - read out of a file in this repo or a device by this session; the file or command is named.
- **[primary]** - read from a primary source (AOSP source, vendor binary, an official page) at the URL given.
- **[lead]** - a community claim or an inference that no primary source in this pass confirmed. Treat it as
  something to test on a frame, never as a fact to code against.

## 1. The two frames are not the same silicon, and the docs currently say they are

This is the finding that touches the most code. `EO1-specs.md` heads its software note "Geniatech / API 19"
and discusses EO1 and EO2 together; `AGENTS.md` says the native library "does not load on an EO1" because
of a `DT_NEEDED` on `libsystemwriteservice.so`, which "the EO1 firmware never shipped"; `docs/TSPLAYER.md`
then spends a section on getting TsPlayer onto an EO1. The teardown that link already points at says the
EO1 is not an Amlogic at all.

The EO1's processor, in the photograph this repo already carries as `_img/eo1-processor-fullwidth.jpg`, is
marked `MCIMX6D5EYM10AC` / `UHWM1411` / `KOREA`, which is a **Freescale (now NXP) i.MX6Dual**: dual-core
Cortex-A9 at 1 GHz, Vivante GC2000 GPU, and an integrated i.MX6 video processing unit. The teardown names
the same part number and the same 1 GHz dual-core description, and lists the rest of the board: an
LS Research TiWi-BLE Wi-Fi/BT module, a Kingston `EMMC04G-S100` (4 GB eMMC), four Hynix H5TQ2G63FFR chips
making 1 GB DDR3, an unpopulated Ethernet and microSD footprint, a real-time clock on the underside of the
board, a pair of SMD DIP switches, a "little green button", and a micro-USB OTG port. It also records that
plugging the frame into a computer enumerates as USB `18d1:4e42` ("Google Inc." / "Nexus 7 (debug)")
presenting MTP under the name **NITROGEN6X**, and that `adb devices` lists the frame as `unauthorized` -
adb is enabled on EO1 over USB, but the RSA confirmation dialog needs a tap the frame cannot give.
[primary] [https://www.5khz.com/2016-05-01/electric-objects-eo1-digital-art-display-teardown/] [measured: the die marking in `_img/eo1-processor-fullwidth.jpg`]

The EO2, read out of the dump in this repo, is Amlogic: [measured]

```
ro.product.model=EO2        ro.product.board=stvm8b   ro.board.platform=meson8   ro.hardware=amlogic
ro.build.fingerprint=MBX/stvm8b/stvm8b:4.4.2/V002M8B01_20160907/20160907:eng/test-keys
ro.build.tags=test-keys     ro.build.type=user        ro.build.version.sdk=19
service.adb.tcp.port=5555   #ro.adb.secure=1
```

Consequences worth acting on:

1. **TsPlayer can never load on an EO1, and the reason is bigger than the linker miss.** An i.MX6 ROM has
   no `/dev/amstream*` and no Amlogic media services, so `libTsPlayer-jni.so` has nothing to talk to even
   if `libsystemwriteservice.so` were supplied. The `DT_NEEDED` observation in `AGENTS.md` is consistent
   with that - it is the first symptom, not the cause. The "Getting TsPlayer onto an EO1" branch table in
   `docs/TSPLAYER.md` should be scoped to Amlogic frames, because acting on it for an EO1 spends a
   dangerous experiment (`System.load` of a client whose `getSystemWriteService()` polls forever under a
   global mutex on the main looper) to fix a frame that would still have no video plane. [measured: the
   prop file and the photo] [lead: that the EO1 ROM contains no Amlogic media stack - no EO1 filesystem
   dump exists in this repo, so this is inference from the silicon and needs one `ls /dev/amstream*` on a
   real EO1 to close.]
2. **The measured video ceilings are Amlogic numbers.** The 300 000 macroblocks/s and 15 Mbit/s gates were
   measured on EO2-class hardware (no datasheet for either figure turned up in this pass; they stay the
   repo's own measurements). The i.MX6Dual VPU is generally described as H.264 High Profile to Level 4.1
   at 1080p60, which would be a different shape of limit - so an EO1 that stutters at a clip an EO2 plays,
   or the reverse, is expected rather than a bug, and a single shared gate value is a compromise either
   way. That datasheet figure was recalled rather than read in this pass; confirm it at
   [IMX6DQCEC](https://www.nxp.com/docs/en/data-sheet/IMX6DQCEC.pdf) before quoting it anywhere. [lead]
3. **`HardwareCapabilities` keys on the model, not the silicon.** `HardwareCapabilitiesFactory.detect()`
   returns `Eo2Capabilities` when the device or model string contains "eo2" and `Eo1Capabilities` for
   everything else, which is right for buttons and wrong for video: it makes "EO1" mean "Amlogic minus a
   library" to every caller. A second axis - the platform (`ro.board.platform` / `Build.HARDWARE`, i.e.
   `meson8` versus `mx6q`/`mx6dl`) - would let `preferTsPlayer` and the routing table be stated per
   silicon while the button and sensor flags stay per model. This is also what makes a bug report
   interpretable: `diagnostic` JSON blocks and `/state` should print `ro.product.board`,
   `ro.board.platform` and `/proc/cpuinfo` (one line each) so the reader learns which family a frame is
   on without a teardown photo.
4. **There is a public source tree behind the EO1's firmware.** The NITROGEN6X name is not incidental: the
   teardown notes Boundary Devices' Android BSP for that board (`boundarydevices/imx-android-r13.4-ga`,
   still public, last pushed 2015) and that an Electric Objects employee forked it
   (`ksarantakos/imx-android-r13.4-ga`, confirmed live and a fork of the Boundary Devices repo). Whatever
   the EO1's `init.*.rc`, USB/adb configuration and kernel look like, that lineage is where to read them
   instead of guessing. [primary: GitHub API for both repos]

## 2. Things the EO2's own filesystem says that the docs have not absorbed

All of this is in `EO2/data/backup/` already; it is "new" only in the sense of not being written down.

| Evidence | Value, as read | Why it matters |
|---|---|---|
| `build.prop` | `service.adb.tcp.port=5555`, `#ro.adb.secure=1` commented out | The unauthenticated root adb the README warns about is a **shipped build property**, not an init script. It also says how an EO2 could be re-armed after a factory reset: set the property, restart adbd - which is exactly what `ensureTcpAdbd` already does. |
| `build.prop` | `ro.build.tags=test-keys`, fingerprint `MBX/stvm8b/…:eng/test-keys` | The ROM was signed with the AOSP **public** test key. [lead] If the platform key is the well-known test key, an APK signed with it could hold signature-level permissions (reboots, secure settings, package install) with no root - which would replace several workarounds the app carries today (`pm disable` of vendor pollers, the loopback-adbd install path, the opt-in reboot rung). One experiment decides it: sign the debug APK with `build/target/product/security/testkey` and see whether `INSTALL_PACKAGES`/`REBOOT` come up granted. Do not assume it; the vendor may have used a private key with public test tags on the rest of the image. |
| `build.prop` | `ro.screen.has.brightness=false`, `ro.screen.has.timeout=false`, `ro.platform.has.touch=false` | The platform's own statement of what the board's display controls can do. `/state` should print these three rather than letting `supportsScreenBrightness()` be the whole story: they are the reason an EO2 self-reports no brightness *control*, and they are readable with no privilege. |
| `build.prop` | `ro.platform.has.cecmode=false` | CEC is compiled out of this build, which explains why `cec_input` exists as a kernel input device with no AOSP `HdmiControlService` behind it. Mapping media keycodes in `EventManager` remains the only lever, and it needs that property's consent - so the repo's open CEC item is probably dead on this ROM without a prop change, and the investigation cost of finding that out is one `setprop`. |
| `build.prop` | `sys.screensaver.enable=true`, `persist.sys.hideStatusBar=true`, `persist.sys.usb.config=mtp,adb` | A ROM screensaver that is **on** by default is a thing that can paint over a frame's rotation. Worth one tick of `/state` capture plus one `setprop sys.screensaver.enable=false` experiment on a frame that shows a screensaver nobody configured. The USB line says an EO2 already carries adb in its USB function config. |
| `build.prop` | `media.amplayer.support-exname=divx,h264,avi,ts,m2ts,mkv,mp4,mpg,mpeg,rm,rmvb,wmv,ts,dat,vob,vc1` and `…support-exname1=mp2,mp3,ac3` | The Amlogic player advertises containers this app's accepted-extension table does not (VC-1, WMV3, DivX, RM/RMVB, VOB, m2ts). The app's own rule is "the extension is not a verdict", so this is not a request to widen the allowlist; it is a hint that a clip the framework refuses may be playable through the vendor engine, if any future routing work wants a rung beyond MediaPlayer. [measured] |
| `system/etc/media_codecs.xml` | decoders advertised: `avc`, `h263`, `mjpeg`, `mpeg2`, `mpeg4`, `vc1`, `wmv3` (+ `avc` encoder). **No HEVC/H.265 entry.** | Corroborates the "HEVC never decodes" rule from the ROM's own codec advertisement rather than only from measurement. |
| file listing | `libopenHEVC.so`, `libstagefright_soft_amh265dec.so`, `/sys/bus/platform/drivers/amvdec_h265`, `/sys/devices/virtual/amstream-dev/amstream_hevc` all exist | The H.265 pieces are *present* but unadvertised - a generic Amlogic filesystem, not an enabled path. Soft H.265 on this CPU would be far below realtime, so the rule holds. One `lsmod` tells whether the module is even loaded. [measured: presence; lead: everything about it being usable] |
| `cache/recovery/last_log` | references `fw_setenv`, `/dev/block/env`, `wipe_data`, and update cache under `http://www.geniatech.net/down-eng/upgrade/stvm8b_4.4/` | Recovery can rewrite the bootloader environment, and the vendor's own OTA image path was `stvm8b_4.4` - the Amlogic **reference** image name for this board, which is what a stock image for an unbrick would be sought under. That host no longer resolves. [measured] |
| file listing | `/system/lib/libTsPlayer-jni.so`, `/system/lib/libxwalkcore.so`, `/system/lib/libxwalkdummy.so` | Confirms the repo's claim that the `.so` this app ships by LFS is the same one in the EO2 ROM, and shows Crosswalk in the **system** partition, not only the app's. |
| file listing | `/system/bin/remotecfg`, `/system/etc/remote.conf`, `/system/app/RemoteIME.apk`, `/system/priv-app/IpRemote.apk` | The ROM carries an IR/remote configuration tool and a remote-driven input method. `remotecfg` with `remote.conf` is how a vendor maps a physical remote to keycodes on Amlogic Android - a candidate for giving an EO2 real buttons, and it emits ordinary key events, so `EventManager` would need no new plumbing. Cost of checking: `adb shell remotecfg --help`. [measured: presence] |
| `system/etc/security/cacerts` | 156 files | Matches the "156 anchors measured on a live EO2" claim in `docs/TRUST.md` against the dump rather than the device. [measured] |

## 3. Log capture is UID-filtered on this platform, which changes what `/crashes` can hold

Android 4.4's log transport is a kernel logger on `/dev/log/*`, and AOSP states the rule in the device
node's own configuration: "Anyone can read the logs, but if they're not in the 'logs' group, then they'll
only see log entries for their UID." [primary: `ueventd.rc`, aosp-mirror/platform_system_core, tag
android-4.4.4_r1 - the comment is above `/dev/log/* 0666 root log`] The group membership comes from
`platform.xml`, which assigns gid `log` to `READ_LOGS`.

`app/src/main/AndroidManifest.xml:26` **already declares** `READ_LOGS`. Declaring it is not having it: on a
sideloaded package a `signature|system|development` permission is not granted at install. So the
`logcat -d -b system` scrape `CrashSources` spawns runs as this app's UID and, unless the permission is
actually granted, sees only this app's own lines - the system buffer's `system_server`, ANR and watchdog
text is filtered out by the kernel before the binary ever runs. Every hardened detail of that drain (read
to EOF, bounded bytes, one owner of `destroy()`) is correct and can still be harvesting almost nothing.

Two checks make this concrete on a frame, both one-liners: [lead: that the grant survives; the filtering
rule itself is [primary] above]

```
adb shell dumpsys package com.aphex3k.eo1 | grep -A4 'requested permissions'   # granted=true?
adb shell pm grant com.aphex3k.eo1 android.permission.READ_LOGS                 # then re-scrape, compare
```

`READ_LOGS` carries the *development* flag on this platform - `protectionLevel="signature|system|development"`
at line 1746 of AOSP 4.4.4's `core/res/AndroidManifest.xml` [primary] - which is the class of permission
`pm grant` is allowed to change. A development permission is also preserved across a reinstall when it was
already granted, which is what would make the fix survive this app's own `pm install -r` self-update path;
that reinstall behaviour was read from `PackageManagerService` by a research pass and not re-verified
here, so confirm it on hardware before documenting it [lead]. Either way `/state` should carry the answer:
a `logs.read_granted` boolean turns "no crash in the buffer" from a wrong reassurance into an honest one.

Related: on 4.4 there is no way to widen the log window from a shell. The research pass read a KitKat
`logcat` source whose option string contains `-g` (report size) but **not** `-G` (set size, added in 6.0),
and buffer sizes fixed in the logger driver's init at 256 KB per buffer. [lead - from source read, not
re-fetched by this session; confirm with `logcat -G 16M` and `logcat -g` on the frame, both instant.] If
that holds, `README`'s "~13 minutes of logcat" is not a tuning problem but a hard platform limit, and the
only long-window capture is the file-rotation form the same binary does support:
`logcat -f <file> -r <kbytes> -n <count>` from a root shell - worth a paragraph in
`docs/WEBSERVER.md#logs--past-state` next to the durable log.

## 4. What the vendor's own app already solved, in its own words

Strings and class names read out of `.electric-objects/apk/com.electricobjects.app-1.apk` (in this repo)
plus the files it left on the EO2: [measured: presence and names; interpretation is this session's]

- `WifiHelper$WifiJiggler`, `ACTION_JIGGLE_WIFI`, `WAIT_FOR_JIGGLE_TIMEOUT` - the vendor's Wi-Fi repair was
  a timer-driven jiggle, i.e. the same shape as the app's `DeviceRepair` network rung. A useful precedent
  when the current rung under-repairs: the vendor's timeout constant is readable from the dex.
- `StaticArtActivity`, `GifArtActivity`, `VideoArtActivity`, `WebArtActivity` (with a `USE_IFRAME` flag) -
  the vendor's art-type model is four engines chosen by *artwork kind*, not one rotation. This app's
  per-backend pool merged into one shuffled pass is a different design, and the four-way split is the
  reference for how the original behaved on this hardware.
- `libxwalkcore.so` (Crosswalk, ~37 MB, armeabi-v7a) with `assets/xwalk-command-line` =
  `xwalk --ignore-gpu-blacklist --force-device-scale-factor=1.0` - the vendor rendered web art on a
  bundled modern Chromium rather than the ROM's Chromium 33. That matters for this app's `web` backend,
  whose honest limitation is "Android 4.4's Chromium 33 … the shipped root bundle does not reach this
  browser, because Chromium validates inside its own engine". A bundled Crosswalk would bring its own
  newer engine *and its own CA bundle*, which is the one way to make an `https://` page with a 2026
  certificate render on a frame. The cost is 37 MB of the ~800 MB budget and a second browser to keep
  patched, so it is an option to weigh, not a fix to apply. [measured: presence in the dump]
- `libpl_droidsonroids_gif.so` and the `electricobjects` GitHub org's `android-gif-drawable` fork - the
  vendor decoded animated GIFs with a maintained native library instead of the framework `Movie` class.
  If this app's GIF handling is single-frame on either frame, that library is the known-good answer for
  API 19.
- `/sys/class/saradc/saradc_ch1` and `/sys/class/aml_keys` read directly - **the vendor's light sensor was
  an SAR ADC sysfs channel, not a `SensorManager` device.** That is exactly why `dumpsys sensorservice` on
  the EO2 shows an empty list: there was never a HAL. It also means an EO1's ambient-light path may be
  readable at a sysfs node that `BrightnessManager` never looks at, and that a frame whose sensor shows as
  absent might only be missing a driver binding. One `ls /sys/class/saradc/` on each frame settles it, and
  on the EO2 the node is not in the dump's file list at all - consistent with "no sensor, not an
  unregistered one". [measured: names in the APK; `files.txt` for EO2]

### Connected Art: a URL contract nobody has reimplemented

`electricobjects/eo-js` (the organisation's own, now archived, public) is nine lines of JavaScript and
reads cleanly. [primary: https://raw.githubusercontent.com/electricobjects/eo-js/master/electricobjects.js]
The cloud appended `?eo_device_token=<JWT>` to any URL it pointed a frame at; the page read that parameter
and POSTed `{token: …}` to `https://art-api.electricobjects.com/devices`, receiving `{id, name, latitude,
longitude, release_name, timezone}`; with no token in the URL it fell back to `/devices/sample`. That is
the whole of the "Connected Art" API: **a device-info endpoint plus a token in the query string.**

Why it is worth anything here: generative art written for an EO calls that function to decide what to
paint, so any such page currently loads on this app's `web` backend and dies on its fetch. Serving the
same shape locally - accept an `eo_device_token` query parameter, answer `POST /devices` with the frame's
own name, timezone and (optionally configured) lat/long, and hand out a sample document when no token is
present - would make an unknown body of 2015-era EO art work unmodified on a frame that has no cloud. It
is a compatibility feature with no upstream competition, it fits the LAN server's existing dependency-free
style, and the device name and time zone it needs are already stored.

## 5. Upstream services as of today

- **Immich.** The latest release is **v3.3.6, published 2026-11-04** on the `release/v3.3` branch, a
  one-line ML/telemetry bug fix ([release notes](https://github.com/immich-app/immich/releases/tag/v3.3.6)).
  The repo's band ("all of 3.x", verified against the 3.3.3 OpenAPI spec) is one patch behind the current
  release and needs no change: nothing in the 3.3 line moved an endpoint this app calls. No 4.x has been
  released as of this writing. [primary: GitHub releases API]
- **Root program.** The shipped bundle is healthy: 120 roots, none expired, **ISRG Root X1 and X2 present**
  (so Let's Encrypt chains validate app-side regardless of the 2013 ROM store), and **GlobalSign Root R46
  and E46 present alongside R3**, so the R3→R46 migration is already covered. Reading every PEM's
  `notAfter` rather than the manifest column gives the real horizon: the earliest expiry is
  **2028-12-06** (`netlock-arany-class-gold-f-tan-s-tv-ny`), then **GlobalSign Root CA - R3 on
  2029-03-18**, and eight anchors in all expire before 2030. A periodic `update-roots.sh` run remains the
  right cadence; nothing in it is time-critical inside the next two years.
  [measured: `openssl x509 -enddate` on all 120 files in `certs/roots/`]
- **No Entrust, Inc. anchor is in the bundle**, so a host whose certificate chains to an Entrust root is
  refused by these frames by construction. Note the grep trap when re-checking this: `identrust-*.pem`
  matches a case-insensitive search for "entrust", and **IdenTrust is a different company** - the two
  IdenTrust roots are present and are not what this bullet is about. Entrust's public trust is being
  withdrawn through 2025-2026 in any case, so the practical consequence is only that a "TLS gate: anchors"
  failure on an older enterprise host is an absent root rather than a broken one, and one line in
  `docs/TRUST.md`'s four-gates section would stop that being guessed at.
  [measured: absence of any `entrust-*.pem` from the bundle; the public distrust timeline was **not**
  confirmed by a primary source in this pass, so treat the date half of that sentence as [lead].]

## 6. Gaps this pass did not close

Reporting these as gaps rather than guesses matters, because two of the four research packets this run
wanted to delegate were blocked, and web search returned no results at all in this session - so the items
below are *question lists with named sources to check*, not findings.

1. **Whether a bundled Conscrypt provider gives this app TLS 1.2/1.3 on API 19 without Google Play
   Services.** If some `conscrypt-android` release still installs on KitKat, it would replace a good part
   of `MergedTrust`/`RootBundle` machinery with a provider that has its own anchor store and current
   cipher suites, and it would reach the places reflection into the framework provider cannot. The check
   is mechanical and needs no device: download two or three `conscrypt-android` AARs from Maven Central and
   read `minSdkVersion` out of each bundled `AndroidManifest.xml`, then look for a TLS 1.3 statement in the
   README. Until that is read from the artifacts, nothing here should be changed. Note for the ceiling
   table in `AGENTS.md`: `org.conscrypt:*` is not currently in it, so if it is ever added, its own
   last-KitKat release needs hand-ignoring like every other coordinate.
2. **Toolchain headroom.** Whether anything newer than AGP 8.7.3 / Gradle 8.9 still accepts `minSdk 19`,
   and whether `java.time` desugaring actually runs on API 19 (which would let the hand-rolled
   `CallSupplier`-style shims go away) - both are answerable from release notes and the D8 documentation,
   and neither was established in this pass.
3. **Testing an API 19 build on Apple Silicon.** The repo already documents the API-19-AVD failure and the
   API-21 arm64 fallback. What this pass could not establish is whether anyone has a better answer (a cheap
   real KitKat device driven over adb, an ARM board with the same ABI, Robolectric configured for SDK 19).
   Given finding 1 above, a real EO1 is worth more than it currently looks like: it is the only way to see
   the i.MX6 behaviour the docs currently do not model.
4. **CI.** The repo notes that nothing compiles a release on a PR. Whether `ubuntu-latest` still carries a
   usable JDK + SDK combo for an AGP 8.7.3 API-19 build with LFS objects was not established.
5. **No EO1 filesystem dump exists in this repository.** Every EO1 ROM statement in `AGENTS.md` and
   `docs/TSPLAYER.md` - the missing `libsystemwriteservice.so`, the `clientPath`, the absence of a light
   sensor HAL - is therefore carried on a single frame's behaviour plus inference. `adb pull /system` plus
   `getprop > /tmp/eo1-getprop.txt` from one EO1, committed as a directory alongside `EO2/`, would convert
   the most consequential cluster of open questions in the docs into reads. With the OTG-mouse trick in
   §1 that session is reachable: plug the frame into a host, attach a mouse on the OTG port, and accept the
   adb authorization dialog when it appears on the panel.

## 7. Action list, cheapest payoff first

| # | Action | Cost | What it buys |
|---|---|---|---|
| 1 | On each frame: `getprop ro.product.board ro.board.platform`, `cat /proc/cpuinfo`, `ls /dev/amstream*`, `ls /sys/class/backlight /sys/class/saradc`, `logcat -g`, `lsmod \| grep -i h265`, `dumpsys package com.aphex3k.eo1 \| grep -A4 requested` | one adb session | Converts §1-§3's [lead] rows into [measured] ones, and closes the CEC/backlight/sensor questions for free |
| 2 | Print `ro.product.board`, `ro.board.platform` and the `READ_LOGS` grant state in `/state` and in the diagnostics report | one small PR | Bug reports stop being ambiguous about silicon, and "no crash in the buffer" becomes an honest answer |
| 3 | Grant `READ_LOGS` once per frame, then re-scrape and compare what `logcat -b system` yields | five minutes | Either the crash capture starts carrying system/ANR text, or the durable log's importance is proven and documented |
| 4 | Split `HardwareCapabilities` into a model axis (buttons, sensor) and a silicon axis (`meson8` vs i.MX6) that decides `preferTsPlayer` and the routing gates | a contained refactor | Stops an EO1 being documented and coded as "an EO2 with a missing library" |
| 5 | Add a Connected Art shim: `?eo_device_token` accepted, `POST /devices` answered from the stored device name and time zone | small, additive, LAN-only | A body of original EO art runs on the `web` backend that otherwise cannot run anywhere |
| 6 | Try signing with the AOSP `testkey` and see whether signature-level permissions arrive | one experiment, EO2 first | If yes: headless installs, silenced vendor pollers and the reboot rung stop needing the adbd loopback path. If no, that option is closed and can be written down as closed |
| 7 | Scope the "ship `libsystemwriteservice.so` to an EO1" recipe to Amlogic frames, or retire it | doc edit | Stops the next reader spending a frame-freezing experiment on a frame with no Amlogic plane |
| 8 | One line in `docs/TRUST.md` on Entrust anchors being absent, and one on the bundle's own expiry horizon (first anchor goes 2028-12-06, GlobalSign R3 on 2029-03-18) | doc edit | Two support guesses become reads |

## Sources

- [Electric Objects EO1 Digital Art Display Teardown - John Ehringer, 5khz.com (2016-05-01)](https://www.5khz.com/2016-05-01/electric-objects-eo1-digital-art-display-teardown/)
- [eo-js (Connected Art client library), electricobjects GitHub org](https://github.com/electricobjects/eo-js) -
  [the source file read](https://raw.githubusercontent.com/electricobjects/eo-js/master/electricobjects.js)
- [AOSP `ueventd.rc`, android-4.4.4_r1](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/android-4.4.4_r1/rootdir/ueventd.rc)
- [boundarydevices/imx-android-r13.4-ga](https://github.com/boundarydevices/imx-android-r13.4-ga) and
  [the Electric Objects employee's fork](https://github.com/ksarantakos/imx-android-r13.4-ga)
- [Immich v3.3.6 release notes (2026-11-04)](https://github.com/immich-app/immich/releases/tag/v3.3.6)
- Local evidence, in this repository: `EO2/data/backup/system/build.prop`,
  `EO2/data/backup/system/etc/media_codecs.xml`, `EO2/data/backup/cache/recovery/last_log`,
  `EO2/data/files.txt`, `EO2/data/backup/system/etc/security/cacerts`,
  `.electric-objects/apk/com.electricobjects.app-1.apk`, `certs/roots/MANIFEST.tsv`,
  `_img/eo1-processor-fullwidth.jpg`, `app/src/main/AndroidManifest.xml`
- Community claims carried as [lead] only: the same `stvm8b` board string appearing on a rebranded
  MyGica/Geniatech ATV 585 (Armbian forum), which is what suggests an Amlogic USB-Burning-Tool image could
  revive a bricked EO2. Not confirmed by a primary source; `althafvly/AmlogicKitchen` unpacks such images.
