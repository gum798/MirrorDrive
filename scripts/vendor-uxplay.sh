#!/usr/bin/env bash
set -euo pipefail
UXPLAY_TAG="v1.73.6"
DEST="app/src/main/cpp/uxplay"
rm -rf "$DEST"
git clone --depth 1 --branch "$UXPLAY_TAG" https://github.com/FDH2/UxPlay "$DEST-tmp"
mkdir -p "$DEST"
cp -R "$DEST-tmp/lib" "$DEST/lib"
# Exclude GStreamer renderers — not used on Android
rm -rf "$DEST/lib/../renderers" 2>/dev/null || true
cp "$DEST-tmp/LICENSE" "$DEST/LICENSE"
rm -rf "$DEST-tmp"
echo "Vendored UxPlay $UXPLAY_TAG into $DEST/lib"
