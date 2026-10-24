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
report, work out which gate you are at. `/state`'s `trust` block answers gates 1, 2 and 4 — the
anchor counts and `missingBundleAnchors`, `gates` (`anyEcdsa`, `anyAead`, `suiteCount`, the suite
list), and `nowMs` read against your own clock. Gate 3 is answered by no route: it shows up only in
the text of the failure itself, which is why the symptom is spelled out under it.
`TrustCensus.classifyFailure` sorts an exception into all four, but **it has no production caller**
— only its unit test runs it — so it is the taxonomy this document uses, not something a frame
reports. Wiring it into the fetch path would make gate 3 nameable on the device rather than
inferred from a log line.

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
frame's gap, not the size of the bundle, which is **10** — see "Where the roots come from". The
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

**Ten** roots: `certs/roots/*.pem`, one pure PEM each, with provenance and SHA-256 fingerprints in
[`certs/roots/README.md`](../certs/roots/README.md). `RootBundle.ENTRIES` carries the same ten — it is
generated from that directory — and `/state`'s `trust.bundleSize` reports the number on the device.
They are compiled into the APK rather than downloaded because the APK is already authenticated by
signature (`com.aphex3k.update`), and a second fetch path for trust material would mean a second
verifier and a second set of failure modes for no gain. `res/raw` and `assets/` are avoided on
purpose: release builds run `shrinkResources = true`, and a trust anchor that a resource-shrinking
step can decide is unreferenced is the wrong thing to discover on a frame behind glass.

Regenerate with:

```bash
./gen-root-bundle.sh
```

The generator refuses to emit an entry for a certificate that does not parse, is past its own expiry,
or disagrees with its own DER encoding, and `RootBundleTest` re-proves all of it from the generated
file on every test run — including that each entry is genuinely self-signed, because an intermediate
is not an anchor.

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
