#!/usr/bin/env bash
# Shared helpers for talking to the urbit-mcp-server over HTTP.
#
# Source this in other scripts:
#   source "$(dirname "$0")/lib-mcp.sh"
#
# Env vars (all have sensible defaults):
#   URL   — ship HTTP root (default http://localhost:8080)
#   SHIP  — ship @p with leading ~ (default ~zod)
#   CODE  — +code for the initial cookie exchange
#
# After mcp_login, the cookie is cached in $MCP_COOKIE.

set -euo pipefail

URL="${URL:-http://localhost:8080}"
SHIP="${SHIP:-~zod}"
CODE="${CODE:-}"
MCP_COOKIE=""

# Prompt for +code if not set and not piped in.
_mcp_require_code() {
    if [ -z "$CODE" ]; then
        echo -n "enter +code for $SHIP: " >&2
        read -r CODE
    fi
}

# Log in via /~/login and cache the urbauth cookie.
mcp_login() {
    _mcp_require_code
    local raw
    raw="$(curl -fsS -i -X POST -d "password=${CODE#+}" "$URL/~/login" || true)"
    MCP_COOKIE="$(printf '%s' "$raw" | \
        awk -v ship="$SHIP" 'tolower($1) == "set-cookie:" {
            for (i = 2; i <= NF; i++) {
                if (match($i, /^urbauth-[^=]+=[^;]+/)) {
                    print substr($i, RSTART, RLENGTH)
                    exit
                }
            }
        }')"
    if [ -z "$MCP_COOKIE" ]; then
        echo "mcp_login: no urbauth cookie returned (bad +code?)" >&2
        return 1
    fi
    echo "[mcp] logged in as $SHIP" >&2
}

# Invoke an MCP tool. Args must be a JSON object string.
#
#   mcp_call mount-desk '{"desk":"groups"}'
#
# Returns raw MCP response on stdout. Caller is expected to inspect
# the `result.content[0].text` or similar for human-readable output.
mcp_call() {
    local tool="$1"
    local args="${2:-{}}"
    [ -n "$MCP_COOKIE" ] || { echo "mcp_call: not logged in" >&2; return 1; }
    local payload
    payload="$(jq -nc \
        --arg tool "$tool" \
        --argjson args "$args" \
        '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:$tool,arguments:$args}}')"
    local response
    response="$(curl -fsS -X POST \
        -H "content-type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -H "Cookie: $MCP_COOKIE" \
        -d "$payload" \
        "$URL/mcp")" || {
        echo "mcp_call $tool: HTTP error" >&2
        return 1
    }
    # Streamable HTTP sometimes returns a single SSE "data: …" frame;
    # strip the prefix if present.
    printf '%s' "$response" | sed -n 's/^data: //p' | head -1
    printf '%s' "$response" | jq -e 'has("jsonrpc")' > /dev/null 2>&1 && echo "$response"
}

# Run `mcp_call` and fail loudly if the response carries an MCP error.
mcp_call_ok() {
    local out
    out="$(mcp_call "$@")"
    printf '%s\n' "$out"
    printf '%s' "$out" | jq -e '.error | not' > /dev/null || {
        echo "mcp_call $1 failed: $out" >&2
        return 1
    }
}

# Sanity: list available tools. Useful diagnostic — if this fails, the
# MCP desk isn't installed or isn't serving /mcp.
mcp_ping() {
    [ -n "$MCP_COOKIE" ] || { echo "mcp_ping: not logged in" >&2; return 1; }
    local payload='{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
    curl -fsS -X POST \
        -H "content-type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -H "Cookie: $MCP_COOKIE" \
        -d "$payload" \
        "$URL/mcp" > /dev/null && echo "[mcp] ping ok" >&2
}
