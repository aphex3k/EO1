# Root bundle inputs

One file per certificate, pure PEM, nothing else. `./gen-root-bundle.sh` reads this directory and
writes `app/src/main/java/com/aphex3k/eo1/trust/RootBundle.java`; it aborts rather than emit a
bundle for a certificate that does not parse, is expired, or disagrees with its own DER encoding.

To add a root: drop the PEM here and re-run the script. Keep it to anchors that are actually
needed — every entry is compiled into the APK and trusted by the app's own TLS path.

## Provenance

Extracted 2026-10-23 from the machine-local `ca-certificates` bundle
(`/opt/homebrew/etc/ca-certificates/cert.pem`, the Mozilla store). The SHA-256 fingerprints below are
the check: re-fetch a root from its publisher and confirm the fingerprint matches before committing a
replacement; Let's Encrypt publishes its own root fingerprints on its certificate documentation page.

| file | subject | store name (`subject_hash_old`) | SHA-256 fingerprint | expires |
|---|---|---|---|---|
| `certum-trusted-root.pem` | C=PL, O=Asseco Data Systems S.A., OU=Certum Certification Authority, CN=Certum Trusted Root CA | `f8fc53da.0` | `FE:76:96:57:38:55:77:3E:37:A9:5E:7A:D4:D9:CC:96:C3:01:57:C1:5D:31:76:5B:A9:B1:57:04:E1:AE:78:FD` | Mar 16 12:10:13 2043 GMT |
| `digicert-global-root-g2.pem` | C=US, O=DigiCert Inc, OU=www.digicert.com, CN=DigiCert Global Root G2 | `c90bc37d.0` | `CB:3C:CB:B7:60:31:E5:E0:13:8F:8D:D3:9A:23:F9:DE:47:FF:C3:5E:43:C1:14:4C:EA:27:D4:6A:5A:B1:CB:5F` | Jan 15 12:00:00 2038 GMT |
| `entrust-root-ca-g2.pem` | C=US, O=Entrust, Inc., OU=See www.entrust.net/legal-terms, OU=(c) 2009 Entrust, Inc. - for authorized use only, CN=Entrust Root Certification Authority - G2 | `455f1b52.0` | `43:DF:57:74:B0:3E:7F:EF:5F:E4:0D:93:1A:7B:ED:F1:BB:2E:6B:42:73:8C:4E:6D:38:41:10:3D:3A:A7:F3:39` | Dec  7 17:55:54 2030 GMT |
| `globalsign-root-ca-r3.pem` | OU=GlobalSign Root CA - R3, O=GlobalSign, CN=GlobalSign | `1e8e7201.0` | `CB:B5:22:D7:B7:F1:27:AD:6A:01:13:86:5B:DF:1C:D4:10:2E:7D:07:59:AF:63:5A:7C:F4:72:0D:C9:63:C5:3B` | Mar 18 10:00:00 2029 GMT |
| `globalsign-root-r46.pem` | C=BE, O=GlobalSign nv-sa, CN=GlobalSign Root R46 | `1b0f7e5c.0` | `4F:A3:12:6D:8D:3A:11:D1:C4:85:5A:4F:80:7C:BA:D6:CF:91:9D:3A:5A:88:B0:3B:EA:2C:63:72:D9:3C:40:C9` | Mar 20 00:00:00 2046 GMT |
| `isrg-root-x1.pem` | C=US, O=Internet Security Research Group, CN=ISRG Root X1 | `6187b673.0` | `96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6` | Jun  4 11:04:38 2035 GMT |
| `isrg-root-x2.pem` | C=US, O=Internet Security Research Group, CN=ISRG Root X2 | `8794b4e3.0` | `69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70` | Sep 17 16:00:00 2040 GMT |
| `sectigo-pss-root-e46.pem` | C=GB, O=Sectigo Limited, CN=Sectigo Public Server Authentication Root E46 | `3afde786.0` | `C9:0F:26:F0:FB:1B:40:18:B2:22:27:51:9B:5C:A2:B5:3E:2C:A5:B3:BE:5C:F1:8E:FE:1B:EF:47:38:0C:53:83` | Mar 21 23:59:59 2046 GMT |
| `sectigo-pss-root-r46.pem` | C=GB, O=Sectigo Limited, CN=Sectigo Public Server Authentication Root R46 | `e071171e.0` | `7B:B6:47:A6:2A:EE:AC:88:BF:25:7A:A5:22:D0:1F:FE:A3:95:E0:AB:45:C7:3F:93:F6:56:54:EC:38:F2:5A:06` | Mar 21 23:59:59 2046 GMT |
| `usertrust-rsa-ca.pem` | C=US, ST=New Jersey, L=Jersey City, O=The USERTRUST Network, CN=USERTrust RSA Certification Authority | `35105088.0` | `E7:93:C9:B0:2F:D8:AA:13:E2:1C:31:22:8A:CC:B0:81:19:64:3B:74:9C:89:89:64:B1:74:6D:46:C3:D4:CB:D2` | Jan 18 23:59:59 2038 GMT |
