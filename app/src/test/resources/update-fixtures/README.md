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
| `unsigned.zip` | Same payload, no signature | `no-signature` |
| `truncated.zip` | First 300 bytes of `signed-sha256.zip` | `zip-error` |
| `cert-a.pem` / `cert-b.pem` | X.509 signing certs (PEM), for the expected-signer gate | — |

Payload (identical across all zips): `hello.txt` = `Hello EO1 fixture\n` (18 B)
and `data.bin` = 512 B, `bytes(range(256))` repeated twice
(SHA-256 `110009dcee21620b166f3abfecb5eff7a873be729d1c2d53822e7acc5f34eb9b`).

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

# 5. truncated copy
head -c 300 signed-sha256.zip > truncated.zip

# 6. PEM certs
keytool -list -rfc -keystore fixture-a.keystore -storepass fixture \
  -alias fixture-a > cert-a.pem
keytool -list -rfc -keystore fixture-b.keystore -storepass fixture \
  -alias fixture-b > cert-b.pem
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
