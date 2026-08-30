#!/bin/bash
# Build the debug APK, ensure/start an API 19 AVD named EO1 (EO1-like constraints),
# install the app, and stream logcat until Ctrl+C.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

AVD_NAME="EO1"
PACKAGE_ID="com.aphex3k.eo1"
ACTIVITY="${PACKAGE_ID}/${PACKAGE_ID}.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
BOOT_TIMEOUT_SEC="${BOOT_TIMEOUT_SEC:-180}"

EMU_PID=""
SERIAL=""
CLEANED_UP=0

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
  # Newest cmdline-tools version dir if "latest" is missing
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
  local ini="${HOME}/.android/avd/${AVD_NAME}.ini"
  [[ -f "$ini" ]] || return 1
  local path
  path="$(grep -E '^path=' "$ini" | head -1 | cut -d= -f2-)"
  [[ -n "$path" && -f "${path}/config.ini" ]] || return 1
  echo "${path}/config.ini"
}

avd_exists() {
  "${EMULATOR}" -list-avds 2>/dev/null | grep -qx "${AVD_NAME}"
}

validate_existing_avd() {
  local config
  config="$(avd_config_path)" || die "AVD ${AVD_NAME} is listed but config.ini was not found under ~/.android/avd/"

  local target abi sysdir
  target="$(grep -E '^target=' "$config" | head -1 | cut -d= -f2- || true)"
  abi="$(grep -E '^abi.type=' "$config" | head -1 | cut -d= -f2- || true)"
  sysdir="$(grep -E '^image\.sysdir\.1=' "$config" | head -1 | cut -d= -f2- || true)"

  local api_ok=0
  if [[ "$target" == "android-19" ]]; then
    api_ok=1
  elif [[ "$sysdir" == *"/android-19/"* || "$sysdir" == *"android-19/"* ]]; then
    api_ok=1
    target="android-19"
  fi

  if [[ "$api_ok" -ne 1 ]]; then
    die "AVD ${AVD_NAME} exists but is not API 19 (target='${target:-unknown}', image='${sysdir:-unknown}'). Rename or delete it, then re-run (maxSdk is 19)."
  fi
  if [[ -z "$abi" ]]; then
    die "AVD ${AVD_NAME} has no abi.type in config.ini"
  fi
  log "Using existing AVD ${AVD_NAME} (target=${target}, abi=${abi})"
}

image_installed() {
  local pkg="$1"
  local rel="${pkg#system-images;}"
  rel="${rel//;/\//}"
  [[ -d "${SDK_ROOT}/system-images/${rel}" ]]
}

preferred_system_images() {
  if is_apple_silicon; then
    echo "system-images;android-19;default;armeabi-v7a"
    echo "system-images;android-19;default;x86"
  else
    echo "system-images;android-19;default;x86"
    echo "system-images;android-19;default;armeabi-v7a"
  fi
}

ensure_system_image() {
  local pkg
  for pkg in $(preferred_system_images); do
    if image_installed "$pkg"; then
      SYSTEM_IMAGE="$pkg"
      log "Using system image ${SYSTEM_IMAGE}"
      return
    fi
  done

  # Install first preference for this host
  SYSTEM_IMAGE="$(preferred_system_images | { read -r first; echo "$first"; })"
  log "Installing system image ${SYSTEM_IMAGE} via sdkmanager..."
  set +o pipefail
  yes | "${SDKMANAGER}" --licenses >/dev/null 2>&1 || true
  yes | "${SDKMANAGER}" "${SYSTEM_IMAGE}"
  local sdk_rc=$?
  set -o pipefail
  [[ $sdk_rc -eq 0 ]] || die "Failed to install ${SYSTEM_IMAGE}"
  image_installed "${SYSTEM_IMAGE}" || die "System image ${SYSTEM_IMAGE} still missing after install"
}

device_eo1_available() {
  "${AVDMANAGER}" list device 2>/dev/null | grep -Eq 'id:[[:space:]]*[0-9]+[[:space:]]+or[[:space:]]+"EO1"'
}

patch_avd_config() {
  local config
  config="$(avd_config_path)" || die "Cannot patch AVD config for ${AVD_NAME}"

  set_ini() {
    local key="$1" value="$2"
    if grep -qE "^${key}=" "$config"; then
      # portable in-place edit
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

create_avd() {
  ensure_system_image
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
    # Ensure skin dimensions when no custom device profile
    local config
    config="$(avd_config_path)"
    if ! grep -qE '^skin.name=' "$config"; then
      echo "skin.name=1080x1920" >>"$config"
      echo "skin.path=1080x1920" >>"$config"
    fi
  fi

  patch_avd_config
}

cleanup() {
  if [[ "$CLEANED_UP" -eq 1 ]]; then
    return
  fi
  CLEANED_UP=1

  # Only print/stop if we actually started the emulator
  if [[ -z "$EMU_PID" && -z "$SERIAL" ]]; then
    return
  fi

  echo
  log "Stopping debug session..."

  if [[ -n "$SERIAL" ]]; then
    "${ADB}" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  fi
  if [[ -n "$EMU_PID" ]] && kill -0 "$EMU_PID" 2>/dev/null; then
    kill "$EMU_PID" >/dev/null 2>&1 || true
    wait "$EMU_PID" 2>/dev/null || true
  fi
}

list_emulator_serials() {
  "${ADB}" devices 2>/dev/null | awk '/^emulator-[0-9]+\tdevice$/ { print $1 }'
}

wait_for_boot() {
  local deadline=$((SECONDS + BOOT_TIMEOUT_SEC))
  local preexisting="$1"
  local serial=""
  local emu_log="${2:-/tmp/eo1-emulator.log}"

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

boot_failure_hint() {
  echo "error: Emulator AVD ${AVD_NAME} (API 19) did not become ready within ${BOOT_TIMEOUT_SEC}s." >&2
  if is_apple_silicon; then
    echo "error: This host is Apple Silicon (arm64). API 19 system images (armeabi-v7a / x86) generally cannot run here." >&2
    echo "error: Use an x86_64 Linux or Intel Mac host (as Jenkins does with system-images;android-19;default;x86), or a remote x86 emulator." >&2
  else
    echo "error: Check that KVM/HAXM acceleration works and that ${SYSTEM_IMAGE:-the API 19 image} is installed." >&2
  fi
}

app_pid_on_device() {
  # pidof is missing on some API 19 images; fall back to ps.
  local pid
  pid="$("${ADB}" -s "$SERIAL" shell pidof "${PACKAGE_ID}" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if [[ -n "$pid" ]]; then
    echo "$pid"
    return
  fi
  "${ADB}" -s "$SERIAL" shell ps 2>/dev/null | tr -d '\r' | awk -v p="$PACKAGE_ID" '$NF == p { print $2; exit }'
}

follow_logcat() {
  log "Attaching to logcat for ${PACKAGE_ID} (Ctrl+C to stop and quit the AVD)..."
  # Clear once so the session starts fresh
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
    # API 19-friendly fallback: filter common app tags / package string
    "${ADB}" -s "$SERIAL" logcat | grep --line-buffered -E "${PACKAGE_ID}|EO1|Immich|MainActivity|AndroidRuntime|System.err"
  fi
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

# Prefer JDK 21 when multiple JDKs are installed (Gradle 8.9 / AGP 8.7 do not support newer majors).
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

SYSTEM_IMAGE=""
if avd_exists; then
  validate_existing_avd
  patch_avd_config
  # Infer image package from existing AVD for messaging
  local_abi="$(grep -E '^abi.type=' "$(avd_config_path)" | head -1 | cut -d= -f2-)"
  SYSTEM_IMAGE="system-images;android-19;default;${local_abi}"
else
  create_avd
fi

log "Launching emulator ${AVD_NAME}..."
PREEXISTING_SERIALS="$(list_emulator_serials | tr '\n' ' ')"
# Windowed interactive session; constraints align with Jenkins EO1 profile.
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
  >/tmp/eo1-emulator.log 2>&1 &
EMU_PID=$!

if ! wait_for_boot "$PREEXISTING_SERIALS" /tmp/eo1-emulator.log; then
  cleanup
  CLEANED_UP=1
  trap - INT TERM EXIT
  boot_failure_hint
  echo "error: Emulator log: /tmp/eo1-emulator.log" >&2
  tail -n 40 /tmp/eo1-emulator.log >&2 || true
  exit 1
fi

log "Setting display size 1080x1920"
"${ADB}" -s "$SERIAL" shell wm size 1080x1920 || true

log "Installing ${APK_PATH}"
"${ADB}" -s "$SERIAL" install -r "$APK_PATH"

log "Starting ${ACTIVITY}"
"${ADB}" -s "$SERIAL" shell am start -n "$ACTIVITY"

follow_logcat
