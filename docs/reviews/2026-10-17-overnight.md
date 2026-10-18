# Overnight review, night of 2026-10-17

Run by the self-paced loop in `docs/reviews/overnight-loop.md` (main checkout, untracked).

## Status

- Night: 2026-10-17 (started 2026-10-18 00:07 EDT).
- Integration worktree: `~/software/personal/talon-ov-2026-10-17`, branch `polish/overnight-2026-10-17`.
- Base: `github/master` at 60d99a39 (release 1.8.30-rc11). The fetch worked.
- Pass: 1.
- Step: fix. Triage done 01:25 EDT; six fixers launched in the worktrees below.
- Pass 1 started: 00:10 EDT.
- Fixer branches in flight: `polish/ov-2026-10-17-<group>` for settings-calls, chatrepo, chatui, apps, shells, search-groups, in `~/software/personal/talon-ov-2026-10-17-<group>`.
- Next firing: merge each fixer's branch as it reports (step 4), then the full suite and compiles.

## Pass 1

Areas:

1. The review fixes merged as PR #42 (`55b63366..60d99a39`), sync and calls half.
2. The same fixes, the ship-app clients half.
3. The same fixes, the shells, voice and platform half.
4. The chat screen and composer: rendering, threads, reactions, attachments and uploads, link previews, slash commands. The last review barely touched them.
5. Groups and admin, notebooks and galleries, search, the notification pipeline, sign-in and ship switching. Also lightly covered before.

Calls, sync and the shells were reviewed in depth hours ago; their new code is in areas 1 and 3.

### Triage

Every High and every Medium was read against the code by the lead; the Highs all hold (A4-1, A4-2, A3-1 and A5-1, A5-2, A5-3, A5-4, A5-5), and the Mediums spot-checked hold (A1-1, A1-2, A2-1, A2-2, A4-4, A4-7, A4-8). The rest are taken on the reviewers' quoted proof.

Fix groups, one owner per file:

- settings-calls: A1-1, A1-2, A1-7, and two 2026-10-17 follow-ups (a peer's live bucket wiping a waiting write; the settings lock delaying the bootstrap); calls A1-3, A1-8, A1-10 (verify first), and the follow-up "the device that lost an answer race answers a restart offer".
- chatrepo: A1-4, A1-5, A1-6, A1-9, A4-1, the repo half of A4-3 and A5-13 (a local_ row is deleted locally), A4-7, A5-9, A5-14, the repo half of A5-18 (a signed-out flow), A5-19, the Lows on live channel ordinals and a pasted URL's path, and the follow-up subscribe walk-back race.
- chatui: A4-2, the menu half of A4-3, A4-4, A4-5, A4-6, A4-8, A4-9, A4-10, A4-11, A4-12, A4-13, A4-14, A4-15, A4-17, A4-18, A2-13, the screen half of A5-13, and the Lows on the multi-file drop loop, slash commands' raw text, reaction sheet clipping and the gallery post's blank body.
- apps: A2-1, A2-2, A2-3, the action-match half of A2-4, A2-5, A2-6, A2-7, A2-8, A2-9, A2-10, A2-12, A2-14, A2-15, A2-16, A5-12, and the follow-ups on the key grace period and new events reaching Home.
- shells: A3-1 and A5-1, A3-2, A3-3, A3-4, A3-5, A5-2, A5-3, A5-4, A5-6, A5-7, A5-8, A5-10, A5-11, A4-20, the screen half of A5-18, the A5 notification and sign-out Lows, and the follow-ups on the comet landing's tab and other sheets bypassing the app's link handler.
- search-groups: A5-5, A5-15, A5-16, A5-17, A5-20, and the Low on orphan embeddings.

## Decisions for sneagan

- A4-19, privacy default: every http(s) link in a received message is fetched from the reader's device to draw its preview, with no setting. It reveals the reader's IP and read time to the sender's server and can reach LAN addresses. Options: keep; skip private and LAN addresses only; previews only for known safe hosts; a setting, off or on by default. Recommendation: skip private and LAN addresses now (no product change), and decide on a setting.
- A2-11, money: Top up and Subscribe stay tappable while the payment line says "Do not order again", so a second tap makes a second checkout. Recommendation: disable both while a checkout is pending with no page yet.
- A2-4, wire: an add's refusal is matched by the newest refusal with id "", so two quick adds can take each other's refusals. Matching the action is being fixed; a full fix needs a client nonce in the poke, which the calendar app would have to echo.
- From 2026-10-17 M5: put the maps or tel link on the voice result notification's tap, so a hidden card can still open it. A behaviour change to the notification.
- Held as before: IMAGE_AUTO_MAX and the unasked mail image fetch; restartIce in TalonRtc.swift (needs a Mac build); M16's notify-levels marker (trunk's reader).

## Unconfirmed

- A4-16 (iOS): the event card's "Another calendar app" may do nothing with a sandbox file:// URL. Needs iOS knowledge or a device.
- A5: "Host order" may be the groups desk's hash order rather than the host's (`GroupsScryParser.kt:46-58`). Needs the groups desk source.
- A5: whether /v1/foreigns sends an initial fact on watch (bears on A5-9's reach only).

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

### Area 3, shells, voice and platform half of #42

- A3-1 Med: the sync stream started at boot notifies nothing, since the message, DM-request and invite listeners are set only by TalonApp's screen (`TalonSyncService.kt:67`, `BootReceiver.kt:40`, `TalonApp.kt:1264-1345`, `TalonApplication.kt:483`). Also, a process kept alive after the Activity is destroyed goes silent. Fix: wire the listeners in buildShipScoped beside readListener, with a process-level open-chat check; or don't start the stream from the service.
- A3-2 Low-Med: erased drafts come back within the session on desktop and iOS, because FileDraftStore's in-memory map is never cleared (`DraftStore.kt:85`, `DesktopShipDataEraser.kt:36`). Fix: forget(ship), called on the erase path in both shells.
- A3-3 Low: Android leaves the profile sheet over a link's target (`TalonApp.kt:1450-1466`, closeSections at `:761`). Fix: closeSections clears profileSheetShip and the image viewer, as desktop's leaveAllFor does.
- A3-4 Low: desktop and iOS draft previews are missing after launch and wrong after a ship switch (`DraftStore.kt:113-128`). Fix: fill backing from the active ship at construction and on ship change.
- A3-5 Low: picking another ship from the Android drawer drops unsaved profile edits with no prompt (`TalonApp.kt` ~1892, ~3128). Fix: sections.leave around both.

### Area 1, sync and calls half of #42

- A1-1 Med-High: a new settings write is delivered ahead of older kept rows, so a kept del-bucket can later delete what landed after it (e.g. Clear all on the assistant, then a new question) (`SettingsSyncImpl.kt:1769-1776` send, `:1011-1013`, `:1703-1716`). Fix: in send(), flush older rows first under the lock; if one is still unanswered, keep this write too.
- A1-2 Med: a write that landed but was acked late is re-sent and re-applied later, undoing a newer change from another device (`SettingsSyncImpl.kt:1782-1788` deliver, `:1795-1811` flushPending, `:1818-1823` reapplyPending). Breaks testing standard 3. Fix: before re-sending or re-applying a kept entry write, compare with the desk the bootstrap read (or scry the entry), and drop the row if the ship holds that value.
- A1-3 Med-Low: every ring ending in None is filed as answered elsewhere, including a Decline from this phone's notification (`NativeCalls.kt:54`, `CallActionReceiver.kt:47`, `CallController.kt:513-520`, `TelecomCalls.kt:139-147`). Fix: the receiver calls the live controller's reject() when it rings for that callId; otherwise None maps to Rejected until the wire says which.
- A1-4 Low-Med: a channel reply whose echo was lost stays as a grey duplicate until the next launch (`MessageDao.kt:224, 320`, `TlonChatRepo.kt:2766-2770, 2889, 3764`). Fix: drop parentId == null from the scry-reap filters; reap exact twins in fetchThread.
- A1-5 Low: the ch.gone early return gives a channel up without deleting it, now reachable with the early stream (`TlonChatRepo.kt:882-889`). Fix: deleteSoon there, or skip stream() when subscribeAll failed.
- A1-6 Low-Med: DMs and club messages in flight at a kill are never requeued (inserted with status null) (`TlonChatRepo.kt:1195`, `MessageDao.kt:232`). Fix: insert as pending, clear on the ack.
- A1-7 Low: a stale kept status-seen write lowers the ship's high-water mark (`SettingsSyncImpl.kt:221-232`). Fix: drop it when the desk is already at or above it.
- A1-8 Low: after joinGranted switches lines, the old line's host keeps showing us recording until the result dialog closes (`CallController.kt:1090-1102, 1184-1196`, `PartyRecording.kt:185-190`). Fix: endRecordingAnnounce before line.leave().
- A1-9 Low: requeued rows show "N queued" from launch until the first drain (`TlonChatRepo.kt:416, 1386, 830`). Fix: leave unchecked requeued rows out of shipSlow until the drain asks.
- A1-10 Low, low confidence: the 5 s and 3 s caps on decline and stop also cover the PUT itself.

### Area 5, groups, notes, search, notifications, sign-in

- A5-1 High: Android without push notifies nothing once the activity is gone (same root as A3-1; swiping from recents clears the listeners while the service keeps the stream) (`TalonApp.kt:1264-1265, 1344-1345`, `TalonSyncService.kt:66-67`, `TalonApplication.kt:483`). Fix: listeners in buildShipScoped through a tested common decision.
- A5-2 High (iOS): the notifications offer after sign-in enrolls with an empty +code: LoginDraft.clear() runs before onLoginCode reads it (`LoginScreen.kt:142, 259-260`, `NotificationSetupDialog.kt:58`). Also the relay gets the raw text, not trimmed or de-plussed. Fix: capture the trimmed code before clearing.
- A5-3 High: Android's in-process path never notifies a DM or club at the default level (MENTIONS demands a mention) (`TalonApp.kt:1277-1281` vs common `notifyAllowed` `NewMessageDiff.kt:171-175`). Fix: use notifyAllowed.
- A5-4 High: desktop and iOS chat-list Sign out skips push cleanup and lands on login even with other ships saved (`App.kt:3399-3409`). Fix: forgetShip(it, false).
- A5-5 High: smart search, Ask and search_history return deleted messages with their old text (`AndroidSearchEmbedderClient.kt:62`, `DesktopSearchEmbedderClient.kt:61`). Fix: one hydrate helper filtering isDeleted.
- A5-6 Med-High (desktop): a comet's first-login landing follows a ship switch and pokes the other ship (acceptInvite, installTrunk) (`App.kt:295, 880-946, 502-521`). Fix: clear pendingLanding in leaveShip, or bind it to its ship.
- A5-7 Med (desktop, iOS): Add ship polls the previous ship into `__loggedout__.db`, which nothing erases, and starts a CallController (`App.kt:708, 752-754, 1161-1171, 786-797, 2186-2190`). Fix: restore only loggedInShip.
- A5-8 Med: Android's group-invite notification opens a page that cannot accept the invite, and carries no ship (`TalonApp.kt:1326-1341, 2278-2287`, `GroupHomeScreen.kt:226-236`). Fix: route to Invites; pass forShip.
- A5-9 Med: group invites arriving while the stream is down are never announced (`TlonChatRepo.kt:794, 3704` notify = false). Fix: notify = !firstRun and true in catchUpAfterQuit.
- A5-10 Med: Android push hint and local notification both alert for one message (`Notifications.kt:573`, `TalonMessagingReceiver.kt:194-232`). Fix: leave a showing notification with that tag alone; setOnlyAlertOnce on replace.
- A5-11 Med: Android re-alerts replayed messages however old after a resume (`TalonApp.kt:1265-1298`). Fix: skip older than 5 min, as desktop does, in the common decision.
- A5-12 Med: a 502/503/504 on a scry, thread or sign-in shows the internal path in red (`NetErrors.kt:42-47`, `UrbitChannel.kt:413, 437`, `LoginScreen.kt:479-510`). Same root as A2-2. Fix: isShipSlow matches HTTP 50[234] on scry, thread and login, not all 5xx (a scry 500 is notServed); a friendly line.
- A5-13 Med: a refused notebook or gallery post stays forever, can't be deleted, and the notebook shows it as published (`MessageDao.kt:224`, `TlonChatRepo.kt:3250-3267`, `NotebookListScreen.kt:172`). Same root as A4-3. Fix: local delete of local_ rows; SendStateNote on NotebookCard and the post header.
- A5-14 Med: search text drops link addresses and mentions' @p (`MessageDao.kt:484-501`, `Story.kt:436-438, 446`). Related to A4-8. Fix: append URL_TAG and MENTION_TAG items, off the excerpt.
- A5-15 Med: smart search finds an edited message only by its old text (`EmbeddingIndexer.kt:76-94`, `DesktopEmbeddingIndexer.kt:68-86`). Fix: re-embed when the hash differs.
- A5-16 Med: the indexer sees new messages only at its next start; Android's smart switch mid-session does nothing (`TalonApp.kt:1230-1240`, `App.kt:1371-1374`). Fix: key on the switch, stop when off, start when smart mode is used.
- A5-17 Med: Android Back from a search result returns to an empty search (`SearchScreen.kt:69`, `TalonApp.kt:2662, 2981-2989`). Fix: hoist the query into the shell.
- A5-18 Med: a lapsed login is never surfaced; chat retries a 403 forever (`UrbitChannel.kt:129-134`, `TlonChatRepo.kt:501-502`). Fix: a signedOut flow the shells turn into "Sign in again".
- A5-19 Med: group join, leave, accept, decline and create run on the screen's scope; createChannel pokes without confirm (`TlonChatRepo.kt:2298-2405, 1893-1980`, `GroupHomeScreen.kt:279`, `FolderAssignmentSheet.kt:166`, `GroupInfoPane.kt:430`, `GroupInvitesScreen.kt:139-165`). Fix: carry; confirm on create plus refresh.
- A5-20 Med-Low: Group admin offers New channel to non-admins (`GroupAdminScreen.kt:129-133` vs `:548-554`). Fix: gate on mayAdminister.
- A5 Lows: live channel add resets ordinal (`TlonChatRepo.kt:3995-4012`, `GroupDao.kt:35`); CatchUpWorker waits forever when the repo isn't started, and ingestPosts never notifies (`CatchUpWorker.kt:57-65`); hints for a ship no longer signed in are shown (`TalonMessagingReceiver.kt:104-109`) and stopPushesFor clears the device id even when unregister failed (`TalonApplication.kt:395-401`); Android never re-sends a rotated UnifiedPush endpoint (`TalonApp.kt:1203`); desktop re-notifies an earlier message when the newest is deleted (`NewMessageDiff.kt:86-89`); desktop notification click only raises the window (`SystemNotifier.kt:181`); desktop and iOS mail notices fire while the inbox is on screen (`App.kt:1336-1339`); orphan embedding rows never deleted; Android sign-out of the last ship leaves TalonSyncService running (`TalonApplication.kt:643-646`); desktop and iOS picking the active ship closes the chat (`App.kt:2114-2118, 2219-2222`); desktop and iOS forget skip forgetHomeListSnapshot; a pasted ship URL keeps its path (`UrbitSession.kt:26-34`); GalleryPostScreen draws a blank body with a live comment box while post is null (`:159, :269`).
- A5 Unconfirmed: Host order may be hash order (`GroupsScryParser.kt:46-58`); whether /v1/foreigns gives an initial fact.
