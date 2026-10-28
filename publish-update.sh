#!/usr/bin/env bash
#
# publish-update.sh — build a signed release APK and stage it for the app's self-update.
#
# Usage:
#   ./publish-update.sh [--check-only] <VERSION_CODE> [VERSION_NAME]
#
#   --check-only  validate the tree (currently: the CA root bundle) and exit without building.
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
#   EO1_UPDATE_ROOTS_OPTIONAL
#                      Set to publish over an unverifiable CA root bundle. Unset (default) makes a
#                      bundle that does not regenerate from certs/roots, disagrees with its manifest,
#                      or carries an expired anchor a hard error before anything is built. This script
#                      never fetches roots — ./update-roots.sh does, by hand, and the result is a
#                      commit. See docs/TRUST.md.
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

# Shared with ./gen-root-bundle.sh and ./update-roots.sh so that all three answer "is this root still
# live?" the same way — see the note in that file about `-checkend` disagreeing with itself.
. "$ROOT/cert-expiry.sh"

# --check-only runs the release's validations and stops: no build, no manifest, no versionCode consumed,
# and therefore no signing props or update host required either. It exists so the CA root gate below can be
# exercised without waiting on a full assembleRelease.
CHECK_ONLY=0
_pos=()
for _a in "$@"; do
    case "$_a" in
        --check-only) CHECK_ONLY=1 ;;
        -h|--help)    sed -n '1,40p' "$0"; exit 0 ;;
        *)            _pos+=("$_a") ;;
    esac
done
set -- ${_pos[@]+"${_pos[@]}"}

if [ "$CHECK_ONLY" -eq 0 ]; then
    [ $# -ge 1 ] || { echo "Usage: ./publish-update.sh [--check-only] <VERSION_CODE> [VERSION_NAME]" >&2; exit 2; }
    case "$1" in
        ''|*[!0-9]*) echo "ERROR: VERSION_CODE must be a positive integer, got: '$1'" >&2; exit 2 ;;
    esac
    if [ "$1" -le 0 ]; then
        echo "ERROR: VERSION_CODE must be > 0" >&2; exit 2
    fi
fi
VERSION_CODE=${1:-0}
VERSION_NAME=${2:-}

# Everything a publication needs but a check does not: the host the manifest will point at, the keystore
# the APK gets signed with, and the monotonicity ledger. None of them matter to `--check-only`, which
# builds nothing and consumes nothing.
UPDATE_DIR="${EO1_UPDATE_DIR:-$ROOT/update-out}"
LAST_FILE="$UPDATE_DIR/.last-published-version"
if [ "$CHECK_ONLY" -eq 0 ]; then
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

    mkdir -p "$UPDATE_DIR"
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
fi

# The shipped CA roots, checked rather than fetched. This script never touches the network for trust
# material: what a release compiles in is what is committed, because BUILD_DATE is the commit's committer
# timestamp and GIT_DIRTY is `git status` in this same tree — a bundle pulled during a release would let
# two APKs carrying different roots report an identical GIT_SHA, which is the one property every
# versionCode -> mapping -> source lookup in a post-mortem depends on. So the gate asks three questions
# and nothing else: does the committed bundle still regenerate byte-for-byte from the committed PEMs, does
# its manifest agree with it, and is any of it past its own expiry. None needs a mirror, and none can be
# quietly true.
echo "==> Checking the CA root bundle"
ROOTS="$ROOT/certs/roots"
RB="$ROOT/app/src/main/java/com/aphex3k/eo1/trust/RootBundle.java"
ROOTS_PROBLEM=""
if [ ! -d "$ROOTS" ] || [ ! -f "$RB" ]; then
    ROOTS_PROBLEM="no certs/roots or no RootBundle.java to check"
else
    ROOTS_TMP="${TMPDIR:-/tmp}/eo1-roots-check.$$"
    mkdir -p "$ROOTS_TMP"
    if sh "$ROOT/gen-root-bundle.sh" "$ROOTS" "$ROOTS_TMP/RootBundle.java" >/dev/null 2>"$ROOTS_TMP/err"; then
        # Compared on the trust-relevant projection, not byte-for-byte. Everything that decides what a
        # frame accepts — the PEM bytes, the store names, which constant each entry points at — is
        # compared exactly. The human-readable subject is left out, because how a distinguished name
        # renders is a property of the openssl that printed it and not of the certificate: OpenSSL 3
        # knows OID 2.5.4.97 is `organizationIdentifier` and LibreSSL prints its DER instead, which is
        # three lines of diff on the current set and nothing else. Requiring byte equality would make
        # this gate pass or fail depending on whether the machine has homebrew in PATH.
        # Real files, not <( … ): BSD diff cannot open a process-substitution fd and reports that as a
        # difference, which would refuse every release on a Mac.
        roots_normalize() {
            sed -E -e 's/^ *new Entry\(".*", "([0-9a-f]{8})", (PEM_[A-Z0-9_]+)\),$/  ENTRY \1 \2/' \
                "$1" > "$2"
        }
        roots_normalize "$ROOTS_TMP/RootBundle.java" "$ROOTS_TMP/cand.norm"
        roots_normalize "$RB" "$ROOTS_TMP/committed.norm"
        if ! diff -q "$ROOTS_TMP/cand.norm" "$ROOTS_TMP/committed.norm" >/dev/null 2>&1; then
            ROOTS_PROBLEM="$RB does not match certs/roots/*.pem — it drifted from its own input.
         Re-run ./gen-root-bundle.sh (or ./update-roots.sh) and commit the result."
        fi
        # The manifest is what a reviewer reads, so a bundle that grew or shrank without it being
        # regenerated means the release says one thing about itself in git and another on the device.
        if [ -z "$ROOTS_PROBLEM" ] && [ -f "$ROOTS/MANIFEST.tsv" ]; then
            RB_COUNT=$(grep -c 'new Entry(' "$RB")
            MANIFEST_COUNT=$(wc -l < "$ROOTS/MANIFEST.tsv" | tr -d ' ')
            if [ "$RB_COUNT" != "$MANIFEST_COUNT" ]; then
                ROOTS_PROBLEM="RootBundle.java carries $RB_COUNT anchors but certs/roots/MANIFEST.tsv lists $MANIFEST_COUNT.
         /state would report a bundle whose provenance file disagrees with it. Re-run ./update-roots.sh."
            fi
        fi
    else
        ROOTS_PROBLEM="certs/roots does not generate cleanly:
$(sed 's/^/         /' "$ROOTS_TMP/err")"
    fi
    rm -rf "$ROOTS_TMP"
    if [ -z "$ROOTS_PROBLEM" ]; then
        # Checked here rather than by ./gradlew test because this script never runs the test suite, and
        # the wall clock is the one input that changes without a commit: a root that lapses between
        # releases would otherwise ride into an APK as an anchor that is compiled in and inert.
        # cert-expiry.sh rather than `-checkend`, because that flag reports a live 2046 root as expired
        # under LibreSSL — and this gate runs under whichever openssl the release machine's PATH resolves,
        # which on a Mac with no homebrew in PATH is LibreSSL.
        EXPIRED=""
        UNDATED=""
        for pem in "$ROOTS"/*.pem; do
            [ -f "$pem" ] || continue
            state=$(cert_expiry_state "$pem")
            if [ "$state" = expired ]; then
                EXPIRED="${EXPIRED}         $(basename "$pem") expired $(cert_not_after "$pem")
"
            elif [ "$state" = unknown ]; then
                UNDATED="${UNDATED}         $(basename "$pem") says $(cert_not_after "$pem")
"
            fi
        done
        if [ -n "$EXPIRED" ]; then
            ROOTS_PROBLEM="shipped anchors are past their own notAfter — inert on every frame:
${EXPIRED}         ./update-roots.sh prunes these; run it and commit the result."
        fi
        if [ -n "$UNDATED" ]; then
            ROOTS_PROBLEM="${ROOTS_PROBLEM}this machine cannot read the expiry date on:
${UNDATED}         Refusing to publish a bundle whose freshness it cannot confirm — fix the host, not the check."
        fi
    fi
fi

if [ -n "$ROOTS_PROBLEM" ]; then
    if [ -n "${EO1_UPDATE_ROOTS_OPTIONAL:-}" ]; then
        echo "WARNING: the CA root bundle is unverified and this release ships it anyway:" >&2
        echo "         $ROOTS_PROBLEM" >&2
        echo "         Frames will trust exactly what this build compiled in, plus their ROM's 2013 store." >&2
    else
        echo "ERROR: $ROOTS_PROBLEM" >&2
        echo "       Release refused before anything was built, so no versionCode was consumed." >&2
        echo "       If this is deliberate, re-run with EO1_UPDATE_ROOTS_OPTIONAL=1." >&2
        exit 1
    fi
fi

if [ "$CHECK_ONLY" -eq 1 ]; then
    echo
    echo "Checks passed. Nothing was built, signed, staged or published."
    exit 0
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
