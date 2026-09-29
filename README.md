# EO1 and EO2 Replacement APK

[![Build Status](https://jenkins.codingmerc.com/buildStatus/icon?job=EO1%2Fmain)](https://jenkins.codingmerc.com/job/EO1/)

This repository is a [rewrite](https://gitea.codingmerc.com/michael/EO1/issues/26) of [spalt/EO1](https://github.com/spalt/EO1). The goal is to ideally use a
self-hosted [immich.app](https://immich.app/) instance as a backend. This will allow **full self-custody** of the system.

![photo of eo1 custom app development](_img/IMG_1451.jpg) ![photo of eo1 running custom app](_img/IMG_1452.jpg)

This concept guarantees that you control all aspects of the images displayed on your EO1 and/or EO2 and they will never go offline or stop displaying art ever again, because someone else decided to do so!

## Concept

On your smart device, you will run the immich app for your platform and device. It supports phones and tablets running iOS or Android. The app connects to the cloud hosting an instance of the immich server. You need to host it yourself or have someone host it for you!

- A simple single-user configuration runs the same user credentials on the picture frame as well as on the smart device.
- In a multi-user configuration, everyone can upload pictures to one shared album or several shared albums which is accessible from the account that is used on the frames.
- The client app on the frames will select a random picture from the pool of all available pictures in shared or non-shared albums.
- A new picutre is chosen every interval.
- A best effort is made that once every picture had been displayed once, the cycle starts anew.

<img src="_img/single_user.png" width=403/> <img src="_img/multi_user.png" width=403/>

## Getting started

The following documentation assumes, that you have set up Immich already.

### Requirements

- Immich server **3.0.0–3.2.2** (verified against the **3.2.2** OpenAPI spec). Immich’s HTTP API changes between releases; newer servers may show an “unsupported” toast until this app is updated to match.
- You need a way to connect a keyboard and mouse to your EO1 frame.  You can get one of these [USB OTG Adapters](https://www.amazon.com/gp/product/B01C6032G0/?&_encoding=UTF8&tag=aph0dc-20&linkCode=ur2&linkId=a2e10d0fcebbd4425ace19f040a24e27&camp=1789&creative=9325) and connect a [USB keyboard with hub built-in](https://www.amazon.com/gp/search?ie=UTF8&tag=aph0dc-20&linkCode=ur2&linkId=56fac2fd57bf775c7512756260c58b6e&camp=1789&creative=9325&index=pc-hardware&keywords=usb) to it, then a USB mouse to the keyboard
  - Alternatively, you can get an [OTG Hub](https://www.amazon.com/dp/B01HYJLZH6?psc=1&ref=ppx_yo2ov_dt_b_product_details&_encoding=UTF8&tag=aph0dc-20&linkCode=ur2&linkId=49938883224aa721262057e366759275&camp=1789&creative=9325) and connect a mouse and keyboard to it directly
- Immich Account Host
- Immich Account Login (Username/Password)
- (optionally) a web server hosting the APK for download onto the frames

#### Caveats

EO1 has only one data port thus OTG and adb (via USB) can not be used at the same time. Unfortunatly the Android OS version 4.4.2 running on EO1 does not support wireless adb connection. That is the reason why the APK has to be sideloaded via the built-in browser.

Additionally the old Android 4.4.2 version installed on EO1 supports a limited set of TLS encryption next to a swath of outdated SSL stuff for accessing resources via the web. That means that the immich instance hosting your albums needs to let their guard down at least a litte.
Amongst other things, I had to disable PFS as well as enable TLSv1.2 in addition to modern TLSv1.3. If the app complains about SSL connection errors, it is likely that the immich host and the Android OS don't share at least one compatible cipher and protocol.

### Setup for EO1

- Upload some pictures to your Immich account
- Connect the mouse & keyboard to your EO1 via the mentioned OTG method
- Connect the EO1 to the power adapter and let it boot
- When your EO1 finishes the boot animation and it hangs on the "Getting Art" dialog, hit **\[WINDOWS + B\]** on the keyboard to open a web browser
- You need to tell your EO1 to allow side-loading.
  1. Swipe down on the top right and go to **Settings > Security**
  2. Make sure "Unknown Sources" is checked
- Go back to the browser and go to this URL: <http://gitea.codingmerc.com/michael/EO1/releases/download/v1.3.0/app-release.apk>
  - You can build the app from this repository and host it yourself if you want to not use a precompiled APK
- When it finished downloading, install the file by pulling down the notification bar on the top left and clicking it, then agreeing to the prompts
- Restart/power cycle your EO1 by unplugging and plugging only the power cable
  - At this point, the keyboard and mouse are still connected via OTG
- Because this APK is designated as a "Home screen replacement", when EO1 boots, it will ask if you want to load the Electric Object app or the EO1 app. Select EO1 and choose "Always".
- The first time the EO1 is run you will need to specify the information listed above. Click OK to save and continue. **To get back to the configuration screen later, press C on your connected keyboard**
  - Username: this is the same email address you would use to log in on the web-app or the native apps on iOS or Android
    - Depending in how you set up your immich installation this is your own user or a shared user account dedicated for use with the frames
  - Password: same as above
    - Keep in mind that this password is stored in clear-text on the frame. Everyone with access to your device can recover the password!
  - Server / Host: Insert the same server URL you use to to log in on the web-app or the native apps on iOS or Android
    - If you connect to immich on your browser by using `http://immich.local`, that is what you want to use in the configuration window as well
    - If you run immich on a different port than `80`, you need to include that in the server string like `http://immich.local:8080`
- You can now unplug your mouse and keyboard and hang your EO1 back on the wall!

### Setup for EO2

Your EO2 most likely has android debugging enabled - which (while insecure) makes it much easier to adjust settings remotely.
This guide assumes your EO2 already has an IP address on your network that is known to you (likely a DHCP reservation). If your EO2 has been reset or isn't connected to your network, [this reddit post](https://www.reddit.com/r/electricobjects/comments/1amitpa/howto_video_for_resurrecting_eo2/) has instructions on getting it online.

- Upload some pictures to your Immich account
- Connect the EO2 to the power adapter and let it boot. It should stop at "Connecting to the internet..."
- Download and install [adb (Android Debug Bridge)](https://developer.android.com/tools/adb)
  1. I was never succesful getting the adb command to work on my Win10 machine - the connection always failed. However it worked from both Windows Subsystem Linux on that machine and my Linux server, so my guess is there's an issue with Windows adb.
- Connect to your EO2
  1. `adb connect EO_IP_ADDRESS`
- Disable the EO2 app
  1. `adb shell pm disable com.electricobjects.app`
- Download the latest release apk from this repo and install it using this command:
  1. `adb install -r PATH/TO/eo1-app-release.apk`
- At this point, when you reboot your EO2, the configuration window should pop up. You can either attempt to connect a keyboard to the EO2 to edit the file directly, or follow these instructions to create the configuration.json file manually
  1. open the configuration_example.json from the root of this repo (either clone the repo or copy-paste the contents to a new text file)
  2. adjust the values for your setup. If you have multiple EO2s, see the section below around managing multiple EO2s with immich
  3. once the file is edited with your preferences, copy it to the EO2 with the following command:
  4. `adb push PATH/TO/configuration.json /data/data/com.aphex3k.eo1/files/configuration.json`
- Reboot your EO2 with the following command:
  1. `adb reboot`
- When your EO2 reboots, it should connect to the server and grab some art!
- If you used the electricobjects website in the past to download and display art, please [share your cached art with the world!](https://www.reddit.com/r/electricobjects/comments/16umi1m/eo2_saving_your_art/)

### Managing multiple EO2s with immich

In a situation where you will want to manage multiple EO2s (or when you want to have granular control on what a single EO2 displays) you'll want to create an immich account for **each EO**. These accounts should be separate from your master (admin) account that manages the immich instance.
In the immich Administration area, click the "Create User" button and name it for the EO2 (eg EO2-LivingRoom). _at the point of writing this, each account requires a separate email address, however in my testing this address is not used and does not need to be verifed. If this changes in future versions of immich, you'll have to provide unique emails for each EO._
Use the created email/password combo for each EO2 in the app configuration.json
Create separate albums for your pictures - either organized as previously on the electricobjects website (each album is a playlist) or create albums specific for each device
Choose the album you want to show on each device, click the Share icon in the up-right of the immich app, and choose the account associated with the device.
The app will only be able to see content from your immich database that is shared with it.

### Local debug (emulator)

Clone with Git LFS so the Amlogic TsPlayer native library is present:

```bash
git lfs pull
```

Modern Android Studio no longer supports the Gradle/SDK pins this project needs for IDE debugging. Use:

```bash
./debug.sh
```

That builds the debug APK (armeabi-v7a carries the Amlogic TsPlayer `.so`; arm64-v8a is a native-lib-free emulator fallback), creates or reuses an AVD named `EO1` (API 19) constrained like the real hardware / Jenkins CI, installs and launches the app, then attaches to logcat. Press **Ctrl+C** to stop and quit the emulator.

On **Apple Silicon**, API 19 images generally cannot boot, and 32-bit ARM emulators are unsupported. After API 19 fails, the script falls back to AVD `EO1_API21` (`system-images;android-21;default;arm64-v8a`). The committed LFS TsPlayer `.so` is **armeabi-v7a** only (EO1 hardware); the arm64-v8a fallback has no native player, so the app falls back to `MediaPlayer` there. That path is an approximation for development, not EO1 fidelity. Debug APKs omit `maxSdk` so they can install on the fallback AVD; **release** builds still use `maxSdk 19` and armeabi-v7a only for real devices.

## Further Reading

1. [EO1 Hardware specifications](EO1-specs.md)
1. [AGENTS.md](AGENTS.md) — repository map for AI coding agents

## Contribution

1. You can provide feedback by opening [Issues](https://gitea.codingmerc.com/michael/EO1/issues) and describing an idea or problem.
1. You can donate in Dollar via [PayPal](https://www.paypal.me/aphex3k) or in [Bitcoin](https://getalby.com/p/michaelhenke).
1. If you happen to have a working but no longer needed EO1/EO2 picture frame, please reach out via email!
