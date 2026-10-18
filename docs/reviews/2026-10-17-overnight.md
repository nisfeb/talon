# Overnight review, night of 2026-10-17

Run by the self-paced loop in `docs/reviews/overnight-loop.md` (main checkout, untracked).

## Status

- Night: 2026-10-17 (started 2026-10-18 00:07 EDT).
- Integration worktree: `~/software/personal/talon-ov-2026-10-17`, branch `polish/overnight-2026-10-17`.
- Base: `github/master` at 60d99a39 (release 1.8.30-rc11). The fetch worked.
- Pass: 1.
- Step: review. Five reviewers launched 00:10 EDT.
- Pass 1 started: 00:10 EDT.
- Fixer branches in flight: none yet.
- Next firing: when the reviewers' reports are in, triage them (step 2), adding the Follow-ups from `docs/reviews/2026-10-17-review.md`.

## Pass 1

Areas:

1. The review fixes merged as PR #42 (`55b63366..60d99a39`), sync and calls half.
2. The same fixes, the ship-app clients half.
3. The same fixes, the shells, voice and platform half.
4. The chat screen and composer: rendering, threads, reactions, attachments and uploads, link previews, slash commands. The last review barely touched them.
5. Groups and admin, notebooks and galleries, search, the notification pipeline, sign-in and ship switching. Also lightly covered before.

Calls, sync and the shells were reviewed in depth hours ago; their new code is in areas 1 and 3.

### Findings

(after triage)

## Decisions for sneagan

(none yet)

## Unconfirmed

(none yet)

## False positives

(none yet)

## Pass 1 raw findings (untriaged)

### Area 2, ship-app half of #42

- A2-1 Med: a refusal found after the screen has gone is cleared by the gone screen's callback calling `heard()` (`CalendarScreen.kt:534, 1131`, `DmChatScreen.kt:712, 714`); the not-taken path also wipes an unrelated waiting refusal. Fix: drop `heard()` there; CalendarScreen collects `unheard` while open.
- A2-2 Med: a note save on a 502 shows "Your ship couldn't take it (PUT /notes/...: HTTP 502)" (`NotesRepo.kt:317` notSent, `:373`; `UrbitChannel.kt:468` error string; `NetErrors.kt:46` isShipSlow prefix). R8's createNote `write()` has the same shape. Fix: typed HTTP error, or isShipSlow and readableReason learn `HTTP 5xx` and `HTTP \d{3}`.
- A2-3 Med (one check): `refusal()` reads outcomes.json once at 1.5 s (`CalendarRepo.kt:274-282`); a busy ship refusing later makes a task silently vanish and an event called added. Fix: look again during or after `afterItemWrite`'s passes.
- A2-4 Low: an add's refusal matched by id "" and newest, ignoring action, so one add can take another's refusal (`CalendarRepo.kt:278`). Fix: match action; a client nonce is a wire decision.
- A2-5 Low: the assistant's `wrote` never undoes `assumeEvent` on refusal (`AssistantActions.kt:898-903`). Fix: keep Written, undo on refusal.
- A2-6 Low: Home's Try again stacks full refreshes (`HomeScreen.kt:1348`). Fix: disable while refreshing.
- A2-7 Low: a "pending" note save is handled as a failure, restoring the old copy (`NotesWire.kt:316`, `NotesRepo.kt:382`, `NoteScreen.kt:172-176`). Fix: treat pending as taken, said calmly.
- A2-8 Low: a deleted note in another's notebook can reappear after the 3 s settle (`NotesRepo.kt:405`).
- A2-9 Low: switchOff with the ship down makes ~10 sequential requests (`OrreryRepo.kt:703-731`). Fix: after the first Unreachable, mark the rest missed.
- A2-10 Low: payment start of 0 when the account read failed lets any balance settle as PAID (`ArmillaryRepo.kt:408`). New angle on M21.
- A2-11 Low, money: Top up stays tappable while the line says not to order again (`AiSettingsSection.kt:540`).
- A2-12 Low: mail reads twice on every desktop focus (`MailRepo.kt:320-323`, `App.kt:1504`). Fix: skip the inbox aside after a failed refresh; age-limit focus reads.
- A2-13 Low: the chat card can show an older unrelated refusal from `unheard` (`StoryRenderer.kt:934`).
- A2-14 Low: raw error text on Orrery pages (`OrreryScreen.kt:420` NotOnHere `it.message`; ReadingRow `_error`).
- A2-15 Low: "was not ticked" for a failed untick (`HomeScreen.kt:1391`).
- A2-16 Low: the Orrery and Armillary retry loops run every 15 min in the background (`OrreryRepo.kt:830-838`, `ArmillaryRepo.kt:258-267`).
