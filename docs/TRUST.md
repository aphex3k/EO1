# TLS trust on a 2013 store

Why an EO1/EO2 cannot validate a current Immich server, what this app now does about it, and what no
amount of certificate work can fix.

## The failure

Every HTTPS connection the app makes gets its trust decision from the device. Before this feature,
`ApiServiceGenerator.enableTls12OnPreLollipop` called `SSLContext.init(null, null, null)` — a null
trust manager means "the platform default", which on these frames is the ROM's CA store, assembled
around 2013. Measured on a live EO2 (`adb shell ls /system/etc/security/cacerts | wc -l`):

| | |
|---|---|
| anchors shipped | **156** |
| already past their own `notAfter` | **63** |
| matching ISRG / Let's Encrypt | **0** |

That last row is the actual failure, and it is not the same story as "the certificates expired". An
expired anchor is inert: it is never selected ahead of a valid one, so 63 dead roots cost nothing
except directory entries. What killed Let's Encrypt was the September 2024 expiry of the DST Root CA
X3 cross-signature that LE used to serve old Androids a chain they could still build. The rescue
chain is gone, `ISRG Root X1` was never added to a KitKat store, and there is no anchor left to
chain to.

So the fix is an **add**, not a replace. Retiring the expired 63 is hygiene with no upside, and
pruning one that some other host still chains to is a regression — which is why nothing in this app
removes an anchor as part of installing one.

## Four gates, not one

A frame can have a perfect anchor set and still fail to connect. Before spending an install on a
report, work out which gate you are at. `/state`'s `trust` block answers gates 1, 2 and 4 on a frame
that is talking to its server — the anchor counts and `missingBundleAnchors`, `gates` (`anyEcdsa`,
`anyAead`, `suiteCount`, the suite list), and `nowMs` read against your own clock. Gate 3 is invisible
until something fails, because it is the server's decision rather than the device's.

`TrustCensus.classifyFailure` sorts a failure into the four, and the frame reports that verdict
instead of leaving you to infer it from exception text. `TrustCensus.gateNote` appends
`[TLS gate: NAME]` to a backend's last-fetch record — which is what the state dump and a **Capture
now** report print on their `backends:` lines — and to an update's `lastError`, which `/state` shows
in its `update` block and the page shows beside the version. The names map onto the gates below:
`TRUST_ANCHOR_MISSING` is gate 1, `TLS_PROTOCOL` gates 2 or 3, `NO_SHARED_CIPHER` gate 3, and
`CERT_EXPIRED` / `CERT_NOT_YET_VALID_CHECK_CLOCK` gate 4.

It appends **nothing** when the failure was never about trust. A timeout, a refused connection or a
DNS failure keeps its bare message, because the classification's whole purpose is to stop an operator
reinstalling roots against a dead radio — so the absence of a `TLS gate` note is itself a finding,
and the more common one. The note lives on the record rather than in a remembered global: a later
healthy connection must not have to know to clear it, and `/logs` still shows the raw
`SSLHandshakeException`, which carries the detail this one summarises.

1. **Anchors.** The subject must be in the store. Fixed by `RootBundle` (below).
2. **Cipher suites.** OkHttp 3.12's ECDSA suites are all AEAD, and this platform's OpenSSL has no
   AEAD at all. A server presenting an ECDSA chain therefore has *nothing* in common with the frame
   until `TLS_ECDHE_ECDSA_WITH_AES_128/256_CBC_SHA` are named — which `ApiServiceGenerator` now does,
   as a **union** with `MODERN_TLS`'s list. Replacing rather than widening that list is a real
   regression: it deletes TLS 1.2 GCM for every newer platform this same code runs on, and the
   Immich live-tests fail with `handshake_failure`. No root install can open this gate.
   Measured on a bench EO2, the entire supported list is 53 suites and **none of them is AEAD** — the
   strongest digest anything in it uses is SHA-1 — and the ECDSA entries are exactly
   `TLS_ECDHE_ECDSA_WITH_{AES_128,AES_256}_CBC_SHA`, `_3DES_EDE_CBC_SHA` and `_RC4_128_SHA`. That is
   the hardware ceiling: a server that offers only GCM cannot be reached from this device in any
   direction, and certificates are not the reason it fails. `/state`'s `trust.gates` reports
   `anyEcdsa`, `anyAead` and the list, so the gate can be named on a frame that has no `openssl`.
3. **Server policy.** A host configured AEAD-only or TLS-1.3-only cannot be reached by a 2013 client
   from any direction. The answer is a server-side cipher policy or plain `http://`, never a
   certificate change. This is not hypothetical: `immich.nas.codingmerc.com` serves an ECDSA leaf and
   accepts only `ECDHE-ECDSA-AES*-GCM-*` on TLS 1.2, rejecting every CBC option including 3DES, and
   has no RSA chain — so a KitKat frame cannot complete a handshake with it whatever anchors it holds.
   The symptom is `sslv3 alert handshake failure` raised at `SSL23_GET_SERVER_HELLO`, *before* the
   certificate is read, which is how to tell gate 3 from gate 1 in a log.
4. **The clock.** Let's Encrypt issues 90-day certificates and this app contains no time sync. On a
   frame powered off for a week, every certificate reads not-yet-valid or expired and a flawless
   anchor bundle buys nothing. Compare `/state`'s `nowMs` against your own clock before believing
   any other diagnosis. (Measured EO2 clock: correct.)

All four are properties of an outbound HTTPS connection. A frame configured only to show the files
uploaded to itself — one `{"type":"local"}` backend, no `https://` address anywhere in its
configuration — makes no TLS connection to fetch media and so consults no certificate at all.

## How this app now trusts

Two mechanisms, at two ranges. Both read the same generated file, so there is one bundle to keep
right.

### App-local (`MergedTrust`) — ships in the APK, needs no privilege

Platform anchors are enumerated at runtime and merged with `RootBundle`, and the result is installed
at the app's single TLS choke point. This covers every connection the app makes with OkHttp — the
Immich clients and `UpdateManager`'s download client, which reach the network through the same
method — plus Paho MQTT, which cannot take an `OkHttpClient` and is given the same
`SSLSocketFactory` by `MqttManager` when the broker scheme is `ssl`.

Rollback is installing the previous APK. Nothing on the device was ever written.

The rule that makes this safe is that **merging may only ever add**. If the platform's anchors cannot
be enumerated, or the merged set comes out smaller than the platform's, the manager is not installed
at all and the frame trusts exactly what it trusted before. A narrowed trust set would break hosts
that work today and report itself as a certificate problem. The same reasoning applies to the cipher
list in gate 2 above, and the first draft of this change got that backwards.

**Measured on a bench EO2** (`/state`'s `trust` block, app build with this change installed):
`mode=merged`, `platformAnchors=156`, `trustedAnchors=164` — the ROM's own 156 anchors enumerated
through its provider, plus the 8 shipped roots that particular device was missing. That 8 is one
frame's gap, not the size of the bundle — **10** at the time of that reading, **11** once
`amazon-root-ca-1` was added for Flickr on 2026-10-26, and a whole distribution's store since the
Debian-sourced bundle landed on 2026-10-27. See "Where the roots come from". The
census reproduced the host-side measurements exactly (`readable=true`, `total=156`, `expired=63`,
`unparseable=0`), which also proves the app uid can read `/system/etc/security/cacerts` under
SELinux enforcing.

Getting there caught a bug no unit test could: the first version asked for
`TrustManagerFactory.getInstance("X.509")`. A workstation's JDK accepts that spelling as an alias, and
this ROM's provider does not —
`NoSuchAlgorithmException: TrustManagerFactory X.509 implementation not found`. The failure rule worked
exactly as designed: it refused to install, fell back to `platform-only`, and said why in `detail`.
Which is also the lesson — **23 green unit tests and an inert fix.** The algorithm name is now resolved
through `getDefaultAlgorithm()` with the literals as fallbacks, and each enumeration route is tried
independently so a refusal on one cannot skip past the other.

What it does **not** reach: the kiosk `WebView`. Chromium-33 validates inside its own engine and
ignores every Java trust manager, so an `https://` page still depends on the ROM store. (No
`onReceivedSslError` handler exists, so an untrusted page fails like any other dead one.) That gap is
the only remaining reason to write to the device, and it is why the second mechanism exists.

### Device store (`RootBundle` filenames) — needs a root shell

`com.aphex3k.eo1.trust.SubjectHashOld` implements the platform's own naming rule: MD5 over the DER
encoding of the certificate's SubjectName — the name's full TLV, not its contents — first four bytes
read little-endian, printed as eight lowercase hex digits. That is OpenSSL's `X509_NAME_hash_old`,
and it is verifiable rather than folklore: all 156 files in the EO2 store hash to their own basename,
and the ROM's `conscrypt.jar` carries both `X509_NAME_hash_old` and `md5_hash`.

Getting the name right is not cosmetic. Conscrypt reaches an anchor on the TLS path by probing
`<hash>.<index>` and stops at the first index that is not there — it does not scan for anything that
parses. A valid certificate filed under a wrong name is invisible to validation while still being
listed by the Settings UI. That is the failure mode this whole mechanism has to be written against.

**Status: not yet implemented.** The measured facts about where such a write could land:

| target | label | mode | what it means |
|---|---|---|---|
| `/system/etc/security/cacerts` | `system_file` | 0755, files 0644 | world-readable, so the app *can* read what lands here — but `/system` is mounted **`ro`** by `init.amlogic.board.rc:18` (`ext4 … ro`), so writing needs a remount, and a remount is the one step here that can leave an unattended frame with an unreadable CA directory and no watchdog rung that recovers it |
| `/data/misc/keychain/cacerts-added` | `system_data_file` | `0770 system:system` | created every boot by `init.rc:207` (`mkdir /data/misc/keychain 0771 system system`); others get `--x`, so an app uid can open a filename it already knows but `listFiles()` returns null — enumeration is blind to it until the directory is chmod'd |
| `/data/misc/keychain/cacerts-removed` | same | — | the framework's own masking: an identical file here retires a ROM anchor with no partition write, and is undone by deletion |

Both routes are shadowed by a third fact: **SELinux is `Enforcing`** on this ROM, the app runs as
`u:r:untrusted_app:s0`, and the privileged writer runs as `u:r:shell:s0` with `uid=0`. So the open
question is not "can root write it" but "can an `untrusted_app` read what root wrote" — under
enforcing policy, Unix mode bits are the smaller half of the answer. That is measurable, and it
should be measured by a write attempt on one bench frame before any of it is shipped: create the
file, then have the app's own `TrustCensus` report whether it can see the anchor. Anything else is a
guess dressed as a plan.

## Where the roots come from

**The roots are Debian's, not a list this project maintains.** `certs/roots/*.pem` is the whole
`/usr/share/ca-certificates/mozilla` set from a pinned Debian `ca-certificates` package — Mozilla's
trust table, published with a version, a checksum and a per-file `md5sums` list — refreshed by
[`../update-roots.sh`](../update-roots.sh) and committed by a human. [`MANIFEST.tsv`](MANIFEST.tsv) is
the list a reviewer reads (file, the store name Android gives it, SHA-256, expiry, subject, Mozilla
label) and its diff is the change; [`debian.json`](debian.json) is the pin the download is checked
against. `RootBundle.ENTRIES` carries exactly those anchors — it is generated from that directory — and
`/state`'s `trust.bundleSize` reports the number on the device. Where an anchor was chosen for a specific
reason rather than inherited — the single Amazon Trust Services root that Flickr's chain actually closes
at — that measurement is recorded in
[`certs/roots/README.md`](../certs/roots/README.md), and it survives here as a fact about a server's chain
even though all four Amazon roots now arrive with the distribution's set.

Following a distribution rather than curating is the point, and it has a price worth stating. Mozilla
adds and removes trust continuously, and this project has no better source of truth than its list. So
**a root Debian removes is gone from frames at the next release** — there is no override list to keep
one, by an explicit decision, because an anchor the app carries after its own upstream withdrew it is a
root nobody is watching. Between releases the only lever is `trust_bundle` at the end of this page,
which is all-or-nothing for the entire bundle and has no UI. **Distrust latency equals release
cadence**, and that is a property of the product rather than an oversight.

They are compiled into the APK rather than downloaded because the APK is already authenticated by
signature (`com.aphex3k.update`), and a second fetch path for trust material would mean a second
verifier and a second set of failure modes for no gain. `res/raw` and `assets/` are avoided on
purpose: release builds run `shrinkResources = true`, and a trust anchor that a resource-shrinking
step can decide is unreferenced is the wrong thing to discover on a frame behind glass.

Refresh the set (fetch, verify, prune expired, show what would be added and removed, write nothing):

```bash
./update-roots.sh --dry-run --suite sid
```

Then do it for real, read the diff, and commit it:

```bash
./update-roots.sh --suite sid
```

`./gen-root-bundle.sh` is the lower half — it turns `certs/roots/*.pem` into `RootBundle.java` and takes
no part in fetching. It refuses to emit an entry for a certificate that does not parse, is past its own
expiry, disagrees with its own DER encoding, duplicates an anchor already in the set, or whose file name
would sanitize to a Java identifier another input already claimed.

Neither script runs during a build, and `publish-update.sh` reaches no network for trust material: it
regenerates `RootBundle.java` from the committed PEMs into a temp directory and refuses the release if
the result differs from what is committed, if `MANIFEST.tsv` disagrees with the entry count, or if any
shipped anchor is past its own expiry — the same fail-closed-with-an-escape-hatch shape as the R8
mapping, and `EO1_UPDATE_ROOTS_OPTIONAL=1` is its opt-out. That separation is what keeps the bundle
reviewable at all: `BUILD_DATE` in a release is the commit's timestamp, so roots that varied without a
commit would let two APKs carrying different trust material report the same `GIT_SHA`.

Expiry is why the refresh prunes and the test only warns. A distribution's store always contains
something on its way out — one sid release carries 120 anchors with nothing expiring before 2028, one
stable release carries 148 with one already expired and another lapsing within weeks — and a
`./gradlew test` that goes red on a calendar date, on every branch and every old commit, is a test that
gets deleted rather than fixed. `RootBundleTest` prints what lapsed; the release gate is what refuses.

The switch is the `trust_bundle` key in the app's **default SharedPreferences** —
`/data/data/com.aphex3k.eo1/shared_prefs/com.aphex3k.eo1_preferences.xml`, read as
`SettingsManager.PREF_TRUST_BUNDLE` by `SettingsManager.isTrustBundleEnabled()` and
`ApiServiceGenerator`, **on by default** (the key is usually absent, because absence is the default)
and never exported. There is **no UI for it**: the options dialog has no checkbox and no web route
writes it, so switching it off means editing that file from a root or `adb` shell. That is the
opposite of `trusted_network` and `loopback_adb`, and deliberately so: those
grant a privilege, so nothing happens until an operator asks; this one trusts a set of public roots
that arrived inside an APK the frame already verified. The failure it protects against is the quiet
one. Off restores exactly the pre-feature behaviour, which is why the escape hatch does not need to be
found before shipping.

## Checking any of this on a frame

```bash
curl -s http://<frame>:8080/state | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["trust"], indent=2))'
```

`mode` is the field to read first: `merged` means the bundle is in force for this app's connections;
`platform-only` means it is not, and `detail` says whether that was the operator's choice or a
refusal. `stores[].readable` distinguishes an empty store from one the app was not allowed to open,
which is the difference between a broken frame and a working one.
