# Overnight bug-hunt and polish review — 2026-10-14

Branch: `polish/overnight-2026-10-14` (base `origin/master` 2b8ff1d8). Not pushed.
Prompt: `.overnight-review-prompt.md`. Runs via a self-paced `/loop`, resumable.

STATUS: IN PROGRESS

## Summary

- Confirmed bugs: 10
- Fixed: 2 (commits 6d307846, 4ec3d2a7)
- Reported: 12 (3 High, 5 Med, 4 Low)
- Polish items: 0 (dimension 7 not yet run)

## Dimension coverage

| # | Dimension | State |
|---|-----------|-------|
| 1 | Cross-platform discipline (capability + section flags, android-only) | DONE — clean |
| 2 | Testing Standard applied to recent features | DONE — 1 fixed, findings below |
| 3 | Offline-first + sync correctness | DONE — 6 findings (2 High), below |
| 4 | Concurrency / lifecycle | DONE — 1 fixed (R5), 3 new findings, SSE/leaks clean |
| 5 | Failure-path honesty | DONE — clean (one thorough pass) |
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

### F2 — message edit and delete died when leaving the chat mid-poke (commit 4ec3d2a7)

This is the fix for R5 (found in dimension 3, applied in dimension 4).
`TlonChatRepo.edit`, `delete`, `editNotebookPost` and `editGalleryPost` poked the
ship directly, and every caller launched them on the screen's
`rememberCoroutineScope`, so leaving the chat mid-poke cancelled the edit/delete.
Wrapped each in `carry { … }` (the repo's own scope, as the channel-admin writes
and the F1 fix do). Test `TlonChatRepoSendTest."an edit whose screen is left
mid-poke still reaches the ship"` sets `ship.holdPoke`, cancels the caller scope
mid-poke, and asserts the edit still lands. 38/38 in that class pass — the 37
existing edit/delete wire tests confirm the `carry` wrap did not change behavior.
R7 (delete has no rollback on refusal) is a separate concern, still open.

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

### R5 (High) — message edit and delete run on the screen's scope, dropped on navigation — FIXED (see F2, commit 4ec3d2a7)

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

### R11 (High) — NotesRepo note writes run on the screen scope and leave the note stuck "saving…" forever

`NotesRepo.updateNote` (:312) and the `poke`-based writes (:272, used by
deleteNote/renameNote/moveNote/publishNote/unpublishNote) run `ch.apiJson`/
`ch.poke` directly in the caller's context — unlike the same file's `write()`
helper (:299) which deliberately wraps in `scope.async { … }.await()` "so leaving
the screen does not stop it". Callers launch on `rememberCoroutineScope`
(`NoteScreen.kt:164`). Worse than a plain dropped write: `updateNote` calls
`applyLocalEdit` (marks the row pending) at :320 BEFORE the network call and only
clears it at :350 AFTER. If the screen scope is cancelled mid-flight (drawer or
section switch, Android activity recreation), pending is never cleared, and
`replaceTree` (:344-349) re-applies pending on every later scry — the note reads
"saving…" permanently. Fix: route these through the repo scope, the same
`scope.async { … }.await()` the `write()` helper already uses. Same proven class
as F1/F2; deferred only to keep this iteration scoped.

### R12 (Med) — group-admin writes run on the screen scope, dropped on navigation

`TlonChatRepo.approveRequest` (:2385), `denyRequest` (:2390), `kickFromGroup`
(:2030), `banFromGroup` (:2035), `unbanFromGroup` (:2041), `revokeTokenInvite`
(:2375) all reach `ch.poke` with no `carry`/repo-scope hop, and `GroupAdminScreen.kt`
launches them on `rememberCoroutineScope` (e.g. :220, :235, :250). Leaving the
admin screen during the round-trip cancels the poke (and, with `confirm = true`,
the ack-wait), so the membership change may not land. Fix: wrap in `carry`, same
as F2.

### R13 (Low) — mail "File to Lattice" runs on the screen scope

`MailThreadPane.kt:245` (`file()`) calls `MailRepo.publishToLattice`
(`MailRepo.kt:496`), a plain suspend with no scope hop; navigating away during the
HTTP publish drops it and loses the result line. Archival, not a core write. Fix:
route through MailRepo's own `scope.async`, matching `sendMessage` (`MailRepo.kt:676`).

### Dimension 4 clean (verified)

- Eyre SSE liveness: NO regression. `UrbitChannel.streamIdleMs` resets on every
  stream line including eyre's `:` heartbeats; the `TlonChatRepo` watchdog
  (:860-874) never force-reconnects a heartbeating-but-eventless stream. Guarded
  and covered by `ChannelHeartbeatTest`/`ChannelHeartbeatLiveTest`.
- Subscription/listener leaks: clean. Single-slot listeners set once at app-shell
  level under keyed DisposableEffects, not per-screen. No `Unit`-keyed subscribe
  to a changing param.
- Shared mutable state / races: clean. Shared collections use Concurrent*/
  MutableStateFlow atomic update. The one TOCTOU (`markReadSoon`) is on the
  serialized event-apply path with a benign duplicate-read consequence.
- No `runBlocking` on a UI path; the single `GlobalScope.launch` (App.kt:1365) is
  a deliberate deferred teardown that must outlive the composition re-key.

## Dimension 5 notes — failure-path honesty (clean)

One thorough finder pass, no new distinct bug. Every path that could conflate
"ship didn't answer" with "nothing here" is already guarded, most with in-code
comments naming the exact bug they prevent:

- `scryFirstMatching` (TlonChatRepo.kt:5509) does collapse timeout / exhausted
  budget / all-404 into one `null`, but every caller treats null honestly, not as
  empty: `refreshConversation` (:2778), `loadOlder` (:2862) and `fetchActivityFeed`
  (:2593) all `error("the ship did not …")`, and `bootstrapContacts` (:5035) only
  updates the book `if (bookBody is JsonObject)` ("No answer is not an empty book").
- List screens distinguish loading / failed / genuinely-empty: chat
  (DmChatScreen.kt:2006 "Messages could not be loaded" vs "No messages yet"),
  gallery (GalleryGridScreen.kt:140), activity (ActivityList.kt:66 with a Retry
  button), mail (MailList + `_error` via MailNotice; transport+parse wrapped as
  AuspexError), notebook (NotesChannelScreen.kt:208 "isn't synced yet" vs "Nothing
  here yet").
- Silent catches checked are best-effort cache restores (CalendarRepo getOrNull
  falls back to retained state) or handled (`writeInBackground` default onFailed is
  never the live path).
- Retryable failures keep their retry affordance (activity Retry, gallery/chat
  "open again to retry", mail backoff + clearError).

Minor (not a finding): MailList on a first-load failure shows `MailNotice(error)`
AND "No mail." together — honest but slightly redundant; could suppress the empty
line while an error is showing.

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
