# EO1 Replacement APK

This repository is a fork of [spalt/EO1](https://github.com/spalt/EO1). The goal is to use a 
self-hosted [immich.app](https://immich.app/) instance as backend instead of using Flickr. This will allow full self custody of the system.

## Getting started

### Requirements 

- You need a way to connect a keyboard and mouse to your EO1.  You can get one of these [USB OTG Adapters](https://www.amazon.com/gp/product/B01C6032G0/?&_encoding=UTF8&tag=aph0dc-20&linkCode=ur2&linkId=a2e10d0fcebbd4425ace19f040a24e27&camp=1789&creative=9325) and connected a USB keyboard to it, then a USB mouse to the keyboard. Alternatively you can get an [OTG Hub](https://www.amazon.com/dp/B01HYJLZH6?psc=1&ref=ppx_yo2ov_dt_b_product_details&_encoding=UTF8&tag=aph0dc-20&linkCode=ur2&linkId=49938883224aa721262057e366759275&camp=1789&creative=9325) and connect mouse and keybord to it directly.
- Keep in mind that OTG and adb (via USB) can not be used at the same time.
- Immich Account Host
- Immich Account Login (Username/Password)
- (optionally) a web server hosting the apk for download on the frames

### Setup

- Upload some EO art to your Immich account.
- Once you boot up your EO1 and it hangs on the "Getting Art" dialog, hit **WINDOWS + B** to open a web browser
- You need to tell your EO1 to allow side-loading.  Swipe down on the top right and go to Settings > Security.  In there make sure "Unknown Sources" is checked.
- Go back to the browser and go to this URL: https://github.com/aphex3k/EO1/releases/download/v0.0.1/app-release.apk
    - You can build the app from this repository and host it yourself, if you want to not use a precompiled apk
- When it finishes, install the file by pulling down the notification bar and clicking it, then agreeing to the prompts.
- Restart/power cycle your EO1
- Because this APK is designated as a "Home screen replacement", when it boots, it will ask if you want to load the Electric Object app, or the EO1 app.  Select EO1 and choose "Always".
- The first time the EO1 is run you will need to specify the information above.  Click OK to save and continue.  **To get back to the configuration screen later, push C on your connected keyboard** 
- You can now unplug your mouse and keyboard and hang your EO1 back on the wall!
