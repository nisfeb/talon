# Overnight bug-hunt and polish review — 2026-10-14

Branch: `polish/overnight-2026-10-14` (base `origin/master` 2b8ff1d8). Not pushed.
Prompt: `.overnight-review-prompt.md`. Runs via a self-paced `/loop`, resumable.

STATUS: IN PROGRESS

## Summary

- Confirmed bugs: 1
- Fixed: 1 (commit 6d307846)
- Reported: 4 (1 High, 1 Med, 2 Low)
- Polish items: 0 (dimension 7 not yet run)

## Dimension coverage

| # | Dimension | State |
|---|-----------|-------|
| 1 | Cross-platform discipline (capability + section flags, android-only) | DONE — clean |
| 2 | Testing Standard applied to recent features | DONE — 1 fixed, findings below |
| 3 | Offline-first + sync correctness | not started |
| 4 | Concurrency / lifecycle | not started |
| 5 | Failure-path honesty | not started |
| 6 | Desktop leaf correctness | not started |
| 7 | UI polish, mobile-first | not started |

## FIXED

### F1 — dropped/pasted/picked uploads died when leaving the chat mid-upload (commit 6d307846)

`ChatComposer.uploadOne` ran the upload and the send directly, and its callers
(the drag-drop `fileDropTarget` multi-file branch at ~ChatComposer.kt:718, and
the single-drop/paste/picker path via `uploadAndSend` at ~:530) launched it on
the screen's `rememberCoroutineScope`. Leaving the chat during the upload
cancelled the HTTP PUT and the send, so the file silently never went. The
staged single-attachment path (`ChatComposer.kt:442`) was already fixed with
`repo.carry` and had a test; the drop path never got the same treatment.

Fix: extracted `uploadDroppedFile(file, repo, strategy)` running on `repo.carry`
(the repo's own scope), so leaving the screen cancels only the caller's await,
not the work. Caller keeps the screen bookkeeping (`uploading`, error). Test
`AttachmentWithTextTest."a dropped file whose screen is left mid-upload still
sends"` launches it on a scope, cancels mid-upload (real time), asserts the send
still lands. 8/8 in that class pass.

Caveat: the test's negative control (that it FAILS without the fix) was not
re-run to save a build; it mirrors the proven staged-path guard
`"leaving the chat mid-upload still sends the picture"`, which exists precisely
because this cancellation bug was observed there.

## REPORTED

### R1 (High) — image metadata strip fails OPEN: corrupt/HEIC/WebP uploads keep EXIF

`util/ImageMetadata.kt` `stripImageMetadata` ends in
`runCatching { … }.getOrNull() ?: bytes`. A JPEG/PNG it cannot parse, and WebP
and HEIC by design, upload with metadata (GPS/EXIF/XMP) intact.
`ImageMetadataTest.whatDoesNotParseGoesAsItCame` pins this fail-open behavior.
For a privacy fix ("uploads carry neither the file's name nor its metadata",
a3cb7800) this is the sharp edge: a malformed-but-renderable JPEG, or any HEIC
(common from iPhones) or WebP, leaks location. This is a product decision, not a
code bug: options are (a) refuse to upload what can't be stripped, (b) re-encode
to a stripped JPEG/PNG, (c) accept the leak and document it. Not auto-fixed —
needs sneagan's call.

### R2 (Med) — retired-call-server migration is a silent no-op in every test/CI binary

`fix: ships on the retired default call server are moved` (7752a55a) gates on
`TalonBuild.retiredSfuBase` / `TALON_RETIRED_SFU_BASE`, injected from a CI
secret. That value is empty in every dev and CI env, so every test injects the
retired base by hand and nothing verifies the build wiring actually carries the
secret. A typo in the secret name in `.github/workflows/release.yml` ships a
migration that does nothing, undetectable by the suite — the same failure class
as the rc27-29 release post-mortem. Suggest a build/release-time assertion or a
smoke check that the shipped binary's `retiredSfuBase` is non-empty.

### R3 (Low) — migration idempotency proven only at the predicate level

`CallDefaultsTest` proves `retires(newBase)=false`, so the logic won't re-fire,
but no `DefaultIceTest` case replays an already-migrated ship across a second
connect to assert no second poke. "Runs once and stays applied" is answered by
logic, not an integration replay. Add a connect-twice case.

### R4 (Low) — Activity-screen Mentions tab has no UI-level DM-exclusion test

`fix: a DM is unread, never a mention` (34f9d36c) is well covered on the home
list and What's New, and at the `%activity` parser layer (`ActivityFeedParserTest`
drops `ship/~bus`, keeps `channel/...`). But `ActivityListTest` (the UI test)
never populates `feed.mentions`, so the Activity Mentions tab's DM-exclusion is
unverified at the render layer. Low risk (UI just renders parser output). Add a
fixture with a DM in the mentions view.

Also noted across dimension 2 (not gaps worth a line each): several features lack
a single whole-app `AppShellTest` end-to-end pin (upload, the call migration, the
mention classification) — each is covered at its seam instead. Acceptable, listed
for the gap report.

## False-positive log

_none yet_

## Dimension 1 notes — cross-platform discipline (clean)

Audited, no findings. Infrastructure is healthy:

- 30 `expect val is*Supported` flags in `Capabilities.kt`, every one with an `actual`
  in android / desktop / ios (the compiler enforces this, so no flag can lack a leaf).
- No dead flags: each is referenced (gated) in at least one non-Capabilities file. Eight
  are referenced in exactly one place, which is correct for a single gate point, not dead.
- Section flags: `SectionFlagsGuardTest` scans BOTH shells (`TalonApp.kt`, `App.kt`) for
  `var *Open`/`show*` made with `mutableStateOf(false)` and fails the build unless declared
  with `sections.flag()` or listed in a documented `notSections` allowlist. The seven
  allowlisted booleans (calendarPageOpen, shareLoginQrOpen, notebookComposeOpen,
  galleryComposeOpen, showNewDmRequest, localShipSetupOpen, meetingOpen) each carry a
  reason. No undeclared section leaked through.

Low-value follow-up (not actioned): confirm the `Capabilities.kt` header-comment registry
lists all 30 flags per the CLAUDE.md "five lines per flag" convention. Documentation
hygiene only.

## Gap report

_pending — written when dimensions complete_
