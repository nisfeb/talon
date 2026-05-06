#!/usr/bin/env bash
# Tiny mutation tester.
#
# For each file in $TARGETS, and each mutation operator, flip every
# occurrence one at a time and run the test suite. If the suite still
# passes, the mutant is a **survivor** — a test gap worth closing.
# If the suite fails, the mutant was **killed** — good.
#
# Not a substitute for Pitest on large projects. But on our narrow
# surface (the urbit/ pure helpers), this is sufficient and transparent.
#
# Usage:
#     scripts/mutate/mutate.sh [file-pattern ...]
#
# If no patterns are given, runs on the default target list below.
# Patterns are `find -path` globs relative to repo root.
#
# Output: mutation-report.md (overwritten each run). Also prints a
# summary to stdout.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

: "${JAVA_HOME:=/home/sneagan/jdk-install/jdk-17.0.12+7}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

REPORT="mutation-report.md"
LOG_DIR="build/mutation-logs"
mkdir -p "$LOG_DIR"

# ── default target files ─────────────────────────────────────────
DEFAULT_TARGETS=(
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/UrbitIds.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/ChannelType.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/ChatStory.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/MarkdownBlocks.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/Markdown.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/ActivityParser.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/ChannelEventRouter.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/GroupEventRouter.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/GroupAdminParser.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/CiteParser.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/PostIngest.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/DottedIdDedupe.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/WireShapes.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ai/DailyDigestSchedule.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ai/DailyDigestMentionMatcher.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ai/DailyDigestPrompt.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ai/DailyDigestSelector.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ai/WatchwordSanitizer.kt"
    # Pure helpers added during the rc1-rc20 stretch. Each has range
    # checks / threshold comparisons / loop conditions worth mutating.
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailItem.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/Contacts.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/EmojiSpan.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/ChatScrollHeuristic.kt"
    "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/ImageViewerSwipe.kt"
)

TARGETS=("${@:-${DEFAULT_TARGETS[@]}}")

# ── mutation operators ───────────────────────────────────────────
# Each is a Perl regex + replacement pair applied one-hit-at-a-time.
# The name field goes into the report. Order doesn't matter.
#
# Keep patterns narrow — e.g. require whitespace around operators so
# we don't rewrite string contents or identifiers.
MUTATIONS=(
    'eq-to-neq|== |!= '
    'neq-to-eq|!= |== '
    'and-to-or|&&|\|\|'
    'or-to-and|\|\||&&'
    'lt-to-gte| < | >= '
    'gt-to-lte| > | <= '
    'lte-to-gt| <= | > '
    'gte-to-lt| >= | < '
    'true-to-false|\btrue\b|false'
    'false-to-true|\bfalse\b|true'
    # Strip a unary not — catches any `!cond` where we never test the
    # happy path.
    'drop-not|!([a-zA-Z_])|$1'
    # Off-by-one mutations.
    'plus1-to-plus0|\+ 1|+ 0'
    'minus1-to-minus0|- 1|- 0'
    # Agent mark flips — catches "mark wasn't bumped" regressions.
    'mark-group-4-to-3|"group-action-4"|"group-action-3"'
    'mark-chan-2-to-1|"channel-action-2"|"channel-action-1"'
    'mark-dm-2-to-1|"chat-dm-action-2"|"chat-dm-action-1"'
    'mark-club-2-to-1|"chat-club-action-2"|"chat-club-action-1"'
    # Essay kind flips — catches /diary channel posts getting /chat
    # kind, etc.
    'kind-diary-to-chat|"/diary"|"/chat"'
    'kind-heap-to-chat|"/heap"|"/chat"'
    # Id normalization — removing the dot-strip should always fail a
    # test (that's the whole point of the IdNormalizationInvariant
    # suite we added).
    'skip-dot-strip|\.replace\(".", ""\)||'
    # Nest prefix flips — catches channel-type routing bugs.
    'prefix-chat-to-diary|"chat/"|"diary/"'
    'prefix-diary-to-heap|"diary/"|"heap/"'
    'prefix-heap-to-chat|"heap/"|"chat/"'
)

# ── test runner ──────────────────────────────────────────────────
run_tests() {
    ./gradlew :composeApp:desktopTest --quiet \
        > "$LOG_DIR/last.log" 2>&1
}

# ── main loop ────────────────────────────────────────────────────
survived=0
killed=0
skipped=0
declare -a SURVIVORS=()

echo "# Mutation report" > "$REPORT"
echo >> "$REPORT"
echo "_Run: $(date -Iseconds)_" >> "$REPORT"
echo >> "$REPORT"
echo "| Status | File | Line | Operator | Before → After |" >> "$REPORT"
echo "|---|---|---|---|---|" >> "$REPORT"

for file in "${TARGETS[@]}"; do
    if [ ! -f "$file" ]; then
        echo "skip: $file (not found)"
        continue
    fi
    echo "mutating $file"
    for spec in "${MUTATIONS[@]}"; do
        name="${spec%%|*}"
        rest="${spec#*|}"
        pattern="${rest%%|*}"
        replacement="${rest#*|}"

        # Find every (1-indexed) line that matches this pattern.
        # Use perl so the regex flavors stay consistent with the
        # substitution below.
        mapfile -t lines < <(
            perl -ne 'print "$.\n" if /'"$pattern"'/' "$file" 2>/dev/null || true
        )

        for line in "${lines[@]}"; do
            [ -z "$line" ] && continue

            # Skip lines inside `/* … */` or starting with `//`. Cheap
            # heuristic — mutations in comments break compilation (ok)
            # or no-op (noise in the report).
            orig_line="$(sed -n "${line}p" "$file")"
            trimmed="$(echo "$orig_line" | sed -e 's/^[[:space:]]*//')"
            case "$trimmed" in
                //*|\*/*|\**) skipped=$((skipped+1)); continue ;;
            esac

            # Back up the file before mutating so revert works
            # regardless of working-tree state. We cannot rely on
            # `git checkout --` because there are usually uncommitted
            # changes in this project.
            cp "$file" "$LOG_DIR/backup.kt"

            # Apply the mutation on just this line.
            perl -i -ne '
                BEGIN { $ln = '"$line"'; }
                if ($. == $ln && !$done) { s/'"$pattern"'/'"$replacement"'/ and $done = 1; }
                print;
            ' "$file"

            new_line="$(sed -n "${line}p" "$file")"
            if [ "$orig_line" = "$new_line" ]; then
                # Didn't actually change — treat as skip (shouldn't
                # happen if the perl ne match above was accurate).
                skipped=$((skipped+1))
                continue
            fi

            # Compile + test. Compilation failures count as killed —
            # a syntactically invalid mutant still reveals the mutation
            # is observed by the type system.
            if run_tests; then
                survived=$((survived+1))
                SURVIVORS+=("$file:$line [$name]")
                printf '| ❌ SURVIVED | `%s` | %s | %s | `%s` → `%s` |\n' \
                    "$file" "$line" "$name" \
                    "$(echo "$orig_line" | sed 's/|/\\|/g; s/^[[:space:]]*//')" \
                    "$(echo "$new_line"  | sed 's/|/\\|/g; s/^[[:space:]]*//')" \
                    >> "$REPORT"
                printf '  SURVIVED %s:%s [%s]\n' "$file" "$line" "$name"
            else
                killed=$((killed+1))
                # Don't log killed in the table — too noisy.
            fi

            # Revert this file so the next mutation starts clean.
            cp "$LOG_DIR/backup.kt" "$file"
        done
    done
done

echo >> "$REPORT"
echo "## Summary" >> "$REPORT"
echo >> "$REPORT"
echo "- Killed:   $killed" >> "$REPORT"
echo "- Survived: $survived" >> "$REPORT"
echo "- Skipped:  $skipped" >> "$REPORT"
if [ "$survived" -gt 0 ]; then
    score="$(awk -v k="$killed" -v s="$survived" 'BEGIN{printf "%.1f", 100*k/(k+s)}')"
    echo "- Mutation score: $score%" >> "$REPORT"
fi

echo
echo "== summary =="
echo "killed:   $killed"
echo "survived: $survived"
echo "skipped:  $skipped"
if [ "$survived" -gt 0 ]; then
    echo
    echo "survivors (test gaps):"
    for s in "${SURVIVORS[@]}"; do
        echo "  $s"
    done
fi
echo
echo "full report at $REPORT"
