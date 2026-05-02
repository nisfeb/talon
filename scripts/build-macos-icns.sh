#!/usr/bin/env bash
#
# Generate composeApp/src/desktopMain/resources/icon.icns from icon.png.
# Runs only on macOS — uses sips (built-in) to produce the iconset
# bitmaps and iconutil (built-in) to pack them into a single .icns.
#
# Output: composeApp/src/desktopMain/resources/icon.icns
#
# CI uses this to give the .app / .dmg / dock-after-install a real
# Talon logo instead of jpackage's default icon. The .icns is *not*
# committed because it's a derived artifact and cross-platform
# generation needs png2icns / pyicns; keeping it CI-side avoids
# checking a binary into source control.

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "$0: only runs on macOS (needs sips + iconutil)" >&2
    exit 0
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$ROOT/composeApp/src/desktopMain/resources/icon.png"
OUT="$ROOT/composeApp/src/desktopMain/resources/icon.icns"

if [[ ! -f "$SRC" ]]; then
    echo "$0: missing source icon at $SRC" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ICONSET="$WORK/Talon.iconset"
mkdir -p "$ICONSET"

# Apple's iconset spec — each (size, scale) pair gets a file.
# Sizes 16, 32, 128, 256, 512 with a @1x and @2x for each. The 1024
# slot is the @2x of 512. Source is 512px, so we upscale for 1024;
# softer than a native 1024 source but produces a valid icns.
declare -a SIZES=(
    "16 icon_16x16.png"
    "32 icon_16x16@2x.png"
    "32 icon_32x32.png"
    "64 icon_32x32@2x.png"
    "128 icon_128x128.png"
    "256 icon_128x128@2x.png"
    "256 icon_256x256.png"
    "512 icon_256x256@2x.png"
    "512 icon_512x512.png"
    "1024 icon_512x512@2x.png"
)

for entry in "${SIZES[@]}"; do
    px="${entry%% *}"
    name="${entry##* }"
    sips -z "$px" "$px" "$SRC" --out "$ICONSET/$name" >/dev/null
done

iconutil -c icns "$ICONSET" -o "$OUT"
echo "wrote $OUT"
