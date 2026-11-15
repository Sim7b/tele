#!/usr/bin/env bash
# Builds dist/tele_api-<version>.zip, to import in Dispatcharr (Plugins → Import).
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(python3 -c 'import json; print(json.load(open("tele_api/plugin.json"))["version"])')
mkdir -p dist
rm -f "dist/tele_api-$version.zip"
zip -qr "dist/tele_api-$version.zip" tele_api -x '*/__pycache__/*' '*.pyc'
echo "dist/tele_api-$version.zip"
