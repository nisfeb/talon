#!/usr/bin/env bash
#
# Grab a Play Store / App Store screenshot from the connected phone.
#
# Usage:
#   scripts/screenshots/shot.sh                  # auto-numbered into ~/talon-screenshots/
#   scripts/screenshots/shot.sh home             # named: ~/talon-screenshots/home.png
#   OUT=~/wip scripts/screenshots/shot.sh chat   # custom dir
#
# Output paths are printed so you can copy/paste into uploads.

set -euo pipefail

OUT="${OUT:-$HOME/talon-screenshots}"
mkdir -p "$OUT"

if [ -n "${1:-}" ]; then
    name="$1.png"
else
    # Next available NN-shot.png index (zero-padded so they sort).
    n=$(ls "$OUT"/[0-9][0-9]-shot.png 2>/dev/null | wc -l)
    name=$(printf "%02d-shot.png" "$((n + 1))")
fi

dest="$OUT/$name"
adb exec-out screencap -p > "$dest"

# screencap on disconnected/unauthorized devices returns 0 with empty
# stdout, leaving a 0-byte file. Catch that explicitly.
if [ ! -s "$dest" ]; then
    rm -f "$dest"
    echo "shot failed: empty file (phone connected and authorized?)" >&2
    exit 1
fi

echo "$dest ($(stat -c %s "$dest" 2>/dev/null || stat -f %z "$dest") bytes)"
