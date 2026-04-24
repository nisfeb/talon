# Testing strategy

Talon's tests are opinionated about what they're worth: we catch
regressions in the **parsers and wire formats we depend on**, because
that's where every outage has come from. We don't try to test UI
behavior, DB correctness, or the Urbit network itself; those live in
the manual fakezod runbook (`scripts/fakezod/`).

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

## What's NOT tested

- **Compose UI.** Would need Robolectric + compose-test. Skipped.
- **Room DAO queries.** Thin SQL; low bug rate; needs Robolectric.
- **OkHttp / SSE loops.** Network code — the fakezod runbook is the
  integration test.
- **Urbit agent behavior.** Belongs in fakezod tests, not the JVM.

## Running

```sh
./gradlew :app:testReleaseUnitTest
```

Reports at `app/build/reports/tests/testReleaseUnitTest/index.html`.
All tests run in ~1 s combined.

## Directory layout

```
app/src/test/
├── kotlin/io/nisfeb/talon/urbit/    # parser / shape tests
├── kotlin/io/nisfeb/talon/ui/       # widget decoder tests
└── resources/fixtures/              # captured JSON payloads
    ├── activity/                    # %activity updates
    ├── channels/                    # %channels posts + replies
    └── ...
```

Tests load fixtures via `Fixtures.load("activity/update-foo.json")`.

## Adding a fixture

When you notice the upstream agent has changed shape (you'll usually
find out when a test fails or a bug is reported):

1. Capture the real payload from `adb logcat -s TlonChatRepo`.
2. Save it to `app/src/test/resources/fixtures/<agent>/<scenario>.json`.
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

## Adding tests for a new feature

Every new outbound poke needs a WireShapes test, ideally with the
builder function defined next to the existing ones so the repo
delegates to it. Every new inbound parser needs a test with a captured
fixture. Every new pure helper (time / id / text transform) gets a
round-trip test.

If you find yourself writing a test that needs a mocked DAO, a fake
OkHttp, or compose-test — stop. Either extract the pure part and test
that, or defer to the fakezod runbook for integration coverage.

## Checking for wire drift (manual)

Before a release:

1. Boot fakezod + install latest tlon-apps
   (`scripts/fakezod/install-tlon.sh`).
2. Walk through the pre-release checklist in `RELEASE.md`.
3. Watch `adb logcat -s TlonChatRepo` for any `poke nack` or
   `applyChannelDelta … keys=[Unknown]` warnings.
4. If you see something unexpected, capture the payload, add a fixture,
   write a test, fix the code.

## Test inventory

```
ActivityParserTest      19  %activity summary + source-key + read-action pokes
AdminWireShapesTest     16  every a-group.{seat,entry,role,meta} branch
ChannelEventRouterTest  14  SSE delta classification + tombstone detection
WireShapesTest          15  channel + group-action-4 envelopes
ChatWireShapesTest       9  chat-dm-action-2 / chat-club-action-2 deltas
MarkdownBlocksTest      16  notebook composer (# ` ``` ` >)
MarkdownTest            13  inline bold/italic/code/link/patp
CiteParserTest          13  chan / group / desk / file / bait cite variants
ChatStoryTest           11  chat composer with blockquote grouping
PostIngestTest          12  seal + reply + reacts + tombstone ingestion
UrbitIdsTest            10  dotAtom + undotAtom
UrbitTimeTest            7  unix ↔ @da, post-id formatting
ChannelTypeTest          7  nest → channel type routing
GroupAdminParserTest     7  /v2/groups/<flag> response parsing
WidgetDecodersTest      15  [tz|…] [cal|…] [poll|…] [loc|…] decoders
```

Total ~180 tests, sub-second runtime.
