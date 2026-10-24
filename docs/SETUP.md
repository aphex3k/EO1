# Installing the APK

This is the one-time part of getting a frame running: putting the app on the board and getting it
to take the screen. Everything after it — pictures, interval, quiet hours — needs only a browser;
see [the README](../README.md#getting-pictures-on-it-with-no-server).

Which section you need is decided by the board in your hands, and the two are genuinely different:
an EO1 has one data port and no wireless adb, so the APK goes in through the ROM's own browser with
a keyboard and mouse attached; an EO2 ships with debugging on, so it goes in over the network with
`adb`. [Which frame is this?](../README.md#which-frame-is-this) resolves the difference in one
table if you are not sure what you own.

There is no CI and no release artifact to download. All builds are local:
[`publish-update.sh`](../publish-update.sh) builds and signs a release APK and writes a matching
`update-manifest.json` to `./update-out`, and it is that directory you serve over plain HTTP on
your LAN. Example: <http://your-host/update-out/eo1-release-1.apk>

## Requirements

- A frame joined to your Wi-Fi. Joining it is done in the ROM's own settings UI, and an OTG keyboard
  makes that much easier on an EO1; the panel on either board is **not** a touchscreen.
- The APK itself, reachable over HTTP from the frame (EO1) or a machine with `adb` (EO2).
- A keyboard, once, on one board — for the step that turns on local uploads. It is a one-time
  bootstrap on the frame's own screen, not a requirement of using the frame; see
  [the seam, stated plainly](#the-one-step-that-needs-hands).
- (optionally) an Immich server — only if you want that source. Nothing here needs one.
- (optionally) a web server hosting the APK for download onto the frames.

### The one step that needs hands

A frame's files only rotate once its configuration holds a backend entry of type `local`. Uploading
a file does not write that entry, and nothing else writes it either — so on a frame without one, an
upload sits safely on disk, lists normally in the page's Files table, and never reaches the screen.

Two ways to write it:

- Press **"+ Add Local uploads"** in the options dialog's Media tab, then **Save**. This is the
  entire configuration of a serverless frame: no server, no username, no password, no token.
- Hand-write a one-entry [configuration document](#writing-configurationjson-by-hand) and push it.
  On an EO2 that is the only keyboard-free route.

The options dialog can also be raised from any browser on the LAN with
`http://<frame>:8080/control?action=config`, which is how you get the dialog onto the frame's screen
without a keyboard — but operating it still needs a pointer, and neither frame has one.

### Caveats

EO1 has only one data port, thus OTG and adb (via USB) cannot be used at the same time.
Unfortunately the Android 4.4.2 running on EO1 does not support wireless adb connection. That is the
reason why the APK has to be sideloaded via the built-in browser.

The old Android 4.4.2 installed on EO1 also supports a limited set of TLS encryption next to a swath
of outdated SSL stuff. What that means for connecting to a server is covered in
[docs/TRUST.md](TRUST.md); a frame whose only source is its own uploads consults no certificate at
all, and needs none of it.

## Setup for EO1

- Connect the mouse & keyboard to your EO1 via the mentioned OTG method
- Connect the EO1 to the power adapter and let it boot
- When your EO1 finishes the boot animation and it hangs on the "Getting Art" dialog, hit
  **\[WINDOWS + B\]** on the keyboard to open a web browser
- You need to tell your EO1 to allow side-loading.
  1. Swipe down on the top right and go to **Settings > Security**
  2. Make sure "Unknown Sources" is checked
- Go back to the browser and go to the URL where you host the APK (see
  [Installing the APK](#installing-the-apk) above).
- When it finished downloading, install the file by pulling down the notification bar on the top
  left and clicking it, then agreeing to the install prompts. That's the one-time bootstrap — every
  later self-update then runs fully headless (no further manual steps; see
  [How the install runs](UPDATING.md#how-the-install-runs))
- Restart/power cycle your EO1 by unplugging and plugging only the power cable
  - At this point, the keyboard and mouse are still connected via OTG
- Because this APK is designated as a "Home screen replacement", when EO1 boots, it will ask if you
  want to load the Electric Object app or the EO1 app. Select EO1 and choose "Always".
- The first time the EO1 runs with nothing usable configured, the options dialog opens on the
  **Media** tab by itself — nothing to press or type. From there, either press **"+ Add Local
  uploads"** (and, if you want it, tick *Trusted Network* while you are there: the same screen then
  prints the frame's Wi-Fi address and its configuration token) and **Save**, or add an Immich
  backend:
  - Server: the same server URL you use to log in on the web-app or the native apps on iOS or Android
    - If you connect to immich in your browser via `http://immich.local`, that is what you want to use here
    - If you run immich on a different port than `80`, include it in the server string, like `http://immich.local:8080`
  - Username: the same email address you would use to log in on the web-app or the native apps on iOS or Android
    - Depending on how you set up your immich installation this is your own user or a shared user account dedicated for use with the frames
  - Password: same as above
    - Keep in mind that this password is stored in clear-text on the frame. Everyone with access to your device can recover the password!
  - API version: leave blank for `auto` (the frame probes the server's version on first contact) or pin a version like `3.1.0` — see [Multiple media backends](../README.md#multiple-media-backends)
  - Pointing the frame at more than one server? Click **"+ Add Immich backend"** again for each additional host. **"+ Add Local uploads"** is what turns on the frame's own files.
- Press **Save** at the bottom of the dialog. **To get back to the configuration screen later, press C on your connected keyboard** — or raise the same dialog from a browser, as above.
- You can now unplug your mouse and keyboard and hang your EO1 back on the wall!

## Setup for EO2

Your EO2 most likely has android debugging enabled - which (while insecure) makes it much easier to
adjust settings remotely. Note what that costs: the ROM listens for adb on TCP port 5555 with
`ro.adb.secure` unset, from boot, which means the frame is reachable **as root** by anything that can
route to it. That is the board's factory state, not something this app turns on. If the frame is in a
shared or public space, put it behind a network you control.

This guide assumes your EO2 already has an IP address on your network that is known to you (likely a
DHCP reservation). If your EO2 has been reset or isn't connected to your network,
[this reddit post](https://www.reddit.com/r/electricobjects/comments/1amitpa/howto_video_for_resurrecting_eo2/)
has instructions on getting it online.

- Connect the EO2 to the power adapter and let it boot. It should stop at "Connecting to the internet..."
- Download and install [adb (Android Debug Bridge)](https://developer.android.com/tools/adb)
  1. I was never successful getting the adb command to work on my Win10 machine - the connection always failed. However it worked from both Windows Subsystem Linux on that machine and my Linux server, so my guess is there's an issue with Windows adb.
- Connect to your EO2
  1. `adb connect EO_IP_ADDRESS`
- Disable the EO2 app
  1. `adb shell pm disable com.electricobjects.app`
- Disable the vendor firmware updater too, so the frame stops waking itself hourly to phone a dead host
  1. `adb shell pm disable com.geniatech.upgrade`
  2. The app asks the frame to do this itself at every launch, through its own loopback `adbd` — but that path is dead while a host `adb` client is attached (and on an EO1, always), so running it once here is what makes it stick. A package-level disable covers every entry point the package exports and survives a reboot; `/logs` says whether the frame still has work to do. See [docs/WEBSERVER.md](WEBSERVER.md#vendor-ota-pollers)
- Install the APK (see [Installing the APK](#installing-the-apk) above):
  1. `adb install -r PATH/TO/eo1-release.apk`
- At this point, when you reboot your EO2, the configuration window should pop up. You can either attempt to connect a keyboard to the EO2 to edit the file directly, or follow these instructions to create the configuration.json file manually
  1. open the configuration_example.json from the root of this repo (either clone the repo or copy-paste the contents to a new text file)
  2. adjust the values for your setup — the file carries a `backends` list; see [Multiple media backends](../README.md#multiple-media-backends) below. If you have multiple EO2s, see the Immich section of the README
  3. once the file is edited with your preferences, copy it to the EO2 with the following command:
  4. `adb push PATH/TO/configuration.json /data/data/com.aphex3k.eo1/files/configuration.json`
- Reboot your EO2 with the following command:
  1. `adb reboot`
- When your EO2 reboots, it should connect and start displaying!

## Writing configuration.json by hand

The smallest valid document that displays the frame's own uploads — no server, no account, no
password:

```json
{
  "backends": [
    { "type": "local", "id": "local" }
  ]
}
```

`interval` (minutes, default 5), `selectedTimeZoneId` and `quietHours` all have working defaults, so
omitting them is fine and the frame will pick sensible values. The full template is
[`configuration_example.json`](../configuration_example.json), and the field-by-field meaning is in
[Multiple media backends](../README.md#multiple-media-backends).

Writing the file by hand is also the only route that never needs a pointer at all: push it, reboot,
done. On an EO2 that arrives over `adb`, which is why
[the EO2 path above](#setup-for-eo2) is the keyboard-free one.

## After installing

The frame should now be showing something. What to put on it, and how to change your mind later
without touching the wall, is the README's job:

- [Getting pictures on it with no server](../README.md#getting-pictures-on-it-with-no-server)
- [The frame's web page](../README.md#the-frames-web-page)
- [Day to day](../README.md#day-to-day)
