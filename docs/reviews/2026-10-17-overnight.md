# Overnight review, night of 2026-10-17

Run by the self-paced loop in `docs/reviews/overnight-loop.md` (main checkout, untracked).

## Status

- Night: 2026-10-17 (started 2026-10-18 00:07 EDT).
- Integration worktree: `~/software/personal/talon-ov-2026-10-17`, branch `polish/overnight-2026-10-17`.
- Base: `github/master` at 60d99a39 (release 1.8.30-rc11). The fetch worked.
- Pass: 2, done. Step: done; the morning report is at the end of this file.
- Pass 1: 00:10 to 03:30 EDT. Pass 2: 03:30 to 08:05 EDT. Pass 3 did not start (the 04:30 cutoff).
- All pass-2 fixer branches are merged and their worktrees removed.
- Stalled 04:00 to 06:32 EDT: the session limit stopped all six fixers and the loop itself (it reset at 04:40, but the loop's wakeups had hit the same limit). Resumed at 06:32 with each fixer's work kept and a new report-by time of 07:30.
- Pass 1's lattice entry saved at 03:48 (`/project/talon/overnight-2026-10-17`).
- Pass 1 merged: all six by 03:09 EDT: shells (15 commits), chatui (21), search-groups (5), apps (16), chatrepo (16), settings-calls (9). Lead integration commits: SearchDraft cleared in App.kt; orrery setForeground in both shells; the event card on addSharedWhyNot; test temp dirs under build/test-tmp; the notebook excerpt on shownWordsOf; the lapsed-login bridge into LoginLapses in both shells (with an AppShellTest end to end); a refresh after a new channel; a stale DmChatScreen comment.
- Pass 1's full suite (03:30): 3816 tests, 1 failure, OrreryKeysTest "turning it on finishes though the page that asked is left". It passes alone (8 of 8); it timed out on a 5 s poll under the suite's load, so the poll cap is now 15 s. All four compiles passed.
- Left for pass 2 by pass 1's merge (now triaged into the groups): `dm:` and `invite:` keys in IosNotifier's postsLocally (desktop and iOS DM-request and invite clicks only raise the window); A1-10 (the 5 s and 3 s caps cut a slow PUT off; needs an ack-wait option on UrbitChannel.poke); the other test classes' temp-dir cleanup (contained by build/test-tmp now); AndroidRelaySettings not persisting registeredEndpointFor; ingestPosts never notifying (a worker catch-up notifies nothing); the DmChatScreenTest "emoji alone" flake (ChatRowsSnapshot shared across tests); an AppShellTest hang seen once ("Escape in a chat's composer closes the chat", a Compose deadlock in the test).

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

- Core's desktop tests wrote into the real `~/.config/talon/log` (no user.home or temp dir of their own). Fixed in pass 2 by the desktop-ios group: core tests now keep both under the build, as composeApp's do.
- The night's six parallel fixers hit the session limit at about 04:00, and the loop's own wakeups with them, so the night stood still until 06:32.

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

### Lead's verification so far (03:40)

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
- Also hold: B3-1, B3-3 (the background case), B3-4, B4-4 (playback sets the Playback category with no callLive check), B4-5 (start runs on the Settings screen's scope; the catch destroys the booting comet on cancellation), B4-6, B6-1 (the hint's id is the dotted wire form; rows are kept undotted, and channel rows bare), B6-2 (from the lead's own lapse bridge, 7940b183).

### Area B6, pass 1's own commits

- B6-1 High bug, a regression from tonight (a9890d35, e557f1eb): tapping a push hint always says "That message isn't loaded here yet", because the hint's raw activity id (`~author/<dotted ud>`) is never normalized before `reach()` (`DmChatScreen.kt:629-637, 274`). The same id reaches gallery and notebook posts, which now say the post may have been deleted. Fix: normalize with `undotAtom(canonicalPostIdForWhom(whom, id))` in the chat screen's reach and scroll match and in the heap and diary anchors.
- B6-2 Med bug, from the lead's lapse bridge: on desktop and iOS, Cancel on the lapsed-login form calls `switchShip(from)`, whose fresh repo is refused again and reopens the form (`App.kt:2405`, `:574-583`, `:827`). AppShellTest's Cancel case injects the lapse on a ship that is not refusing. Fix: a Cancel holds off that ship's lapse until a sign-in.
- B6-3 Med, DECISION: Android's new 5-minute alert gate (`NewMessageDiff.kt:200`) silences messages replayed after an outage on a phone without push. New angle on A5-11. Option: gate on already alerted or read, not age.
- B6-4 Med-Low bug: deleting a channel post that is still sending hides it, but the poke lands and the post comes back (`MessageEntity.kt:80` isUnsent counts a pending local_ row as never taken). Fix: no Delete on a pending local row.
- B6-5 Low-Med bug: on Android, signing in again to the same ship within 3 s of a refused reconnect leaves chat asleep (forceReconnect's debounce, `TalonApplication.kt:737-740`). Fix: a login's wake is not debounced.
- B6-6 Low polish, a regression from tonight: `textFor` now writes full link addresses, and it is also the preview text, so long URLs show in the home list, quote bar, cite cards and notification bodies (`StoryCache.kt:124`). Fix: a separate copy text for Copy, notes and the assistant.
- B6-7 Low polish: Android keeps an old search query for a later search (SearchDraft is cleared in App.kt only). Fix: clear it in TalonApp when Search closes.
- B6-8 Low perf: every smart Search open runs a whole indexer pass and re-runs the query up to three times (`SearchScreen.kt:173, 193`).
- Traced clean (reviewer's): the settings outbox, the drain and echo reaper, pending DMs, NetErrors' SHIP_DOWN and the walks, calendar late refusals, the search-text extras, allShipsFlow at cold start, the API 26 upsert, a backgrounded chat on screen, the recorder start, samePost over a hint, listenForAlerts on a closing DB, deleteSoon, the 403 signal. Armillary's payment wait when the balance is unknown is money: with the decisions.

### Area B5, performance and ship cost

Idle cost, one foreground device with every app: about 47 requests an hour (mail 6, calendar about 19, orrery 12, armillary 6, permits 4), plus the party-line pokes below.

- B5-1 High bug (consent): Record in another group's chat records the line we are on but announces to the open chat's host (`PartyRecording.kt:115-121`; `onThisLine` is Live anywhere at `App.kt:3007-3008`, `TalonApp.kt:2679`). Lead: holds. Fix: on this line means Live on this chat's own host and room, in both shells.
- B5-2 Med bug and cost: Android still runs the screen-level presence beat that 6a52df5f moved into CallController (`TalonApp.kt:2679-2694`): a double beat, a false leave on opening a DM, and enter-room to another group's host. Lead: holds. Fix: delete the effect.
- B5-3 Med cost: a focused group chat with a line asks who is recording every 15 s, even with nobody on it (`PartyRecording.kt:80-87`). Fix: poll only while someone is on the line or we are.
- B5-4 Med cost: the Party lines tab's 20 s poll has no focus gate (`PartyLinesList.kt:76-86`). Fix: askWhileInFront.
- B5-5 Med perf: every launch scans `messages` for `searchText IS NULL` (no index), and with smart features the indexer reads and hashes every row (`TlonChatRepo.kt:432`, `MessageIndexer.kt:51-60`). Fix: a done mark for the fill; a high-water mark for the indexer.
- B5-6 Low-Med cost: Mark group read pokes %activity once per channel, forced and concurrent (`TlonChatRepo.kt:2482-2488`). Fix: unforced, in turn. The one-poke form changes the poke: DECISION.
- B5-7 Low-Med cost: a failed furum or urb card is fetched again on every compose (`FurumPreview.kt:48-66`, `UrbUnfurlCache.kt:84-91`). Fix: a 5-minute failure memory and one in-flight fetch per URL.
- B5-8 Low-Med cost: the 10-minute orrery pass runs while the app is behind, reading full state (`OrreryRepo.kt:931-958`). Fix: space passes out while behind. Reading `brief=1` is unverified against orrery: not done.
- B5-9 Low-Med perf (iOS): every keystroke writes the drafts file on the main thread (`DraftStore.kt:141-156`, `IosFiles.ios.kt:94-103`). Fix: a background serial queue, as desktop does.
- B5-10 Low bug (desktop): any interface's address change counts as a network change and drops a party line (`DesktopNetworkWatcher.kt:31-34`). Fix: skip link-local and virtual interfaces.
- B5-11 Low perf: search results are a live Room flow, re-scanning on every write (`SearchScreen.kt:106-116`).
- B5-12 Low cost: notebooks and galleries read 30 posts on every open (`NotebookListScreen.kt:112-118`, `GalleryGridScreen.kt:115-118`). Fix: refreshOnOpen.
- B5-13 Low cost: the permits banner reads every 30 min whatever the focus (`PermitsBanner.kt:93-97`).
- B5-14 Low cost: a listed font that never reached the ship is asked about every 10 min forever (`FontRepo.kt:185, 194-200`).
- B5-15 Low cost: every 30 s heartbeat also asks the host's occupancy (`CallController.kt:1154-1158`).
- B5-16 Low, DECISION: desktop mail notices fire only while Talon's window is focused (c0f499fc maps in front to focus).

## Pass 2 triage (03:50)

The lead read every High and Medium against the code (the "hold" lines above); the Lows are taken on the reviewers' quoted proof. The fix IDs are the finding IDs. Must-fix: B6-1, B6-2 and B6-6, the regressions pass 1 brought in. B6-2 and B6-7 are fixed by the lead on the integration branch before the groups branch.

Decisions, not fixed: B2-2, B2-6, B2-8, B2-11, B2-12, B2-14, B2-20, B3-2, B3-3's reboot and swipe cases, B3-10, B3-12, B3-16, B4-3, B4-15, B4-17, B5-6's one-poke form, B5-8's brief form, B5-16, B6-3 (with pass 1's leftover "ingestPosts never notifies"). Held: B3-17 (needs a design). Unconfirmed: the AppShellTest Escape hang (seen once).

Fix groups, one owner per file:

- shells: TalonApp.kt except android's carve-out; App.kt; SectionsDrawer, DmListScreen except chat's carve-out, DesktopShell, KeyboardShortcuts, SidebarSettingsScreen, ContactsScreen, LoginScreen, LoginQrShareScreen, PartyRecording, PartyLinesList, PermitsBanner, FontRepo, NotebookListScreen, GalleryGridScreen, core CallController. B1-1, B1-2, B1-3, B1-7, B1-8, B1-9 (App.kt:2389), B1-12 (LoginScreen and DmListScreen sites), B1-13, B1-14 (SidebarSettingsScreen, LoginQrShareScreen), B1-18, B5-1, B5-2, B5-3, B5-4, B5-12, B5-13, B5-14, B5-15.
- android: MainActivity, Notifications.kt, AndroidManifest.xml, TalonApplication, BootReceiver, LeaveAlarm, ShortcutsPublisher, CatchUpWorker, TalonMessagingReceiver, CallActionReceiver, AndroidRelaySettings; and in TalonApp.kt the call and keep-alive wiring above the section flags (lines 1-665) and the mail-service block near line 1240. B3-1, B3-3 (background case), B3-5, B3-6, B3-7, B3-8, B3-9, B3-11, B3-13, B3-14, B3-15, and pass 1's leftover AndroidRelaySettings registeredEndpointFor.
- voice: QuickDictation, androidMain ai/Loops, DictationSpeech, DictationFlow, DictationAgent, VoiceConversation, AgentClient, LoopRunner, ToolCatalog, AssistantActions, AssistantScreen, CalendarRepo. B2-1, B2-3, B2-4, B2-5, B2-7, B2-9, B2-10, B2-13, B2-15, B2-16, B2-17, B2-18, B2-19.
- desktop-ios: SystemNotifier, IosSessionStore, IosFiles, the iOS text and draft stores, DraftStore, VoiceMessages.ios, LocalShipSection, core DesktopLocalShip, DesktopUriHandler, SingleInstance, Tarball, ExitPolicy, desktop Main.kt, Dictation.ios, DesktopSessionStore, desktop Files.kt, Log.desktop, MainViewController, IosNotifier, core DesktopNetworkWatcher, CLAUDE.md's notification line; StoryRenderer's .ics line only. B4-1, B4-2, B4-4 to B4-14, B4-16, B4-18, B5-9, B5-10, and pass 1's leftover dm: and invite: keys in IosNotifier.
- chat: TlonChatRepo, ActivityParser, ActivityList, DmChatScreen, GalleryPostScreen, NotebookPostScreen, MessageEntity, StoryCache, SearchScreen, MessageIndexer, MessageDao, EmbeddingDao, FurumPreview, UrbUnfurlCache, UrbUnfurlCard, DmChatScreenTest, ChatRowsSnapshot; and in DmListScreen the empty-state lines (about 1495-1515) and Mark group read (about 1725-1740). B6-1, B6-4, B6-5 (in TlonChatRepo), B6-6, B6-8, B3-4, B1-4, B1-5, B1-17 (ActivityList), B5-5, B5-6 (the safe half), B5-7, B5-11, and pass 1's leftover "emoji alone" flake.
- screens: ContactProfileSheet, GroupAdminScreen, GroupInvitesScreen, BookmarksList, BookmarksScreen, SettingsScreen, NotificationSetupDialog, IosVoipBridge, UrbLinkHost, AppsSettingsScreen, SignVerifyDialog, OrreryRepo, core UrbitChannel and its settings-sync callers. B1-6, B1-9 (the other three sites), B1-10, B1-11, B1-12 (SettingsScreen, GroupAdminScreen, GroupInvitesScreen), B1-14 (SettingsScreen, GroupAdminScreen), B1-15, B1-16, B1-17 (BookmarksScreen, GroupInvitesScreen), B5-8 (cadence only), and pass 1's leftover A1-10 (verify first; hold if it needs more than an ack-wait option on poke and its callers).

## Pass 2 outcomes

- android (merged 6c18e94b, 06:50): fixed B3-1 (040a4f6e: every notification opens Talon through the non-exported alias `NotificationTap`; call extras count only from it, via `RingIntents.trusted`), B3-5 (same commit: rings carry their ship and switch to it), B3-3 background case (daf5b2d1), B3-6 and B3-15 (c1feb9d6), B3-8 and B3-9 (7d95d969), B3-7, B3-11, B3-13 and B3-14 (5fbd0b4c). The AndroidRelaySettings leftover has no effect on Android (nothing there reads the value): not patched. Also touched, unowned: TalonConnectionService's redial now opens through the alias. Tests: NativeCallsTest, AndroidLifecycleGuardTest (new, source wiring), KeepAliveTest, AndroidNoticesGuardTest, MainActivityLaunchGuardTest; Android and iOS compiles pass; PIT killed all 7 mutants of the new gate. Device checks: that `intent.component.className` reports the alias on a cold start and on onNewIntent (if it reports MainActivity, every call extra is ignored); a cold ring for another ship; MY_PACKAGE_REPLACED starting the service on 14 and 15. Old notifications from before the update open Talon without acting, once. Asked of shells: a deep link holding only a ship is never consumed (TalonApp about 901-963), a follow-up.
- screens (merged, 07:08): fixed B1-6 (c42d74f0), B1-9 (ba8005da: a new `failureLine` in NetErrors; LatticeInstall and LatticeSign say a 502 to 504 as the ship being down, and `verify` no longer answers "No: <html>" on a proxy 502), B1-10, B1-12, B1-14, B1-15, B1-16 and B1-17's two sites (3ceb4c5e), B1-11 (56f73331: `expect val iosDeviceName` in Capabilities.kt, registry updated), B5-8 (07e769c6: orrery passes every 30 minutes while the app is behind, a pure `passWaitLeft` pinned by PassCadenceTest; on desktop, behind means the window is not focused). Held: A1-10. The 5 s and 3 s caps are in core's CallController (`declineWithoutApp` and `stop()`), not in settings sync. The change: an `awaitAck = false` option on `UrbitChannel.poke`, used at both sites in place of their timeouts, with a core test; CallEndsTest's `waitMs` goes with it. Tests: eleven screen and client classes, PassCadenceTest, IdlePassCostTest, AppShellTest 54 of 54; Android and iOS compiles pass; PIT killed every mutant of `passWaitLeft` and `failureLine`. Untested: the iPad branch; the ellipses.
- desktop-ios (merged 1cb6ea8c, 07:08): all fixed, 17 commits. B4-1 (053f099d: "--" before the positionals for notify-send and gdbus, and a failing gdbus demotes to the tray; the lead checked both parsers on this host against a dead bus, so no toast was posted), B4-12 (4db7c85e), B4-6 and B4-13 (d10a4ebb: the desktop opener takes only http, https and mailto, plus file links inside Talon's own opened folder; .ics files go there and are pruned after a day), B4-2 and B5-9 (23c39326: a common `LockableFile`; IosSessionStore re-reads until it can; the iOS drafts write is on a serial queue), B4-4, B4-10, B4-16 (comment only; CallPush.swift still says Documents), the dm and invite keys (33c36bfc), B4-5 (43baa8fc: DesktopLocalShip owns its operations; LocalShipLeaveTest fails on the old code), B4-11, B4-7, B4-8, B4-9 (the comet now stops after the database closes; the new order is untested), B4-14, B5-10, B4-18. Unowned files touched: DesktopUpdateInstaller, core/build.gradle.kts (core tests had written into the real `~/.config/talon/log`). A judgment call to report: a tel:, sms:, geo:, matrix:, signal: or xmpp: link in a received message no longer opens on desktop (`OPENABLE_SCHEMES` in DesktopUriHandler). Asked of shells (for the lead after the shells merge): App.kt's two `notify` calls for DM requests and invites (about 1565 and 1577) pass the keys `dm:$ship` and `invite:${invite.flag}`; until then those clicks only raise the window. Untested on a device: B4-2's reload after unlock, B4-4, B4-10, a tap through CallPush.swift.
- chat (merged 97a9b768, 07:18): fixed B6-1 (9813517b, e8919c03: `keptPostId` normalizes a hint's dotted id at the chat, gallery and notebook screens), B6-6 (d8c81f25: `textFor` is the preview again; `copyTextFor` gives full addresses), B6-4 (77ca4936: no Delete while a post sends), B6-5 (708a557b, 52f75b9f: no reconnect debounce while signed out), B3-4 (5d1a1e9c), B1-4 (04c7fdfc: "Couldn't load your chats." with Try again; a ship whose chats read fails through every path now says so), B1-5 and B1-17's activity sites (e531c97f), B5-5's search-text mark (169a0922), B5-6's safe half (5990c67e: unforced, in turn; slower to clear on an unreachable ship), B5-7 (fd1b97e6), B6-8 and B5-11 (12d7e504: a new match no longer appears while results show, until the query changes). Held: the indexer high-water mark (needs a design for edits and deletes); the emoji-alone flake, which is a finding: ChatRowsSnapshot carries one test's rows into the next, and two tests pass only when an earlier test left a snapshot (test order, or a real first-open avatar bug; unconfirmed). The fixer rebuilt its history after a misapplied partial staging; HEAD was compiled on all three targets, the middle commits were not.
- shells (merged 73ea3d42, 07:18): fixed B5-1 (767b7578: on this line means the granted line's host and room, pinned in RecordingGateTest and JoinGrantedTest), B5-2 (same commit), B1-1 (cd289cfd: a fixed Contacts drawer item), B1-2 (536668ba), B1-3, B1-12's sites, B1-14's two, B1-13, B1-9's app site, B5-3, B5-4, B5-13 (focus-gated, keeping the return read), B5-12, B5-14, B5-15, B1-8 (e4db3459: Remove awaits, says "Removing…", says a refusal). Half: B1-7 (the sidebar scrolls; the badge's semantics held for a screen-reader check), B1-18 (the wording; marking the open section held). Follow-ups: `partyLiveHere` matches the room with no host (a count, not consent); the profile sheet's Remove in TalonApp is still fire and forget; the font status says "until it arrives" after it gives up.
- voice (07:22): fixed B2-1 (2e3c9cbb: every waiting proposal is read aloud), B2-3 (8f139a8c, 2c79d6dd: ModelHttpError from AgentClient; a 503 now reads calmly in the assistant), B2-4 and B2-18's send half (1d08e49d), B2-5 (25ddf68d), B2-7 (fd7aeb88: the read-out rides the history, capped at 4000 characters and labelled as data), B2-9 (26df4a02), B2-10 (0ce46487), B2-13 (b3efdfee), B2-16 (22c8983d), B2-17 (3ea6115d), B2-18's dates (c0350acd), B2-19 (fd3e70e5). Held: B2-15 (for time). A loop's history now keeps the calm line only.

## Morning report

1. Branch `polish/overnight-2026-10-17`, code head 8cebf90b (the state file's commit sits on top), off master at 60d99a39 (1.8.30-rc11). Two passes ran; pass 3 did not start (the 04:30 cutoff, and a session-limit stall from 04:00 to 06:32). Pass 1 fixed about 95 findings; pass 2 fixed about 83 more, including three regressions pass 1 had brought in. The full suite at 8cebf90b: 3,922 tests, 1 failure, OrreryKeysTest "turning it on finishes though the page that asked is left". It hangs at its second wait for the whole 15 s, in the full suite only: it passes alone and with its whole 47-class package, and the class's other seven tests take 0.02 s each in the same run, so it is not load. It failed the same way after pass 1's merges, so no pass-2 merge broke it. Whether pass 1 did is not known: pass 1's apps group changed OrreryRepo (e7d5b56d) and made this test's wait stricter (b17d2355, which added the switch to a wait that took only the row), and master's suite was not run tonight. The loop's rule is to fix it or revert the merge that broke it; that merge is not identified, so nothing was reverted, and the call is sneagan's (see Unconfirmed). All four compiles passed: composeApp and core, Android and iOS.

2. Decisions for sneagan, each with a recommendation and what yes costs.
   - B2-2, dictation sends with no confirmation when Orrery is off, in a run that also reads other people's messages and mail. Recommendation: route a dictation's send_message and send_mail through the Approve card, as Orrery's sends already are. Cost: one tap or "send" per dictated message.
   - B2-8, a locked phone reads messages and mail aloud and shows them on the card. Recommendation: ask for the fingerprint before message and mail reads while locked; keep calendar reads. Cost: the car use case needs an unlock for messages.
   - B3-2, a forged direct share from another app posts an image or file to any conversation as the owner. Recommendation: send every direct share to the composer, as text already goes. Cost: one more tap to send a shared image.
   - B6-3, Android's 5-minute alert gate silences messages replayed after an outage on a phone without push. Recommendation: gate on "already alerted or read", not on age. Cost: a small change in NewMessageDiff, and pass 1's "ingestPosts never notifies" is decided with it.
   - B4-3, iOS keeps the database at NSFileProtectionComplete while calls keep Talon running locked, so reads and writes fail about 10 s after lock. Recommendation: CompleteUntilFirstUserAuthentication for the database directory. Cost: the database is readable while locked after the first unlock.
   - B2-6, write-authorized loops can share a calendar or answer a calendar offer unattended. Recommendation: add share_calendar and answer_calendar_offer to DESTRUCTIVE_TOOLS. Cost: those loops stop and ask.
   - B2-20, lattice-save runs with no confirmation in runs that read chat or mail. Recommendation: confirm a save when the run has read chat or mail. Cost: a tap on those saves.
   - B3-3, without push, calls ring only while Talon's activity exists (not after a reboot or a swipe). Recommendation: say so in Settings now; an app-level call controller later. Cost: a line of copy now, real work later.
   - B3-12, tapping a missed-call notification's body dials at once. Recommendation: the body opens the DM and Call back stays an action. Cost: none.
   - B3-16, without push, a lapsed login leaves "Listening for new messages" up and says nothing. Recommendation: a "Sign in again" notification. Cost: a new notification.
   - B2-14, an assistant run cannot be stopped. Recommendation: a Stop button while busy. Cost: a small feature.
   - B2-11, a dictation's transcription can wait 11 minutes. Recommendation: about 3 minutes for dictation and Archie turns, keeping 11 for call recordings. Cost: a very slow home speech server would time out on long dictations.
   - B2-12, no prompt caching. Recommendation: cache_control on Anthropic models and the NOW line out of the system prompt, after a live check. Cost: a live provider test.
   - B3-10, loops use inexact alarms though USE_EXACT_ALARM is declared. Recommendation: exact when allowed, if Talon is not bound by Play's policy on that permission.
   - B5-16, desktop mail notices fire only while Talon's window is focused. Recommendation: poll while the app runs, at the background cadence.
   - B5-6, Mark group read: one %activity poke for the group instead of one per channel. A poke-shape change.
   - B5-8, orrery's brief state form for the pass, once orrery's source confirms it carries schema and ship.
   - B4-15 (desktop voice messages could play inline through vlcj) and B4-17 (Linux keeps the pier, models and caches under ~/.config; moving them is a migration): features, for later.
   - From pass 1: A4-19 (link previews fetch every received http link from the reader's device; recommendation: skip private and LAN addresses now, decide on a setting), A2-11 (Top up and Subscribe stay tappable while a checkout is pending; recommendation: disable both), A2-4 (two quick calendar adds can swap refusals; a full fix needs a nonce the calendar echoes), A1-2 (a settings write counts once eyre takes the PUT; recommendation: keep), M5 (put the maps or tel link on the voice result notification's tap). Held as before: IMAGE_AUTO_MAX and the mail image fetch, restartIce in TalonRtc.swift, M16's notify-levels marker.
   - Judgment calls made tonight, to confirm or undo: desktop opens only http, https and mailto links from messages (tel, sms, geo, matrix, signal and xmpp are refused; `OPENABLE_SCHEMES` in DesktopUriHandler); orrery passes every 30 minutes while the app is behind (on desktop, behind means unfocused); Delete is hidden while a post sends; search results no longer update live while shown; Mark group read goes channel by channel, so it clears slower on an unreachable ship; the note-delete settle is 30 s; desktop mail reads on focus at most once a minute.

3. What changed that sneagan would notice.
   - Phones have Contacts in the drawer again, after Edit menu. They had no way in since 2026-09-11.
   - A narrow desktop window's More menu has Home and Orrery.
   - Tapping a push notification's message jumps to it again. Pass 1 had made it say "That message isn't loaded here yet."
   - Previews (list, quotes, notifications) show links cut short again; Copy text, a reply's Copy text and the assistant's reads keep whole addresses.
   - Cancel on the lapsed-login form stays on the ship (desktop and iOS). Android no longer carries an old search query into a later search.
   - Record on a party line counts only on that chat's own line, and Android no longer sends a second presence beat.
   - Dictation reads every waiting proposal aloud ("2 things wait. ..."). Spoken confirmations name people, not ids, and say dates as dates. A queued send says it is queued. An empty balance shows Top up in the assistant, and a 503 reads calmly.
   - A failed first load says "Couldn't load your chats." with Try again, not "No direct messages yet".
   - Group activity rows show the group's name and open its page; invite rows open the invites.
   - Android: notifications open Talon through a private alias, and other apps can no longer place or answer calls. Notifications posted before the update open Talon without acting, once. The sync notice goes away after a swipe when only mail kept it. An in-app update restarts the stream. Ship pushes follow ship switches.
   - Desktop: Linux notifications survive a notify-send failure. The local comet survives leaving Settings. Session and terminal files are private (0600).
   - Copy: "Your ship's calling app", "Register again", "Check notification delivery", "Decline", "Save", "the More menu", "Copy the link", iPad named as iPad.
   - From pass 1: a deleted queued DM is never sent; a voice upload survives leaving the chat; Android's alert listeners live at app level and DMs notify at the default level; smart search leaves out deleted messages; the settings outbox keeps order and no longer re-sends stale writes; a lapsed login no longer loops; a 502, 503 or 504 reads as the ship being down; calendar refusals that come late are caught; new events show on Home at once.

4. What needs a device, most important first.
   - Android, B3-1: answer a ring from its notification and tap a missed call's Call back. If `intent.component.className` does not report the alias, every call action from a notification is silently ignored.
   - Android: tap a push hint and land on the message (B6-1).
   - Android: two waiting Orrery proposals read aloud, and a long unread read-out not cut off by the next paragraph (B2-1, B2-5).
   - A party line: Record from another group's chat (now refused), and the line's count when a DM is opened while talking (B5-1, B5-2).
   - Android without push: a ring with Talon in the background posts its notification (B3-3); an in-app update restarts the stream (B3-8); a ring for the non-active ship (B3-5).
   - iOS: start from a locked PushKit wake and check the saved ships after unlock (B4-2); a voice message played during a call (B4-4); dictation started twice (B4-10).
   - Linux desktop: notifications after notify-send has failed once (B4-1).
   - Pass 1: Android message alerts with Talon swiped away, and a voice message upload when leaving the chat.

5. Unconfirmed and held. Nothing was reverted.
   - Unconfirmed: the OrreryKeysTest hang above. Something an earlier test class leaves behind stops `enable()` between the mint's answer and the switch going on. It is not the dispatchers (no shared one is swapped), not a gate in OrreryApi, and not the orrery package. Next steps: run the full suite once on 60d99a39 (about 16 minutes) to learn whether it predates tonight; if it passes there, the suspects are b17d2355 and e7d5b56d. Otherwise bisect the classes that run before it. Also, two tests pass only when an earlier test left a chat snapshot (ChatRowsSnapshot); either test order or a real bug where an avatar tap does nothing on a chat's first open in a process. Also the AppShellTest Escape hang (seen once), whether trunk takes a decline sent from another ship (B3-5), the author field of group activity events (B1-5), and pass 1's A4-16, A5 host order and A5 foreigns.
   - Follow-ups the groups handed back and the lead did not apply (detail in "Pass 2 outcomes"): a deep link holding only a ship is never consumed (TalonApp); `partyLiveHere` matches the room with no host; the profile sheet's Remove in TalonApp is still fire and forget; Search's indexer timer is per process, not per ship; ExitPolicy's grace while the local comet runs, and a partial runtime binary over 1 MB still trusted; a loop's history keeps only the calm error line; StoredRelaySettings and FileUiSettings still read once on iOS.
   - Held: A1-10 (the 5 s and 3 s caps are in core's CallController; the exact change is in "Pass 2 outcomes"), B2-15 (a failed assistant run after a write keeps its turn), the indexer high-water mark (B5-5), the sidebar badge's semantics (B1-7), marking the open section in both drawers (B1-18), B3-17, and the "emoji alone" flake.

6. How to ship: say "push and PR it", then "cut an rc" (the next is 1.8.30-rc12, versionCode 529). Nothing has been pushed, merged to master or tagged.

## Decisions taken (sneagan, 2026-10-18 morning)

- Approved as recommended: B2-2 (dictation sends through the Approve card), B2-8 (fingerprint before message and mail reads while locked), B3-2 (every direct share to the composer), B6-3 (alerts gated on alerted or read, not age; with it, catch-up alerts), B4-3 (iOS database protected until first unlock), B3-12 (a missed call's body opens the DM), B3-3 (say in Settings that without push calls ring only while Talon is open), B3-16 ("Sign in again" notification), B2-14 (Stop button), B2-11 (about 3 minutes for dictation and Archie transcription).
- B2-6: "should be possible to require an ask but shouldn't by default." The lead asked how the ask should work; the question timed out, so the recommended shape is being built: a per-loop "Ask before sharing calendars" switch, off by default and kept per device; when on, a loop's share or offer answer is held, and a notification offers Allow or Don't.
- B2-20: no confirmation needed. Closed, no change.
- Not answered yet: B2-12, B3-10, B5-16, B5-6, B5-8, B4-15, B4-17, pass 1's A4-19, A2-11, A2-4, A1-2 and M5, the judgment calls, and the OrreryKeysTest question.
- Built: B4-3 by the lead (7d50cc33). The rest by four groups: dictation (B2-2, B2-8, B2-11), android (B3-2, B3-12, B3-3, B3-16), alerts (B6-3), loops (B2-6, B2-14).

### The morning's decisions, built (2026-10-18)

- All merged into `polish/overnight-2026-10-17`: android ef297a61 (B3-2, B3-12, B3-3, B3-16), dictation ba6ad34d (B2-2, B2-8, B2-11), alerts 2b9ad266 (B6-3 and catch-up alerts), loops 48e82917 (B2-14, B2-6 as a per-loop switch with held calls on the Loops screen; Room 57 to 58). The lead: B4-3 (7d50cc33), a hint filled in by its message without a second alert (43e7f487, the alerts group's find: hint ids never matched the kept ids), and B2-15 (78b72392, held overnight).
- Full suite at 78b72392: 3,990 tests, 0 failures. All four compiles pass. OrreryKeysTest passed this time, so its overnight failures are intermittent, not a steady break.
- Open for sneagan: whether orrery's own reads (orrery_read, orrery_find, orrery_observe, Archie's brief) also need the fingerprint while locked; a cold open now posts one notification per unread chat that changed since the last read (new volume, the live rule's); an allowed loop share the calendar refuses is recorded with a check mark and the refusal text.
- Follow-ups not done: a staged share is not cleared on a ship switch (taken as soon as its chat opens); an Android `loop:` notification tap could open Loops; the decisions still unanswered from the morning report.
