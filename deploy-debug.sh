#!/bin/bash
# Build an APK of this project and deploy it: to a connected physical device when available
# (preferring PREFERRED_PHYSICAL_SERIAL, default 20061229), otherwise ensure/start
# an API 19 AVD named EO1 (EO1-like constraints), install the app, and stream
# logcat until Ctrl+C.
# On Apple Silicon, if API 19 cannot boot, fall back to EO1_API21 (API 21 arm64-v8a) — debug only.
# Physical-device adb shell/install calls use ADB_CMD_TIMEOUT_SEC / ADB_INSTALL_TIMEOUT_SEC
# so a wedged EO1/EO2 fails fast instead of hanging forever.
#
# THIS FILE IS THE DEPLOY FLOW FOR BOTH VARIANTS. `VARIANT` (env, default debug) selects which;
# ./deploy-debug.sh is this file, and ./deploy-release.sh execs this file with VARIANT=release.
# One copy of the flow is deliberate: the logcat filter in follow_logcat, the AVD constraint
# patching in patch_avd_config and the adb timeout policy are each a hard-won rule, and a second
# copy of a rule is how the two variants drift until one of them lies. Variant differences live in
# the `case "$VARIANT"` block below and in the places that branch on it — keep them there rather
# than forking the flow.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

AVD_NAME="EO1"
EXPECTED_API=19
PACKAGE_ID="com.aphex3k.eo1"
ACTIVITY="${PACKAGE_ID}/${PACKAGE_ID}.MainActivity"
# APK_PATH and GRADLE_TASK come from the variant block below.
BOOT_TIMEOUT_SEC="${BOOT_TIMEOUT_SEC:-180}"
EMU_LOG="/tmp/eo1-emulator.log"
FALLBACK_AVD_NAME="EO1_API21"
FALLBACK_SYSTEM_IMAGE="system-images;android-21;default;arm64-v8a"
FALLBACK_API=21
PREFERRED_PHYSICAL_SERIAL="${PREFERRED_PHYSICAL_SERIAL:-20061229}"
ADB_CMD_TIMEOUT_SEC="${ADB_CMD_TIMEOUT_SEC:-15}"
ADB_INSTALL_TIMEOUT_SEC="${ADB_INSTALL_TIMEOUT_SEC:-120}"

EMU_PID=""
SERIAL=""
CLEANED_UP=0
USING_EMULATOR=0
SYSTEM_IMAGE=""
TIMEOUT_BIN=""
ASSUME_YES=0
PUSH_CONFIG=1
INSTALL_OUT=""
INSTALL_RC=0
UNINSTALLED_APP=0

die() {
  echo "error: $*" >&2
  exit 1
}

log() {
  echo "==> $*" >&2
}

# --- variant ------------------------------------------------------------------
# The two entry points differ only in which Gradle variant is built, and the resulting APK differs
# in ways that decide what can be installed where:
#   * app/src/release/AndroidManifest.xml sets maxSdkVersion 19 and release sets
#     ndk.abiFilters 'armeabi-v7a' only (debug adds arm64-v8a). So a release APK cannot install on
#     the API 21 arm64 fallback AVD at all — NO_MATCHING_ABIS and INSTALL_FAILED_OLDER_SDK — and on
#     Apple Silicon, where API 19 images do not boot, that leaves a physical EO1/EO2 as the only
#     release target. Refused up front rather than after a doomed 180s emulator boot.
#   * release signs only when local.properties carries eo1.signing.*; without it assembleRelease
#     produces an UNSIGNED APK that no device will install (publish-update.sh refuses to build one;
#     a deploy cannot publish one either, so it says so before building).
#   * versionCode defaults to 1 unless -PVERSION_CODE is passed (app/build.gradle), so a plain
#     release deploy is a downgrade over any published build already on the frame and pm refuses it.
#     VERSION_CODE is passed through when set, and a refused downgrade is retried with `install -d`.
#   * a release APK never replaces a debug-signed install in place, and the other way round: pm
#     answers INSTALL_FAILED_UPDATE_INCOMPATIBLE. The only way past that is an uninstall, which
#     discards the frame's configuration.json (its credentials), SharedPreferences, uploaded media,
#     files/eo1-app.log and the crash/report stores — so it is asked for, never assumed.
#   * release is R8-minified: the on-screen debug board and /state's debug-gated fields are off and
#     a crash trace names obfuscated classes until retraced against app/build/outputs/mapping/.
VARIANT="${VARIANT:-debug}"
case "$VARIANT" in
  debug)
    GRADLE_TASK="assembleDebug"
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    ALLOW_ABI_FALLBACK=1
    SUPPORTED_ABIS="armeabi-v7a (EO1/EO2 hardware), arm64-v8a (Apple Silicon emulator fallback only)"
    ;;
  release)
    GRADLE_TASK="assembleRelease"
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
    ALLOW_ABI_FALLBACK=0
    SUPPORTED_ABIS="armeabi-v7a (EO1/EO2 hardware) only - and maxSdk 19, so no API 21 emulator"
    ;;
  *)
    die "VARIANT must be 'debug' or 'release' (got '${VARIANT}')"
    ;;
esac

# The one signing source app/build.gradle reads is rootProject.file('local.properties'), so asking
# that file is asking the real question — no gradle.properties path can sign this variant.
release_signing_configured() {
  local props="${ROOT}/local.properties"
  [[ -r "$props" ]] && grep -q '^eo1\.signing\.store\.file=.+' "$props"
}

usage() {
  cat >&2 <<'USAGE'
usage: ./deploy-debug.sh [--yes] [--no-config]
       ./deploy-release.sh [--yes] [--no-config]

Builds the variant's APK with Gradle, installs it on a connected physical device
(PREFERRED_PHYSICAL_SERIAL, default 20061229) or on an API 19 AVD named EO1, starts the app,
and streams logcat until Ctrl+C.

  --yes        allow the one destructive step: uninstalling the frame's app when its signature
               does not match this APK (debug and release builds never replace each other)
  --no-config  do not push this checkout's configuration.json to the device

Environment: PREFERRED_PHYSICAL_SERIAL, BOOT_TIMEOUT_SEC, ADB_CMD_TIMEOUT_SEC,
ADB_INSTALL_TIMEOUT_SEC, VARIANT (debug|release).
release only: VERSION_CODE — pass it ABOVE the versionCode the frame's update manifest publishes,
so the frame does not replace this deploy with the published build (VERSION_CODE=215
./deploy-release.sh); EO1_DEPLOY_ALLOW_UNSIGNED=1 builds an unsigned APK anyway (no device will
take it); EO1_DEPLOY_UNINSTALL=1 answers the uninstall prompt the way --yes does.
USAGE
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -y|--yes) ASSUME_YES=1 ;;
      --no-config) PUSH_CONFIG=0 ;;
      -h|--help) usage; exit 0 ;;
      *) usage; die "unknown argument: $1" ;;
    esac
    shift
  done
}

# Populate TIMEOUT_BIN once (call after PATH is finalized). Empty string means use bash fallback.
init_timeout_bin() {
  if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="$(command -v timeout)"
  elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="$(command -v gtimeout)"
  else
    TIMEOUT_BIN=""
    log "No timeout/gtimeout on PATH; using bash fallback for adb command limits"
  fi
}

# Run a command with a wall-clock timeout (seconds). Exit 124 on timeout (GNU timeout convention).
run_with_timeout() {
  local secs="$1"
  shift
  if [[ -n "$TIMEOUT_BIN" ]]; then
    # TERM at deadline, then KILL shortly after; exit status stays 124 on timeout.
    "$TIMEOUT_BIN" -k 2 "$secs" "$@"
    return $?
  fi

  "$@" &
  local pid=$!
  local i=0
  while (( i < secs )); do
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid"
      return $?
    fi
    sleep 1
    i=$((i + 1))
  done
  kill "$pid" 2>/dev/null || true
  sleep 1
  kill -KILL "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  return 124
}

adb_timed_out() {
  # GNU timeout => 124; some builds with KILL-only => 128+9.
  [[ "$1" -eq 124 || "$1" -eq 137 ]]
}

adb_cmd() {
  local secs="$1"
  shift
  run_with_timeout "$secs" "${ADB}" -s "$SERIAL" "$@"
}

adb_unresponsive_hint() {
  echo "Try: adb kill-server && adb start-server; unplug/replug USB; or power-cycle the EO1/EO2." >&2
}

# Fail fast when adb lists a device but shell never responds (common on wedged Geniatech boards).
ensure_device_responsive() {
  local out rc
  log "Probing adb shell on ${SERIAL} (timeout ${ADB_CMD_TIMEOUT_SEC}s)..."
  set +e
  out="$(adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell echo ok 2>&1)"
  rc=$?
  set -e
  out="$(printf '%s' "$out" | tr -d '\r' | tr -d '\n')"
  if [[ $rc -eq 0 && "$out" == *ok* ]]; then
    return 0
  fi
  if adb_timed_out "$rc"; then
    echo "error: Device ${SERIAL} is listed by adb but shell is unresponsive (timed out after ${ADB_CMD_TIMEOUT_SEC}s)." >&2
    adb_unresponsive_hint
    exit 1
  fi
  echo "error: Device ${SERIAL} adb shell failed (rc=${rc}): ${out}" >&2
  adb_unresponsive_hint
  exit 1
}

host_arch() {
  uname -m
}

is_apple_silicon() {
  [[ "$(uname -s)" == "Darwin" && "$(host_arch)" == "arm64" ]]
}

resolve_sdk_root() {
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    echo "$ANDROID_HOME"
    return
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    echo "$ANDROID_SDK_ROOT"
    return
  fi
  if [[ -d "${HOME}/Library/Android/sdk" ]]; then
    echo "${HOME}/Library/Android/sdk"
    return
  fi
  if [[ -d "${HOME}/Android/Sdk" ]]; then
    echo "${HOME}/Android/Sdk"
    return
  fi
  die "Android SDK not found. Set ANDROID_HOME or install the SDK (e.g. ~/Library/Android/sdk)."
}

find_sdk_bin() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return
  fi
  local candidates=(
    "${SDK_ROOT}/platform-tools/${name}"
    "${SDK_ROOT}/emulator/${name}"
    "${SDK_ROOT}/cmdline-tools/latest/bin/${name}"
    "${SDK_ROOT}/tools/bin/${name}"
  )
  local c
  for c in "${candidates[@]}"; do
    if [[ -x "$c" ]]; then
      echo "$c"
      return
    fi
  done
  local ct
  for ct in "${SDK_ROOT}/cmdline-tools/"*/bin/"${name}"; do
    if [[ -x "$ct" ]]; then
      echo "$ct"
      return
    fi
  done
  die "Required tool not found: ${name} (under PATH or ${SDK_ROOT})"
}

avd_config_path() {
  local name="${1:-$AVD_NAME}"
  local ini="${HOME}/.android/avd/${name}.ini"
  [[ -f "$ini" ]] || return 1
  local path
  path="$(grep -E '^path=' "$ini" | head -1 | cut -d= -f2-)"
  [[ -n "$path" && -f "${path}/config.ini" ]] || return 1
  echo "${path}/config.ini"
}

avd_exists() {
  local name="${1:-$AVD_NAME}"
  "${EMULATOR}" -list-avds 2>/dev/null | grep -qx "${name}"
}

delete_avd() {
  local name="$1"
  if ! avd_exists "$name"; then
    return 0
  fi
  log "Deleting AVD ${name} (will recreate with correct API/ABI)..."
  "${AVDMANAGER}" delete avd -n "$name" 2>/dev/null || true
  rm -rf "${HOME}/.android/avd/${name}.avd" "${HOME}/.android/avd/${name}.ini" 2>/dev/null || true
}

avd_matches_api() {
  local config="$1"
  local api="$2"
  local target sysdir
  target="$(grep -E '^target=' "$config" | head -1 | cut -d= -f2- || true)"
  sysdir="$(grep -E '^image\.sysdir\.1=' "$config" | head -1 | cut -d= -f2- || true)"
  if [[ "$target" == "android-${api}" ]]; then
    return 0
  fi
  if [[ "$sysdir" == *"/android-${api}/"* || "$sysdir" == *"android-${api}/"* ]]; then
    return 0
  fi
  return 1
}

validate_existing_avd() {
  local expected_api="${1:-$EXPECTED_API}"
  local expected_abi="${2:-}"
  local config
  config="$(avd_config_path "$AVD_NAME")" \
    || die "AVD ${AVD_NAME} is listed but config.ini was not found under ~/.android/avd/"

  local abi target sysdir
  target="$(grep -E '^target=' "$config" | head -1 | cut -d= -f2- || true)"
  abi="$(grep -E '^abi.type=' "$config" | head -1 | cut -d= -f2- || true)"
  sysdir="$(grep -E '^image\.sysdir\.1=' "$config" | head -1 | cut -d= -f2- || true)"

  if ! avd_matches_api "$config" "$expected_api"; then
    die "AVD ${AVD_NAME} exists but is not API ${expected_api} (target='${target:-unknown}', image='${sysdir:-unknown}'). Rename or delete it, then re-run."
  fi
  if [[ -z "$abi" ]]; then
    die "AVD ${AVD_NAME} has no abi.type in config.ini"
  fi
  if [[ -n "$expected_abi" && "$abi" != "$expected_abi" ]]; then
    die "AVD ${AVD_NAME} abi is '${abi}' but need '${expected_abi}'. Rename or delete it, then re-run."
  fi
  log "Using existing AVD ${AVD_NAME} (api=${expected_api}, abi=${abi})"
}

image_installed() {
  local pkg="$1"
  local rel="${pkg#system-images;}"
  # Replace package ';' separators with path '/'.
  rel="${rel//;//}"
  [[ -d "${SDK_ROOT}/system-images/${rel}" ]]
}

preferred_api19_system_images() {
  # A release APK carries armeabi-v7a only (release's ndk.abiFilters), and an x86 image offers
  # x86 plus *armeabi* translation — not armeabi-v7a — so pm answers NO_MATCHING_ABIS on it. Prefer
  # the ARM image for that variant whatever the host is; debug carries both ABIs, so it keeps this
  # host's fastest image first.
  if [[ "$VARIANT" == "release" ]]; then
    echo "system-images;android-19;default;armeabi-v7a"
    echo "system-images;android-19;default;x86"
    return
  fi
  if is_apple_silicon; then
    echo "system-images;android-19;default;armeabi-v7a"
    echo "system-images;android-19;default;x86"
  else
    echo "system-images;android-19;default;x86"
    echo "system-images;android-19;default;armeabi-v7a"
  fi
}

install_system_image() {
  local pkg="$1"
  if image_installed "$pkg"; then
    SYSTEM_IMAGE="$pkg"
    log "Using system image ${SYSTEM_IMAGE}"
    return
  fi
  log "Installing system image ${pkg} via sdkmanager..."
  set +o pipefail
  yes | "${SDKMANAGER}" --licenses >/dev/null 2>&1 || true
  yes | "${SDKMANAGER}" "${pkg}"
  local sdk_rc=$?
  set -o pipefail
  [[ $sdk_rc -eq 0 ]] || die "Failed to install ${pkg}"
  image_installed "${pkg}" || die "System image ${pkg} still missing after install"
  SYSTEM_IMAGE="$pkg"
}

ensure_system_image_api19() {
  local pkg
  for pkg in $(preferred_api19_system_images); do
    if image_installed "$pkg"; then
      SYSTEM_IMAGE="$pkg"
      log "Using system image ${SYSTEM_IMAGE}"
      return
    fi
  done
  SYSTEM_IMAGE="$(preferred_api19_system_images | { read -r first; echo "$first"; })"
  install_system_image "$SYSTEM_IMAGE"
}

device_eo1_available() {
  "${AVDMANAGER}" list device 2>/dev/null | grep -Eq 'id:[[:space:]]*[0-9]+[[:space:]]+or[[:space:]]+"EO1"'
}

patch_avd_config() {
  local config
  config="$(avd_config_path "$AVD_NAME")" || die "Cannot patch AVD config for ${AVD_NAME}"

  set_ini() {
    local key="$1" value="$2"
    if grep -qE "^${key}=" "$config"; then
      local tmp
      tmp="$(mktemp)"
      awk -v k="$key" -v v="$value" 'BEGIN{FS=OFS="="} $1==k{$0=k"="v} {print}' "$config" >"$tmp"
      mv "$tmp" "$config"
    else
      echo "${key}=${value}" >>"$config"
    fi
  }

  set_ini hw.ramSize 1024
  set_ini hw.lcd.width 1080
  set_ini hw.lcd.height 1920
  set_ini hw.initialOrientation portrait
  set_ini hw.cpu.ncore 2
  set_ini hw.camera.back None
  set_ini hw.camera.front None
  set_ini hw.audioInput no
  set_ini hw.audioOutput no
  set_ini hw.gpu.enabled yes
  set_ini hw.gpu.mode auto
  set_ini skin.dynamic no
  set_ini skin.name 1080x1920
  set_ini skin.path 1080x1920
  log "Patched ${AVD_NAME} hardware constraints (1GB RAM, 1080x1920 portrait, 2 cores, no cameras)"
}

create_avd_with_image() {
  local pkg="$1"
  SYSTEM_IMAGE="$pkg"
  log "Creating AVD ${AVD_NAME} with ${SYSTEM_IMAGE}"

  local create_args=(create avd --name "${AVD_NAME}" --package "${SYSTEM_IMAGE}" --force)
  if device_eo1_available; then
    create_args+=(--device EO1)
  else
    log "Device profile EO1 not found; using 1080x1920 skin"
  fi

  echo no | "${AVDMANAGER}" --silent "${create_args[@]}" \
    || die "Failed to create AVD ${AVD_NAME}"

  if ! device_eo1_available; then
    local config
    config="$(avd_config_path "$AVD_NAME")"
    if ! grep -qE '^skin.name=' "$config"; then
      echo "skin.name=1080x1920" >>"$config"
      echo "skin.path=1080x1920" >>"$config"
    fi
  fi

  patch_avd_config
}

ensure_avd() {
  local expected_api="$1"
  local image_pkg="$2"
  local expected_abi="${3:-}"

  EXPECTED_API="$expected_api"
  if avd_exists "$AVD_NAME"; then
    local config abi
    config="$(avd_config_path "$AVD_NAME")" \
      || die "AVD ${AVD_NAME} is listed but config.ini was not found under ~/.android/avd/"
    abi="$(grep -E '^abi.type=' "$config" | head -1 | cut -d= -f2- || true)"
    if ! avd_matches_api "$config" "$expected_api" \
      || { [[ -n "$expected_abi" ]] && [[ "$abi" != "$expected_abi" ]]; }; then
      delete_avd "$AVD_NAME"
    else
      validate_existing_avd "$expected_api" "$expected_abi"
      patch_avd_config
      SYSTEM_IMAGE="system-images;android-${expected_api};default;${abi}"
      return
    fi
  fi

  if [[ "$expected_api" -eq 19 ]]; then
    ensure_system_image_api19
    create_avd_with_image "$SYSTEM_IMAGE"
  else
    install_system_image "$image_pkg"
    create_avd_with_image "$image_pkg"
  fi
}

stop_emulator_instance() {
  if [[ -n "$SERIAL" ]]; then
    "${ADB}" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  fi
  if [[ -n "$EMU_PID" ]] && kill -0 "$EMU_PID" 2>/dev/null; then
    kill "$EMU_PID" >/dev/null 2>&1 || true
    wait "$EMU_PID" 2>/dev/null || true
  fi
  EMU_PID=""
  SERIAL=""
}

cleanup() {
  if [[ "$CLEANED_UP" -eq 1 ]]; then
    return
  fi
  CLEANED_UP=1

  if [[ "$USING_EMULATOR" -eq 0 ]]; then
    return
  fi

  if [[ -z "$EMU_PID" && -z "$SERIAL" ]]; then
    return
  fi

  echo
  log "Stopping debug session..."
  stop_emulator_instance
}

list_emulator_serials() {
  "${ADB}" devices 2>/dev/null | awk '/^emulator-[0-9]+\tdevice$/ { print $1 }'
}

list_physical_serials() {
  "${ADB}" devices 2>/dev/null | awk '!/^List of devices/ && !/^$/ && !/^emulator-/ && $2 == "device" { print $1 }'
}

list_attached_physical_serials() {
  "${ADB}" devices 2>/dev/null | awk '!/^List of devices/ && !/^$/ && !/^emulator-/ { print $1 }'
}

physical_device_state() {
  local serial="$1"
  "${ADB}" devices 2>/dev/null | awk -v s="$serial" '$1 == s { print $2; exit }'
}

# 'die' cannot be used from this function: the caller reads it through a command substitution, so an
# exit there only ends the subshell and the script falls through to the emulator as if nothing were
# attached — installing somewhere other than the frame in front of the operator, and for release then
# asserting "no physical EO1/EO2 is attached" one line after naming that serial as unauthorized.
# Statuses: 0 = a serial was printed, 1 = nothing attached (emulator is legitimate), 2 = attached but
# unusable (stop, and say why). Every call site is written `device_unusable "..." || return $?` — the
# status only leaves the chooser if the chooser returns it, which is the whole point of the function.
device_unusable() {
  echo "error: $*" >&2
  return 2
}

select_physical_serial() {
  local ready_serials=""
  local attached_serials=""
  local preferred_state=""
  local serial state count_ready count_attached

  ready_serials="$(list_physical_serials)"
  attached_serials="$(list_attached_physical_serials)"

  preferred_state="$(physical_device_state "$PREFERRED_PHYSICAL_SERIAL")"
  if [[ -n "$preferred_state" && "$preferred_state" != "device" ]]; then
    device_unusable "Preferred physical device ${PREFERRED_PHYSICAL_SERIAL} is attached but not ready (state: ${preferred_state}). Fix adb authorization or connection." || return $?
  fi

  count_attached="$(printf '%s\n' "$attached_serials" | sed '/^$/d' | wc -l | tr -d ' ')"
  count_ready="$(printf '%s\n' "$ready_serials" | sed '/^$/d' | wc -l | tr -d ' ')"

  if [[ "$count_attached" -eq 1 && "$count_ready" -eq 0 ]]; then
    serial="$(printf '%s\n' "$attached_serials" | sed '/^$/d' | head -1)"
    state="$(physical_device_state "$serial")"
    device_unusable "Physical device ${serial} is attached but not ready (state: ${state}). Fix adb authorization or connection." || return $?
  fi

  if [[ "$count_ready" -eq 0 ]]; then
    return 1
  fi

  if printf '%s\n' "$ready_serials" | grep -qx "$PREFERRED_PHYSICAL_SERIAL"; then
    echo "$PREFERRED_PHYSICAL_SERIAL"
    return 0
  fi

  if [[ "$count_ready" -eq 1 ]]; then
    printf '%s\n' "$ready_serials" | sed '/^$/d' | head -1
    return 0
  fi

  device_unusable "Multiple physical devices connected ($(printf '%s\n' "$ready_serials" | sed '/^$/d' | tr '\n' ' ')) but preferred serial ${PREFERRED_PHYSICAL_SERIAL} is not among them. Disconnect extras or attach the preferred device." || return $?
}

wait_for_boot() {
  local deadline=$((SECONDS + BOOT_TIMEOUT_SEC))
  local preexisting="$1"
  local emu_log="${2:-$EMU_LOG}"
  local serial=""

  log "Waiting for emulator to appear on adb (timeout ${BOOT_TIMEOUT_SEC}s)..."
  while (( SECONDS < deadline )); do
    if ! kill -0 "$EMU_PID" 2>/dev/null; then
      return 1
    fi
    if [[ -f "$emu_log" ]] && grep -qE 'FATAL\s+\|' "$emu_log" 2>/dev/null; then
      return 1
    fi
    while IFS= read -r cand; do
      [[ -z "$cand" ]] && continue
      if [[ " ${preexisting} " != *" ${cand} "* ]]; then
        serial="$cand"
        break
      fi
    done < <(list_emulator_serials)
    if [[ -n "$serial" ]]; then
      break
    fi
    sleep 1
  done

  [[ -n "$serial" ]] || return 1
  SERIAL="$serial"
  log "Emulator serial: ${SERIAL}"

  log "Waiting for boot completed..."
  while (( SECONDS < deadline )); do
    if ! kill -0 "$EMU_PID" 2>/dev/null; then
      return 1
    fi
    if [[ -f "$emu_log" ]] && grep -qE 'FATAL\s+\|' "$emu_log" 2>/dev/null; then
      return 1
    fi
    local boot
    boot="$("${ADB}" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$boot" == "1" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

launch_avd() {
  local preexisting
  preexisting="$(list_emulator_serials | tr '\n' ' ')"
  : >"$EMU_LOG"
  USING_EMULATOR=1

  log "Launching emulator ${AVD_NAME}..."
  "${EMULATOR}" -avd "${AVD_NAME}" \
    -no-snapshot \
    -camera-front none \
    -camera-back none \
    -memory 1024 \
    -partition-size 1024 \
    -no-boot-anim \
    -screen no-touch \
    -no-audio \
    -no-metrics \
    -selinux permissive \
    -gpu auto \
    >"$EMU_LOG" 2>&1 &
  EMU_PID=$!

  if wait_for_boot "$preexisting" "$EMU_LOG"; then
    return 0
  fi
  return 1
}

boot_failure_hint() {
  echo "error: Emulator AVD ${AVD_NAME} (API ${EXPECTED_API}) did not become ready within ${BOOT_TIMEOUT_SEC}s." >&2
  if is_apple_silicon && [[ "$EXPECTED_API" -eq 19 ]]; then
    echo "error: This host is Apple Silicon (arm64). API 19 system images (armeabi-v7a / x86) generally cannot run here." >&2
  else
    echo "error: Check that acceleration works and that ${SYSTEM_IMAGE:-the system image} is installed." >&2
  fi
}

app_pid_on_device() {
  local pid
  pid="$(adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell pidof "${PACKAGE_ID}" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)"
  if [[ -n "$pid" ]]; then
    echo "$pid"
    return
  fi
  adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell ps 2>/dev/null | tr -d '\r' | awk -v p="$PACKAGE_ID" '$NF == p { print $2; exit }' || true
}

follow_logcat() {
  if [[ "$USING_EMULATOR" -eq 1 ]]; then
    log "Attaching to logcat for ${PACKAGE_ID} (Ctrl+C to stop and quit the AVD)..."
  else
    log "Attaching to logcat for ${PACKAGE_ID} (Ctrl+C to stop)..."
  fi
  adb_cmd "$ADB_CMD_TIMEOUT_SEC" logcat -c >/dev/null 2>&1 || true

  local app_pid=""
  local i
  for i in $(seq 1 30); do
    app_pid="$(app_pid_on_device || true)"
    if [[ -n "$app_pid" ]]; then
      break
    fi
    sleep 1
  done

  # The keyword list below is a WHITELIST, so anything it does not name is invisible: a stack trace
  # arriving through this filter loses its `java.lang.X: message` header and every non-app frame, and
  # what is left is a tail of `at com.aphex3k.eo1...` lines that look like a contradiction — the
  # frames of a trace whose exception the source provably cannot throw. That cost the whole of one
  # investigation into CrashSources. So the anchored trace shapes are part of the filter now, not an
  # afterthought: they keep a dump whole while leaving unrelated vendor chatter out.
  # Two shapes the first widening missed, both verified by running a real trace through the filter:
  # Dalvik's "thread exiting with uncaught exception" is the line that says a thread died at all — losing
  # it leaves frames with no event, exactly the confusion the whitelist caused before — and "... N more"
  # is what says frames were elided, so a trace that was cut short reads as one that ended naturally.
  # <init> and <clinit> need the angle brackets in the class: they are the frames that say a constructor
  # or a static initializer threw, which is usually the line worth reading, and without them a frame like
  # `at java.lang.Thread.<init>(Thread.java:420)` matches nothing and vanishes.
  local trace_shapes='Exception in thread |Caused by: |[[:space:]]at [[:alnum:]_$.<>]+\(|prio=[0-9]+ tid=[0-9]+|\.[A-Za-z_$][A-Za-z_$0-9.]*(Exception|Error|Throwable)(:|$)|thread exiting with uncaught exception|[.][.][.] [0-9]+ more'
  local logcat_grep="${PACKAGE_ID}|EO1|Immich|MainActivity|AndroidRuntime|System.err|displayVideo|displayPictures|TextureVideo|TsVideoView|TsPlayer|MediaPlayer|Glide|OkHttp|Retrofit|download| D video:|${trace_shapes}"

  # Host adb advertises --pid even when the API 19 device cannot filter by it.
  # Use --pid only on the emulator; always grep on physical hardware.
  if [[ "$USING_EMULATOR" -eq 1 && -n "$app_pid" ]] \
    && adb_cmd "$ADB_CMD_TIMEOUT_SEC" logcat -h 2>&1 | grep -q -- '--pid'; then
    "${ADB}" -s "$SERIAL" logcat --pid="${app_pid}"
  else
    "${ADB}" -s "$SERIAL" logcat | grep --line-buffered -E "${logcat_grep}"
  fi
}

push_configuration_if_present() {
  local local_cfg="${ROOT}/configuration.json"
  local remote_dir="/data/data/${PACKAGE_ID}/files"
  local remote_cfg="${remote_dir}/configuration.json"

  if [[ "$PUSH_CONFIG" -eq 0 ]]; then
    log "--no-config: leaving the device's ${remote_cfg} alone"
    return
  fi

  if [[ ! -f "$local_cfg" ]]; then
    log "No ${local_cfg}; skipping config push"
    return
  fi

  # This is the working copy's file, not the frame's. It matters most after an uninstall, which is
  # the one path where the frame had no config to keep and this push is the only thing standing
  # between the operator and an unconfigured frame.
  log "Copying ${local_cfg} to device at ${remote_cfg} (overwrites what the frame had)"
  # adb root may work on emulators; needed so we can write into the app data dir before first launch.
  adb_cmd "$ADB_CMD_TIMEOUT_SEC" root >/dev/null 2>&1 || true
  adb_cmd "$ADB_CMD_TIMEOUT_SEC" wait-for-device >/dev/null 2>&1 || true

  adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell "mkdir -p '${remote_dir}'" \
    || die "Failed to create ${remote_dir} on device"

  adb_cmd "$ADB_INSTALL_TIMEOUT_SEC" push "$local_cfg" "$remote_cfg" \
    || die "Failed to push ${local_cfg} to device"

  local uid=""
  uid="$(adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell "stat -c %u /data/data/${PACKAGE_ID}" 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$uid" || "$uid" == *"stat:"* ]]; then
    uid="$(adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell dumpsys package "${PACKAGE_ID}" 2>/dev/null \
      | tr -d '\r' \
      | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' \
      | head -1 || true)"
  fi
  if [[ -n "$uid" && "$uid" =~ ^[0-9]+$ ]]; then
    adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell "chown ${uid}:${uid} '${remote_dir}' '${remote_cfg}'" || true
    adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell "chmod 771 '${remote_dir}'; chmod 660 '${remote_cfg}'" || true
  else
    log "Could not determine app UID; config pushed but ownership may need a relaunch"
  fi
}

# `adb install` on these ROMs answers with a Failure line on stdout while still exiting 0 often
# enough that neither signal alone is trustworthy, so both are recorded; INSTALL_OUT is what the
# classifier reads, because the *text* is the only thing that distinguishes a signing problem from a
# signature clash from a versionCode downgrade — and the right response to those three is "fix the
# build", "uninstall (destructive)" and "retry with -d" respectively.
attempt_install() {
  local out rc=0
  out="$(adb_cmd "$ADB_INSTALL_TIMEOUT_SEC" install "$@" "$APK_PATH" 2>&1)" || rc=$?
  INSTALL_OUT="$out"
  INSTALL_RC="$rc"
  printf '%s\n' "$out"
}

# An uninstall on a frame is not a restart: it takes the operator's credentials, uploads, durable log
# and diagnostics stores with it. So it is asked for, and it is only answerable from a terminal
# unless --yes / EO1_DEPLOY_UNINSTALL=1 already said yes.
confirm_uninstall() {
  local why="$1"
  if [[ "$ASSUME_YES" -eq 1 || "${EO1_DEPLOY_UNINSTALL:-0}" == "1" ]]; then
    log "Uninstalling ${PACKAGE_ID} without asking (--yes / EO1_DEPLOY_UNINSTALL=1): ${why}"
    return 0
  fi
  local config_note
  if [[ "$PUSH_CONFIG" -eq 1 && -f "${ROOT}/configuration.json" ]]; then
    config_note="This checkout's configuration.json will be pushed back afterwards - it is this working copy's file, not necessarily what the frame was running."
  else
    config_note="No configuration.json is being pushed, so the frame will come up with no backends configured."
  fi
  cat >&2 <<EOF
error: ${why}

The only way to deploy this APK is to uninstall ${PACKAGE_ID} on ${SERIAL}. That discards
everything the app keeps on the frame: configuration.json (its backend credentials), the app's
SharedPreferences (trusted network, device name, brightness floor, MQTT client id, update state),
media uploaded through the web page, the downloaded media cache (usually the largest part of it),
files/eo1-app.log, and the crash and diagnostics stores.
${config_note}

Uninstall and continue? [y/N]
EOF
  local answer=""
  if ! read -r answer </dev/tty; then
    die "No terminal to ask on. Re-run with --yes (or EO1_DEPLOY_UNINSTALL=1) to allow the uninstall, or deploy the variant that matches what the frame already runs."
  fi
  if [[ "$answer" != [yY]* ]]; then
    die "Not uninstalling; the device was left exactly as it was."
  fi
}

# A failed uninstall must not look like a completed one: the reinstall that follows would then fail
# with the same signature error and the operator would read that as the APK being rejected, not as the
# uninstall never happening.
uninstall_app() {
  local out rc=0
  log "Uninstalling ${PACKAGE_ID} from ${SERIAL}"
  out="$(adb_cmd "$ADB_INSTALL_TIMEOUT_SEC" uninstall "$PACKAGE_ID" 2>&1)" || rc=$?
  printf '%s\n' "$out"
  if [[ $rc -eq 0 && "$out" == *Success* ]]; then
    UNINSTALLED_APP=1
    return 0
  fi
  if adb_timed_out "$rc"; then
    echo "error: the uninstall timed out on ${SERIAL} after ${ADB_INSTALL_TIMEOUT_SEC}s." >&2
    adb_unresponsive_hint
  fi
  echo "error: the uninstall did not complete (rc=${rc}): ${out}" >&2
  return 1
}

# Once the package is gone the frame has no HOME app and no LAN server, and nothing else in this run
# says so. Every failure exit after that point names the state and the way out.
report_uninstalled_frame() {
  if [[ "$UNINSTALLED_APP" -ne 1 ]]; then
    return 0
  fi
  echo "warning: ${PACKAGE_ID} is now UNINSTALLED on ${SERIAL}: its data is gone, the panel shows the" >&2
  echo "warning: ROM's own home screen (this app is the HOME launcher), and the LAN page on :8080 is down" >&2
  echo "warning: until an APK goes back on. Re-run this script, or finish by hand:" >&2
  echo "warning:   ${ADB} -s ${SERIAL} install -r ${ROOT}/${APK_PATH}" >&2
}

install_apk() {
  local tried_uninstall=0 tried_downgrade=0 installed_facts=""

  log "Installing ${APK_PATH} (timeout ${ADB_INSTALL_TIMEOUT_SEC}s)"
  attempt_install -r
  while [[ $INSTALL_RC -ne 0 || "$INSTALL_OUT" == *Failure* ]]; do
    if adb_timed_out "$INSTALL_RC"; then
      echo "error: adb install timed out on ${SERIAL} after ${ADB_INSTALL_TIMEOUT_SEC}s." >&2
      adb_unresponsive_hint
      report_uninstalled_frame
      exit 1
    fi
    case "$INSTALL_OUT" in
      # NO_CERTIFICATES is tested before PARSE_FAILED because the real string is
      # INSTALL_PARSE_FAILED_NO_CERTIFICATES: one broad PARSE_FAILED branch would send an operator at a
      # corrupt APK to the signing file, which is the wrong door.
      *NO_CERTIFICATES*)
        report_uninstalled_frame
        die "adb install refused ${APK_PATH}: the APK is not signed. assembleRelease signs only when local.properties carries eo1.signing.store.file / .store.password / .key.alias / .key.password, and a worktree has no local.properties of its own - copy it from the main checkout."
        ;;
      *PARSE_FAILED*|*INVALID_APK*)
        report_uninstalled_frame
        die "adb install could not parse ${APK_PATH} (rc=${INSTALL_RC}): ${INSTALL_OUT} - that is the file, not the signing config. Rebuild it."
        ;;
      *OLDER_SDK*|*NO_MATCHING_ABIS*|*UNSUPPORTED_ABIS*)
        report_uninstalled_frame
        die "adb install refused ${APK_PATH}: ${SERIAL} cannot take the ${VARIANT} APK (release is armeabi-v7a only and maxSdk 19, so the API 21 arm64 emulator is not a target). Attach an EO1/EO2, or deploy the debug variant on the emulator."
        ;;
      *UPDATE_INCOMPATIBLE*|*"signatures do not match"*)
        if [[ $tried_uninstall -eq 0 ]]; then
          tried_uninstall=1
          confirm_uninstall "The build on ${SERIAL} is signed with a different certificate than this ${VARIANT} APK - debug and release builds never replace each other."
          if ! uninstall_app; then
            die "Stopped here without deploying. Nothing was destroyed: ${PACKAGE_ID} and its data are still on ${SERIAL}."
          fi
          attempt_install -r
          continue
        fi
        ;;
      *VERSION_DOWNGRADE*)
        if [[ $tried_downgrade -eq 0 ]]; then
          tried_downgrade=1
          log "The installed build has a higher versionCode than this APK; retrying with install -d (app data kept). Prefer passing VERSION_CODE above the code the frame's update manifest publishes — that avoids this retry and keeps the frame from putting the published build back over this one."
          attempt_install -r -d
          continue
        fi
        ;;
    esac
    report_uninstalled_frame
    die "adb install failed on ${SERIAL} (rc=${INSTALL_RC}): ${INSTALL_OUT}"
  done

  # pm's exit line is not proof this hardware took the build: installs on these frames have been
  # observed printing Success (or nothing at all) while lastUpdateTime never moved. Read the identity
  # back off the device, because a release panel shows no version of its own.
  installed_facts="$(adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell dumpsys package "${PACKAGE_ID}" 2>/dev/null \
    | tr -d '\r' | grep -E 'versionCode=|lastUpdateTime=' | head -2 | tr '\n' ' ' || true)"
  log "Installed the ${VARIANT} build on ${SERIAL}${installed_facts:+ — device now reports: ${installed_facts}}"
  if [[ "$VARIANT" == "release" ]]; then
    log "Nothing on a release panel confirms that: the version overlay is BuildConfig.DEBUG-gated and quiet hours leave the panel black. Check the frame itself: ${ADB} -s ${SERIAL} forward tcp:8080 tcp:8080, then http://localhost:8080/state (app.versionCode, app.gitSha, screenOn)."
  fi
}

install_and_start() {
  local device_abi rc

  set +e
  device_abi="$(adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell getprop ro.product.cpu.abi 2>/dev/null)"
  rc=$?
  set -e
  device_abi="$(printf '%s' "$device_abi" | tr -d '\r' | tr -d '\n')"
  if adb_timed_out "$rc"; then
    echo "error: adb shell getprop timed out on ${SERIAL} after ${ADB_CMD_TIMEOUT_SEC}s." >&2
    adb_unresponsive_hint
    exit 1
  fi
  if [[ $rc -ne 0 ]]; then
    die "adb shell getprop failed on ${SERIAL} (rc=${rc})"
  fi

  case "$device_abi" in
    armeabi-v7a|armeabi) ;;
    arm64-v8a)
      if [[ "$USING_EMULATOR" -ne 1 ]]; then
        die "Cannot install on arm64-v8a device: connect an EO1/EO2 (armeabi-v7a) or use the Apple Silicon emulator fallback."
      fi
      if [[ "$ALLOW_ABI_FALLBACK" -ne 1 ]]; then
        die "Cannot install the ${VARIANT} APK on the arm64-v8a emulator: release ships armeabi-v7a only and caps at maxSdk 19 (app/src/release/AndroidManifest.xml). Attach an EO1/EO2, or deploy the debug variant here."
      fi
      log "arm64-v8a emulator: no native TsPlayer (.so is armeabi-v7a only); MediaPlayer only here"
      ;;
    *)
      die "Cannot install APK: device ABI is '${device_abi:-unknown}'. ${VARIANT} APK supports: ${SUPPORTED_ABIS}."
      ;;
  esac

  install_apk

  # Order kept from debug.sh: the push runs after the install, because on the uninstall path it is
  # the only thing left standing between the operator and an unconfigured frame.
  push_configuration_if_present

  log "Force-stopping ${PACKAGE_ID} for cold start"
  adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell am force-stop "${PACKAGE_ID}" >/dev/null 2>&1 || true

  log "Starting ${ACTIVITY}"
  set +e
  adb_cmd "$ADB_CMD_TIMEOUT_SEC" shell am start -n "$ACTIVITY"
  rc=$?
  set -e
  if adb_timed_out "$rc"; then
    echo "error: adb shell am start timed out on ${SERIAL} after ${ADB_CMD_TIMEOUT_SEC}s." >&2
    adb_unresponsive_hint
    exit 1
  fi
  if [[ $rc -ne 0 ]]; then
    die "adb shell am start failed on ${SERIAL} (rc=${rc})"
  fi
}

# --- main --------------------------------------------------------------------

parse_args "$@"

SDK_ROOT="$(resolve_sdk_root)"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

ADB="$(find_sdk_bin adb)"
EMULATOR="$(find_sdk_bin emulator)"
AVDMANAGER="$(find_sdk_bin avdmanager)"
SDKMANAGER="$(find_sdk_bin sdkmanager)"

export PATH="/usr/bin:/bin:/usr/sbin:/sbin:$(dirname "$ADB"):$(dirname "$EMULATOR"):$(dirname "$AVDMANAGER"):${PATH}"

init_timeout_bin

command -v java >/dev/null 2>&1 || die "java not found on PATH (need JDK 21 for this project)"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi
  if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
    JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME
    export PATH="${JAVA_HOME}/bin:${PATH}"
  fi
fi
log "Using $(java -version 2>&1 | head -1)"

[[ -x "./gradlew" ]] || die "./gradlew not found in ${ROOT}"

trap cleanup INT TERM EXIT

# assembleRelease signs only from local.properties, and an unsigned APK is installable by nothing —
# so this is said before the build rather than as an install failure after it. publish-update.sh
# refuses to publish such a build; a deploy must not quietly produce one either.
if [[ "$VARIANT" == "release" ]] && ! release_signing_configured; then
  if [[ "${EO1_DEPLOY_ALLOW_UNSIGNED:-0}" == "1" ]]; then
    log "WARNING: no eo1.signing.store.file in ${ROOT}/local.properties - the release APK will be UNSIGNED and no device will install it."
  else
    die "No signing config found: ${ROOT}/local.properties is missing or has no eo1.signing.store.file. assembleRelease would produce an UNSIGNED APK that no device accepts, and a worktree has no local.properties of its own - copy it from the main checkout. (Set EO1_DEPLOY_ALLOW_UNSIGNED=1 to build anyway.)"
  fi
fi

log "Building ${VARIANT} APK..."
GRADLE_ARGS=("$GRADLE_TASK")
if [[ -n "${VERSION_CODE:-}" ]]; then
  GRADLE_ARGS+=("-PVERSION_CODE=${VERSION_CODE}")
  log "Passing -PVERSION_CODE=${VERSION_CODE}"
fi
./gradlew "${GRADLE_ARGS[@]}"
[[ -f "$APK_PATH" ]] || die "${VARIANT} APK missing at ${APK_PATH}"

if [[ "$VARIANT" == "release" ]]; then
  log "Release build: R8-minified, so the on-screen debug board and /state's debug-gated fields are off; the trace this session streams names obfuscated classes until retraced (the durable record is still files/eo1-app.log)."

  # A deployed-but-unpublished release has no entry in publish-update.sh's versionCode -> mapping
  # ledger, and app/build/outputs/mapping/release/mapping.txt is overwritten by the very next
  # assembleRelease (and deleted by any clean). So the mapping that decodes THIS frame's next crash is
  # archived with the identity of the build it belongs to, beside the APK it belongs to.
  DEPLOY_GIT_SHA="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  DEPLOY_DIRTY=""
  if [[ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null | head -1)" ]]; then
    DEPLOY_DIRTY=" (dirty working tree)"
  fi
  log "Built from ${DEPLOY_GIT_SHA}${DEPLOY_DIRTY}"
  if [[ -f "app/build/outputs/mapping/release/mapping.txt" ]]; then
    DEPLOY_MAPPING="app/build/outputs/apk/release/mapping-${VERSION_CODE:-1}-${DEPLOY_GIT_SHA}.txt.gz"
    if gzip -c "app/build/outputs/mapping/release/mapping.txt" > "$DEPLOY_MAPPING"; then
      log "Archived this build's R8 mapping to ${DEPLOY_MAPPING} — retrace with that copy, not with app/build/outputs/mapping/release/mapping.txt, which the next assembleRelease overwrites."
    else
      rm -f "$DEPLOY_MAPPING"
      log "WARNING: could not archive the R8 mapping; crash dumps from versionCode ${VERSION_CODE:-1} will only be retraceable while app/build/outputs/mapping/release/mapping.txt survives."
    fi
  fi
  # UpdateManager stages whatever the manifest publishes at a *strictly greater* versionCode than the
  # installed app (UpdateManager:1389), so a deploy below the published code is transient: the frame
  # puts the published build back over it on its next update cycle, once it has a working install
  # transport. Say it while there is still time to re-run with a different versionCode.
  log "versionCode of this APK: ${VERSION_CODE:-1} (app/build.gradle default). If the frame's update manifest publishes anything greater, the frame will replace this deploy with the published build — pass VERSION_CODE=<greater than the published code> to make the deploy stick."
fi

CHOOSER_RC=0
selected="$(select_physical_serial)" || CHOOSER_RC=$?
if [[ $CHOOSER_RC -eq 0 ]]; then
  SERIAL="$selected"
  log "Physical device ${SERIAL} detected; skipping emulator"
  ensure_device_responsive
  install_and_start
  follow_logcat
  exit 0
fi
if [[ $CHOOSER_RC -ne 1 ]]; then
  echo "error: A device is attached but adb cannot use it, so this run did not fall back to the emulator." >&2
  echo "error: ${ADB} kill-server && ${ADB} start-server, accept the authorization prompt on the frame, then re-run." >&2
  exit "$CHOOSER_RC"
fi

# No device attached, so this is now an emulator question — and for release there is no emulator
# answer on this host. Said before a 180s doomed boot rather than after it.
if [[ "$VARIANT" == "release" ]] && is_apple_silicon; then
  echo "error: No physical EO1/EO2 is attached, and the ${VARIANT} APK has no emulator target on Apple Silicon:" >&2
  echo "error: API 19 images do not boot there, and release is armeabi-v7a only with maxSdk 19, so the API 21 arm64 fallback AVD cannot install it either." >&2
  echo "error: Attach the frame (PREFERRED_PHYSICAL_SERIAL=${PREFERRED_PHYSICAL_SERIAL}), or use ./deploy-debug.sh for the emulator path." >&2
  CLEANED_UP=1
  trap - INT TERM EXIT
  exit 1
fi

# Primary: API 19 AVD EO1
AVD_NAME="EO1"
EXPECTED_API=19

# ensure_avd validates an existing AVD by API only, so a pre-existing x86 EO1 would otherwise be
# booted for ${BOOT_TIMEOUT_SEC}s and then refused by pm at install time. Checked before booting,
# and never resolved by deleting the AVD — an emulator someone else built is not this deploy's to eat.
if [[ "$VARIANT" == "release" ]] && avd_exists "$AVD_NAME"; then
  avd_cfg="$(avd_config_path "$AVD_NAME" 2>/dev/null || true)"
  avd_abi=""
  [[ -n "$avd_cfg" ]] && avd_abi="$(grep -E '^abi\.type=' "$avd_cfg" | head -1 | cut -d= -f2- || true)"
  if [[ -n "$avd_abi" && "$avd_abi" != "armeabi-v7a" && "$avd_abi" != "armeabi" ]]; then
    die "AVD ${AVD_NAME} is a '${avd_abi}' image, which cannot take the release APK (armeabi-v7a only, maxSdk 19). Attach an EO1/EO2 over USB; or delete ~/.android/avd/${AVD_NAME} to let this script recreate it from system-images;android-19;default;armeabi-v7a — ARM emulation on this host is slow, and ./deploy-debug.sh runs on the AVD as it is."
  fi
fi

ensure_avd 19 "system-images;android-19;default;x86"

if launch_avd; then
  install_and_start
  follow_logcat
  exit 0
fi

# Primary failed — stop dead emulator before fallback
stop_emulator_instance

# The fallback AVD is API 21 arm64-v8a, which the release APK cannot be installed on at all
# (armeabi-v7a only, maxSdk 19). Booting it would only move the failure from here to adb install.
if [[ "$ALLOW_ABI_FALLBACK" -ne 1 ]]; then
  echo "error: Emulator AVD ${AVD_NAME} (API ${EXPECTED_API}) did not become ready within ${BOOT_TIMEOUT_SEC}s," >&2
  echo "error: and the ${VARIANT} APK has no fallback target - the API ${FALLBACK_API} arm64-v8a AVD cannot install an armeabi-v7a/maxSdk 19 build." >&2
  echo "error: Attach a physical EO1/EO2. This variant prefers an armeabi-v7a API 19 image (an x86 image offers x86 plus armeabi, never armeabi-v7a, so it refuses this APK); the API 21 arm64 fallback exists only for ./deploy-debug.sh." >&2
  echo "error: Emulator log: ${EMU_LOG}" >&2
  tail -n 40 "$EMU_LOG" >&2 || true
  CLEANED_UP=1
  trap - INT TERM EXIT
  exit 1
fi

if ! is_apple_silicon; then
  boot_failure_hint
  echo "error: Emulator log: ${EMU_LOG}" >&2
  tail -n 40 "$EMU_LOG" >&2 || true
  CLEANED_UP=1
  trap - INT TERM EXIT
  exit 1
fi

log "API 19 failed; falling back to ${FALLBACK_AVD_NAME} (API ${FALLBACK_API} arm64-v8a)"
AVD_NAME="$FALLBACK_AVD_NAME"
EXPECTED_API="$FALLBACK_API"
ensure_avd "$FALLBACK_API" "$FALLBACK_SYSTEM_IMAGE" "arm64-v8a"

if ! launch_avd; then
  stop_emulator_instance
  echo "error: Primary API 19 and fallback API ${FALLBACK_API} AVDs both failed to boot." >&2
  echo "error: Emulator log: ${EMU_LOG}" >&2
  tail -n 40 "$EMU_LOG" >&2 || true
  CLEANED_UP=1
  trap - INT TERM EXIT
  exit 1
fi

install_and_start
follow_logcat
