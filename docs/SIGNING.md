# Signing Tuxora release APKs

By default Tuxora release APKs are signed with the **bundled debug key**
(`app/testkey_untrusted.jks`), whose password is public in this repo. That key is fine for
local builds and for CI convenience, but **anyone can sign an APK with it**, so an APK built
by a third party is indistinguishable from an official one and can be installed as an
"update" over a user's install. For real releases, sign with a private key instead.

The build picks the key automatically:

| When | Key used |
|------|----------|
| `TUXORA_KEYSTORE_FILE` env var is set (CI) | your release key |
| `app/keystore.properties` exists (local) | your release key |
| neither | bundled debug key (unchanged behaviour) |

So nothing breaks for contributors who don't configure a key.

## Generate a key

```bash
scripts/make-release-key.sh app/release.jks tuxora
```

`app/release.jks`, `app/keystore.properties` and `*.keystore` are git-ignored — never commit
them.

## Local release build

```bash
cat > app/keystore.properties <<'PROPS'
storeFile=release.jks
storePassword=••••••
keyAlias=tuxora
keyPassword=••••••
PROPS

./gradlew assembleRelease
```

## CI (GitHub Actions)

Store the key and passwords as repository **secrets**, then expose them to the build step:

```yaml
env:
  TUXORA_KEYSTORE_FILE: ${{ github.workspace }}/app/release.jks
  TUXORA_KEYSTORE_PASSWORD: ${{ secrets.TUXORA_KEYSTORE_PASSWORD }}
  TUXORA_KEY_ALIAS: ${{ secrets.TUXORA_KEY_ALIAS }}
  TUXORA_KEY_PASSWORD: ${{ secrets.TUXORA_KEY_PASSWORD }}
steps:
  - run: echo "${{ secrets.TUXORA_KEYSTORE_B64 }}" | base64 -d > app/release.jks
  # ... then ./gradlew assembleRelease
```

(Store the keystore itself as a base64 secret, e.g. `base64 -w0 app/release.jks`.)

## ⚠️ Key continuity

Android identifies an app by its signing key. Once a release is published with a given key,
**every future release must be signed with the same key**, or users have to uninstall before
they can update. Back the key up safely and do not rotate it casually.
