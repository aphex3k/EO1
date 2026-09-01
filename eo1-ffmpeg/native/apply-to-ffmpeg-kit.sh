#!/usr/bin/env bash
# Copy EO1 custom-library build scripts into an ffmpeg-kit checkout and print
# android.sh flags: android-zlib (PNG) + optional libde265/libheif static builds.
#
# FFmpeg LTS n6.0 has no --enable-libheif. Custom libraries still download/build
# static archives; their ffmpeg-enable-flag is "zlib" (already enabled) so configure
# does not fail. HEIC decode through ffmpeg itself is not available on LTS.
#
# Usage:
#   ./eo1-ffmpeg/native/apply-to-ffmpeg-kit.sh /path/to/ffmpeg-kit
#   ./eo1-ffmpeg/native/apply-to-ffmpeg-kit.sh /path/to/ffmpeg-kit --print-flags-only
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRINT_FLAGS_ONLY=0
KIT_DIR=""

for arg in "$@"; do
  case "$arg" in
    --print-flags-only) PRINT_FLAGS_ONLY=1 ;;
    *) KIT_DIR="$arg" ;;
  esac
done

if [[ -z "${KIT_DIR}" ]]; then
  echo "Usage: $0 /path/to/ffmpeg-kit [--print-flags-only]" >&2
  exit 1
fi

if [[ ! -d "${KIT_DIR}/scripts/android" ]]; then
  echo "Not an ffmpeg-kit tree (missing scripts/android): ${KIT_DIR}" >&2
  exit 1
fi

if [[ "${PRINT_FLAGS_ONLY}" -eq 0 ]]; then
  cp "${SCRIPT_DIR}/scripts/android/libde265.sh" "${KIT_DIR}/scripts/android/libde265.sh"
  cp "${SCRIPT_DIR}/scripts/android/libheif.sh" "${KIT_DIR}/scripts/android/libheif.sh"
  chmod +x "${KIT_DIR}/scripts/android/libde265.sh" "${KIT_DIR}/scripts/android/libheif.sh"
fi

# zlib enable-flag is intentional: LTS FFmpeg rejects --enable-libheif / --enable-libde265.
cat <<'EOF'
--enable-android-zlib
--enable-custom-library-1-name=libde265
--enable-custom-library-1-repo=https://github.com/strukturag/libde265.git
--enable-custom-library-1-repo-tag=v1.0.15
--enable-custom-library-1-package-config-file-name=libde265
--enable-custom-library-1-ffmpeg-enable-flag=zlib
--enable-custom-library-1-license-file=COPYING
--enable-custom-library-2-name=libheif
--enable-custom-library-2-repo=https://github.com/strukturag/libheif.git
--enable-custom-library-2-repo-tag=v1.17.6
--enable-custom-library-2-package-config-file-name=libheif
--enable-custom-library-2-ffmpeg-enable-flag=zlib
--enable-custom-library-2-license-file=COPYING
--enable-custom-library-2-uses-cpp
EOF
