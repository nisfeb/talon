#!/usr/bin/env bash
#
# Test harness for scripts/hooks/pre-commit. Sets up a throwaway git
# repo per case, stages synthetic content, runs the hook against the
# repo's staged changes, and asserts blocked-or-passed.
#
# Run locally:  ./scripts/hooks/test-pre-commit.sh
# Run in CI:    same — exits non-zero on any failed assertion.
#
# Exit codes: 0 = all tests passed, 1 = at least one failed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK="$REPO_ROOT/scripts/hooks/pre-commit"
[ -x "$HOOK" ] || { echo "FATAL: $HOOK not executable" >&2; exit 2; }

PASS=0
FAIL=0
FAIL_NAMES=()

# Spin up a fresh empty git repo with the hook installed via
# core.hooksPath. The actual `git commit` path isn't needed — we run
# the hook script directly from inside the repo, which is how git
# would invoke it.
make_repo() {
    local dir
    dir=$(mktemp -d)
    (
        cd "$dir"
        git init -q
        git config user.email "test@example.com"
        git config user.name "test"
        # Create initial commit so `git diff --cached` has something
        # to compare against (without HEAD, --cached behavior is
        # subtly different on some git versions).
        git commit --allow-empty -q -m "init"
    )
    echo "$dir"
}

run_case() {
    local name="$1"
    local expected="$2"   # "block" or "pass"
    local file="$3"
    local content="$4"
    local repo
    repo=$(make_repo)
    mkdir -p "$repo/$(dirname "$file")"
    printf '%s\n' "$content" > "$repo/$file"
    (cd "$repo" && git add "$file" >/dev/null 2>&1)
    # Run the hook with the repo as cwd so its `git diff --cached`
    # call sees the staged content we just wrote.
    local out
    out=$(cd "$repo" && bash "$HOOK" 2>&1)
    local code=$?
    rm -rf "$repo"

    local want_block_code=1
    local want_pass_code=0
    if [ "$expected" = "block" ]; then
        if [ "$code" -ne 0 ]; then
            PASS=$((PASS + 1))
            printf "  ✓ %s\n" "$name"
        else
            FAIL=$((FAIL + 1))
            FAIL_NAMES+=("$name")
            printf "  ✗ %s — expected block, hook passed\n" "$name"
            printf "%s\n" "$out" | sed 's/^/      /'
        fi
    else
        if [ "$code" -eq 0 ]; then
            PASS=$((PASS + 1))
            printf "  ✓ %s\n" "$name"
        else
            FAIL=$((FAIL + 1))
            FAIL_NAMES+=("$name")
            printf "  ✗ %s — expected pass, hook blocked\n" "$name"
            printf "%s\n" "$out" | sed 's/^/      /'
        fi
    fi
}

echo "Running pre-commit hook tests..."

# ── Negative cases (must block) ────────────────────────────────
run_case "blocks /home/sneagan path"           block "src/foo.kt" \
    'val path = "/home/sneagan/jdk-install/jdk-17.0.12+7"'
run_case "blocks wrong github user"            block "src/foo.kt" \
    'val url = "https://github.com/sneagan/talon"'
run_case "blocks personal hostname"            block "src/foo.kt" \
    'val ship = "urbit.nisfeb.com"'
run_case "blocks personal IP"                  block "src/foo.kt" \
    'val host = "45.33.75.69"'
run_case "blocks personal email"               block "src/foo.kt" \
    'val owner = "busy.pace7084@fastmail.com"'
run_case "blocks personal patp in non-test file" block "src/foo.kt" \
    'val ship = "~mister-botter-dozzod-nisfeb"'
run_case "blocks Anthropic-style API key"      block "src/foo.kt" \
    'val key = "sk-ant-abcdefghijklmnopqrstuvwxyz1234"'
run_case "blocks OpenAI-style API key"         block "src/foo.kt" \
    'val key = "sk-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"'
run_case "blocks GitHub PAT"                   block "src/foo.kt" \
    'val tok = "ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"'
run_case "blocks AWS access key id"            block "src/foo.kt" \
    'val k = "AKIAIOSFODNN7EXAMPLE"'
run_case "blocks PRIVATE KEY block marker"     block "src/foo.kt" \
    '-----BEGIN RSA PRIVATE KEY-----'
run_case "blocks urbauth cookie name"          block "src/foo.kt" \
    'val cookie = "urbauth-~mister-botter-dozzod-nisfeb"'

# ── Positive cases (must pass) ─────────────────────────────────
run_case "passes plain content"                pass  "src/foo.kt" \
    'val greeting = "hello world"'
run_case "passes correct github user URL"      pass  "src/foo.kt" \
    'val url = "https://github.com/nisfeb/talon"'
run_case "passes patp in KMP test path"        pass  "src/desktopTest/Foo.kt" \
    'val ship = "~mister-botter-dozzod-nisfeb"'
run_case "passes patp in fixtures path"        pass  "app/src/test/resources/fixtures/post.json" \
    '"author": "~ricsul-bilwyt-dozzod-nisfeb"'
run_case "passes patp in superpowers docs"     pass  "docs/superpowers/spec.md" \
    'Example: ~mister-botter-dozzod-nisfeb'
run_case "passes hook script self-edit"        pass  "scripts/hooks/pre-commit" \
    "echo 'this should not trip on its own ruleset including /home/sneagan/'"

# ── Summary ────────────────────────────────────────────────────
echo
total=$((PASS + FAIL))
if [ "$FAIL" -eq 0 ]; then
    echo "All $total pre-commit hook tests passed."
    exit 0
else
    echo "$FAIL of $total pre-commit hook tests failed:"
    for name in "${FAIL_NAMES[@]}"; do
        echo "  - $name"
    done
    exit 1
fi
