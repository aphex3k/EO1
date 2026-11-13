#!/bin/bash
# Deploy the RELEASE variant to a frame: the same flow as ./deploy-debug.sh — a connected physical
# EO1/EO2 when one is attached, otherwise the API 19 EO1 AVD — built with `assembleRelease`.
#
# The flow itself lives in deploy-debug.sh, which is the variant engine; setting VARIANT=release
# selects the Gradle task, the APK path and the release-specific install rules there. One copy of
# the logcat filter, the AVD constraint patching and the adb timeout policy is deliberate: each of
# those is a lesson, and two copies of a lesson drift until one of them lies.
#
# Read this before the first run, because release fails in ways debug does not:
#   * Signing. `assembleRelease` signs only when local.properties carries eo1.signing.store.file /
#     .store.password / .key.alias / .key.password (app/build.gradle). A git worktree has no
#     local.properties of its own — copy it from the main checkout, or the script stops before
#     building rather than handing you an unsigned APK no device will take.
#   * Target. release is armeabi-v7a only and app/src/release/AndroidManifest.xml sets maxSdk 19, so
#     it installs on real EO hardware or an API 19 AVD — never on the arm64 API 21 fallback AVD that
#     debug uses on Apple Silicon. With no frame attached on Apple Silicon this script stops.
#   * It will not replace the debug build already on the frame: pm answers
#     INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only way past that is an uninstall, which discards
#     configuration.json (the frame's credentials), its SharedPreferences, uploaded media,
#     files/eo1-app.log and the crash/report stores. The script asks; --yes answers.
#   * versionCode. It defaults to 1 (app/build.gradle) unless VERSION_CODE=… is passed, so against a
#     frame running a published build the install is a refused downgrade; the script retries it with
#     `install -d`, but pass VERSION_CODE=<above the published code> instead, for the next reason:
#     UpdateManager stages any manifest build with a strictly greater versionCode than the installed
#     app, so a deploy below what the frame's manifest publishes is undone by the frame itself on its
#     next update cycle.
#   * Observability. Release is R8-minified: the on-screen debug board is off, /state omits its
#     debug-gated fields, the panel shows no version overlay (and quiet hours make it black), and a
#     crash trace names obfuscated classes until retraced. This script therefore archives the build's
#     mapping as app/build/outputs/apk/release/mapping-<versionCode>-<gitSha>.txt.gz — a deployed
#     build that was never published has no entry in publish-update.sh's ledger, and
#     app/build/outputs/mapping/release/mapping.txt is overwritten by the next assembleRelease. The
#     durable record is still files/eo1-app.log; /state's app block is how you confirm the deploy
#     landed at all.
#
# This deploys to a device; it does not publish. Publishing — manifest, versionCode ledger, archived
# mapping, the roots gate — is ./publish-update.sh, and an APK installed from here is not what a
# frame would ever download.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VARIANT=release
export VARIANT
exec "${HERE}/deploy-debug.sh" "$@"
