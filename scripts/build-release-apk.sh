#!/usr/bin/env bash
# Called by semantic-release (prepare step): sets the app version and builds the signed release APK
# into dist/tele-v<version>.apk, which is attached to the GitHub release.
set -euo pipefail

version="$1"

sed -i "s/^appVersion=.*/appVersion=${version}/" gradle.properties
./gradlew --no-daemon assembleRelease

apk="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$apk" ]; then
    echo "::error::Signed release APK not found (is keystore.properties present?)" >&2
    exit 1
fi
mkdir -p dist
cp "$apk" "dist/tele-v${version}.apk"
