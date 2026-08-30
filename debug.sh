#!/bin/bash
# Build the debug APK, deploy to a connected physical device when available
# (preferring PREFERRED_PHYSICAL_SERIAL, default 20061229), otherwise ensure/start
# an API 19 AVD named EO1 (EO1-like constraints), install the app, and stream
# logcat until Ctrl+C.
# On Apple Silicon, if API 19 cannot boot, fall back to EO1_API21 (API 21 arm64-v8a).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

AVD_NAME="EO1"
EXPECTED_API=19
PACKAGE_ID="com.aphex3k.eo1"
ACTIVITY="${PACKAGE_ID}/${PACKAGE_ID}.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
BOOT_TIMEOUT_SEC="${BOOT_TIMEOUT_SEC:-180}"
EMU_LOG="/tmp/eo1-emulator.log"
FALLBACK_AVD_NAME="EO1_API21"
FALLBACK_SYSTEM_IMAGE="system-images;android-21;default;arm64-v8a"
FALLBACK_API=21
PREFERRED_PHYSICAL_SERIAL="${PREFERRED_PHYSICAL_SERIAL:-20061229}"

EMU_PID=""
SERIAL=""
CLEANED_UP=0
USING_EMULATOR=0
SYSTEM_IMAGE=""

die() {
  echo "error: $*" >&2
  exit 1
}

log() {
  echo "==> $*"
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
    validate_existing_avd "$expected_api" "$expected_abi"
    patch_avd_config
    local local_abi
    local_abi="$(grep -E '^abi.type=' "$(avd_config_path "$AVD_NAME")" | head -1 | cut -d= -f2-)"
    SYSTEM_IMAGE="system-images;android-${expected_api};default;${local_abi}"
  elif [[ "$expected_api" -eq 19 ]]; then
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

select_physical_serial() {
  local ready_serials=""
  local attached_serials=""
  local preferred_state=""
  local serial state count_ready count_attached

  ready_serials="$(list_physical_serials)"
  attached_serials="$(list_attached_physical_serials)"

  preferred_state="$(physical_device_state "$PREFERRED_PHYSICAL_SERIAL")"
  if [[ -n "$preferred_state" && "$preferred_state" != "device" ]]; then
    die "Preferred physical device ${PREFERRED_PHYSICAL_SERIAL} is attached but not ready (state: ${preferred_state}). Fix adb authorization or connection."
  fi

  count_attached="$(printf '%s\n' "$attached_serials" | sed '/^$/d' | wc -l | tr -d ' ')"
  count_ready="$(printf '%s\n' "$ready_serials" | sed '/^$/d' | wc -l | tr -d ' ')"

  if [[ "$count_attached" -eq 1 && "$count_ready" -eq 0 ]]; then
    serial="$(printf '%s\n' "$attached_serials" | sed '/^$/d' | head -1)"
    state="$(physical_device_state "$serial")"
    die "Physical device ${serial} is attached but not ready (state: ${state}). Fix adb authorization or connection."
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

  die "Multiple physical devices connected ($(printf '%s\n' "$ready_serials" | sed '/^$/d' | tr '\n' ' ')) but preferred serial ${PREFERRED_PHYSICAL_SERIAL} is not among them. Disconnect extras or attach the preferred device."
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
  pid="$("${ADB}" -s "$SERIAL" shell pidof "${PACKAGE_ID}" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if [[ -n "$pid" ]]; then
    echo "$pid"
    return
  fi
  "${ADB}" -s "$SERIAL" shell ps 2>/dev/null | tr -d '\r' | awk -v p="$PACKAGE_ID" '$NF == p { print $2; exit }'
}

follow_logcat() {
  if [[ "$USING_EMULATOR" -eq 1 ]]; then
    log "Attaching to logcat for ${PACKAGE_ID} (Ctrl+C to stop and quit the AVD)..."
  else
    log "Attaching to logcat for ${PACKAGE_ID} (Ctrl+C to stop)..."
  fi
  "${ADB}" -s "$SERIAL" logcat -c >/dev/null 2>&1 || true

  local app_pid=""
  local i
  for i in $(seq 1 30); do
    app_pid="$(app_pid_on_device || true)"
    if [[ -n "$app_pid" ]]; then
      break
    fi
    sleep 1
  done

  if [[ -n "$app_pid" ]] && "${ADB}" -s "$SERIAL" logcat -h 2>&1 | grep -q -- '--pid'; then
    "${ADB}" -s "$SERIAL" logcat --pid="${app_pid}"
  else
    "${ADB}" -s "$SERIAL" logcat | grep --line-buffered -E "${PACKAGE_ID}|EO1|Immich|MainActivity|AndroidRuntime|System.err"
  fi
}

push_configuration_if_present() {
  local local_cfg="${ROOT}/configuration.json"
  local remote_dir="/data/data/${PACKAGE_ID}/files"
  local remote_cfg="${remote_dir}/configuration.json"

  if [[ ! -f "$local_cfg" ]]; then
    log "No ${local_cfg}; skipping config push"
    return
  fi

  log "Copying ${local_cfg} to device at ${remote_cfg}"
  # adb root may work on emulators; needed so we can write into the app data dir before first launch.
  "${ADB}" -s "$SERIAL" root >/dev/null 2>&1 || true
  "${ADB}" -s "$SERIAL" wait-for-device >/dev/null 2>&1 || true

  "${ADB}" -s "$SERIAL" shell "mkdir -p '${remote_dir}'" \
    || die "Failed to create ${remote_dir} on device"

  "${ADB}" -s "$SERIAL" push "$local_cfg" "$remote_cfg" \
    || die "Failed to push ${local_cfg} to device"

  local uid=""
  uid="$("${ADB}" -s "$SERIAL" shell "stat -c %u /data/data/${PACKAGE_ID}" 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$uid" || "$uid" == *"stat:"* ]]; then
    uid="$("${ADB}" -s "$SERIAL" shell dumpsys package "${PACKAGE_ID}" 2>/dev/null \
      | tr -d '\r' \
      | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' \
      | head -1 || true)"
  fi
  if [[ -n "$uid" && "$uid" =~ ^[0-9]+$ ]]; then
    "${ADB}" -s "$SERIAL" shell "chown ${uid}:${uid} '${remote_dir}' '${remote_cfg}'" || true
    "${ADB}" -s "$SERIAL" shell "chmod 771 '${remote_dir}'; chmod 660 '${remote_cfg}'" || true
  else
    log "Could not determine app UID; config pushed but ownership may need a relaunch"
  fi
}

install_and_start() {
  log "Setting display size 1080x1920"
  "${ADB}" -s "$SERIAL" shell wm size 1080x1920 || true

  log "Installing ${APK_PATH}"
  "${ADB}" -s "$SERIAL" install -r "$APK_PATH"

  push_configuration_if_present

  log "Starting ${ACTIVITY}"
  "${ADB}" -s "$SERIAL" shell am start -n "$ACTIVITY"
}

# --- main --------------------------------------------------------------------

SDK_ROOT="$(resolve_sdk_root)"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

ADB="$(find_sdk_bin adb)"
EMULATOR="$(find_sdk_bin emulator)"
AVDMANAGER="$(find_sdk_bin avdmanager)"
SDKMANAGER="$(find_sdk_bin sdkmanager)"

export PATH="/usr/bin:/bin:/usr/sbin:/sbin:$(dirname "$ADB"):$(dirname "$EMULATOR"):$(dirname "$AVDMANAGER"):${PATH}"

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

log "Building debug APK..."
./gradlew assembleDebug
[[ -f "$APK_PATH" ]] || die "Debug APK missing at ${APK_PATH}"

if selected="$(select_physical_serial)"; then
  SERIAL="$selected"
  log "Physical device ${SERIAL} detected; skipping emulator"
  install_and_start
  follow_logcat
  exit 0
fi

# Primary: API 19 AVD EO1
AVD_NAME="EO1"
EXPECTED_API=19
ensure_avd 19 "system-images;android-19;default;x86"

if launch_avd; then
  install_and_start
  follow_logcat
  exit 0
fi

# Primary failed — stop dead emulator before fallback
stop_emulator_instance

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
