# Testing strategy

Talon's tests are opinionated about what they're worth: we catch
regressions in the **parsers and wire formats we depend on**, because
that's where every outage has come from. We don't try to test UI
behavior, the Urbit network itself, or live network code; those live
in the manual fakezod runbook (`scripts/fakezod/`).

## What's tested

- **Pure JSON shapes on the wire** — every outbound poke body (DMs,
  clubs, channels, activity, group admin) has a snapshot test. When
  Tlon bumps a mark or renames a field, one test fails with a concrete
  diff.
- **Pure JSON parsers on the wire** — every inbound SSE / scry
  response we read has a parser test with real captured JSON. Includes
  admin group shape, activity summaries, cite blocks, channel deltas.
- **Composer semantics** — Markdown inline + block parsers, blockquote
  grouping, dot-atom formatting, tz/cal/poll/loc widget decoders.
- **ID normalization** — dotted vs undotted post ids across DB ↔ wire.
- **Daily digest selection + prompt** — pure scoring + prompt-shape
  helpers.
- **Watchword sanitization** — input cleaning + dedup that runs the
  same on every platform.

## What's NOT tested at the JVM level

- **Compose UI.** Would need Robolectric + compose-test. Skipped.
- **Room DAO queries on Android.** Thin SQL; low bug rate; needs
  Robolectric. The desktop side does exercise Room via
  `sqlite-bundled` since that's pure JVM.
- **OkHttp / SSE loops.** Network code — the fakezod runbook is the
  integration test.
- **Urbit agent behavior.** Belongs in fakezod tests, not the JVM.

## Running

```sh
# Default — runs every test the JVM can run (commonTest + desktopTest).
./gradlew :composeApp:desktopTest
```

Reports at
`composeApp/build/reports/tests/desktopTest/index.html`. The full
suite runs in a few seconds.

558 `@Test` methods across 46 files at last count. Pure-logic tests
that don't depend on JVM specifics live in `commonTest` so any future
non-JVM target picks them up automatically; everything else lives in
`desktopTest`.

## Directory layout

```
composeApp/src/
├── commonTest/kotlin/io/nisfeb/talon/...      # platform-agnostic logic
├── desktopTest/
│   ├── kotlin/io/nisfeb/talon/urbit/          # parser / shape tests
│   ├── kotlin/io/nisfeb/talon/ai/             # AI helpers, watchword sanitize, etc.
│   ├── kotlin/io/nisfeb/talon/ui/             # widget decoder tests
│   └── resources/fixtures/                    # captured JSON payloads
│       ├── activity/                          # %activity updates
│       ├── channels/                          # %channels posts + replies
│       └── ...
```

Tests load fixtures via `Fixtures.load("activity/update-foo.json")`.

### Platform-specific test gap

Most coverage runs on the JVM. Bugs that only manifest under the Room
*Android* driver, MediaPipe text task, or Android activity-lifecycle
won't be caught by CI. When shipping anything Android-specific, smoke
through the fakezod runbook before tagging.

## Adding a fixture

When you notice the upstream agent has changed shape (you'll usually
find out when a test fails or a bug is reported):

1. Capture the real payload from `adb logcat -s TlonChatRepo` (Android)
   or from the AppImage's stderr (`./Talon-x86_64.AppImage 2>&1 | grep TlonChatRepo`).
2. Save to `composeApp/src/desktopTest/resources/fixtures/<agent>/<scenario>.json`.
3. Write a test that asserts the fields your parser depends on.
4. Commit both the fixture and the test.

Fixtures are long-lived. Don't "clean them up" — stale fixtures are
how we remember what the wire used to look like when a 6-month-old
user complains about a bug on an older ship.

## Triaging a failed test

When CI fails after you pull latest:

1. **Fixture test?** The upstream wire changed. Compare the fixture to
   the current real payload. Either update the fixture + adapt the
   parser, or document why we reject the new shape.
2. **Shape-builder test?** You changed our outbound JSON. Verify the
   new shape matches what tlon-apps emits on GitHub
   (`tloncorp/tlon-apps/packages/api/src/client/*`). If not, revert.
3. **Dot-atom / id test?** An id normalization invariant broke. Trace
   the change back to `UrbitIds.kt` or `PostIngest.kt`.
4. **Markdown / widget test?** Composer output shifted. Usually
   intentional — update the test to match the new shape.

## Mutation testing

We ship a small mutation tester at `scripts/mutate/mutate.sh`. It walks
each target source file, flips operators one at a time (==/!=, &&/||,
comparison ops, boolean literals, agent-mark string literals, id
normalization calls), runs the suite, and reports mutants that the
tests don't kill.

```sh
./scripts/mutate/mutate.sh                                          # default urbit/ + ai/ targets
./scripts/mutate/mutate.sh composeApp/src/commonMain/.../Foo.kt     # specific file
```

Output: `mutation-report.md` (overwritten each run) plus a stdout
summary with the list of surviving (test-gap) mutants.

Latest scores: **97%** on the urbit package, **96%** on the
daily-digest selector / prompt helpers. Surviving mutants are mostly
defensive double-checks inside the inline Markdown tokenizer (e.g.,
`i + 1 < len` where the outer `while (i < len)` already guarantees
that invariant) — **equivalent mutants**, not real gaps. If you add
a new check that changes behavior, the mutator will catch it; the
current survivors are inert syntactic variants.

Run time is ~5 minutes on the full default target set.

**When the mutator flags a new survivor**, decide whether it's an
equivalent mutant (no observable behavior change) or a real gap
(kill it by adding or strengthening a test). Document any equivalents
you accept in the same source file with a short `// equivalent under
<condition>` comment so future mutator runs can be triaged fast.

## Adding tests for a new feature

Every new outbound poke needs a WireShapes test, ideally with the
builder function defined next to the existing ones so the repo
delegates to it. Every new inbound parser needs a test with a captured
fixture. Every new pure helper (time / id / text transform) gets a
round-trip test in `commonTest`.

If you find yourself writing a test that needs a mocked DAO, a fake
OkHttp, or compose-test — stop. Either extract the pure part and test
that, or defer to the fakezod runbook for integration coverage.

## Checking for wire drift (manual)

Before a release:

1. Boot fakezod + install latest tlon-apps
   (`scripts/fakezod/install-tlon.sh`).
2. Walk through the pre-release checklist in `RELEASE.md`.
3. Watch `adb logcat -s TlonChatRepo` (Android) or the AppImage stderr
   (desktop) for any `poke nack` or `applyChannelDelta … keys=[Unknown]`
   warnings.
4. If you see something unexpected, capture the payload, add a fixture,
   write a test, fix the code.
