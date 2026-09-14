#!/usr/bin/env bash
# Ship an iOS build to TestFlight, from master only.
#
# Usage: scripts/ios-release.sh [--smoke]
#
# Two rules this enforces rather than asks you to remember:
#
#   1. Builds come from master. nomac packs the WORKING TREE, not a
#      commit, so running it on a feature branch silently ships that
#      branch with no sign of it in the build's own record — the id it
#      reports is its snapshot, not a git commit you can look up later.
#
#   2. The iOS marketing version matches the app's. It lives in the
#      Xcode project and nothing keeps it in step, so it sat at 1.0
#      while the app reached 1.7.8. App Store Connect refused the
#      upload: a bundle has to out-version the last approved one, and
#      by then the 1.0 train was closed to new builds entirely.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

SMOKE=""
[ "${1:-}" = "--smoke" ] && SMOKE="--smoke"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "master" ]; then
    echo "refusing: iOS builds ship from master, and this is $BRANCH" >&2
    exit 1
fi

# .nomac.json is the builder's own pointer file, rewritten by every
# push; everything else dirty means the build would carry work that is
# not in the branch it claims to come from.
DIRTY="$(git status --porcelain | grep -v '\.nomac\.json' || true)"
if [ -n "$DIRTY" ]; then
    echo "refusing: the working tree has changes that are not committed:" >&2
    echo "$DIRTY" >&2
    exit 1
fi

WANT="$(grep -E '^talon\.versionName=' gradle.properties | cut -d= -f2)"
HAVE="$(grep -m1 -E 'MARKETING_VERSION = ' iosApp/iosApp.xcodeproj/project.pbxproj \
    | sed 's/.*MARKETING_VERSION = //; s/;//' | tr -d ' ')"
if [ "$WANT" != "$HAVE" ]; then
    echo "refusing: the app is $WANT and the iOS project says $HAVE." >&2
    echo "Set MARKETING_VERSION in iosApp/iosApp.xcodeproj/project.pbxproj" >&2
    echo "to $WANT and commit it. App Store Connect rejects a bundle that" >&2
    echo "does not out-version the last approved one." >&2
    exit 1
fi

# The builder's CLI comes from nvm, which a non-login shell does not
# put on PATH. Take the newest installed node rather than pinning a
# version that a machine will eventually move past.
if ! command -v npx >/dev/null 2>&1; then
    NVM_BIN="$(ls -d "$HOME"/.nvm/versions/node/*/bin 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "$NVM_BIN" ]; then
        PATH="$NVM_BIN:$PATH"
        export PATH
    fi
fi
if ! command -v npx >/dev/null 2>&1; then
    echo "refusing: npx is not on PATH and no nvm node was found." >&2
    exit 1
fi

echo "==> compiling the iOS targets first (a free check before a paid slot)"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}" \
    ./gradlew :composeApp:compileKotlinIosArm64 --quiet

echo "==> pushing $BRANCH @ $(git rev-parse --short HEAD), version $WANT"
npx -y @nomac/cli push

echo "==> starting the build"
npx -y @nomac/cli build $SMOKE
echo
echo "Follow it with: npx -y @nomac/cli status <build id>"
echo "Bare 'status' answers the last READY build, which is not this one."
