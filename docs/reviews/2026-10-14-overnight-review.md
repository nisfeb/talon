# Overnight bug-hunt and polish review — 2026-10-14

Branch: `polish/overnight-2026-10-14` (base `origin/master` 2b8ff1d8). Not pushed.
Prompt: `.overnight-review-prompt.md`. Runs via a self-paced `/loop`, resumable.

STATUS: IN PROGRESS

## Summary

- Confirmed bugs: 0
- Fixed: 0
- Reported: 0
- Polish items: 0

## Dimension coverage

| # | Dimension | State |
|---|-----------|-------|
| 1 | Cross-platform discipline (capability + section flags, android-only) | DONE — clean |
| 2 | Testing Standard applied to recent features | not started |
| 3 | Offline-first + sync correctness | not started |
| 4 | Concurrency / lifecycle | not started |
| 5 | Failure-path honesty | not started |
| 6 | Desktop leaf correctness | not started |
| 7 | UI polish, mobile-first | not started |

## FIXED

_none yet_

## REPORTED

_none yet_

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
