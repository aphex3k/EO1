# Root bundle inputs

The anchors this build ships, taken wholesale from a Debian `ca-certificates` package rather than chosen
here. `./update-roots.sh` refreshes this directory and writes the two files that describe it;
`./gen-root-bundle.sh` turns the result into
`app/src/main/java/com/aphex3k/eo1/trust/RootBundle.java`. See [docs/TRUST.md](../../docs/TRUST.md) for why
a distribution's list is the right source and what following one costs.

| file | what it is |
|---|---|
| `*.pem` | one pure PEM per anchor, renamed from Debian's Mozilla label |
| `MANIFEST.tsv` | what ships: file, store name (`subject_hash_old`), SHA-256, `notAfter`, subject, original label — **the diff a human reads** |
| `debian.json` | the pin: suite, package version, URL, size, SHA-256 |

To refresh: `./update-roots.sh --dry-run`, read the additions and removals it prints, then
`./update-roots.sh` and commit. To add a root by hand, don't — it will be gone on the next refresh, and
if Debian does not carry an anchor that is information about the anchor.

## What the policy actually is

Every entry is compiled into the APK and trusted by the app's own TLS path, and the set is **not** curated
for need: a frame gets Mozilla's whole trust list, including CAs it will never meet. That replaced an
earlier rule of "keep it to anchors that are actually needed", which was ten files copied off a laptop and
had no way to stay true. The trade is explicit — curation cost a source of truth, and this has one, at the
price of shipping ~120 anchors nobody vetted individually and of inheriting upstream's removals.

Two consequences an agent gets wrong without being told:

- **A removal is a normal diff.** When Mozilla withdraws trust, Debian's generator stops writing the file
  (it admits only `CKT_NSS_TRUSTED_DELEGATOR`), so the anchor disappears here. `update-roots.sh` prints
  those as `REMOVED` before writing anything. There is deliberately no keep-anyway list.
- **An expired anchor is pruned at refresh, not at build.** A distribution store always contains something
  on its way out. `gen-root-bundle.sh` still refuses expired input — that guard is for this directory on
  the hand-curated path — and `publish-update.sh` fails a release over a committed anchor that lapsed.
  `RootBundleTest` only warns, so that `./gradlew test` cannot go red on a date across every old commit.

## Provenance

The pin in `debian.json` is the provenance: fetch that version, re-hash it against that SHA-256, extract
`/usr/share/ca-certificates/mozilla/*.crt`, and every byte here follows. The archive's own `md5sums` member
is checked per file, which is what catches a partially-extracted tree — a silently smaller bundle is the
one failure mode `MergedTrust`'s merging-may-only-add guard cannot see, because that guard compares against
the platform store and not against what this bundle used to hold.

Before 2026-10-27 the ten files here were extracted by hand from a machine-local `ca-certificates` bundle
and their fingerprints were recorded in a table in this file; `git log` back through that date reads as the
curation it was.
