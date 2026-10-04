#!/usr/bin/env bash
#
# publish-update.sh — build signed release and debug APKs and stage them for the app's self-update.
#
# Usage:
#   ./publish-update.sh <VERSION_CODE> [VERSION_NAME]
#
# Environment:
#   EO1_UPDATE_HOST    Base URL the frames will fetch from, no trailing slash,
#                      e.g. http://192.168.1.50/eo1. Required.
#   EO1_UPDATE_DIR     Local staging dir (default: ./update-out). Mirrored to the remote.
#   EO1_UPDATE_REMOTE  Optional rsync target, user@host:/path. Mirrors the staging dir.
#   EO1_UPDATE_NOTES   Optional free-text note embedded in the manifest.
#   EO1_DEBUG_DIR      Local dir for the debug APK (default: ./update-out-debug). Never mirrored.
#
# Signing is read from the gitignored root local.properties:
#   eo1.signing.store.file / eo1.signing.store.password /
#   eo1.signing.key.alias (default EO1) / eo1.signing.key.password
#
# The release APK is published as eo1-release-<VERSION_CODE>.apk next to update-manifest.json.
# The debug APK (signed with the release key so it can auto-upgrade to release, but built
# non-debuggable) is written to the debug dir as eo1-debug-<VERSION_CODE>.apk. It is NOT
# mirrored to the remote: a release-signed build must not be publicly reachable on the update host.
# VERSION_CODE must be strictly greater than the last published one (monotonic, recorded
# in <UPDATE_DIR>/.last-published-version).
#

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

[ $# -ge 1 ] || { echo "Usage: ./publish-update.sh <VERSION_CODE> [VERSION_NAME]" >&2; exit 2; }
VERSION_CODE=$1
VERSION_NAME=${2:-}

case "$VERSION_CODE" in
    ''|*[!0-9]*) echo "ERROR: VERSION_CODE must be a positive integer, got: '$VERSION_CODE'" >&2; exit 2 ;;
esac
if [ "$VERSION_CODE" -le 0 ]; then
    echo "ERROR: VERSION_CODE must be > 0" >&2; exit 2
fi

if [ -z "${EO1_UPDATE_HOST:-}" ]; then
    echo "ERROR: EO1_UPDATE_HOST is required (base URL, no trailing slash)." >&2
    exit 2
fi
case "$EO1_UPDATE_HOST" in
    http://*|https://*) ;;
    *) echo "ERROR: EO1_UPDATE_HOST must start with http:// or https://" >&2; exit 2 ;;
esac
case "$EO1_UPDATE_HOST" in
    */) echo "ERROR: EO1_UPDATE_HOST must not end with '/'" >&2; exit 2 ;;
esac

if ! grep -q '^eo1\.signing\.store\.file=' local.properties 2>/dev/null; then
    echo "ERROR: root local.properties must set eo1.signing.store.file (release signing)." >&2
    exit 2
fi

UPDATE_DIR="${EO1_UPDATE_DIR:-$ROOT/update-out}"
# The debug APK is built non-debuggable when release-signed (see build.gradle) and is
# staged in a separate directory so it is never mirrored to the public update host.
DEBUG_DIR="${EO1_DEBUG_DIR:-$ROOT/update-out-debug}"
mkdir -p "$UPDATE_DIR" "$DEBUG_DIR"

LAST_FILE="$UPDATE_DIR/.last-published-version"
if [ -f "$LAST_FILE" ]; then
    LAST=$(cat "$LAST_FILE")
    if ! case "$LAST" in ''|*[!0-9]*) false ;; *) true ;; esac; then
        echo "ERROR: $LAST_FILE is corrupt ('$LAST'); fix or delete it manually." >&2; exit 2
    fi
    if [ "$VERSION_CODE" -le "$LAST" ]; then
        echo "ERROR: VERSION_CODE $VERSION_CODE must be strictly greater than the last published version $LAST." >&2
        exit 2
    fi
fi

echo "==> Building release APK (versionCode $VERSION_CODE${VERSION_NAME:+, versionName $VERSION_NAME})"
if [ -n "$VERSION_NAME" ]; then
    ./gradlew assembleRelease "-PVERSION_CODE=$VERSION_CODE" "-PVERSION_NAME=$VERSION_NAME"
else
    ./gradlew assembleRelease "-PVERSION_CODE=$VERSION_CODE"
fi

echo "==> Building debug APK (signed with release key) (versionCode $VERSION_CODE${VERSION_NAME:+, versionName $VERSION_NAME})"
if [ -n "$VERSION_NAME" ]; then
    ./gradlew assembleDebug "-PVERSION_CODE=$VERSION_CODE" "-PVERSION_NAME=$VERSION_NAME" "-DUSE_RELEASE_SIGNING_FOR_DEBUG=true"
else
    ./gradlew assembleDebug "-PVERSION_CODE=$VERSION_CODE" "-DUSE_RELEASE_SIGNING_FOR_DEBUG=true"
fi

RELEASE_APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$RELEASE_APK" ]; then
    echo "ERROR: build did not produce $RELEASE_APK" >&2; exit 1
fi

DEBUG_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$DEBUG_APK" ]; then
    echo "ERROR: build did not produce $DEBUG_APK" >&2; exit 1
fi

if command -v shasum >/dev/null 2>&1; then
    SHA256=$(shasum -a 256 "$RELEASE_APK" | awk '{print $1}')
else
    SHA256=$(sha256sum "$RELEASE_APK" | awk '{print $1}')
fi
SIZE=$(wc -c < "$RELEASE_APK" | tr -d '[:space:]')

json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

NOTES="${EO1_UPDATE_NOTES:-}"
if [ -z "$NOTES" ] && [ -n "$VERSION_NAME" ]; then
    NOTES="EO1 $VERSION_NAME"
fi

RELEASE_APK_NAME="eo1-release-$VERSION_CODE.apk"
DEBUG_APK_NAME="eo1-debug-$VERSION_CODE.apk"
cp "$RELEASE_APK" "$UPDATE_DIR/$RELEASE_APK_NAME"
# Debug APK is built for local testing/validation but NOT published to the update host
cp "$DEBUG_APK" "$DEBUG_DIR/$DEBUG_APK_NAME"

MANIFEST="$UPDATE_DIR/update-manifest.json"
cat > "$MANIFEST" <<EOF
{
  "versionCode": $VERSION_CODE,
  "versionName": "$(json_escape "$VERSION_NAME")",
  "apkUrl": "$EO1_UPDATE_HOST/$RELEASE_APK_NAME",
  "sha256": "$SHA256",
  "sizeBytes": $SIZE,
  "notes": "$(json_escape "$NOTES")"
}
EOF

echo "$VERSION_CODE" > "$LAST_FILE"

if [ -n "${EO1_UPDATE_REMOTE:-}" ]; then
    command -v rsync >/dev/null 2>&1 || { echo "ERROR: rsync is required for EO1_UPDATE_REMOTE." >&2; exit 2; }
    echo "==> Mirroring $UPDATE_DIR/ to $EO1_UPDATE_REMOTE"
    rsync -avz "$UPDATE_DIR/" "$EO1_UPDATE_REMOTE/"
fi

echo
echo "Published (staged in $UPDATE_DIR):"
echo "  $RELEASE_APK_NAME  sha256=$SHA256  size=$SIZE"
echo "  update-manifest.json -> $EO1_UPDATE_HOST/$RELEASE_APK_NAME"
echo "Debug APK (not published to update host):"
echo "  $DEBUG_DIR/$DEBUG_APK_NAME"
echo
cat "$MANIFEST"