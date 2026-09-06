#!/usr/bin/env bash
# Publish web/index.html to the site's nginx root — a plain file copy to a
# directory we own. Keeps one backup of what was live. The hero icon is
# referenced relative to the page, so it ships alongside.
#
#   TALON_WEB_HOST=user@host TALON_WEB_PORT=22 TALON_WEB_ROOT=/var/www/site \
#     scripts/deploy-web.sh
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TALON_WEB_HOST:?user@host of the web server}"
: "${TALON_WEB_ROOT:?nginx root directory for the site}"
port=${TALON_WEB_PORT:-22}
ssh -p "$port" "$TALON_WEB_HOST" "cp $TALON_WEB_ROOT/index.html $TALON_WEB_ROOT/index.html.bak 2>/dev/null || true"
scp -q -P "$port" web/index.html branding/play_store_icon_512.png "$TALON_WEB_HOST:$TALON_WEB_ROOT/"
echo "deployed web/index.html → $TALON_WEB_HOST:$TALON_WEB_ROOT"
