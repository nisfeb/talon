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

### Area 4, chat screen and composer

- A4-1 High: deleting a queued DM hides it, but the drain still sends it (`TlonChatRepo.kt:3250-3266` delete, `:1466-1484` drain; `MessageDao.kt:84-85, 236-237, 252-253` softDelete keeps status queued, queued() has no isDeleted filter). Offline, delete throws, so a queued message can't be withdrawn. Fix: a queued or failed row is removed locally with no poke and dropped from neverSent; queued() and queuedCount() filter isDeleted = 0.
- A4-2 High: a voice message being sent is lost when the chat is left, and its file deleted (`ChatComposer.kt:982-1011` send on the screen scope, `:388-393` onDispose deletes). Android and iOS. Fix: upload and send in repo.carry; don't delete while uploading.
- A4-3 Med: a pending, queued or failed channel post's menu sends its `local_` id (delete, react, reply, edit, pin, copy link), and a failed one can never be removed (`TlonChatRepo.kt:1180-1188, 3250-3266`, `UrbitIds.kt:20`, `DmChatScreen.kt:1041-1185, 2522-2559`). Fix: delete local rows locally; hide the others for `local_` ids; onEditLast skips them.
- A4-4 Med: a poll card never counts votes (`StoryRenderer.kt:721-734`, `PollFormat.kt:15-18`, `ReactionPalette.kt:66-67`: VOTE_EMOJIS keep U+FE0F, stored reactions are normalized without it). The test seeds unnormalized emoji. Fix: compare normalized forms for tallies and mineIndex.
- A4-5 Med: sending a voice message wipes the box's text (`ChatComposer.kt:1001-1003`). Fix: keep the draft.
- A4-6 Med: a failed edit throws away the edited text (`ChatComposer.kt:782-791`, `DmChatScreen.kt:1308-1321`, `ThreadList.kt:628-645`). Fix: keep editing state until the write resolves; restore on failure.
- A4-7 Med: pin and unpin run on the chat's scope, outside carry (`TlonChatRepo.kt:3529-3577`, `DmChatScreen.kt:1172-1183`). Fix: carry.
- A4-8 Med: Copy text, New event notes and the assistant's reads get the shortened link label, not the URL (`StoryCache.kt:124`, `Story.kt:448, 337`; callers `DmChatScreen.kt:695-698, 1095-1099`, `ThreadList.kt:374-378`, `AiFeatures.kt:26`, `ToolCatalog.kt:137, 320`). Fix: build textFor from hrefs.
- A4-9 Med: desktop Ctrl+V takes the clipboard's image over its text when both exist (`FileDropTarget.desktop.kt:107-111`, `ChatComposer.kt:1207-1211`). Fix: when a string flavor exists, paste the text.
- A4-10 Med polish: a restored draft opens with the caret at the start (`ChatComposer.kt:127, 272`). Fix: TextRange(length).
- A4-11 Low-Med: a jump to a post not kept locally lands at the bottom silently (`DmChatScreen.kt:273-275, 481-506`). Fix: fetch the post, or say "not loaded here".
- A4-12 Low: Enter after ":D" picks an emoji (`EmojiPicker.kt:91-107`, `ChatComposer.kt:1246-1251`). Fix: open the picker from 2 characters.
- A4-13 Low: the thread menu offers Delete on others' replies to non-admins (`ThreadList.kt:358`). Fix: canModerate as in DmChatScreen.
- A4-14 Low: editing your own comment on a gallery or notebook post always fails (`ThreadList.kt:359`, `TlonChatRepo.kt:3401`). Fix: gate Edit to chat/ or let edit take any channel nest.
- A4-15 Low: Add or Remove contact from a chat's profile sheet is silent on failure and cancelled by leaving (`DmChatScreen.kt:1410-1419`). Fix: repo.carry with a failure line.
- A4-16 Low (iOS): "Another calendar app" may do nothing (`StoryRenderer.kt:900-911`, `Files.kt:33-37`). Low confidence; needs iOS knowledge.
- A4-17 Low: chat dialogs survive a chat switch and act on the new chat's whom (`DmChatScreen.kt:667-682`). Fix: remember(whom) or use target.whom.
- A4-18 Low: after a ship switch another ship's rows can show for the first frames (`DmChatScreen.kt:2156-2161, 325` ChatRowsSnapshot keyed by whom only). Fix: key by ship, or clear on switch.
- A4-19 Low, privacy, DECISION: every http(s) link in a received message is fetched from the reader's device (`LinkPreviewCache.kt:102-103`, `DmChatScreen.kt:1830-1838`), with no setting.
- A4-20 Low: "Hide composer buttons" is ignored in threads (`RightPaneHost.kt:85-99`, `TalonApp.kt:2529-2555`, `App.kt:2715-2735`).
- New angles: M14 via fetchCitePost and fetchThread storing isolated old rows; F1's multi-file drop loop still on the screen scope (`ChatComposer.kt:717-720`); raw `it.message` in `SlashCommands.kt` 219, 316, 343, 387; ReactionDetailsSheet names clip without ellipsis.
