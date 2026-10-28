#!/bin/sh
# Regenerate app/src/main/java/com/aphex3k/eo1/trust/RootBundle.java from certs/roots/*.pem.
#
# Root certificates the frames need in order to validate a modern HTTPS server are public data, so
# they ship inside the APK instead of being downloaded: com.aphex3k.update already authenticates the
# APK by signature, and a second fetch path for trust material would mean a second verifier and a
# second set of failure modes for no gain. This script is the only sanctioned way to change that
# material, and it exists so the emitted Java cannot disagree with the bytes it was given.
#
# Per certificate, what is checked before a byte is written:
#
#   it parses as an X.509 certificate          so the bundle cannot carry junk
#   subject_hash_old is eight hex digits       that is the platform's naming rule; see SubjectHashOld
#   it is not past its own notAfter            an expired anchor is inert, and bundling one makes
#                                              "we ship the roots" a claim that quietly stops being
#                                              true one day without any build changing
#   PEM -> DER -> PEM reproduces the fingerprint  otherwise the reviewed bytes and the shipped bytes
#                                              are different certificates
#
# What is then emitted is the PEM re-encoded from that verified DER, and every line of it must be
# base64 or one of the two fixed markers — never the input file's own text. See the note by the awk
# step for why that distinction is load-bearing rather than tidy.
#
# The hash is emitted as a constant on purpose. It is the platform's own name for the anchor (see
# SubjectHashOld), and it is what TrustCensus compares against a frame's on-disk stores when /state says
# which shipped anchors that frame does not have — so a hash that disagrees with its PEM is a silent
# reporting lie, and it is reviewable here rather than discoverable only on a device. Note that nothing in
# this app writes to /data/misc/keychain: the CertStoreProvisioner named in MergedTrust's javadoc has
# never existed, so this constant is a reporting key, not the input to a `cp` on an adbd command line.
#
# What the emitted text is derived from matters too. The subject is printed with -nameopt RFC2253 because
# that is ASCII (an unescaped subject would make a non-ASCII CA name depend on javac's source charset) and
# because it removes the largest rendering difference between the two openssl flavours a release machine
# can have on PATH — /usr/bin/openssl is LibreSSL and wins a bare PATH, and plain -subject gives
# "/C=US/O=…" there against "C=US, O=…" on OpenSSL 3. It does not remove all of it: a build that knows an
# OID's name prints organizationIdentifier=VATES-Q2826004J where LibreSSL prints its DER as
# 2.5.4.97=#0C0F…, and that is a property of the binary rather than of the certificate. So
# publish-update.sh compares the bundle on its store names and PEM bytes and not on this string, and no
# part of what a frame trusts depends on which openssl generated it.
#
# Usage:  ./gen-root-bundle.sh [cert-dir] [output-java-file]

set -e

CERT_DIR="${1:-certs/roots}"
OUT="${2:-app/src/main/java/com/aphex3k/eo1/trust/RootBundle.java}"
TMP="${TMPDIR:-/tmp}/gen-root-bundle.$$"

command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
# Same directory as this script, not the working directory: publish-update.sh calls the generator by
# absolute path from wherever the release is being run.
EXPIRY_LIB="$(dirname "$0")/cert-expiry.sh"
[ -f "$EXPIRY_LIB" ] || { echo "missing $EXPIRY_LIB — the expiry check cannot be skipped" >&2; exit 1; }
. "$EXPIRY_LIB"
[ -d "$CERT_DIR" ] || { echo "no certificate directory at $CERT_DIR" >&2; exit 1; }
[ -n "$(ls "$CERT_DIR"/*.pem 2>/dev/null)" ] || { echo "no .pem files in $CERT_DIR" >&2; exit 1; }

mkdir -p "$TMP"
: > "$TMP/constants"
: > "$TMP/entries"
: > "$TMP/fingerprints"
: > "$TMP/hashes"
: > "$TMP/idents"
count=0

for pem_file in "$CERT_DIR"/*.pem; do
    [ -f "$pem_file" ] || continue
    name=$(basename "$pem_file" .pem)
    # A Java identifier from the file name: uppercase, dashes to underscores, nothing else surviving.
    const=$(printf '%s' "$name" | tr 'a-z-' 'A-Z_' | tr -cd 'A-Za-z0-9_' | tr 'a-z' 'A-Z')
    [ -n "$const" ] || { echo "$pem_file: file name yields no Java identifier" >&2; exit 1; }
    # Two input files whose names sanitize alike would write the same PEM_<X> constant twice — the last
    # one winning — while both Entry rows still reference it, so one reviewed certificate would silently
    # become another. Debian's labels make this reachable at whole-store scale: they carry '=', '(' and
    # raw accented bytes, all of which the tr pipeline above deletes.
    if grep -qxF "$const" "$TMP/idents"; then
        echo "$pem_file: yields Java identifier $const, already used by an earlier input" >&2
        exit 1
    fi
    printf '%s\n' "$const" >> "$TMP/idents"
    literal_name="$const"

    hash=$(openssl x509 -in "$pem_file" -noout -subject_hash_old 2>"$TMP/err") || {
        echo "$pem_file: openssl could not read a certificate: $(cat "$TMP/err")" >&2; exit 1; }
    hash=$(echo "$hash" | tr -d ' \r\n')
    case "$hash" in
        ???????? ) : ;;
        *) echo "$pem_file: subject_hash_old '$hash' is not eight characters" >&2; exit 1 ;;
    esac
    case "$hash" in
        *[!0-9a-f]*) echo "$pem_file: subject_hash_old '$hash' is not lowercase hex" >&2; exit 1 ;;
    esac
    # Two certificates with the same subject DN really do share a store name — the ".<index>" suffix in
    # the platform's keychain exists for exactly that — so a repeat here is noted, not refused. A
    # duplicate would be MergedTrust's problem to fold, and refusing it would reject legitimate
    # cross-signed pairs.
    if grep -qxF "$hash" "$TMP/hashes"; then
        echo "  note: $pem_file shares store name $hash with an earlier entry" >&2
    fi
    printf '%s\n' "$hash" >> "$TMP/hashes"

    # Not `-checkend`: see cert-expiry.sh, which exists because that flag calls a live 2046 root expired
    # on one of the two openssl flavours this can run under. "unknown" aborts here for the same reason the
    # other checks do — this script's contract is that it vouches for every byte it emits, and a date it
    # cannot read is a date it cannot vouch for.
    expiry=$(cert_expiry_state "$pem_file")
    case "$expiry" in
        expired)
            echo "$pem_file: expired ($(cert_not_after "$pem_file")) — do not bundle it" >&2
            exit 1 ;;
        unknown)
            echo "$pem_file: could not establish whether it is inside its own validity window ($(cert_not_after "$pem_file"))" >&2
            echo "    refusing to emit an entry it cannot vouch for; check that 'date' can parse that form" >&2
            exit 1 ;;
    esac

    fp_pem=$(openssl x509 -in "$pem_file" -noout -fingerprint -sha256 | sed 's/^.*Fingerprint=//' | tr -d ':')
    # The set-level check the per-file checks cannot make: a directory handed to this script by
    # ./update-roots.sh holds whatever a distribution package contained, and the same certificate arriving
    # twice under two labels is expected at that scale, not exotic. Emitting it twice would inflate
    # /state's bundleSize while MergedTrust folded the duplicate into a count it reports as "already
    # present".
    if grep -qxF "$fp_pem" "$TMP/fingerprints"; then
        echo "$pem_file: this certificate is already in the bundle under another file name ($fp_pem)" >&2
        exit 1
    fi
    printf '%s\n' "$fp_pem" >> "$TMP/fingerprints"
    openssl x509 -in "$pem_file" -outform DER -out "$TMP/$name.der" 2>/dev/null
    fp_der=$(openssl x509 -inform DER -in "$TMP/$name.der" -noout -fingerprint -sha256 | sed 's/^.*Fingerprint=//' | tr -d ':')
    [ "$fp_pem" = "$fp_der" ] || {
        echo "$pem_file: PEM and its own DER disagree ($fp_pem vs $fp_der)" >&2; exit 1; }

    # RFC2253, and the optional whitespace, so the emitted subject is the same bytes whoever ran this:
    # see the header. Backslash escaping must come after the prefix is stripped, and both are needed —
    # RFC2253 renders a non-ASCII name as \C5\91, which is a Java escape sequence if it is not doubled.
    subject=$(openssl x509 -in "$pem_file" -noout -subject -nameopt RFC2253 \
        | sed -e 's/^subject=[[:space:]]*//' -e 's/\\/\\\\/g; s/"/\\"/g')

    # The bytes that reach the Java literal are re-derived from the verified DER, never copied out of
    # the input file. This is not tidiness: `openssl x509` parses the FIRST PEM block and ignores
    # whatever text follows it, so before this line existed a .pem that was a valid certificate plus a
    # trailing line of
    #     x"; static { PWNED = "..."; } static String PWNED = "
    # passed all four checks and had that Java pasted straight into RootBundle.java, which then
    # compiles into every APK — and this script is the documented, only sanctioned path for trust
    # material. Re-encoding the DER that already round-tripped to the reviewed fingerprint means the
    # emitted text cannot be anything but the certificate.
    canonical="$TMP/$name.canonical.pem"
    openssl x509 -inform DER -in "$TMP/$name.der" -outform PEM > "$canonical" 2>/dev/null
    fp_canonical=$(openssl x509 -in "$canonical" -noout -fingerprint -sha256 | sed 's/^.*Fingerprint=//' | tr -d ':')
    [ "$fp_canonical" = "$fp_pem" ] || {
        echo "$pem_file: canonical PEM disagrees with the reviewed certificate" >&2; exit 1; }

    # And the whitelist, so the guarantee does not depend on that last check having run: a PEM body is
    # base64 and two fixed marker lines. Anything else — a quote, a backslash, a semicolon, a line
    # that is not one of those three shapes — aborts the generation instead of reaching the literal.
    rejected=$(grep -v -e '^-----BEGIN CERTIFICATE-----$' -e '^-----END CERTIFICATE-----$' \
        -e '^[A-Za-z0-9+/=]*$' "$canonical" || true)
    if [ -n "$rejected" ]; then
        echo "$pem_file: canonical PEM contains a line that is not base64 or a marker:" >&2
        echo "$rejected" >&2
        exit 1
    fi

    # Escape backslash then quote, in that order, before the line is placed inside a Java literal —
    # belt and braces behind the whitelist, and the part that would otherwise still be wrong for any
    # future change to what is emitted.
    escaped="$TMP/$name.escaped.pem"
    sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' "$canonical" > "$escaped"
    awk -v name="$literal_name" '
        { line[NR] = $0 }
        END {
            printf "    private static final String PEM_%s =\n", name
            for (i = 1; i <= NR; i++) {
                printf "            \"%s\\n\"%s\n", line[i], (i == NR ? ";" : "+")
            }
        }' "$escaped" >> "$TMP/constants"

    printf '        new Entry("%s", "%s", PEM_%s),\n' "$subject" "$hash" "$literal_name" >> "$TMP/entries"
    count=$((count + 1))
done

mkdir -p "$(dirname "$OUT")"
{
    cat <<'HEADER'
package com.aphex3k.eo1.trust;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * The root certificates this build ships.
 *
 * <p><b>Generated by {@code ./gen-root-bundle.sh} — do not hand-edit.</b> The input is
 * {@code certs/roots/*.pem}, which {@code ./update-roots.sh} refreshes from the pinned Debian
 * {@code ca-certificates} package recorded in {@code certs/roots/debian.json}. What this build ships,
 * per anchor, is the committed {@code certs/roots/MANIFEST.tsv}; its diff is the reviewed change. The
 * generator refuses to write anything for a certificate that does not parse, is past its own expiry,
 * disagrees with its own DER encoding, or duplicates an anchor already in the set.
 *
 * <p>Constants rather than {@code assets/} or {@code res/raw}, for two reasons that both matter more
 * than tidiness. This is trust material on a device whose release build runs
 * {@code shrinkResources = true}, and an anchor that a resource-shrinking step decides is unreferenced
 * is not something anyone wants to discover about a frame behind glass. And a store name has to be
 * knowable at build time whatever ends up using it — nothing in this app writes to
 * /data/misc/keychain today, so this is a reporting key ({@code TrustCensus} compares it against a
 * frame's stores), and a reporting key that only existed at runtime could not be diffed.
 *
 * <p>The hashes below are the platform's naming rule precomputed (see {@link SubjectHashOld}). They
 * are what makes a wrong one visible in review: if a hash here stops matching the PEM beside it, the
 * anchor is not trusted, silently, on every frame.
 */
public final class RootBundle {

    /** One shipped anchor: the subject it was reviewed as, the name the store will give it, the PEM. */
    public static final class Entry {
        public final String subject;
        public final String subjectHash;
        public final String pem;

        Entry(String subject, String subjectHash, String pem) {
            this.subject = subject;
            this.subjectHash = subjectHash;
            this.pem = pem;
        }

        public X509Certificate certificate() throws CertificateException {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes()));
        }
    }

HEADER
    cat "$TMP/constants"
    cat <<'MIDDLE'

    public static final Entry[] ENTRIES = {
MIDDLE
    cat "$TMP/entries"
    cat <<'FOOTER'
    };

    private RootBundle() {
    }

    public static Entry[] entries() {
        return ENTRIES.clone();
    }

    public static int size() {
        return ENTRIES.length;
    }
}
FOOTER
} > "$OUT"

echo "wrote $OUT with $count root certificates"
