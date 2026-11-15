#!/usr/bin/env bash
#
# check-release-reflection.sh — ask a built release APK whether the types Gson builds by
# reflection survived R8.
#
# Usage:
#   ./check-release-reflection.sh <app-release.apk> [mapping.txt]
#
# Exit status:
#   0  every type found in the APK is one Gson can still build
#   1  at least one is not — printed with the class and the check that failed
#   2  usage error, or the tooling this needs is missing (no dexdump, no unzip)
#
# Why this exists. Nothing in Java constructs this app's DTOs — Gson does, reflectively,
# and R8 cannot see a reflective call — so a DTO with no `new` site meets R8's "no live
# constructor" test, and its horizontal class merging folds such classes into one shared
# shell. That is not hypothetical: in the versionCode 19 APK, `Lq0/b;` (=
# com.aphex3k.eo1.mqtt.Payload) came out PUBLIC ABSTRACT with no instance fields and 101
# borrowed static methods, and TTrack, EnqueuedMetadata, Volume and Mute had no mapping
# entry at all. Every `sonos/+` message on every release frame then failed with
# "Abstract classes can't be instantiated!", while debug builds (minifyEnabled=false) and
# the JVM suite (which never runs R8) were both green.
#
# The convention that keeps it working is @Keep on the DTO class — androidx.annotation
# ships `-keep @androidx.annotation.Keep class * {*;}`. This script does not check for the
# annotation, because that is not the property that matters: it checks the binary for the
# four things Gson needs, so it also catches a keep rule that was written too narrow, a
# `-keepattributes` change that strips @SerializedName, or an R8 upgrade that stops
# honouring something it honours today.
#
# The types it checks are derived from the source, not from a hand-kept list: every class
# literal handed to a fromJson(…) call, plus every class declaring a @SerializedName field
# (which covers the Retrofit-converted immichApi DTOs, which never name a class literal).
# Anything it cannot resolve to a file under app/src/main/java is named in its report
# rather than counted as checked.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main/java"

APK="${1:-}"
MAPPING="${2:-$ROOT/app/build/outputs/mapping/release/mapping.txt}"

if [ -z "$APK" ]; then
    sed -n '1,12p' "$0" >&2
    exit 2
fi
if [ ! -f "$APK" ]; then
    echo "ERROR: no such APK: $APK" >&2
    exit 2
fi
if ! command -v unzip >/dev/null 2>&1; then
    echo "ERROR: unzip is required to read $APK" >&2
    exit 2
fi

DEXDUMP_BIN="${DEXDUMP:-}"
if [ -z "$DEXDUMP_BIN" ] || [ ! -x "$DEXDUMP_BIN" ]; then
    SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
    DEXDUMP_BIN="$(ls -1d "$SDK_DIR"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "$DEXDUMP_BIN" ] || [ ! -x "$DEXDUMP_BIN" ]; then
    DEXDUMP_BIN="$(command -v dexdump 2>/dev/null || true)"
fi
if [ -z "$DEXDUMP_BIN" ]; then
    echo "ERROR: no dexdump found. Set DEXDUMP=/path/to/dexdump, or ANDROID_HOME." >&2
    exit 2
fi

TMP="$(mktemp -d "${TMPDIR:-/tmp}/eo1-reflect.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

if ! unzip -q -o "$APK" 'classes*.dex' -d "$TMP" 2>"$TMP/unzip.err"; then
    echo "ERROR: could not read the dex files out of $APK: $(sed -n '1,2p' "$TMP/unzip.err" | tr '\n' ' ')" >&2
    exit 2
fi

# One pass over each dex into a five-column table:
#   descriptor  access-flag text  instance-field count  no-arg-constructor count  field names
: > "$TMP/facts.tsv"
for dex in "$TMP"/classes*.dex; do
    [ -f "$dex" ] || continue
    "$DEXDUMP_BIN" "$dex" | awk '
        function flush() { if (cls != "") print cls "\t" flags "\t" fields "\t" inits "\t" names }
        /^  Class descriptor/ {
            flush()
            q = $NF; i = index(q, "L")
            cls = i ? substr(q, i, length(q) - i) : ""
            flags = ""; fields = 0; inits = 0; names = ""; pend = 0; sect = ""
            next
        }
        /^  Access flags/    { flags = $0; next }
        /^  Static fields/   { sect = "s"; next }
        /^  Instance fields/ { sect = "i"; next }
        /^  Direct methods/  { sect = "d"; next }
        /^  Virtual methods/ { sect = "v"; next }
        /^    #/             { if (sect == "i") fields++; next }
        /^      name[[:space:]]*:/ {
            if (sect == "i") {
                tok = $NF
                gsub(/[^A-Za-z0-9_$]/, "", tok)
                names = names " " tok
            }
            pend = (sect == "d" && index($0, "<init>") > 0) ? 1 : 0
            next
        }
        /^      type[[:space:]]*:/ { if (pend && index($0, "()V") > 0) inits++; pend = 0; next }
        END { flush() }
    ' >> "$TMP/facts.tsv"
done

reflective_targets() {
    {
        grep -rh 'fromJson(' "$SRC" 2>/dev/null | grep -oE '[A-Za-z_][A-Za-z0-9_]*\.class' | sed 's/\.class$//'
        grep -rl '@SerializedName' "$SRC" 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/\.java$//'
    } | sort -u
}

# Field declarations in one source file, as "<name>\t<annotated>\t<types>", where annotated
# means a @SerializedName sits on the line above it and therefore the Java name is not the
# wire name, and <types> is every identifier in the declared type — the base, its type
# arguments and an array component — comma separated. Methods, statics and initialiser
# values are not fields: `public int mqttPort = 1883;` declares `mqttPort`, so everything
# from the `=` on is dropped before the name is read.
source_fields() {
    awk '
        /@SerializedName/                               { ann = 1; next }
        /^[[:space:]]*(public|protected)[[:space:]].*;/ {
            if ($0 ~ /\(/ || $0 ~ /[[:space:]]static[[:space:]]/) { ann = 0; next }
            decl = $0
            sub(/=.*/, "", decl)
            sub(/;$/, "", decl)
            n = split(decl, parts, /[[:space:]]+/)
            name = parts[n]
            # Everything between the modifier and the field name is the type. A document
            # reached only as `List<Foo>` is still a document Gson has to build, so a gate
            # that looked only at the outer `List` would be checking nothing — which is what
            # a first version of this script did.
            expr = ""
            for (i = 2; i < n; i++) expr = expr " " parts[i]
            types = ""
            while (match(expr, /[A-Za-z_][A-Za-z0-9_]*/)) {
                if (types != "") types = types ","
                types = types substr(expr, RSTART, RLENGTH)
                expr = substr(expr, RSTART + RLENGTH)
            }
            if (name ~ /^[A-Za-z_][A-Za-z0-9_]*$/ && types != "") print name "\t" ann "\t" types
            ann = 0; next
        }
        { ann = 0 }
    ' "$1"
}

# The single .java file behind a simple class name, or empty. Empty means a library type
# (List, Date, String) or a nested class, and the report says so rather than counting it.
file_of() {
    found="$(find "$SRC" -name "$1.java" 2>/dev/null)"
    printf '%s\n' "$found" | sed -n '1p'
}

# A type the app builds with `new` itself has a Java-visible constructor, so R8 has no
# reason to remove one — that is request bodies (Gson only reads their fields on the way
# out). A type nothing constructs is the dangerous case: Gson has to instantiate it.
hand_built() {
    grep -rl "new ${1}(" "$SRC" 2>/dev/null | head -1 || true
}

# Close the list over nesting. A document's field types are documents too — Gson builds
# them on the way in and no Java statement names them — and the two ways a type gets onto
# the list (a fromJson class literal, a @SerializedName declaration) miss exactly the plain
# ones: TTrack is found because it annotates its fields, while Volume and Mute annotate
# nothing and were two of the five classes R8 had merged away in versionCode 19. The
# expansion reads the whole declared type, so a document reached only through a generic
# (`List<Foo>`, `Map<String, Foo>`) is on the list as well — `Foo` is exactly the kind of
# type nothing in Java constructs. Names that resolve to no file under app/src/main/java
# (String, List, Date) drop out here rather than becoming a finding.
TARGETS="$(reflective_targets)"
SEEN=" $(printf '%s\n' "$TARGETS" | tr '\n' ' ') "
WORK="$TARGETS"
while [ -n "$WORK" ]; do
    current="$(printf '%s\n' "$WORK" | sed -n '1p')"
    WORK="$(printf '%s\n' "$WORK" | sed -n '2,$p')"
    current_file="$(file_of "$current")"
    [ -n "$current_file" ] || continue
    while read -r nested; do
        [ -n "$nested" ] || continue
        case "$SEEN" in *" $nested "*) continue ;; esac
        if [ -n "$(file_of "$nested")" ]; then
            SEEN="$SEEN $nested "
            WORK="$WORK
$nested"
            TARGETS="$TARGETS
$nested"
        fi
    done < <(source_fields "$current_file" | cut -f3 | tr ',' '\n' | sort -u)
done

PROBLEM=""
UNCHECKED=""
CHECKED=0

for name in $(printf '%s\n' "$TARGETS" | sort -u); do
    file="$(file_of "$name")"
    if [ -z "$file" ]; then
        # A nested class, or a library type pulled out of a TypeToken. Said out loud
        # rather than counted as checked.
        UNCHECKED="$UNCHECKED $name"
        continue
    fi

    rel="${file#"$SRC"/}"
    rel="${rel%.java}"
    fqn="$(printf '%s' "$rel" | tr '/' '.')"
    desc="L$rel;"

    # A kept class can still be renamed, so the descriptor is whatever mapping.txt says it
    # became. Absent from the mapping *and* from the dex means the class is gone.
    mapped=""
    if [ -f "$MAPPING" ]; then
        mapped="$(awk -v c="$fqn" 'index($0, c " -> ") == 1 { split($0, p, " -> "); sub(/:$/, "", p[2]); print p[2]; exit }' "$MAPPING")"
        if [ -n "$mapped" ]; then
            desc="L$(printf '%s' "$mapped" | tr '.' '/');"
        fi
    fi

    CHECKED=$((CHECKED + 1))
    row="$(awk -F '\t' -v d="$desc" '$1 == d { print; exit }' "$TMP/facts.tsv")"
    if [ -z "$row" ]; then
        if [ -n "$mapped" ]; then
            PROBLEM="$PROBLEM
  $fqn -> $mapped ($desc) — the mapping has that name but no such class is in the dex."
        elif [ -f "$MAPPING" ]; then
            PROBLEM="$PROBLEM
  $fqn — no entry in mapping.txt and no class in the APK: R8 removed or merged it away."
        else
            PROBLEM="$PROBLEM
  $desc — not in the APK."
        fi
        continue
    fi

    flags="$(printf '%s' "$row" | cut -f2)"
    dfields="$(printf '%s' "$row" | cut -f3)"
    inits="$(printf '%s' "$row" | cut -f4)"
    dnames="$(printf '%s' "$row" | cut -f5)"

    # What the source says this type is. A type with no instance fields is not a document
    # Gson maps — it is a constants holder, or a record whose pieces are all static — so
    # demanding fields or a constructor of it would be the gate inventing a requirement.
    fields="$(source_fields "$file")"

    case "$flags" in
        *ABSTRACT*)
            PROBLEM="$PROBLEM
  $desc — abstract in the APK, so Gson cannot construct it. This is the versionCode 19 failure.
    flags:$flags" ;;
    esac

    if [ -n "$fields" ]; then
        if [ "$dfields" -lt 1 ]; then
            PROBLEM="$PROBLEM
  $desc — no instance fields left: Gson wrote them by name, and R8 folded them away."
        fi
        # A type the app itself constructs with `new` (a request body: Gson only reads its
        # fields on the way out) has a Java-visible constructor by definition, so a missing
        # ()V there is R8 being tidy, not a broken build.
        if [ -z "$(hand_built "$name")" ] && [ "$inits" -lt 1 ]; then
            PROBLEM="$PROBLEM
  $desc — no no-argument constructor in the APK, and nothing constructs it in Java either: this is the shape Gson has to instantiate and cannot.
    fields in the APK:$dnames"
        fi
    fi

    # Names are the part a class can keep while still being unreadable: an unannotated
    # public field is matched against the wire by its Java name, so a rename is a document
    # that parses into silence. Annotated fields are exempt — their wire name is the
    # annotation, and R8 may rename the Java side freely.
    while IFS=$(printf '\t') read -r fname fann ftype; do
        [ -n "$fname" ] || continue
        if [ "$fann" != "1" ]; then
            case " $dnames " in
                *" $fname "*) ;;
                *) PROBLEM="$PROBLEM
  $desc.$fname — renamed or dropped, and it carries no @SerializedName, so this build can never see that key again.
    fields in the APK:$dnames" ;;
            esac
        fi
    done < <(printf '%s\n' "$fields")
done

echo "checked $CHECKED reflectively-constructed type(s) in $(basename "$APK")"
if [ -n "$UNCHECKED" ]; then
    echo "not resolved to a source file, therefore not checked:$UNCHECKED"
fi

if [ -n "$PROBLEM" ]; then
    echo "FAIL: this build has types Gson cannot build:$PROBLEM" >&2
    echo >&2
    echo "Fix the type, not the symptom: @Keep on the class is what the other Gson types in" >&2
    echo "this app use (androidx.annotation ships -keep @androidx.annotation.Keep class * { *; })." >&2
    echo "If a finding above says a class is absent while the source still has it, check that the" >&2
    echo "mapping passed as the second argument was produced by THIS build — a mapping from another" >&2
    echo "commit makes a kept-but-renamed class look removed. Pass none to assume no renaming." >&2
    echo "Re-read the binary yourself with:" >&2
    echo "  unzip -p '$APK' classes.dex | '$DEXDUMP_BIN' | grep -A6 '$desc'" >&2
    exit 1
fi

echo "ok: every type Gson builds by reflection is present, concrete, constructible and named"
exit 0
