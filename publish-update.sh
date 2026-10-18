#!/usr/bin/env bash
#
# publish-update.sh — build a signed release APK and stage it for the app's self-update.
#
# Usage:
#   ./publish-update.sh <VERSION_CODE> [VERSION_NAME]
#
# Environment:
#   EO1_UPDATE_HOST    Base URL the frames will fetch from, no trailing slash,
#                      e.g. http://192.168.1.50/eo1. Required.
#   EO1_UPDATE_DIR     Local staging dir (default: ./update-out).
#   EO1_UPDATE_REMOTE  Optional rsync target, user@host:/path. Mirrors the staging dir.
#   EO1_UPDATE_NOTES   Optional free-text note embedded in the manifest.
#   EO1_UPDATE_MAPPING_OPTIONAL
#                      Set to publish without the R8 mapping even when the build did not produce
#                      one. Unset (default) makes a missing mapping a hard error: release is always
#                      minified, so without an archived mapping a crash dump from this build can
#                      never be decoded.
#
# Signing is read from the gitignored root local.properties:
#   eo1.signing.store.file / eo1.signing.store.password /
#   eo1.signing.key.alias (default EO1) / eo1.signing.key.password
#
# The APK is published as eo1-release-<VERSION_CODE>.apk next to update-manifest.json, and the
# build's R8 mapping as mapping-<VERSION_CODE>.txt.gz. The mapping exists only to decode crash dumps
# (docs/WEBSERVER.md "Crash dumps") and is deliberately NOT referenced in the manifest, so no frame
# ever downloads it.
# VERSION_CODE must be strictly greater than the last published one (monotonic, recorded
# in <UPDATE_DIR>/.last-published-version).

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
mkdir -p "$UPDATE_DIR"

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

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
    echo "ERROR: build did not produce $APK" >&2; exit 1
fi

if command -v shasum >/dev/null 2>&1; then
    SHA256=$(shasum -a 256 "$APK" | awk '{print $1}')
else
    SHA256=$(sha256sum "$APK" | awk '{print $1}')
fi
SIZE=$(wc -c < "$APK" | tr -d '[:space:]')

json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

NOTES="${EO1_UPDATE_NOTES:-}"
if [ -z "$NOTES" ] && [ -n "$VERSION_NAME" ]; then
    NOTES="EO1 $VERSION_NAME"
fi

APK_NAME="eo1-release-$VERSION_CODE.apk"
cp "$APK" "$UPDATE_DIR/$APK_NAME"

# Archive the R8 mapping next to the APK. Release sets minifyEnabled=true, so crash dumps from this
# build name obfuscated classes and are only readable against this file — and a crash report carries
# its versionCode, which is exactly the key (<code> is strictly increasing, so versionCode -> mapping
# is 1:1). Never referenced from update-manifest.json: frames have no use for it.
MAPPING_SRC="$ROOT/app/build/outputs/mapping/release/mapping.txt"
MAPPING_NAME=""
if [ -f "$MAPPING_SRC" ]; then
    if command -v gzip >/dev/null 2>&1; then
        MAPPING_NAME="mapping-$VERSION_CODE.txt.gz"
        gzip -9 -c "$MAPPING_SRC" > "$UPDATE_DIR/$MAPPING_NAME"
    else
        MAPPING_NAME="mapping-$VERSION_CODE.txt"
        cp "$MAPPING_SRC" "$UPDATE_DIR/$MAPPING_NAME"
        echo "WARNING: gzip not found; published uncompressed as $MAPPING_NAME" >&2
    fi
    if command -v shasum >/dev/null 2>&1; then
        MAPPING_SHA=$(shasum -a 256 "$UPDATE_DIR/$MAPPING_NAME" | awk '{print $1}')
    else
        MAPPING_SHA=$(sha256sum "$UPDATE_DIR/$MAPPING_NAME" | awk '{print $1}')
    fi
elif [ -n "${EO1_UPDATE_MAPPING_OPTIONAL:-}" ]; then
    echo "WARNING: no R8 mapping found and EO1_UPDATE_MAPPING_OPTIONAL is set - publishing without one." >&2
    echo "         Crash dumps from versionCode $VERSION_CODE will NOT be retraceable." >&2
else
    echo "ERROR: build produced no $MAPPING_SRC" >&2
    echo "       Release builds set minifyEnabled=true, so R8 must emit a mapping. Publishing without" >&2
    echo "       one recreates the failure this archive exists to prevent: a crash on a frame that can" >&2
    echo "       never be decoded. If you deliberately disabled minification, re-run with" >&2
    echo "       EO1_UPDATE_MAPPING_OPTIONAL=1." >&2
    exit 1
fi

MANIFEST="$UPDATE_DIR/update-manifest.json"
cat > "$MANIFEST" <<EOF
{
  "versionCode": $VERSION_CODE,
  "versionName": "$(json_escape "$VERSION_NAME")",
  "apkUrl": "$EO1_UPDATE_HOST/$APK_NAME",
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
echo "  $APK_NAME  sha256=$SHA256  size=$SIZE"
if [ -n "$MAPPING_NAME" ]; then
    echo "  $MAPPING_NAME  sha256=$MAPPING_SHA  size=$(wc -c < "$UPDATE_DIR/$MAPPING_NAME" | tr -d '[:space:]')"
    echo "                 retrace with: gunzip -k $MAPPING_NAME && \$ANDROID_HOME/cmdline-tools/latest/bin/retrace mapping-$VERSION_CODE.txt <dump>"
else
    echo "  (no R8 mapping archived - crash dumps from this build are not retraceable)"
fi
echo "  update-manifest.json -> $EO1_UPDATE_HOST/$APK_NAME"
echo
cat "$MANIFEST"
