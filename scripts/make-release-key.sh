#!/usr/bin/env bash
##
## Generate a release signing key for Tuxora.
##
## Release APKs are signed with the bundled *debug* key unless a real release key is
## configured. This helper creates one. The key and its passwords are secrets - keep them
## out of git (app/keystore.properties and app/release.jks are already .gitignore'd) and,
## for CI, store them as repository secrets (see docs/SIGNING.md).
##
## Usage:
##   scripts/make-release-key.sh [output.jks] [alias]
##
set -euo pipefail

OUT="${1:-app/release.jks}"
ALIAS="${2:-tuxora}"

command -v keytool >/dev/null || { echo "keytool not found (install a JDK)"; exit 1; }

if [ -e "$OUT" ]; then
  echo "Refusing to overwrite existing key: $OUT" >&2
  exit 1
fi

echo "[*] Generating RSA-4096 release key: $OUT (alias: $ALIAS, validity ~27 years)"
echo "    You'll be prompted for a keystore password and your name/org (CN)."
keytool -genkeypair -v \
  -keystore "$OUT" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000

cat <<EOF

[*] Done: $OUT

Next, point the build at it with a local properties file (git-ignored):

  cat > app/keystore.properties <<PROPS
  storeFile=$(basename "$OUT")
  storePassword=<the keystore password you just set>
  keyAlias=$ALIAS
  keyPassword=<the key password you just set>
  PROPS

...or, for CI, set repository secrets and pass them as env vars:
  TUXORA_KEYSTORE_FILE, TUXORA_KEYSTORE_PASSWORD, TUXORA_KEY_ALIAS, TUXORA_KEY_PASSWORD

See docs/SIGNING.md. NOTE: once a release is published with a given key, every future
release must use the SAME key, or users must uninstall before updating (Android rejects an
update signed by a different key).
EOF
