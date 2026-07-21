#!/usr/bin/env bash
set -euo pipefail
# Repo root (this script lives in scripts/). All paths below are relative to it.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
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
# Re-apply the tracked MirrorDrive edit (raop_get_public_key accessor) that lives
# outside the gitignored uxplay/ tree, so re-vendoring never silently drops it.
patch -p1 -d "$DEST" < "$ROOT/patches/uxplay-raop-get-public-key.patch"
echo "Applied patch: raop_get_public_key accessor (raop.c/raop.h)"
