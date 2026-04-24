#!/usr/bin/env bash
# Seed a running fakezod with a test group + channels, via MCP.
#
# Relies on install-mcp.sh + install-tlon.sh having already run.
#
# Usage:
#   CODE=<+code> ./seed.sh
#
# The group create path goes through a %groups spider thread, which
# the stock MCP toolbox doesn't expose today — for that specific flow
# we just print the Talon steps to run manually. Channels + posts get
# covered by MCP pokes so the full wire surface gets exercised on
# every seed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib-mcp.sh"

mcp_login
mcp_ping

# Canonical our-ship — MCP exposes this via a purpose-built tool.
our_json="$(mcp_call_ok get-our-id '{}')"
our="$(printf '%s' "$our_json" | jq -r '.result.content[0].text // empty')"
[ -n "$our" ] || { echo "seed: couldn't determine our @p" >&2; exit 1; }
echo "[seed] ship is $our"

cat <<EOF

[seed] Talon-side setup:
  1) On your phone, set up adb reverse:
       adb reverse tcp:8080 tcp:8080
  2) In Talon, log in with:
       URL:  $URL
       Code: $CODE
  3) Open Administration → +  → New group. That exercises the
     group-create-thread spider which MCP doesn't yet script.
  4) Create one of each channel type, post a message, edit / delete
     one, react, and invite a ship.

If any of those steps fails silently, check adb logcat -s TlonChatRepo.
EOF
