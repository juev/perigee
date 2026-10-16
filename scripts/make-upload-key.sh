#!/usr/bin/env bash
# Makes the upload key for Google Play, once. The keystore is written to
# ~/.local/share/perigee/upload.jks, its password goes to the macOS Keychain,
# and both become secrets of the GitHub repository for the release workflow.
# Prints the SHA-256 of the certificate: the release workflow checks the bundle against it.
set -euo pipefail

keystore="${PERIGEE_UPLOAD_KEYSTORE:-$HOME/.local/share/perigee/upload.jks}"
if [[ -e "$keystore" ]]; then
    printf 'The upload keystore already exists: %s\n' "$keystore" >&2
    exit 1
fi

PERIGEE_UPLOAD_PASSWORD="$(openssl rand -hex 24)"
export PERIGEE_UPLOAD_PASSWORD

mkdir -p "$(dirname "$keystore")"
keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -alias perigee \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Perigee" \
    -storepass:env PERIGEE_UPLOAD_PASSWORD -keypass:env PERIGEE_UPLOAD_PASSWORD
chmod 600 "$keystore"

security add-generic-password -s org.evsyukov.perigee.upload -a perigee -w "$PERIGEE_UPLOAD_PASSWORD"
base64 < "$keystore" | gh secret set PERIGEE_UPLOAD_KEYSTORE_B64
printf '%s' "$PERIGEE_UPLOAD_PASSWORD" | gh secret set PERIGEE_UPLOAD_PASSWORD

keytool -list -v -keystore "$keystore" -alias perigee -storepass:env PERIGEE_UPLOAD_PASSWORD \
    | sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr '[:upper:]' '[:lower:]'
