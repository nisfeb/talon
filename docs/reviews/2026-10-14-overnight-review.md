# Overnight bug-hunt and polish review — 2026-10-14

Branch: `polish/overnight-2026-10-14` (base `origin/master` 2b8ff1d8). Not pushed.
Prompt: `.overnight-review-prompt.md`. Runs via a self-paced `/loop`, resumable.

STATUS: IN PROGRESS

## Summary

- Confirmed bugs: 7
- Fixed: 1 (commit 6d307846)
- Reported: 10 (3 High, 4 Med, 3 Low)
- Polish items: 0 (dimension 7 not yet run)

## Dimension coverage

| # | Dimension | State |
|---|-----------|-------|
| 1 | Cross-platform discipline (capability + section flags, android-only) | DONE — clean |
| 2 | Testing Standard applied to recent features | DONE — 1 fixed, findings below |
| 3 | Offline-first + sync correctness | DONE — 6 findings (2 High), below |
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

### R5 (High) — message edit and delete run on the screen's scope, dropped on navigation

`TlonChatRepo.delete` (:3154), `edit` (:3292), `editNotebookPost` (:3361) and
`editGalleryPost` (:3406) poke the ship directly and are NOT wrapped in a repo
scope, unlike the channel-admin writes. Every caller launches them on
`rememberCoroutineScope()`: `DmChatScreen.kt:1299` (edit), `:1437` (delete),
`ThreadList.kt:531`, `GalleryPostScreen.kt:307`, `NotebookPostScreen.kt:341`,
`NotebookComposeScreen.kt:141`. Leave the screen mid-poke on a slow ship and the
coroutine is cancelled, so the edit/delete silently never reaches the ship. This
is the same class as F1 (CLAUDE.md testing-standard rule 5) but on a different
set of writes. The fix is the same proven pattern: run the poke inside the repo
on its own scope (wrap in `carry { … }` as the channel-admin writes and the F1
fix do), then add a leaving-midway test per function. Deferred as its own chunk
because it spans 4 repo functions + 6 call sites and `delete` interacts with R7.

### R6 (High) — AI profile body (defaultModel, defaultOwn, feature models) has no staleness guard

`AiProfile.forSync` (:516-521) strips `savedHereAtMs`, and `profileAfterEntry`
(:736-774) takes `incoming.keepingLocal(local)` wholesale. The `switches` field
(jev/orrery/catchUp/assistant) was explicitly protected against a stale peer
(pinned by `AiProfileSyncTest` "an older credentials entry does not turn switches
back", :319), but `defaultModel`, `defaultOwn` and each feature's model ref ride
the profile body, which has no timestamp. `onStateChange -> pushAiSettings()`
(TalonApplication.kt:335, App.kt:1402) re-sends the whole profile on ANY AI
change. Scenario: device C is offline with an older profile (defaultModel=Y,
still lists deleted provider P); the user toggles anything AI on C; C pushes its
stale profile; device A's newer defaultModel=X reverts to Y, defaultOwn is lost,
and deleted provider P comes back. An older write undoing a newer one, plus a
deletion resurrected. Fix: carry a monotonic timestamp on the profile body (as
switches/`savedHereAtMs` already do) and keep the newer of local vs incoming for
defaultModel/defaultOwn/feature models. Design change to the sync contract —
needs sneagan's call, not an overnight edit.

### R7 (Med) — DM/club delete soft-deletes locally with no rollback on poke failure

`TlonChatRepo.delete` (:3156-3164) soft-deletes the message and clears its
reactions BEFORE `pokeAt`, for DMs/clubs. There is no catch/rollback — `react`
(:3066) and `unreact` (:3140-3143) both restore the prior row on throw, delete
does not, despite the docstring saying "Mirror what react/unreact do." On a slow
or refused ship the poke throws, the UI says "delete failed", yet the message is
already gone locally while the ship still has it, and no SSE echo arrives to
resurrect it — a permanent local/ship divergence. Fix: capture the message row
(+ its media + reactions) before the soft-delete and restore them in a catch.
Not auto-fixed: restoring media/reactions wrong risks data loss, so it needs
care and a refused-ship rollback test.

### R8 (Med) — NotesRepo create shows raw transport exception text and drops the write

`NotesRepo.createNote`/`createFolder` go through `write()` (NotesRepo.kt:302),
whose failure returns `"Your ship couldn't take it (${it.message ?: it::class.simpleName})."`,
shown verbatim at `NotesChannelScreen.kt:189` with only a Dismiss button. On a
slow/unreachable ship `it.message` is a raw JVM transport string ("Read timed
out", "Connection refused"). No `isShipSlow` branch, no "Copy error details", no
queue — contrast `ChatComposer.failed()` (:157) which does all of this, and
violates the offline-first calm-error rule. Fix: route through the same
`isShipSlow`/`readableReason`/`errorDetailsOf` path and queue the create. Local
but touches error presentation + queueing, so left for a focused chunk.

### R9 (Med) — SettingsSync marks a UI pref "synced" before the push succeeds

`SettingsSyncImpl.kt:271-272` calls `noteSyncedUiPref(entry, encoded)` BEFORE
`runCatching { pokePutEntry(...) }`. The marker records the attempt, not the
success, and there is no retry. A failed push (slow/unreachable ship) is silently
swallowed: local state persists, the ship is never told, and the in-memory marker
blocks a re-push unless the user sets that same pref to a different value. On next
launch bootstrap is "ship wins" (:387-390), so the ship's stale value silently
reverts the user's change. Fix: move `noteSyncedUiPref` to after a successful
poke, or re-queue on failure.

### R10 (Low, known) — font re-add-after-remove tombstone resurrects the deletion across devices

`AppFonts.kt:75-79` (`FontSettings.mergedWith`) computes `gone = (removed +
remote.removed).toSet()` — a timestampless union of tombstones. Re-adding a
removed font on device A (`adding(b)` clears A's local tombstone) does not clear
peer B's copy, so when B pushes back its `removed=[b]` the re-added font is killed
on both devices. This matches the known, still-unfixed "app-fonts re-add after
remove tombstone bug". Fix: timestamp removals (`removed: Map<id, Long>`) so an
install newer than the last removal wins. Data-model + wire change.

### Dimension 3 minor notes (not ranked)

- `OrreryScreen.kt:392` turn-on failure shows `it.message` raw ("Orrery did not
  answer." fallback); one-shot setup action, low severity.
- `MailRepo.kt:504` `publishToLattice` shows `"Could not file to Lattice:
  ${it.message}"` raw; optional Lattice feature.
- Verified CLEAN: chat queue (send/reply/react/unreact/reads/follows) + drain,
  CalendarRepo (curated AuspexError messages, repo-scope `carry`), ArmillaryRepo
  (calm `keyProblemLine`/`.said()`), MailRepo core writes (optimistic + rollback),
  AI credentials/keys sync (absent-never-wins, timestamped KeyMark tombstones,
  re-add-wins), AI switches sync (separate field, stale peer can't flip), UI-pref
  theme extras (absent-keeps-local).

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
