#!/usr/bin/env bash
# Makes the release key for the APKs of GitHub releases, once. The keystore is
# written to ~/.local/share/perigee/release.jks, its password goes to the macOS
# Keychain, and both become secrets of the GitHub repository for the release workflow.
# Prints the SHA-256 of the certificate: the release workflow checks the APKs against it.
# A phone takes an update only when it is signed with the same key: a lost
# key cannot be replaced, so keep a copy of the keystore and its password elsewhere.
set -euo pipefail

keystore="${PERIGEE_RELEASE_KEYSTORE:-$HOME/.local/share/perigee/release.jks}"
if [[ -e "$keystore" ]]; then
    printf 'The release keystore already exists: %s\n' "$keystore" >&2
    exit 1
fi

PERIGEE_RELEASE_PASSWORD="$(openssl rand -hex 24)"
export PERIGEE_RELEASE_PASSWORD

mkdir -p "$(dirname "$keystore")"
keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -alias perigee \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Perigee" \
    -storepass:env PERIGEE_RELEASE_PASSWORD -keypass:env PERIGEE_RELEASE_PASSWORD
chmod 600 "$keystore"

security add-generic-password -s org.evsyukov.perigee.release -a perigee -w "$PERIGEE_RELEASE_PASSWORD"
base64 < "$keystore" | gh secret set PERIGEE_RELEASE_KEYSTORE_B64
printf '%s' "$PERIGEE_RELEASE_PASSWORD" | gh secret set PERIGEE_RELEASE_PASSWORD

keytool -list -v -keystore "$keystore" -alias perigee -storepass:env PERIGEE_RELEASE_PASSWORD \
    | sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr '[:upper:]' '[:lower:]'
