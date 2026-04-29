#!/usr/bin/env bash
#
# One-time install of the in-repo git hooks.
#
# Sets `core.hooksPath` to `scripts/hooks` so git invokes the
# versioned hooks under scripts/hooks/ instead of the per-clone
# .git/hooks/ default. Idempotent — safe to re-run.
#
#   ./scripts/install-hooks.sh

set -euo pipefail

cd "$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "ERROR: not inside a git repository" >&2
    exit 1
}

TARGET="scripts/hooks"
[ -d "$TARGET" ] || { echo "ERROR: $TARGET/ not found" >&2; exit 1; }

current=$(git config --get core.hooksPath 2>/dev/null || true)
if [ -n "$current" ] && [ "$current" != "$TARGET" ]; then
    echo "WARNING: core.hooksPath was set to '$current'; overwriting to '$TARGET'." >&2
fi

git config core.hooksPath "$TARGET"

# Make every executable file under scripts/hooks/ runnable. Git won't
# fire a hook that lacks +x, and on Windows the +x bit can drop after
# checkout; this fixes that case without per-file chmod commands.
chmod +x "$TARGET"/* 2>/dev/null || true

# List what's now wired up.
echo "git hooks installed: core.hooksPath = $(git config --get core.hooksPath)"
echo "active hooks:"
ls -1 "$TARGET" | sed 's/^/  /'
