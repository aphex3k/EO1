#!/usr/bin/env bash
#
# update-roots.sh — refresh certs/roots/ from a Debian ca-certificates package, and show what changed.
#
# Usage:
#   ./update-roots.sh [--suite sid|stable|testing|<codename>] [--dry-run]
#
# What this is for. The frames trust whatever com.aphex3k.eo1.trust.RootBundle was compiled from, and the
# ROM they run on ships a CA store from about 2013, so the bundle is the only reason an Immich host on a
# current certificate validates at all. Until now that bundle was ten root files a human copied out of a
# laptop's TLS trust store, which ages and has no source of truth. Debian's ca-certificates package is the
# same trust list Mozilla publishes, versioned, with a per-file checksum list inside the archive — so it
# becomes the source, and "which roots do we ship" turns into "which Debian version did we last take".
#
# What this is NOT. It is not part of a build and not part of a release: ./publish-update.sh never runs it
# and never reaches the network. That separation is the whole design. Trust material that arrives inside a
# signature-verified APK is reviewable because a person committed it; a build that fetched its own roots
# would produce two different APKs reporting the same GIT_SHA, which is exactly the claim
# app/build.gradle's provenance comment exists to keep true. So: run this, read the diff it prints, commit
# it. The app itself gets no ability to update its certificates, at any runtime.
#
# The integrity story is two Debian endpoints agreeing, not a signature: the version, filename, size and
# SHA-256 all come from the suite's own package index, and the downloaded archive is then re-hashed and
# compared. There is no Debian keyring on a macOS release machine, so the pin that actually binds is the
# SHA-256 written into certs/roots/debian.json — which is repo content, which is why the diff is the review.
#
# Output, all committed together:
#   certs/roots/*.pem            one verified PEM per anchor, renamed from Debian's Mozilla label
#   certs/roots/MANIFEST.tsv     what ships: file, store name, fingerprint, expiry, subject, label
#   certs/roots/debian.json      the pin: suite, version, URL, SHA-256, size
#   app/.../trust/RootBundle.java  generated from the above by ./gen-root-bundle.sh
#
# Removing anchors is expected behaviour, not a failure to route around: Mozilla withdraws trust and
# Debian follows, so an anchor this bundle carried last time may be gone this time. Those are printed as
# REMOVED before anything is written, because they are the lines in the diff that change what a frame
# will refuse to connect to.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

SUITE=sid
DRY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --suite)   SUITE="${2:-}"; shift 2 || true ;;
        --dry-run) DRY=1; shift ;;
        -h|--help) sed -n '1,45p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
    esac
done
[ -n "$SUITE" ] || { echo "ERROR: --suite needs a value" >&2; exit 2; }
case "$SUITE" in
    *[!a-z0-9-]*) echo "ERROR: suite '$SUITE' is not a Debian suite name" >&2; exit 2 ;;
esac

command -v curl   >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v openssl >/dev/null || { echo "ERROR: openssl is required" >&2; exit 1; }
. "$ROOT/cert-expiry.sh" || { echo "ERROR: missing cert-expiry.sh" >&2; exit 1; }
tar --version >/dev/null 2>&1 || { echo "ERROR: tar is required (bsdtar reads the .deb directly)" >&2; exit 1; }

# GNU and BSD coreutils spell the same hash differently, and a release machine has whichever the user
# installed. Pick once; publish-update.sh makes the same choice for the same reason.
if command -v sha256sum >/dev/null 2>&1; then hash256() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum   >/dev/null 2>&1; then hash256() { shasum -a 256 "$1" | awk '{print $1}'; }
else echo "ERROR: need sha256sum or shasum" >&2; exit 1; fi
if command -v md5sum >/dev/null 2>&1; then hashmd5() { md5sum "$1" | awk '{print $1}'; }
elif command -v md5   >/dev/null 2>&1; then hashmd5() { md5 -q "$1"; }
else echo "ERROR: need md5sum or md5 (Debian's md5sums member is md5)" >&2; exit 1; fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/update-roots.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

DEST="$ROOT/certs/roots"
GEN="$ROOT/gen-root-bundle.sh"
[ -d "$DEST" ] || { echo "ERROR: no $DEST" >&2; exit 1; }
[ -x "$GEN" ] || [ -f "$GEN" ] || { echo "ERROR: $GEN missing" >&2; exit 1; }

INDEX_PATH="dists/$SUITE/main/binary-all/Packages.gz"
MIRROR="https://deb.debian.org/debian"

echo "==> Resolving ca-certificates in Debian $SUITE"
curl -fsSL --max-time 120 "$MIRROR/$INDEX_PATH" -o "$WORK/Packages.gz" || {
    echo "ERROR: could not fetch $MIRROR/$INDEX_PATH" >&2; exit 1; }
gunzip -c "$WORK/Packages.gz" > "$WORK/Packages" 2>/dev/null || cp "$WORK/Packages.gz" "$WORK/Packages"

# The stanza, not just the field: Packages indexes every arch-all package, so take the block whose
# Package: line is exactly ca-certificates and read the four fields out of that block only. Deliberately
# not eval'd — these values arrive over the network from a mirror, and a stanza that turned out to contain
# shell would otherwise run it.
awk 'BEGIN { RS = "\n\n" } /^Package: ca-certificates\n/ { print; exit }' \
    "$WORK/Packages" > "$WORK/stanza"
[ -s "$WORK/stanza" ] || { echo "ERROR: $SUITE's index names no ca-certificates package at all" >&2; exit 1; }
PIN_VERSION=$(sed -n 's/^Version: //p'    "$WORK/stanza" | head -1)
PIN_FILE=$(sed -n 's/^Filename: //p'      "$WORK/stanza" | head -1)
PIN_SIZE=$(sed -n 's/^Size: //p'          "$WORK/stanza" | head -1)
PIN_SHA256=$(sed -n 's/^SHA256: //p'      "$WORK/stanza" | head -1)

for v in PIN_VERSION PIN_FILE PIN_SIZE PIN_SHA256; do
    [ -n "${!v:-}" ] || { echo "ERROR: $SUITE's ca-certificates stanza has no $v" >&2; exit 1; }
done
case "$PIN_SIZE" in
    ''|*[!0-9]*) echo "ERROR: index Size for ca-certificates is not a byte count: '$PIN_SIZE'" >&2; exit 1 ;;
esac
case "$PIN_SHA256" in
    [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*[0-9a-f]) : ;;
    *) echo "ERROR: index SHA-256 for ca-certificates does not look like one: '$PIN_SHA256'" >&2; exit 1 ;;
esac
case "$PIN_FILE" in
    pool/*_all.deb) : ;;
    *) echo "ERROR: unexpected Filename from the index: $PIN_FILE" >&2; exit 1 ;;
esac
echo "    $SUITE carries ca-certificates $PIN_VERSION"
echo "    $PIN_FILE  size=$PIN_SIZE  sha256=$PIN_SHA256"

echo "==> Downloading and verifying the archive"
DEB="$WORK/package.deb"
curl -fsSL --max-time 300 "$MIRROR/$PIN_FILE" -o "$DEB" || {
    echo "ERROR: could not fetch $MIRROR/$PIN_FILE" >&2; exit 1; }
ACTUAL_SIZE=$(wc -c < "$DEB" | tr -d '[:space:]')
ACTUAL_SHA=$(hash256 "$DEB")
if [ "$ACTUAL_SHA" != "$PIN_SHA256" ]; then
    echo "ERROR: downloaded archive hashes to $ACTUAL_SHA, the index said $PIN_SHA256." >&2
    echo "       Not extracting it. Either the mirror and the index disagree or something between you and" >&2
    echo "       Debian changed the bytes; do not work around this check to ship a bundle you cannot name." >&2
    exit 1
fi
if [ "$ACTUAL_SIZE" != "$PIN_SIZE" ]; then
    echo "ERROR: downloaded $ACTUAL_SIZE bytes, the index said $PIN_SIZE — truncated fetch." >&2; exit 1
fi
echo "    sha256 matches the index"

# A .deb is an ar archive whose members tar reads natively; bsdtar on macOS has done this since it grew
# libarchive's ar reader, and it keeps dpkg off a machine that has no reason to own it.
mkdir -p "$WORK/ar" "$WORK/payload" "$WORK/control"
tar -xf "$DEB" -C "$WORK/ar" || { echo "ERROR: could not read the .deb as an archive" >&2; exit 1; }
[ -f "$WORK/ar/data.tar.xz" ] || { echo "ERROR: no data.tar.xz in the archive" >&2; exit 1; }
tar -xf "$WORK/ar/data.tar.xz" -C "$WORK/payload"
[ -f "$WORK/ar/control.tar.xz" ] && tar -xf "$WORK/ar/control.tar.xz" -C "$WORK/control"

MOZ="$WORK/payload/usr/share/ca-certificates/mozilla"
[ -d "$MOZ" ] || { echo "ERROR: no usr/share/ca-certificates/mozilla in this package" >&2; exit 1; }

# Debian ships ./md5sums, and it is the only authoritative statement of which files the package intended
# to install. Checking the glob against it is what turns "tar exited 0 having written 40 of 120 members"
# into a failure: a silently smaller bundle is the one way to lose anchors that MergedTrust's
# merging-may-only-add guard cannot catch, because that guard compares against the platform store and not
# against what this bundle used to hold.
if [ -f "$WORK/control/md5sums" ]; then
    grep 'mozilla/[^/]*\.crt' "$WORK/control/md5sums" | sed 's#.*  \./#./#' > "$WORK/md5.list"
else
    echo "    note: no md5sums member; falling back to the extracted listing alone" >&2
    : > "$WORK/md5.list"
fi
LISTED=$(wc -l < "$WORK/md5.list" | tr -d '[:space:]')
PRESENT=$(find "$MOZ" -name '*.crt' -type f | wc -l | tr -d '[:space:]')
echo "==> Package content"
echo "    md5sums lists $LISTED mozilla certificates; extracted tree holds $PRESENT"
if [ "$LISTED" -gt 0 ] && [ "$LISTED" -ne "$PRESENT" ]; then
    echo "ERROR: the archive holds a different number of roots than its own manifest says." >&2; exit 1
fi
if [ "$LISTED" -gt 0 ]; then
    while read -r line; do
        want=${line%% *}
        path=${line#*  }
        f="$WORK/payload/${path#./}"
        [ -f "$f" ] || { echo "ERROR: md5sums lists $path but it is not in the archive" >&2; exit 1; }
        got=$(hashmd5 "$f")
        [ "$got" = "$want" ] || { echo "ERROR: $path is $got, md5sums says $want" >&2; exit 1; }
    done < "$WORK/md5.list"
    echo "    every listed file matches its md5"
fi
[ "$PRESENT" -gt 0 ] || { echo "ERROR: no certificates to take from this package" >&2; exit 1; }

echo "==> Building the candidate set (skipping anchors already past their own expiry)"
STAGED="$WORK/staged"; mkdir -p "$STAGED"
: > "$WORK/manifest.new"
: > "$WORK/names"
KEPT=0; PRUNED=0
for crt in "$MOZ"/*.crt; do
    [ -f "$crt" ] || continue
    label=$(basename "$crt" .crt)
    case "$label" in */*|.|..) echo "ERROR: unexpected certificate path from the archive" >&2; exit 1 ;; esac

    blocks=$(grep -c 'BEGIN CERTIFICATE' "$crt" || true)
    if [ "${blocks:-1}" -ne 1 ]; then
        echo "ERROR: $label holds $blocks PEM blocks; this package is not one-cert-per-file as expected" >&2
        exit 1
    fi

    # Prune here rather than letting gen-root-bundle.sh abort. Its refusal is the guard for the
    # hand-curated path, but a distribution store of this size always contains something on its way out —
    # trixie carries a root that expired in 2025 and another expiring in November 2026 — and a release
    # step that stops working on a calendar date is a step that will be worked around.
    expiry=$(cert_expiry_state "$crt")
    case "$expiry" in
        expired)
            echo "    PRUNED (expired $(cert_not_after "$crt")): $label"
            PRUNED=$((PRUNED + 1))
            continue ;;
        unknown)
            # Not "prune it" and not "ship it": a date this machine cannot read is a date it must not
            # decide on. Passing it through would reach the generator, which refuses what it cannot
            # vouch for, and the failure would arrive labelled as something about the certificate rather
            # than something about the host.
            echo "ERROR: cannot establish whether '$label' is inside its own validity window" >&2
            echo "       (openssl reported '$(cert_not_after "$crt")')." >&2
            exit 1 ;;
    esac

    # Debian's label is derived from Mozilla's CKA_NSS_TRUST_STRING, which keeps '=', '(' and raw
    # accented bytes. gen-root-bundle.sh turns a file name into a Java identifier by *deleting* anything
    # outside [A-Za-z0-9_], so two labels that differ only in punctuation would name the same constant
    # and one reviewed certificate would silently become another. Lowercase-kebab here, with the
    # collision refused in the generator as well: defence in depth on the same property.
    name=$(printf '%s' "$label" \
        | LC_ALL=C tr 'A-Z' 'a-z' \
        | LC_ALL=C sed -e 's/[^a-z0-9][^a-z0-9]*/-/g' -e 's/^-*//' -e 's/-*$//')
    [ -n "$name" ] || { echo "ERROR: label '$label' yields no usable file name" >&2; exit 1; }
    if grep -qxF "$name" "$WORK/names"; then
        echo "ERROR: '$label' maps to $name.pem, already taken by another label in this package" >&2
        exit 1
    fi
    printf '%s\n' "$name" >> "$WORK/names"

    # Canonical bytes, not the archive's text: the generator re-encodes from verified DER anyway, and
    # writing that form here keeps the committed .pem identical to what a rebuild would produce.
    openssl x509 -in "$crt" -outform DER 2>/dev/null | openssl x509 -inform DER -outform PEM > "$STAGED/$name.pem"
    fp=$(openssl x509 -in "$STAGED/$name.pem" -noout -fingerprint -sha256 | sed 's/^.*Fingerprint=//' | tr -d ':| ')
    hash=$(openssl x509 -in "$STAGED/$name.pem" -noout -subject_hash_old 2>/dev/null | tr -d ' \r\n')
    enddate=$(openssl x509 -in "$STAGED/$name.pem" -noout -enddate | cut -d= -f2)
    subject=$(openssl x509 -in "$STAGED/$name.pem" -noout -subject -nameopt RFC2253 | sed -e 's/^subject=[[:space:]]*//')
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$name.pem" "$hash" "$fp" "$enddate" "$subject" "$label" >> "$WORK/manifest.new"
    KEPT=$((KEPT + 1))
done
echo "    kept $KEPT, pruned $PRUNED expired"
[ "$KEPT" -gt 0 ] || { echo "ERROR: every certificate in the package was expired" >&2; exit 1; }
sort -o "$WORK/manifest.new" "$WORK/manifest.new"

echo "==> Comparing against what this tree ships now"
# Normalise "what we ship today" into the same six-column shape as the new manifest, so the removal list
# names subjects whether or not a manifest has ever been written. Before the first run that truth is the
# committed PEMs, and the first run is exactly when losing an anchor most needs saying out loud — Entrust
# Root CA G2 is in this directory and is in no Debian sid build.
if [ -f "$DEST/MANIFEST.tsv" ]; then
    cp "$DEST/MANIFEST.tsv" "$WORK/manifest.old"
else
    : > "$WORK/manifest.old"
    for p in "$DEST"/*.pem; do
        [ -f "$p" ] || continue
        f=$(basename "$p")
        h=$(openssl x509 -in "$p" -noout -subject_hash_old 2>/dev/null | tr -d ' \r\n')
        fp=$(openssl x509 -in "$p" -noout -fingerprint -sha256 | sed 's/^.*Fingerprint=//' | tr -d ':| ')
        end=$(openssl x509 -in "$p" -noout -enddate | cut -d= -f2)
        subj=$(openssl x509 -in "$p" -noout -subject -nameopt RFC2253 | sed -e 's/^subject=[[:space:]]*//')
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$f" "$h" "$fp" "$end" "$subj" "(hand-curated)" >> "$WORK/manifest.old"
    done
    sort -o "$WORK/manifest.old" "$WORK/manifest.old"
fi
cut -f3 "$WORK/manifest.old" | sort > "$WORK/fp.old"
cut -f3 "$WORK/manifest.new" | sort > "$WORK/fp.new"

ADDED=$(comm -13 "$WORK/fp.old" "$WORK/fp.new" | grep -c . || true)
REMOVED=$(comm -23 "$WORK/fp.old" "$WORK/fp.new" | grep -c . || true)
echo "    anchors in: $ADDED   anchors out: $REMOVED"
# -m keeps comm's columns aligned with the sorted fingerprint lists, then field 7 is the old row.
if [ "$REMOVED" -gt 0 ]; then
    echo "    REMOVED — a frame on the next release will not trust these unless its ROM has its own copy:"
    while IFS=$'\t' read -r f h fp end subj label; do
        grep -qxF "$fp" "$WORK/fp.new" || echo "      $f  $subj"
    done < "$WORK/manifest.old"
fi
if [ "$ADDED" -gt 0 ]; then
    # Removals are listed in full above and additions are not: a first refresh against a distribution
    # store adds ~110 anchors, and a wall of good news is how the one line that changes what a frame will
    # refuse to connect to stops being read. The full list is in the committed manifest either way.
    shown=0
    echo "    ADDED:"
    while IFS=$'\t' read -r f h fp end subj label; do
        grep -qxF "$fp" "$WORK/fp.old" && continue
        if [ "$shown" -ge 15 ]; then
            echo "      ... and $((ADDED - shown)) more; see certs/roots/MANIFEST.tsv"
            break
        fi
        echo "      $f  $subj"
        shown=$((shown + 1))
    done < "$WORK/manifest.new"
fi

if [ "$DRY" -eq 1 ]; then
    gen_out="$WORK/RootBundle.java"
    sh "$GEN" "$STAGED" "$gen_out" >/dev/null
    echo "==> Dry run: generator accepted $(grep -c 'new Entry(' "$gen_out") anchors. Nothing written."
    echo "    Re-run without --dry-run to replace $DEST and app/src/main/java/com/aphex3k/eo1/trust/RootBundle.java."
    exit 0
fi

echo "==> Writing $DEST"
# The whole directory is the candidate set now, so the previous set goes with it. This is the only place
# trust material is removed, it is a tracked change in git, and it happens after the diff was printed —
# which is what makes "follow Debian, no override" an auditable decision instead of a side effect.
find "$DEST" -maxdepth 1 -name '*.pem' -delete
cp "$STAGED"/*.pem "$DEST"/
cp "$WORK/manifest.new" "$DEST/MANIFEST.tsv"

cat > "$DEST/debian.json" <<EOF
{
  "writtenBy": "./update-roots.sh — do not hand-edit; this file is the pin, and the pin is the review",
  "note": "Integrity comes from re-hashing the download against sha256 below. There is no Debian keyring on a macOS release machine, so nothing verifies a signature here; what binds is that this file is committed.",
  "source": "https://deb.debian.org/debian",
  "suite": "$SUITE",
  "package": "ca-certificates",
  "version": "$PIN_VERSION",
  "path": "$PIN_FILE",
  "sizeBytes": $PIN_SIZE,
  "sha256": "$PIN_SHA256",
  "anchorsShipped": $KEPT,
  "anchorsPrunedExpired": $PRUNED
}
EOF

sh "$GEN" "$DEST" "$ROOT/app/src/main/java/com/aphex3k/eo1/trust/RootBundle.java"

echo
echo "Done. RootBundle.java now ships $KEPT anchors from ca-certificates $PIN_VERSION."
echo "Read the diff before committing it — certs/roots/MANIFEST.tsv is the human-readable version:"
echo "  git diff --stat certs/roots app/src/main/java/com/aphex3k/eo1/trust/RootBundle.java"
echo "  git diff certs/roots/MANIFEST.tsv certs/roots/debian.json"
