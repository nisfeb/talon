#!/usr/bin/env bash
# Smoke-check the MCP endpoint on a running, MCP-installed fakezod.
#
# Usage:
#   CODE=<+code> ./ping-mcp.sh
set -euo pipefail
source "$(dirname "$0")/lib-mcp.sh"
mcp_login
mcp_ping
