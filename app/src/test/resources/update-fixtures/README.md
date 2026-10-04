# Update self-test fixtures

Pre-generated archives for `ApkSignatureVerifierTest` (JAR-signature verification
without the Android platform). These are plain zips with a JAR-style
`META-INF/` signature block — the verifier treats APKs and zips identically.

**Keystores and raw inputs are intentionally NOT committed** (no private keys in
the repo). They live in a scratch dir alongside this tree; regenerate per below.

## Contents

| File | What it is | Expected verifier result |
|------|-----------|--------------------------|
| `signed-sha256.zip` | Signed with keystore A, `SHA-256` digest / `SHA256withRSA` | valid, signer cert CN=EO1 Fixture A |
| `signed-sha1.zip` | Signed with keystore A, `SHA-1` digest / `SHA1withRSA` | valid, signer cert CN=EO1 Fixture A |
| `signed-keystore-b.zip` | Signed with keystore B, `SHA-256` | valid, signer cert CN=EO1 Fixture B |
| `signed-wrapped-name.zip` | Signed with keystore C, `SHA-256`; contains an 86-char entry name whose `Name:` line wraps at 72 columns in `MANIFEST.MF` | valid, signer cert CN=EO1 Fixture C |
| `unsigned.zip` | Same payload, no signature | `no-signature` |
| `truncated.zip` | First 300 bytes of `signed-sha256.zip` | `zip-error` |
| `cert-a.pem` / `cert-b.pem` / `cert-c.pem` | X.509 signing certs (PEM), for the expected-signer gate | — |

Payload: `hello.txt` = `Hello EO1 fixture\n` (18 B) and `data.bin` = 512 B,
`bytes(range(256))` repeated twice
(SHA-256 `110009dcee21620b166f3abfecb5eff7a873be729d1c2d53822e7acc5f34eb9b`).
`signed-wrapped-name.zip` instead carries `hello.txt` plus
`META-INF/androidx.lifecycle_lifecycle-viewmodel-savedstate-aphex3k-regression-check.module.json`
(`Wrapped-name fixture payload\n`) — the 86-char entry name forces the
`Name:` line to wrap at 72 columns in `MANIFEST.MF`, exercising the wrapped-
name / raw-section digest paths.

## Regeneration

Requires `keytool` + `jarsigner` (any JDK 17+ works; fixtures were generated
with JDK 21). All commands run in one scratch dir.

```bash
mkdir -p eo1-fixtures && cd eo1-fixtures

# 1. payload
printf 'Hello EO1 fixture\n' > hello.txt
python3 -c 'import sys; sys.stdout.buffer.write(bytes(range(256)) * 2)' > data.bin

# 2. unsigned zip
zip unsigned.zip hello.txt data.bin

# 3. keystores — password `fixture`, RSA 2048, self-signed, 10-year validity
keytool -genkeypair -keystore fixture-a.keystore -storepass fixture \
  -alias fixture-a -keypass fixture -keyalg RSA -keysize 2048 \
  -dname "CN=EO1 Fixture A, OU=Testing, O=EO1, C=US" -validity 1825
keytool -genkeypair -keystore fixture-b.keystore -storepass fixture \
  -alias fixture-b -keypass fixture -keyalg RSA -keysize 2048 \
  -dname "CN=EO1 Fixture B, OU=Testing, O=EO1, C=US" -validity 1825
keytool -genkeypair -keystore fixture-c.keystore -storepass fixture \
  -alias fixture-c -keypass fixture -keyalg RSA -keysize 2048 \
  -dname "CN=EO1 Fixture C, OU=Testing, O=EO1, C=US" -validity 1825

# 4. signed zips (copy payload, then sign in place)
cp unsigned.zip signed-sha256.zip
jarsigner -keystore fixture-a.keystore -storepass fixture -keypass fixture \
  -digestalg SHA-256 -sigalg SHA256withRSA signed-sha256.zip fixture-a

cp unsigned.zip signed-sha1.zip
jarsigner -keystore fixture-a.keystore -storepass fixture -keypass fixture \
  -digestalg SHA-1 -sigalg SHA1withRSA signed-sha1.zip fixture-a

cp unsigned.zip signed-keystore-b.zip
jarsigner -keystore fixture-b.keystore -storepass fixture -keypass fixture \
  -digestalg SHA-256 -sigalg SHA256withRSA signed-keystore-b.zip fixture-b

# 4b. wrapped-name zip: an 86-char entry name so the MANIFEST.MF "Name:" line
# wraps at 72 columns (jarsigner keeps the wrapped form in the manifest; the
# .SF digest then covers the wrapped bytes)
mkdir -p "META-INF"
printf 'Wrapped-name fixture payload\n' > \
  "META-INF/androidx.lifecycle_lifecycle-viewmodel-savedstate-aphex3k-regression-check.module.json"
zip wrapped-payload.zip hello.txt \
  "META-INF/androidx.lifecycle_lifecycle-viewmodel-savedstate-aphex3k-regression-check.module.json"
jarsigner -keystore fixture-c.keystore -storepass fixture -keypass fixture \
  -digestalg SHA-256 -sigalg SHA256withRSA wrapped-payload.zip fixture-c
mv wrapped-payload.zip signed-wrapped-name.zip

# 5. truncated copy
head -c 300 signed-sha256.zip > truncated.zip

# 6. PEM certs
keytool -list -rfc -keystore fixture-a.keystore -storepass fixture \
  -alias fixture-a > cert-a.pem
keytool -list -rfc -keystore fixture-b.keystore -storepass fixture \
  -alias fixture-b > cert-b.pem
keytool -list -rfc -keystore fixture-c.keystore -storepass fixture \
  -alias fixture-c > cert-c.pem
```

Then copy `*.zip` + `*.pem` into `app/src/test/resources/update-fixtures/`
(never the keystores).

## Signature anatomy (why the verifier is written the way it is)

- JDK `jarsigner` signs the DER **`SET OF` (tag `0x31`)** re-encoding of the
  signed attributes (byte-sorted attribute TLVs —
  `sun.security.pkcs.PKCS9Attributes.getDerEncoding()`), **not** the `[0]`
  wrapper bytes stored in the `.RSA` file. `ApkSignatureVerifier` rebuilds that
  SET OF before RSA verification; verifying over the stored bytes always fails.
- JDK 21 writes `signatureValue` as an **OCTET STRING** (`0x04`) of the raw
  RSA bytes; tools like `apksigner` write a **BIT STRING** (`0x03`) with one
  unused-bits byte. The verifier accepts both shapes (candidates are always
  cryptographically checked, so leniency is safe).
- The `.SF` `messageDigest` attribute is cross-checked against the raw `.SF`
  bytes as stored in the zip, independent of the RSA step.
- **Attribute-less SignerInfo** (the shape `apksigner`/AGP v1 signing writes,
  i.e. every release APK this repo ships): no `[0]` SignedAttributes at all —
  the SignerInfo is `version, issuerAndSerial, digestAlgorithm,
  signatureAlgorithm (bare `rsaEncryption`), signatureValue`, and the
  signature covers the **raw `.SF` bytes** directly, with the effective
  algorithm formed from `digestAlgorithm` + `rsaEncryption`
  (sha256 → `SHA256withRSA`). JDK `jarsigner` always writes signed
  attributes, so no committed fixture exercises this path — it is pinned by
  the real-APK behavior observed on-device; the verifier accepts both shapes.
- **Manifest line wrapping**: entry names long enough to push a `Name:` line
  past 72 bytes are wrapped in `MANIFEST.MF` (continuation line starts with a
  single space). The `.SF` per-entry digest covers the section **as stored**
  (wrapped, CRLF), not a re-canonicalized unwrapped form, and entry lookups
  must use the fully unwrapped name. `signed-wrapped-name.zip` pins this.
