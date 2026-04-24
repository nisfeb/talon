#!/usr/bin/env bash
# Boot a local fakezod with a predictable pier directory.
#
# Usage:
#   ./boot.sh              # first boot: creates ./pier/zod, prints +code
#   ./boot.sh --resume     # re-attach to an existing pier
#
# On first boot this tarballs through to +code. Second boot on, you can
# just `./boot.sh --resume` to continue.
#
# The fakezod's HTTP server listens on a local port — 8080 by default,
# next free if taken. The final log line prints the URL and +code.

set -euo pipefail

URBIT_VERSION="${URBIT_VERSION:-v3.5}"
URBIT_DIR="${URBIT_DIR:-$(pwd)/.urbit}"
PIER_DIR="${PIER_DIR:-$(pwd)/pier/zod}"
HTTP_PORT="${HTTP_PORT:-8080}"

resume=0
for arg in "$@"; do
    case "$arg" in
        --resume) resume=1 ;;
        -h|--help)
            sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
    esac
done

# ── resolve the urbit binary ──────────────────────────────────────
platform="linux-x86_64"
case "$(uname -s)" in
    Linux*)  platform="linux-x86_64" ;;
    Darwin*) platform="macos-x86_64" ;;
    *) echo "unsupported platform: $(uname -s)" >&2; exit 1 ;;
esac

urbit_bin="$URBIT_DIR/urbit"
if [ ! -x "$urbit_bin" ]; then
    echo "[boot] downloading urbit $URBIT_VERSION for $platform..." >&2
    mkdir -p "$URBIT_DIR"
    url="https://github.com/urbit/vere/releases/download/$URBIT_VERSION/$platform.tgz"
    curl -fL "$url" -o "$URBIT_DIR/urbit.tgz"
    tar -xzf "$URBIT_DIR/urbit.tgz" -C "$URBIT_DIR" --strip-components=1
    rm "$URBIT_DIR/urbit.tgz"
    chmod +x "$urbit_bin"
fi

# ── boot or resume ────────────────────────────────────────────────
mkdir -p "$(dirname "$PIER_DIR")"

if [ "$resume" -eq 0 ] && [ ! -d "$PIER_DIR" ]; then
    echo "[boot] fresh pier at $PIER_DIR (this takes ~30s)" >&2
    cd "$(dirname "$PIER_DIR")"
    "$urbit_bin" -F zod -p "$HTTP_PORT" \
        -c "$(basename "$PIER_DIR")"
else
    echo "[boot] resuming pier at $PIER_DIR" >&2
    "$urbit_bin" -p "$HTTP_PORT" "$PIER_DIR"
fi
