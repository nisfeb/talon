#!/usr/bin/env bash
# Install Tlon's desks (%groups, %chat, %channels, %activity, %contacts,
# %storage, %groups-ui) onto a running fakezod, using the MCP server for
# every ship operation. The MCP server must already be installed — see
# install-mcp.sh for that one-time step.
#
# Usage:
#   CODE=<+code> ./install-tlon.sh
#
# Env overrides:
#   TLON_APPS_DIR — path to a tlon-apps checkout (default: ./.tlon-apps,
#                   auto-cloned if absent)
#   PIER_DIR      — default: ./pier/zod
#   URL / SHIP    — passed through to lib-mcp.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib-mcp.sh"

TLON_APPS_DIR="${TLON_APPS_DIR:-$SCRIPT_DIR/.tlon-apps}"
PIER_DIR="${PIER_DIR:-$SCRIPT_DIR/pier/zod}"

DESKS=(groups chat channels activity contacts storage groups-ui)

# ── ensure tlon-apps is checked out ────────────────────────────
if [ ! -d "$TLON_APPS_DIR" ]; then
    echo "[tlon] cloning tloncorp/tlon-apps → $TLON_APPS_DIR" >&2
    git clone --depth=1 https://github.com/tloncorp/tlon-apps "$TLON_APPS_DIR"
else
    echo "[tlon] updating $TLON_APPS_DIR" >&2
    (cd "$TLON_APPS_DIR" && git pull --ff-only)
fi

[ -d "$PIER_DIR" ] || {
    echo "error: pier not found at $PIER_DIR (boot the fakezod first)" >&2
    exit 1
}

mcp_login
mcp_ping

for d in "${DESKS[@]}"; do
    src="$TLON_APPS_DIR/desk/$d"
    mount="$PIER_DIR/$d"
    if [ ! -d "$src" ]; then
        echo "[tlon] warn: $src not in tlon-apps checkout — skipping" >&2
        continue
    fi

    echo "[tlon] === $d ==="

    # new-desk is idempotent from the MCP side — running it on an
    # existing desk just returns the existing handle.
    mcp_call_ok new-desk "{\"desk\":\"$d\"}" > /dev/null || true
    mcp_call_ok mount-desk "{\"desk\":\"$d\"}" > /dev/null

    # Mount point should exist in the pier filesystem now. Hose the
    # sources in with rsync — faster than per-file insert-file calls
    # over HTTP, and Clay picks them up at commit time.
    if [ ! -d "$mount" ]; then
        echo "error: $mount didn't materialize after mount-desk" >&2
        exit 1
    fi
    echo "[tlon] $d: syncing sources..." >&2
    rsync -a --delete "$src/" "$mount/"

    mcp_call_ok commit-desk "{\"desk\":\"$d\"}" > /dev/null
    mcp_call_ok install-app "{\"app\":\"$d\"}" > /dev/null
done

echo
echo "[tlon] all desks installed. Verify on the ship with +vats."
echo "[tlon] point Talon at:"
echo "  URL:  $URL"
echo "  Ship: $SHIP"
echo "  Code: (whatever +code currently returns in dojo)"
