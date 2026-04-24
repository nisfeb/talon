#!/usr/bin/env bash
# Install the urbit-mcp-server onto a running fakezod.
#
# Run this ONCE after boot.sh, before install-tlon.sh. After it lands,
# the ship exposes /mcp over HTTP and every subsequent desk operation
# can go through the MCP tools (see lib-mcp.sh).
#
# The initial %mcp desk creation has to happen in dojo — we can't
# manipulate the ship before its own action-surface is installed. The
# script pauses and prints the two dojo commands you need to run.
#
# Usage:
#   ./install-mcp.sh
#
# Env overrides:
#   MCP_SRC  — path to the urbit-mcp-server checkout
#              (default: ~/software/groundwire/urbit-mcp-server)
#   PIER_DIR — default: ./pier/zod (relative to cwd)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MCP_SRC="${MCP_SRC:-$HOME/software/groundwire/urbit-mcp-server}"
PIER_DIR="${PIER_DIR:-$SCRIPT_DIR/pier/zod}"
PIER_ABS="$(cd "$(dirname "$PIER_DIR")" && pwd)/$(basename "$PIER_DIR")"

[ -d "$MCP_SRC" ] || {
    echo "error: urbit-mcp-server not found at $MCP_SRC" >&2
    echo "  set MCP_SRC=/path/to/urbit-mcp-server or clone it." >&2
    exit 1
}
[ -x "$MCP_SRC/build.sh" ] || {
    echo "error: $MCP_SRC/build.sh missing or not executable" >&2
    exit 1
}
[ -d "$PIER_DIR" ] || {
    echo "error: pier not found at $PIER_DIR (boot the fakezod first)" >&2
    exit 1
}
command -v peru >/dev/null || {
    echo "error: peru is required by the MCP build — install from" >&2
    echo "  https://github.com/buildinspace/peru" >&2
    exit 1
}

# ── Step 1 — create + mount the desk in dojo ────────────────────
cat <<EOF

[mcp] Step 1 / 3 — create and mount the MCP desk.

In the fakezod's dojo window, paste these commands:

    |new-desk %mcp
    |mount %mcp

Wait for dojo to echo "mounted desk %mcp", then come back here.
EOF
read -r -p "press enter when done... "

if [ ! -d "$PIER_ABS/mcp" ]; then
    echo "error: $PIER_ABS/mcp still doesn't exist — did the mount succeed?" >&2
    exit 1
fi

# ── Step 2 — build + copy sources ──────────────────────────────
echo
echo "[mcp] Step 2 / 3 — building + copying sources into the pier..."
(cd "$MCP_SRC" && ./build.sh -p "$PIER_ABS/mcp")

# ── Step 3 — commit + install in dojo ───────────────────────────
cat <<EOF

[mcp] Step 3 / 3 — commit + install.

Back in dojo:

    |commit %mcp
    |install our %mcp

Wait for "activated app mcp" (or similar).
EOF
read -r -p "press enter when done... "

echo
echo "[mcp] install complete. Verify with:"
echo "  CODE=<+code> ./ping-mcp.sh"
echo "  (or run install-tlon.sh next to pull Tlon's desks)"
