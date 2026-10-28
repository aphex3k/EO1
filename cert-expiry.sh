#!/bin/sh
#
# cert-expiry.sh — sourced, never run. One answer to "is this certificate still inside its own window".
#
# Why this exists as a file rather than as `openssl x509 -checkend 0` inlined twice. That flag is the
# obvious way to ask the question and it gives a wrong answer on at least one of the two openssl flavours
# a release machine can have on PATH: measured on the current Debian sid set, LibreSSL 3.3.6 reports
# `certum-trusted-network-ca-2.pem` (notAfter 2046-10-06, notBefore 2011) as EXPIRED while OpenSSL 3.5.9
# reports it VALID — and the two agree on the other 119 anchors, including two other roots that also
# expire in 2046. Whichever script trusts that verdict is the failure it produces: a generator that
# trusts it refuses to build over a live root, and a refresh that trusts it prunes one silently and
# commits the removal.
#
# So the date is read as text and compared with the clock. Both openssl flavours print -enddate
# identically ("Mmm D HH:MM:SS YYYY GMT"), and `date` is asked to parse it with whichever spelling this
# platform has: GNU's `-d` (which macOS rejects outright rather than misparsing, so the order is safe)
# or BSD's `-j -f`.
#
# Usage:  . ./cert-expiry.sh
#         cert_not_after "$pem"          -> "Oct  6 08:39:56 2046 GMT" (or empty)
#         cert_expiry_state "$pem"       -> "valid" | "expired" | "unknown"
#
# "unknown" is a real third state and callers must not collapse it into either other one; the
# conservative direction is different for each caller and is commented where it is handled.

cert_not_after() {
    # Verbatim, including the double space OpenSSL writes before a single-digit day: that is the exact
    # string the BSD format below is known to parse, and re-flowing it is how this becomes guesswork.
    openssl x509 -in "$1" -noout -enddate 2>/dev/null | cut -d= -f2
}

cert_not_after_epoch() {
    when=$(cert_not_after "$1")
    [ -n "$when" ] || return 1
    # GNU first because macOS rejects `-d` outright rather than misparsing it, so a failed attempt is a
    # clean no-op on the platform that does not have it.
    epoch=$(date -u -d "$when" +%s 2>/dev/null) && [ -n "$epoch" ] && { printf '%s' "$epoch"; return 0; }
    epoch=$(date -j -u -f '%b %e %T %Y %Z' "$when" +%s 2>/dev/null) && [ -n "$epoch" ] && { printf '%s' "$epoch"; return 0; }
    return 1
}

cert_expiry_state() {
    end=$(cert_not_after_epoch "$1") || { echo unknown; return 0; }
    now=$(date -u +%s 2>/dev/null) || { echo unknown; return 0; }
    # Equal to the second counts as expired: an anchor whose window has closed cannot validate a chain.
    if [ "$end" -le "$now" ]; then echo expired; else echo valid; fi
}
