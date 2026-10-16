#!/usr/bin/env bash
# Builds the Play bundle signed with the upload key, on a Mac where
# scripts/make-upload-key.sh was run: the key from ~/.local/share/perigee/upload.jks,
# its password from the Keychain. PERIGEE_UPLOAD_KEYSTORE and PERIGEE_UPLOAD_PASSWORD
# point elsewhere. The bundle: app/build/outputs/bundle/playRelease/app-play-release.aab
set -euo pipefail

repo_root="$(git -C "$(dirname "${BASH_SOURCE[0]}")/.." rev-parse --show-toplevel)"
export PERIGEE_UPLOAD_KEYSTORE="${PERIGEE_UPLOAD_KEYSTORE:-$HOME/.local/share/perigee/upload.jks}"

if [[ ! -f "$PERIGEE_UPLOAD_KEYSTORE" ]]; then
    printf 'Upload keystore not found: %s\n' "$PERIGEE_UPLOAD_KEYSTORE" >&2
    exit 1
fi

if [[ -z "${PERIGEE_UPLOAD_PASSWORD:-}" ]]; then
    PERIGEE_UPLOAD_PASSWORD="$(security find-generic-password -s org.evsyukov.perigee.upload -a perigee -w)"
    export PERIGEE_UPLOAD_PASSWORD
fi

exec "$repo_root/gradlew" --no-daemon -p "$repo_root" :app:bundlePlayRelease
