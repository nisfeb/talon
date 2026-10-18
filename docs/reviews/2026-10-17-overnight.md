# Overnight review, night of 2026-10-17

Run by the self-paced loop in `docs/reviews/overnight-loop.md` (main checkout, untracked).

## Status

- Night: 2026-10-17 (started 2026-10-18 00:07 EDT).
- Integration worktree: `~/software/personal/talon-ov-2026-10-17`, branch `polish/overnight-2026-10-17`.
- Base: `github/master` at 60d99a39 (release 1.8.30-rc11). The fetch worked.
- Pass: 2 (pass 1 merged; its full suite running).
- Step: fix. Triage done 01:25 EDT; six fixers launched in the worktrees below.
- Pass 1 started: 00:10 EDT.
- Fixer branches in flight: `polish/ov-2026-10-17-<group>` for settings-calls, chatrepo, chatui, apps, shells, search-groups, in `~/software/personal/talon-ov-2026-10-17-<group>`.
- Merged: all six by 03:09 EDT: shells (15 commits), chatui (21), search-groups (5), apps (16), chatrepo (16), settings-calls (9). Lead integration commits: SearchDraft cleared in App.kt; orrery setForeground in both shells; the event card on addSharedWhyNot; test temp dirs under build/test-tmp; the notebook excerpt on shownWordsOf; the lapsed-login bridge into LoginLapses in both shells (with an AppShellTest end to end); a refresh after a new channel; a stale DmChatScreen comment.
- Pass 2 started 03:30 EDT: six read-only reviewers (UI polish; voice and the assistant; Android services and lifecycle; desktop and iOS leaves; performance and ship cost; pass 1's own commits), launched while pass 1's full suite runs.
- Pass 1's full suite (03:30): 3816 tests, 1 failure, OrreryKeysTest "turning it on finishes though the page that asked is left". It passes alone (8 of 8); it timed out on a 5 s poll under the suite's load, so the poll cap is now 15 s. All four compiles passed.
- Next firing: record the remaining pass-2 reports (B2, B3, B5, B6), then triage all of pass 2 into fix groups.
- Left for pass 2 (found while merging): `dm:` and `invite:` keys in IosNotifier's postsLocally (desktop and iOS DM-request and invite clicks only raise the window); A1-10 (the 5 s and 3 s caps cut a slow PUT off; needs an ack-wait option on UrbitChannel.poke); the other test classes' temp-dir cleanup (contained by build/test-tmp now); AndroidRelaySettings not persisting registeredEndpointFor; ingestPosts never notifying (a worker catch-up notifies nothing); the DmChatScreenTest "emoji alone" flake (ChatRowsSnapshot shared across tests); an AppShellTest hang seen once ("Escape in a chat's composer closes the chat", a Compose deadlock in the test).

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

## Found by the loop itself

- The Talon desktop tests leave their temp directories in /tmp: about 6.7 GB of `/tmp/talon-*` (5,000 from the settings outbox tests alone) had filled the 16 GB tmpfs to 100% during pass 1, which broke a PIT run. Cleared at 02:50 (dirs older than 30 minutes). A pass-2 fix: the tests delete what they create.

## Decisions for sneagan

- A4-19, privacy default: every http(s) link in a received message is fetched from the reader's device to draw its preview, with no setting. It reveals the reader's IP and read time to the sender's server and can reach LAN addresses. Options: keep; skip private and LAN addresses only; previews only for known safe hosts; a setting, off or on by default. Recommendation: skip private and LAN addresses now (no product change), and decide on a setting.
- A2-11, money: Top up and Subscribe stay tappable while the payment line says "Do not order again", so a second tap makes a second checkout. Recommendation: disable both while a checkout is pending with no page yet.
- A2-4, wire: an add's refusal is matched by the newest refusal with id "", so two quick adds can take each other's refusals. Matching the action is being fixed; a full fix needs a client nonce in the poke, which the calendar app would have to echo.
- A1-2, settings sync, an assumption to confirm: a settings write now counts as delivered once eyre accepts its channel PUT (2xx), without waiting for the poke's ack. That is what stops a late-acked write being sent again over another device's newer change. It assumes a 2xx means eyre handed the poke to our own %settings in the same event; a later nack would then go unseen and the next bootstrap's ship copy would win. Recommendation: keep it.
- Settings-calls and apps judgement calls, recorded: the note-delete settle is now 30 s (a refused delete comes back after 30 s, not 3 s); desktop mail reads on focus at most once a minute; a calendar refusal now matches its action as well as its id, which relies on the calendar writing the poke's action into outcomes.json.
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

## Pass 2 raw findings (untriaged)

### Area B4, desktop and iOS leaves

- B4-1 High (Linux): notifications can stop for the session. gdbus gets a bare "-1" and exits with usage (seen locally), and its exit code is never checked; notify-send is demoted to gdbus on any failure, including a title or body starting with "-" (no "--") (`SystemNotifier.kt:145-213`). Fix: "--" before positionals in both; check gdbus's exit code so the tray fallback runs.
- B4-2 Med-High (iOS): IosSessionStore reads sessions.json once at construction; built while the device is locked it reads empty and a later save overwrites the saved ships (`IosSessionStore.ios.kt:80`, `IosFiles.ios.kt:201-252`; commit 220a2dda guarded only IosAiSettings). Same shape: relay.json, theme, drafts. Fix: IosAiSettings' unreadable guard in IosSessionStore and IosTextStore.
- B4-3 Med-High (iOS), DECISION: the Room database directory is NSFileProtectionComplete while calls keep Talon running locked, so DB reads and writes fail ~10 s after lock (`AppDatabase.ios.kt:64-86`). Option: CompleteUntilFirstUserAuthentication. A privacy default.
- B4-4 Med (iOS): playing a voice message during a call sets the audio session to Playback and cuts our mic (`VoiceMessages.ios.kt:244, 318`). Fix: skip setCategory when callLive.
- B4-5 Med (desktop): leaving Settings while the local comet starts kills it and leaves Start greyed out (`LocalShipSection.kt:52, 102-116, 155`, `DesktopLocalShip.kt:325-329`). Fix: operations on a scope DesktopLocalShip owns; the catch sets Failed or Stopped and doesn't destroy on cancellation.
- B4-6 Med (desktop, security): DesktopUriHandler hands any string to xdg-open, open or rundll32 (`DesktopUriHandler.kt:40-47`; `Story.kt:443-449` link target from the message's href). Fix: allow http, https, mailto plus internal routing; app-made files through openFile.
- B4-7 Med-Low (desktop): a failing lock call locks the user out with "already running" (`SingleInstance.kt:119-135`). Fix: let the launch go on for a Throwable that isn't OverlappingFileLockException.
- B4-8 Low-Med (desktop): a partly extracted vere binary is trusted forever (`Tarball.kt:30-40`, `DesktopLocalShip.kt:206`). Fix: extract to bin.part, rename after a size check.
- B4-9 Low-Med (desktop): the 10 s exit deadline is shorter than the comet's stop budget (`ExitPolicy.kt:11`, `Main.kt:193`, `DesktopLocalShip.kt:117-134`). Fix: bound localShip.stop() itself, or stop it after the DB close.
- B4-10 Low-Med (iOS): starting dictation again while listening kills the new listen (old task's cancel callback calls stop) (`Dictation.ios.kt:75-131`). Fix: a generation counter.
- B4-11 Low (desktop, privacy): terminal.log records the +code answer and sessions.json holds the cookie with default permissions (`DesktopLocalShip.kt:399`, `DesktopSessionStore.kt:41-49`). Fix: 0600 as keepSecret does.
- B4-12 Low: the notification icon is written to shared /tmp under a predictable name, following symlinks (`SystemNotifier.kt:229-235`). Fix: under AppDirs.
- B4-13 Low: event .ics files pile up world-readable in /tmp (`Files.kt:33-37`, `StoryRenderer.kt:899`). Fix: cache dir and prune.
- B4-14 Low: desktop log rotation only at launch (`Log.desktop.kt:19-28`). Fix: check size every N lines.
- B4-15 Low, DECISION (a feature): desktop never plays audio inline though vlcj is present (`VoiceMessages.desktop.kt:113-115`).
- B4-16 Low, doc: the iOS crash report is not in Documents as two comments claim (`MainViewController.kt:62-68`, `CallPush.swift:491`). Fix: the comments (Kotlin side), or write to Documents.
- B4-17 Low, DECISION: Linux puts the pier, models, caches and logs under ~/.config (`AppDirs.kt:167-171`). Moving them is a migration.
- B4-18 Low, doc: CLAUDE.md says macOS notifications use osascript; SystemNotifier says AWT TrayIcon.

### Area B1, UI polish

- B1-1 High bug: Contacts cannot be opened on Android or iOS. The only way in is the chat list's More menu, which is desktop only (`DmListScreen.kt:899`, `:1001-1006`), and the drawer has no Contacts entry (`SectionsDrawer.kt:59-90`). Fix: a fixed Contacts drawer item beside "Edit menu", wired in both shells, not a RailItem (that is the synced rail-order bucket).
- B1-2 Med-High bug: a narrow desktop window's menu has no Home and no Orrery. App.kt never passes `onOpenHome` (`App.kt:3433-3530`) and the menu has no `RailItem.Actions` case; hiding Orrery from the sidebar loses it, though SidebarSettingsScreen promises "it stays available in the kebab menu". Fix: pass onOpenHome and add an Orrery item gated on orreryOn.
- B1-3 Med bug: the sign-in form does not scroll (`LoginScreen.kt:182-216`, same in `LoginQrShareScreen.kt:87-110`), so the keyboard or landscape hides Connect and the error line. Fix: verticalScroll on the form column.
- B1-4 Med bug: the chat list says "No direct messages yet" when the first load failed (`DmListScreen.kt:1503-1508`; bootstrap reads are runCatching and logged, `TlonChatRepo.kt:760-781, 863`). Fix: bootstrap records a failed read; the list shows "Couldn't load your chats" with Try again.
- B1-5 Med bug: group activity rows (ask, join, kick, role, invite) show `group/~host/name` and do nothing on tap (`TlonChatRepo.kt:5825`, `ActivityParser.kt:39-47`, `ActivityList.kt:190, 298`); the author may read "someone" (wire unverified); note events read "Note create". Fix: group title, tap to the group, labels.
- B1-6 Med-Low bug: the profile sheet does not scroll (`ContactProfileSheet.kt:89-100`), so a long bio or landscape hides the actions.
- B1-7 Med-Low bug: the desktop sidebar does not scroll (`DesktopShell.kt:195-216`); a ~614dp-tall window clips Settings. Its badge dot has no semantics.
- B1-8 Low polish: Contacts' Remove is fire and forget (`TalonApp.kt:2082-2084`, `App.kt:2684-2686`), no progress, silent failure. New angle on A4-15 and M38.
- B1-9 Low polish: raw exception text at `UrbLinkHost.kt:121`, `AppsSettingsScreen.kt:146` (LatticeInstall; a 502 reads as a refusal), `App.kt:2389`, `SignVerifyDialog.kt:63`.
- B1-10 Low polish: the member dialog lists role ids, not titles (`GroupAdminScreen.kt:286`).
- B1-11 Low polish: an iPad is called "this iPhone" in about ten strings.
- B1-12 Low polish: a comet's full @p shows where shipHandle belongs (LoginScreen, DmListScreen ship menu, SettingsScreen, GroupAdminScreen, GroupInvitesScreen).
- B1-13 Low polish: keyboard shortcut labels use enum names (`KeyboardShortcuts.kt:106`); use railLabel.
- B1-14 Low polish: jargon: "%trunk", "the ship's cordon list", "Save metadata", "kebab menu", "talon:// URI", "Re-register (rotate token)", "Diagnose distributor".
- B1-15 Low polish: an empty bookmarks folder says "a bookmark below" with nothing below (`BookmarksList.kt:156`).
- B1-16 Low polish: Invites mixes "Reject" and "decline" (`GroupInvitesScreen.kt:166, 201, 277`).
- B1-17 Low polish: maxLines with no ellipsis at `ActivityList.kt:254, 313`, `BookmarksScreen.kt:261`, `GroupInvitesScreen.kt:257`.
- B1-18 Low polish: the drawer marks the open section differently in the two shells (`TalonApp.kt:1752`, `App.kt:3617`); "Always on the sidebar" on phones.
- False positives (reviewer's): the lightbox Close label, admin and invite spinners, MenuBadgeDot, 32dp IconButtons, icons inside text buttons, the dead gatedOff branch, iPhone landscape sidebar, the admin title.

### Area B2, voice and the assistant

- B2-1 High bug: only the first message proposal is read aloud, but a spoken "send" approves every waiting proposal (`QuickDictation.kt:704-705`, `DictationFlow.kt:167-170`, `DictationSpeech.kt:36`). Fix: read every waiting proposal aloud.
- B2-2 High, DECISION: with Orrery off, a dictation sends mail and messages with no confirmation in a run that also reads other people's content (`DictationAgent.kt:45, 135-142`, `confirm = { _, _ -> true }`); `AgentLoop.kt:17-22` claims injection is contained by construction. Option: route a dictation's send_message and send_mail through ownerDecides.
- B2-3 Med-High bug: AgentClient raises a plain error, never ModelHttpError (`AgentClient.kt:161-164`), so the assistant, Archie, dictation and loops never show Top up or a calm 429 or 5xx line. Fix: throw ModelHttpError, with outOfCredit for 402; loops' error through modelProblem.
- B2-4 Med bug: send_message says "Sent." and send_event "Posted" when the message was only queued (`ToolCatalog.kt:158-159`, `AssistantActions.kt:759-760`, `DictationAgent.kt:196`). Fix: check the queue as sendConfirmed does and say it is queued.
- B2-5 Med bug: Voice.say gives up after 60 s without tts.stop() (`QuickDictation.kt:1066-1078`), so a long paragraph is cut off by the next and the mic opens while Archie talks. Fix: a length-scaled budget and tts.stop() on expiry.
- B2-6 Med, DECISION: write-authorized loops can call share_calendar and answer_calendar_offer unattended (`LoopRunner.kt:66`, `DESTRUCTIVE_TOOLS = setOf("delete_event")`). Recommendation: add both.
- B2-7 Med bug and cost: Archie keeps the whole read-out in the system prompt for every later turn (`VoiceConversation.kt:95`), unlabelled as others' words. Fix: carry it as the reading turn's reply, capped and labelled.
- B2-8 Med, DECISION: a locked phone reads messages and mail aloud and shows them on the card (`DictationAgent.kt:170-171` filters writes only). A privacy default.
- B2-9 Med bug: an Archie turn that fails after a write asks the owner to say it again, and the write happens twice (`QuickDictation.kt:469-477`, `VoiceConversation.kt:96-105`). Fix: "It may have done part of it" when a write ran.
- B2-10 Med bug: Archie's opener waits uncapped for the calendar read and the mail listing (`Loops.kt:356, 363`). Fix: withTimeoutOrNull on both.
- B2-11 Med-Low, DECISION: a dictation's transcription can wait 11 minutes (ARMILLARY_WAIT_MS, chosen 2026-10-16 for slow home hardware); the wake lock is 10 minutes and two comments say 120 s and 180 s. Option: a shorter cap for dictation and Archie turns.
- B2-12 Med polish (cost), DECISION: no prompt caching, and the NOW line at the end of the system prompt would defeat it (`AgentClient.kt:197-209, 283-299`). Needs a live provider check the loop cannot make.
- B2-13 Low-Med bug: "check off …" and "let me know when …" are routed as questions (`DictationFlow.kt:188, 201`). Fix: narrow LOOKUP.
- B2-14 Low-Med, DECISION (a feature): an assistant run cannot be stopped (`AssistantScreen.kt:761, 805-810`). Option: a Stop button while busy.
- B2-15 Low bug: an assistant run that fails after a write saves nothing to history and puts the question back (`AssistantScreen.kt:628-637`).
- B2-16 Low bug: complete_task says "Done" when the calendar refused (`CalendarRepo.kt:667-668`).
- B2-17 Low bug: all loops due in one Android fire share one 120 s budget (`Loops.kt:94, 590`). New angle on M40.
- B2-18 Low polish: spoken confirmations read ids and ISO dates (`DictationAgent.kt:190-198`).
- B2-19 Low polish: the confirm card names the recipient by nickname only (`AssistantScreen.kt:1244-1246`).
- B2-20 Low, DECISION: lattice-save runs unconfirmed alongside chat and mail reads (`LatticeMemory.kt:18-23`).
- False positives (reviewer's): DictationService is not exported; LOCKED_OK calendar; orrery_instruct in LOCKED_WRITES unreachable; LoopWorker overlap; the desktop ticker restart; the Ask route label; M4 and M5 guards present; Retry re-reads the lock; AskUrbit and friends have no tools.

### Lead's verification so far (03:50)

- Hold, read in the code: B1-1 (no Contacts path where isDrawerNavigation, since c5fc4f85 on 2026-09-11), B1-2 (no RailItem.Actions case; App.kt passes no onOpenHome), B1-3, B1-6, B1-7 (no verticalScroll in the four), B1-4, B1-5 (title and tap; the author is unverified), B2-1, B2-3, B2-4, B2-5, B2-7, B2-10, B4-1 (run locally: gdbus prints usage for a bare "-1" and exits 1, and "--" fixes it), B4-2.
- B2-11 moved to decisions: the 11-minute wait is deliberate.

### Area B3, Android services, permissions and lifecycle

- B3-1 Med-High bug (security): the exported MainActivity places a call for any caller's `call_back` extra and answers a ring for any caller's `answer_from` (`MainActivity.kt:293-315`, used at `TalonApp.kt:582, 310`). Lead: holds; MainActivity is exported as the launcher and has no gate. Fix: notification PendingIntents go to a non-exported activity-alias, and the call extras count only through it. No UX change.
- B3-2 Med, DECISION (security and product): a forged direct share with any EXTRA_SHORTCUT_ID uploads and posts an image or file to that conversation as the owner (`MainActivity.kt:354-356`, `TalonApp.kt:1190-1196`). Options: every direct share goes to the composer; or accept only ids among the published shortcuts.
- B3-3 Med bug: without push, a ring is never shown when Talon is only in the background, since `Notifications.showIncomingCall` is called from the push receiver alone and `onShowIncomingCallUi() = Unit`. Lead: holds for that case. Fix: post it from TalonApp's callState effect when Incoming and the activity is not started. After a reboot or a swipe there is no call controller at all: DECISION (an app-level controller, or saying calls ring only while Talon is open).
- B3-4 Low-Med bug: the old repo's second stop() marks the new repo's stream disconnected after any ship switch (`TlonChatRepo.kt:1037-1043` vs `:566`), so CatchUpWorker retries forever without push. Lead: holds. Fix: only a started repo clears the flag.
- B3-5 Low-Med bug (multi-ship): a ring for a ship that is not active cannot be answered from its notification; the ring intents carry no ship (`Notifications.kt:328-335`). Fix: EXTRA_FOR_SHIP on Answer and Show, switching first. Decline from the other ship is unconfirmed.
- B3-6 Low-Med bug: with push and mail, the sync service outlives the swiped activity though mail polling stopped with it (`TalonApp.kt:1236-1244`).
- B3-7 Low bug (battery): LeaveAlarm's 30-minute poll is never cancelled when Orrery goes off (`LeaveAlarm.kt:58-63`).
- B3-8 Low bug: BootReceiver has no MY_PACKAGE_REPLACED, so an in-app update leaves a phone without push with no stream until opened.
- B3-9 Low polish: the retired "calls" and "daily-digest" channels are never deleted (`Notifications.kt:47, 176`).
- B3-10 Low, DECISION: loops use inexact alarms; USE_EXACT_ALARM is already declared for the leave alarm. Using it for loops is a permission-use choice.
- B3-11 Low bug (multi-ship): ShipPushes.ensure runs only for the ship active at process start (`TalonApplication.kt:314`).
- B3-12 Low, DECISION (UX): tapping a missed-call notification's body dials at once. Recommendation: the body opens the DM, Call back stays an action.
- B3-13 Low bug: TalonApplication's appScope has no exception handler, so one throw kills the app (`TalonApplication.kt:125`).
- B3-14 Low bug (multi-ship): pinned conversation shortcuts carry no ship (`ShortcutsPublisher.kt:84-89`).
- B3-15 Low (battery): CatchUpWorker is scheduled every 15 minutes even with push (`TalonApplication.kt:303`).
- B3-16 Low, DECISION: without push, a lapsed login leaves "Listening for new messages" up with nothing telling the owner. Option: a "Sign in again" notification.
- B3-17 Low (ship cost), HELD: background repo starts from loops and CatchUpWorker are never stopped. Stopping them is unsafe while the activity may share the repo; needs a design.
- False positives (reviewer's): PendingIntent request codes, request code 0 sharing, the camera FGS type, the boot start on Android 15, TripService, LocationWatch and HealthWatch, the clock widget, catch-up with no channel, the onScreen check, the sticky restart, DictationService's starts, LoopWorker below 12.
- Needs a device (reviewer's): the 45 s ring stop under the freezer on 16.1+; background writes in a cached process; the in-app ring tone in the background.
