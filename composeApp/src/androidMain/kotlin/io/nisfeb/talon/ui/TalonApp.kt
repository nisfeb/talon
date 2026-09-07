package io.nisfeb.talon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.compose.runtime.rememberCoroutineScope
import io.nisfeb.talon.Notifications
import io.nisfeb.talon.ShareIntent
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.TalonSyncService
import io.nisfeb.talon.ui.RailItem
import io.nisfeb.talon.ui.BatteryExemptionBanner
import io.nisfeb.talon.ui.CalendarLauncher
import io.nisfeb.talon.ui.InlineAudioPlayer
import io.nisfeb.talon.ui.InlineVideoPlayer
import io.nisfeb.talon.ui.LocalCalendarLauncher
import io.nisfeb.talon.ui.LocalInlineMediaPlayer
import io.nisfeb.talon.ui.LocalMapsLauncher
import io.nisfeb.talon.ui.MapsLauncher
import io.nisfeb.talon.ui.MediaKind
import io.nisfeb.talon.ui.VoiceRecordButton
import io.nisfeb.talon.ui.rememberAutofillModifier
import io.nisfeb.talon.ui.rememberLocationProvider
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.ui.screens.ShareTargetScreen
import io.nisfeb.talon.ui.screens.DmChatScreen
import io.nisfeb.talon.ui.screens.DmListScreen
import io.nisfeb.talon.ui.ContactProfileSheet
import io.nisfeb.talon.ui.screens.ImageViewerScreen
import io.nisfeb.talon.ui.screens.LoginScreen
import io.nisfeb.talon.ui.screens.SettingsScreen
import io.nisfeb.talon.ui.screens.SidebarSettingsScreen
import io.nisfeb.talon.ui.screens.NewDmScreen
import io.nisfeb.talon.ui.screens.ProfileEditScreen
import io.nisfeb.talon.ui.screens.SearchScreen
import io.nisfeb.talon.ui.screens.ActivityFeedScreen
import io.nisfeb.talon.ui.screens.GroupAdminListScreen
import io.nisfeb.talon.ui.screens.GroupAdminScreen
import io.nisfeb.talon.ui.screens.GroupHomeScreen
import io.nisfeb.talon.ui.screens.GroupInvitesScreen
import io.nisfeb.talon.ui.screens.GalleryComposeScreen
import io.nisfeb.talon.ui.screens.GalleryGridScreen
import io.nisfeb.talon.ui.screens.GalleryPostScreen
import io.nisfeb.talon.ui.screens.NotebookComposeScreen
import io.nisfeb.talon.ui.screens.NoteScreen
import io.nisfeb.talon.ui.screens.NotesChannelScreen
import io.nisfeb.talon.ui.screens.NotebookListScreen
import io.nisfeb.talon.ui.screens.NotebookPostScreen
import io.nisfeb.talon.ui.screens.BookmarksScreen
import io.nisfeb.talon.ui.screens.DailyDigestScreen
import io.nisfeb.talon.ui.screens.GroupInfoScreen
import io.nisfeb.talon.ui.screens.MediaListScreen
import io.nisfeb.talon.ui.screens.StatusFeedScreen
import io.nisfeb.talon.ui.screens.ThreadScreen
import io.nisfeb.talon.ui.screens.WatchwordsScreen
import io.nisfeb.talon.ui.RightPaneState
import io.nisfeb.talon.ui.RightPaneStateReducer
import io.nisfeb.talon.urbit.MediaCategory
import kotlinx.coroutines.launch

/** How many times to ask a group's host whether a party line exists
 *  before giving up — same widening backoff App.kt uses. */
private const val PEEK_ATTEMPTS = 3

/** Cheap substring test — Story mention spans serialize as {"ship":"~patp"}. */
private fun isMentioned(contentJson: String, ourPatp: String): Boolean {
    if (ourPatp.isBlank()) return false
    return contentJson.contains("\"ship\":\"$ourPatp\"")
}

/**
 * Resolve a share-intent URI to a human-readable filename. Falls back
 * to the last path segment (also sanitized) then `default` if nothing
 * usable is available.
 */
private fun resolveFileName(
    resolver: android.content.ContentResolver,
    uri: android.net.Uri,
    default: String,
): String {
    val sanitize: (String) -> String = { it.replace(Regex("[^A-Za-z0-9._-]"), "_") }
    runCatching {
        resolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val name = if (idx >= 0) cursor.getString(idx) else null
                if (!name.isNullOrBlank()) return sanitize(name)
            }
        }
    }
    val tail = uri.lastPathSegment?.substringAfterLast('/')
    if (!tail.isNullOrBlank()) return sanitize(tail)
    return default
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TalonApp(
    initialOpenWhom: String? = null,
    initialScrollMessageId: String? = null,
    /** Set when a notification's tap-intent points at a thread reply
     *  — TalonApp routes into ThreadScreen anchored on
     *  [initialThreadAnchor] rather than just the chat. */
    initialOpenThread: String? = null,
    initialThreadAnchor: String? = null,
    /** Set when a Daily Digest notification's tap-intent points at
     *  the brief — TalonApp opens DailyDigestScreen on first
     *  composition. The value is the ship the digest belongs to;
     *  unused for routing today (the screen reads the active ship's
     *  digest itself) but stashed for future per-ship routing. */
    initialOpenDigest: String? = null,
    pendingShare: ShareIntent? = null,
    /** When non-null, the user already picked the share target in
     *  the system share sheet (Sharing Shortcut). Skip the in-app
     *  picker and dispatch [pendingShare] straight to this whom. */
    pendingShareTarget: String? = null,
    onShareConsumed: () -> Unit = {},
    /** Called once TalonApp has consumed an `initialOpen*` /
     *  `initialScroll*` / `initialThread*` / `initialOpenDigest`
     *  param. MainActivity uses this to clear its source mutable
     *  state so re-tapping the same notification re-routes —
     *  without it the param value never changes on the second tap
     *  and the LaunchedEffect below doesn't re-fire. */
    onDeepLinkConsumed: () -> Unit = {},
    /** The @p whose call the user answered from the notification. */
    initialAnswerFrom: String? = null,
    /** The ring the Answer action was posted for — accept() pins on it
     *  so a stale notification can't answer a different caller. */
    initialAnswerCallId: String? = null,
    onAnswerConsumed: () -> Unit = {},
    /** A ship to call, from a missed-call notification's Call back. */
    initialCallBack: String? = null,
    onCallBackConsumed: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as TalonApplication
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    // Follow TalonApplication's active-ship flow so switch / logout /
    // add-ship events reshape the UI without each callback having to
    // update local state.
    // Read the active ship straight from the StateFlow rather than
    // funnelling it through a remember-backed mirror. Earlier versions
    // pumped `activeShip` into a local `loggedInShip` via a
    // LaunchedEffect — at first composition `loggedInShip` was always
    // null while `activeShip` already held the right value, producing
    // a one-frame LoginScreen flash plus a key(loggedInShip) reset
    // that remounted the entire subtree as the LaunchedEffect caught
    // up. That looked like a black/empty home on quick app opens and
    // share-sheet entries.
    val activeShip by app.activeShipFlow.collectAsState()
    val allShips by app.allShipsFlow.collectAsState()
    val loggedInShip = activeShip
    // Trunkline signaling — one controller per logged-in ship. The
    // overlay is a Popup, so incoming calls surface over any screen.
    val callEngineProvider = rememberCallEngineProvider()
    // One player for the process: it owns a loop thread, and two would
    // ring over each other.
    val callSounds = remember { io.nisfeb.talon.call.AndroidCallSoundPlayer() }
    val callController = remember(loggedInShip) {
        if (loggedInShip != null) {
            io.nisfeb.talon.call.CallController(app.session, callEngineProvider, sounds = callSounds, defaults = io.nisfeb.talon.call.buildCallDefaults)
                .also { it.start() }
        } else {
            null
        }
    }
    val micGate = rememberMicGate()
    val partyLine = remember(callController, micGate) {
        if (callController != null) {
            io.nisfeb.talon.call.PartyLine(
                app.ktorHttp,
                io.nisfeb.talon.call.PeerLinkFactory { ice, send ->
                    // Only the up link captures; a down link needs no
                    // permission and must never sit on a dialog.
                    if (send && !micGate()) {
                        io.nisfeb.talon.util.Log.w(
                            "PartyLine",
                            "RECORD_AUDIO denied; joining without a working mic",
                        )
                    }
                    io.nisfeb.talon.call.AndroidPeerLink(app, ice, send)
                },
                callSounds,
                videoSupported = io.nisfeb.talon.ui.isPartyVideoSupported,
            ).also { line ->
                callController.onTicket = { host, ticket ->
                            // The topic lives on the host's room, not
                            // in Galène — carry it across as we join.
                            line.setTopic(
                                callController.lineFor(host, ticket.name)?.title.orEmpty(),
                            )
                            line.join(ticket, loggedInShip ?: "")
                        }
                callController.onDenied = { name, why -> line.showRefused(name, why) }
            }
        } else {
            null
        }
    }
    androidx.compose.runtime.LaunchedEffect(partyLine, callController) {
        // A 1:1 call and a party line can be live at the same time, and
        // every setMuted is per-track — CallController's mute physically
        // cannot quiet the party mic. Answering a private call while
        // unmuted on a group line broadcast the whole conversation to
        // the room. Mute the line for the call.
        val line = partyLine ?: return@LaunchedEffect
        callController?.state?.collect { st ->
            if (st is io.nisfeb.talon.call.CallUiState.Active) line.setMuted(true)
        }
    }
    androidx.compose.runtime.DisposableEffect(callController) {
        // The push path rings the system ringtone unless it knows the
        // in-app ring will sound; that is only true while a controller
        // is actually composed (not on the login screen).
        io.nisfeb.talon.Notifications.callControllerLive = callController != null
        onDispose {
            io.nisfeb.talon.Notifications.callControllerLive = false
            partyLine?.leave()
            callController?.stop()
        }
    }
    // Pre-wire-4 lines never bound to their group; reconcile so their
    // rosters mirror instead of freezing at the opening snapshot.
    // Mirrors App.kt.
    callController?.let { c ->
        val bindRooms by c.rooms.collectAsState()
        val bindWire by c.wire.collectAsState()
        androidx.compose.runtime.LaunchedEffect(bindRooms, bindWire) {
            loggedInShip?.let { me ->
                io.nisfeb.talon.call.PartyLineHost.bindLegacyRooms(c, app.db, me)
            }
        }
    }
    // The push notification rings; the app decides when to stop. Once
    // the controller is showing anything other than an incoming call —
    // answered, declined, or the caller gave up — the notification has
    // nothing left to say.
    if (callController != null) {
        val callState by callController.state.collectAsState()
        androidx.compose.runtime.LaunchedEffect(callState) {
            if (callState !is io.nisfeb.talon.call.CallUiState.Incoming) {
                io.nisfeb.talon.Notifications.cancelIncomingCall(app)
            }
        }

        // Answering from the notification. The platform action only
        // opens the activity — the media negotiation needs the
        // controller and a live channel — so the accept has to happen
        // here, or the user lands on the in-app ring and has to press
        // Answer a second time. On a cold start the ring hasn't
        // arrived over SSE yet, so wait for it rather than firing once
        // and missing.
        if (initialAnswerFrom != null) {
            androidx.compose.runtime.LaunchedEffect(initialAnswerFrom, initialAnswerCallId) {
                // Matched to the ring timeout rather than picked: a
                // cold start has to open the channel and run its
                // scries before the ring can land, and giving up
                // while the caller is still ringing is the bug this
                // is here to fix.
                val rang = kotlinx.coroutines.withTimeoutOrNull(
                    io.nisfeb.talon.call.CallController.DEFAULT_RING_TIMEOUT_MS,
                ) {
                    callController.state.first { s ->
                        s is io.nisfeb.talon.call.CallUiState.Incoming &&
                            s.peer == initialAnswerFrom
                    }
                }
                if (rang != null) callController.accept(forCallId = initialAnswerCallId)
                onAnswerConsumed()
            }
        }

        // Hold the microphone while there is live audio. Without a
        // microphone-typed foreground service, Android 14+ hands the
        // recorder silence the moment the app stops being visible —
        // which read as "backgrounding Talon mutes you, but you can
        // still hear them".
        // partyLine is non-null exactly when callController is, but
        // read through a remembered fallback rather than a `remember`
        // inside an elvis — a remember that only runs on one side of
        // a branch is a slot that appears and disappears.
        val idleParty = remember {
            kotlinx.coroutines.flow.MutableStateFlow<io.nisfeb.talon.call.PartyState>(
                io.nisfeb.talon.call.PartyState.Idle,
            )
        }
        val partyState by (partyLine?.state ?: idleParty).collectAsState()
        val onAir = callState is io.nisfeb.talon.call.CallUiState.Active ||
            callState is io.nisfeb.talon.call.CallUiState.Outgoing ||
            partyState is io.nisfeb.talon.call.PartyState.Live
        // Naming the peer, but never an opaque room id — a party
        // line's room is a Galène identifier, not something to put on
        // a lock screen.
        val onAirWith = (callState as? io.nisfeb.talon.call.CallUiState.Active)?.peer.orEmpty()
        val onAirMuted = (callState as? io.nisfeb.talon.call.CallUiState.Active)?.muted
            ?: (partyState as? io.nisfeb.talon.call.PartyState.Live)?.muted
            ?: false
        // The notification's Mute and Hang up need the live call or
        // line, which only this composition holds. Hand the service a
        // pair of closures for as long as we're on air.
        androidx.compose.runtime.DisposableEffect(onAir, callController, partyLine) {
            if (onAir) {
                io.nisfeb.talon.CallForegroundService.controls =
                    io.nisfeb.talon.CallForegroundService.Controls(
                        toggleMute = {
                            val cs = callController.state.value
                            val ps = partyLine?.state?.value
                            when {
                                cs is io.nisfeb.talon.call.CallUiState.Active ->
                                    callController.setMuted(!cs.muted)
                                ps is io.nisfeb.talon.call.PartyState.Live ->
                                    partyLine.setMuted(!ps.muted)
                            }
                        },
                        hangUp = {
                            if (callController.state.value is io.nisfeb.talon.call.CallUiState.None) {
                                partyLine?.leave()
                            } else {
                                callController.hangup()
                            }
                        },
                    )
            }
            onDispose { io.nisfeb.talon.CallForegroundService.controls = null }
        }
        // Starting is keyed on the title too, so an outgoing call that
        // connects re-posts as "On a call with ~ship" instead of
        // keeping the placeholder for the length of the call. Stopping
        // is keyed only on onAir, so a title change updates the
        // notification without dropping the mic hold in between.
        androidx.compose.runtime.LaunchedEffect(onAir, onAirWith, onAirMuted) {
            if (onAir) io.nisfeb.talon.CallForegroundService.start(context, onAirWith, onAirMuted)
        }
        androidx.compose.runtime.DisposableEffect(onAir) {
            onDispose { if (onAir) io.nisfeb.talon.CallForegroundService.stop(context) }
        }
    }
    val androidAudioDevices = remember {
        io.nisfeb.talon.call.AndroidAudioDevices(app)
    }
    val contactMap by remember {
        contactMapFlow(
            app.db.contacts().stream(),
            app.db.clubs().stream(),
            app.db.groups().streamGroups(),
            app.db.groups().streamChannelGroups(),
        )
    }.let { flow -> flow.collectAsState(initial = ContactMap.EMPTY) }
    // Register every call and party line with telecom for as long as
    // it lasts, and let the audio picker route through it meanwhile.
    val telecomCalls = remember(callController, partyLine) {
        if (callController != null && partyLine != null) {
            io.nisfeb.talon.call.TelecomCalls(
                app, callController, partyLine,
                nameFor = { ship -> contactMap.displayName(ship) },
                scope = appScope,
            )
        } else {
            null
        }
    }
    DisposableEffect(telecomCalls) {
        telecomCalls?.start()
        androidAudioDevices.telecom = telecomCalls?.route
        onDispose {
            androidAudioDevices.telecom = null
            telecomCalls?.stop()
        }
    }
    // A ring that ends unanswered on our side — timed out, or the
    // caller gave up — becomes a missed-call notice with Call back.
    // Not one we declined: that was an answer.
    if (callController != null) {
        var ringingFrom by remember { mutableStateOf<String?>(null) }
        val missedState by callController.state.collectAsState()
        androidx.compose.runtime.LaunchedEffect(missedState) {
            when (val s = missedState) {
                is io.nisfeb.talon.call.CallUiState.Incoming -> ringingFrom = s.peer
                is io.nisfeb.talon.call.CallUiState.Active -> ringingFrom = null
                is io.nisfeb.talon.call.CallUiState.Ended -> {
                    val who = ringingFrom
                    ringingFrom = null
                    if (who != null && s.reason != "declined") {
                        io.nisfeb.talon.Notifications.showMissedCall(app, who, contactMap.displayName(who))
                    }
                    stopShowingOverLockScreen(context)
                }
                is io.nisfeb.talon.call.CallUiState.None -> {
                    ringingFrom = null
                    stopShowingOverLockScreen(context)
                }
                else -> {}
            }
        }
        if (initialCallBack != null) {
            androidx.compose.runtime.LaunchedEffect(initialCallBack) {
                callController.placeCall(initialCallBack)
                onCallBackConsumed()
            }
        }
    }

    var addingAnotherShip by remember { mutableStateOf(false) }
    // Locally-owned navigation state. Initial values from the
    // notification-tap intent (or null on a normal cold launch). The
    // LaunchedEffect below routes any LATER param updates from
    // MainActivity (onNewIntent re-fires consumeIntent), which a
    // plain `remember { mutableStateOf(initialX) }` would silently
    // ignore — that was the rc23-era "tap a notification, nothing
    // happens" bug.
    // A "group:~host/name" deep link (group-invite notification) opens
    // the group, not a chat, so keep it out of openWhom.
    var openWhom by remember {
        mutableStateOf(initialOpenWhom?.takeUnless { it.startsWith("group:") })
    }
    // True exactly while the chat's partyLineBar slot is composing the
    // inline call/party surfaces (a DisposableEffect in that slot flips
    // it). Tracking actual composition instead of `openWhom != null`
    // matters: plenty of branches keep openWhom set without rendering
    // DmChatScreen — the thread screen, group info, notes/ channels,
    // full-screen overlays — and a live call must float its strip
    // there. Mirrors App.kt.
    val inlineCallUiShown = remember { mutableStateOf(false) }
    callController?.let {
        io.nisfeb.talon.ui.CallOverlay(
            it,
            nameFor = { ship -> contactMap.displayName(ship) },
            audioDevices = androidAudioDevices,
            // With the chat slot on screen the strip renders under its
            // header, beside the party-line bar. Floating it as well
            // would put the same call in two places.
            stripShownInline = inlineCallUiShown.value,
        )
    }
    // A live party line whose chat slot is gone (back on the list, a
    // thread, settings…) still needs a surface — the mic can be live
    // and unmuted. Same floating container CallOverlay uses for its
    // own strip, same bar the chat renders inline. Mirrors App.kt.
    partyLine?.let { line ->
        val floatingPartyState by line.state.collectAsState()
        if (floatingPartyState !is io.nisfeb.talon.call.PartyState.Idle &&
            !inlineCallUiShown.value
        ) {
            androidx.compose.ui.window.Popup(
                alignment = androidx.compose.ui.Alignment.TopCenter,
            ) {
                io.nisfeb.talon.ui.PartyLineBar(
                    line,
                    nameFor = { ship -> contactMap.displayName(ship) },
                    audioDevices = androidAudioDevices,
                    // Failed is sticky; floated with no chat slot to
                    // return to, this is its only way off the screen.
                    onDismiss = { line.dismissFailure() },
                    selfShip = loggedInShip.orEmpty(),
                    // No onModerate here: this floating fallback doesn't
                    // know which room the line belongs to (that lives
                    // with the chat slot). SFU-side moderation still
                    // works; it just isn't persisted on the host ship
                    // from here.
                )
            }
        }
    }

    var openGroupFlag by remember {
        mutableStateOf(initialOpenWhom?.takeIf { it.startsWith("group:") }?.removePrefix("group:"))
    }
    // Scroll target is consumed once the chat screen actually uses it,
    // so navigating back and reopening doesn't re-snap to the same msg.
    var pendingScrollMessageId by remember { mutableStateOf(initialScrollMessageId) }
    var openThread by remember { mutableStateOf<String?>(initialOpenThread) }
    /** When non-null, ThreadScreen anchors its initial scroll to this
     *  reply id rather than the newest one. Consumed-once after the
     *  thread screen reads it. */
    var pendingThreadAnchor by remember { mutableStateOf<String?>(initialThreadAnchor) }
    // Chat scroll position + first-anchor flag, owned here so they survive
    // DmChatScreen unmounting when a thread opens (on phones the thread is
    // its own full-screen when-branch). Keyed on openWhom: preserved across
    // the thread round-trip, reset on an actual chat switch so a freshly
    // opened conversation still snaps to the newest message.
    val chatScrollState = remember(openWhom) { LazyListState() }
    val chatScrollAnchored = remember(openWhom) { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var revealGroupRequest by remember { mutableStateOf<String?>(null) }
    var newDmOpen by remember { mutableStateOf(false) }
    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    var viewerImageList by remember {
        mutableStateOf<io.nisfeb.talon.ui.screens.ViewerImageList?>(null)
    }
    var editingProfile by remember { mutableStateOf(false) }
    var statusFeedOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var activityOpen by remember { mutableStateOf(false) }
    var contactsOpen by remember { mutableStateOf(false) }
    var watchwordsOpen by remember { mutableStateOf(false) }
    // Curated contact book (%contacts /v1/book) — gates add affordances
    // and backs the Contacts screen.
    val bookContacts by app.repo.bookContacts.collectAsState()
    // AI settings — gates the assistant entry point (see DmListScreen
    // onOpenAssistant below); collected here so a toggle/key change
    // recomposes the gate live.
    val aiState by app.aiSettings.state.collectAsState()
    // Daily Digest screen — initialOpenDigest non-null means we
    // arrived from a notification tap, so route straight there.
    var digestOpen by remember { mutableStateOf(initialOpenDigest != null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var assistantOpen by remember { mutableStateOf(false) }
    var loopsOpen by remember { mutableStateOf(false) }
    var sidebarSettingsOpen by remember { mutableStateOf(false) }
    var adminListOpen by remember { mutableStateOf(false) }
    var adminGroupFlag by remember { mutableStateOf<String?>(null) }
    var invitesOpen by remember { mutableStateOf(false) }
    var profileSheetShip by remember { mutableStateOf<String?>(null) }
    /** Login-handoff QR generator. Reachable from the LoginScreen
     *  "Generate QR for someone" link (pre-login) and from settings
     *  once a ship is logged in. State is local because the screen
     *  is short-lived and doesn't need to survive ship switches. */
    var shareLoginQrOpen by remember { mutableStateOf(false) }

    // Phase 3 right-pane state — Android phone always renders these as
    // full-screen replaces (no DesktopShell on phone). Tablet landscape
    // gets the wide-pane right-column rendering via App.kt's path; this
    // file is the phone-only entry. groupInfoDrilldown is sub-state of
    // groupInfoOpenFor — both null = pane closed.
    var groupInfoOpenFor by remember { mutableStateOf<String?>(null) }
    var groupInfoDrilldown by remember { mutableStateOf<MediaCategory?>(null) }
    // Re-route on every deep-link delivery from MainActivity. The keys
    // here match every initial* param TalonApp accepts. When any of
    // them flips to a non-null value (notification tap → onNewIntent
    // → MainActivity.consumeIntent → state changes → recompose), this
    // effect picks it up and updates the local navigation state.
    // After applying, we tell MainActivity to clear the source state
    // so re-tapping the same notification (which carries the same
    // param value) re-fires the effect.
    LaunchedEffect(
        initialOpenWhom,
        initialScrollMessageId,
        initialOpenThread,
        initialThreadAnchor,
        initialOpenDigest,
    ) {
        var consumed = false
        // A chat-targeted deep-link must clear any modal pane that
        // would render BEFORE the chat in the render-when block —
        // otherwise the user lands on (say) StatusFeed even though
        // openWhom was updated, because StatusFeed's branch matches
        // first. See the render when at "statusFeedOpen ->" et al.
        val chatTargeted = initialOpenWhom != null ||
            initialOpenThread != null ||
            initialScrollMessageId != null
        if (chatTargeted) {
            statusFeedOpen = false
            bookmarksOpen = false
            activityOpen = false
            watchwordsOpen = false
            digestOpen = false
            settingsOpen = false
            sidebarSettingsOpen = false
            adminListOpen = false
            adminGroupFlag = null
            invitesOpen = false
            searchOpen = false
            newDmOpen = false
            editingProfile = false
            groupInfoOpenFor = null
            groupInfoDrilldown = null
        }
        if (initialOpenWhom != null) {
            // A group-invite notification deep-links as "group:~host/name"
            // — route it to the group screen, not openWhom (which only
            // understands channel nests and DM ships).
            if (initialOpenWhom.startsWith("group:")) {
                openGroupFlag = initialOpenWhom.removePrefix("group:")
            } else {
                openWhom = initialOpenWhom
            }
            consumed = true
        }
        if (initialScrollMessageId != null) {
            pendingScrollMessageId = initialScrollMessageId
            consumed = true
        }
        if (initialOpenThread != null) {
            openThread = initialOpenThread
            consumed = true
        }
        if (initialThreadAnchor != null) {
            pendingThreadAnchor = initialThreadAnchor
            consumed = true
        }
        if (initialOpenDigest != null) {
            digestOpen = true
            consumed = true
        }
        if (consumed) onDeepLinkConsumed()
    }
    // Right-pane state mutators — delegate to RightPaneStateReducer
    // so the mutual-exclusion rules live in one tested place. See
    // App.kt's matching block for the rationale (rc6 audit caught
    // three regressions where inline mutations missed a clear).
    // Translates this file's older naming (openThread /
    // pendingThreadAnchor) into the reducer's canonical fields.
    val rightPaneSnapshot: () -> RightPaneState = {
        RightPaneState(
            openThreadParent = openThread,
            openThreadReplyAnchor = pendingThreadAnchor,
            groupInfoOpenFor = groupInfoOpenFor,
            groupInfoDrilldown = groupInfoDrilldown,
        )
    }
    val applyRightPaneState: (RightPaneState) -> Unit = { next ->
        openThread = next.openThreadParent
        pendingThreadAnchor = next.openThreadReplyAnchor
        groupInfoOpenFor = next.groupInfoOpenFor
        groupInfoDrilldown = next.groupInfoDrilldown
    }
    val openThreadAction: (parentId: String, anchor: String?) -> Unit = { p, a ->
        applyRightPaneState(RightPaneStateReducer.openThread(rightPaneSnapshot(), p, a))
    }
    val openGroupInfoAction: (whom: String) -> Unit = { w ->
        applyRightPaneState(RightPaneStateReducer.openGroupInfo(rightPaneSnapshot(), w))
    }
    val openCategoryAction: (MediaCategory) -> Unit = { c ->
        applyRightPaneState(RightPaneStateReducer.openCategory(rightPaneSnapshot(), c))
    }
    val closeDrilldownAction: () -> Unit = {
        applyRightPaneState(RightPaneStateReducer.closeDrilldown(rightPaneSnapshot()))
    }
    val closeRightPaneAction: () -> Unit = {
        applyRightPaneState(RightPaneStateReducer.closeRightPane(rightPaneSnapshot()))
    }
    val openConversationAction: () -> Unit = {
        applyRightPaneState(RightPaneStateReducer.openConversation(rightPaneSnapshot()))
    }
    val switchShipAction: () -> Unit = {
        applyRightPaneState(RightPaneStateReducer.switchShip(rightPaneSnapshot()))
    }
    // Notebook / gallery drill-down state. openWhom picks the channel;
    // these pick the particular post / compose view within it.
    var openNotebookPostId by remember { mutableStateOf<String?>(null) }
    var notebookComposeOpen by remember { mutableStateOf(false) }
    // Edit-mode params for the notebook compose screen. Null unless
    // the user picked "Edit" from a post's overflow menu.
    var notebookEditPostId by remember { mutableStateOf<String?>(null) }
    var notebookEditTitle by remember { mutableStateOf("") }
    var notebookEditImage by remember { mutableStateOf("") }
    var notebookEditBody by remember { mutableStateOf("") }
    var notebookEditSentMs by remember { mutableStateOf(0L) }
    var openGalleryPostId by remember { mutableStateOf<String?>(null) }
    var galleryComposeOpen by remember { mutableStateOf(false) }
    // %notes (v12 Markdown notebooks): which note is open inside a
    // notes/ channel. Null = showing the notebook's folder tree.
    // Keyed on openWhom: note ids are only unique within a notebook, so
    // an id left over from the last one would render a note that doesn't
    // exist here — an endless spinner on a freshly opened notebook.
    var openNoteId by remember(openWhom) { mutableStateOf<Long?>(null) }

    // Per-ship persistent store for the last-open conversation.
    // `remember(app)` keeps the same SharedPreferences-backed instance
    // across recompositions so the read happens once and the same
    // object is reused for all subsequent set() calls.
    val lastOpenChatStore = remember(app) {
        io.nisfeb.talon.notify.AndroidLastOpenChatStore(app)
    }
    val lastOpenChatState by lastOpenChatStore.state.collectAsState()
    // True once the seed has had its chance for the current ship. Guards
    // the mirror effect below so the pre-seed `openWhom == null` at launch
    // doesn't clear the persisted entry before we've restored from it.
    var chatSeeded by remember(loggedInShip) { mutableStateOf(false) }
    // Seed openWhom from persisted store on first composition or ship
    // switch so we restore the user's last chat.
    LaunchedEffect(loggedInShip) {
        if (loggedInShip != null) {
            if (openWhom == null) lastOpenChatState[loggedInShip]?.let { openWhom = it }
            chatSeeded = true
        }
    }
    // Mirror openWhom into the store: persist on open, and CLEAR on
    // back-out (openWhom == null) so closing on the home list reopens to
    // the list rather than forcing the last chat back open.
    LaunchedEffect(openWhom, loggedInShip) {
        val ship = loggedInShip ?: return@LaunchedEffect
        if (!chatSeeded) return@LaunchedEffect
        val whom = openWhom
        if (whom != null) lastOpenChatStore.set(ship, whom)
        else lastOpenChatStore.clear(ship)
    }

    // Single entry point for "open this conversation target" clicks.
    // `group:<flag>` (from citation taps) opens a lightweight
    // GroupHomeScreen — a member sees the channel list, a non-member
    // sees a Join CTA. Other targets route to the usual `openWhom`.
    val openConversation: (String) -> Unit = { target ->
        openConversationAction()
        if (target.startsWith("group:")) {
            openGroupFlag = target.removePrefix("group:")
        } else {
            openWhom = target
        }
    }

    // Pure dispatcher for a share into a chosen conversation. Same
    // logic the picker's onPick uses, hoisted so the
    // `pendingShareTarget` auto-dispatch path (Sharing Shortcut)
    // doesn't have to duplicate it. Caller is responsible for
    // toggling openWhom and calling onShareConsumed afterward.
    // The launches below swallowed failures whole: a share that couldn't
    // read or upload just disappeared. Toast the reason (uploadImage's
    // empty-bytes message names the cloud-photo fix) instead of going mute.
    val shareFailedToast: (Throwable) -> Unit = { t ->
        appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                "Share failed: ${t.message ?: "unknown error"}",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    val dispatchShare: (ShareIntent, String) -> Unit = { share, whom ->
        when (share) {
            is ShareIntent.Text -> {
                val existing = app.drafts.load(whom)
                val merged = if (existing.isBlank()) share.text
                    else "${existing.trimEnd()}\n\n${share.text}"
                app.drafts.save(whom, merged)
            }
            is ShareIntent.Image -> {
                appScope.launch {
                    runCatching {
                        val resolver = context.contentResolver
                        val name = resolveFileName(resolver, share.uri, default = "image")
                        val bytes = resolver.openInputStream(share.uri)
                            ?.use { it.readBytes() }
                            ?: error("cannot read shared image bytes")
                        val hostedUrl = app.repo.uploadImage(
                            bytes = bytes,
                            contentType = share.mimeType,
                            fileName = name,
                        )
                        app.repo.sendImage(
                            whom = whom,
                            src = hostedUrl,
                            width = 0,
                            height = 0,
                            alt = name,
                        )
                    }.onFailure { shareFailedToast(it) }
                }
            }
            is ShareIntent.File -> {
                appScope.launch {
                    runCatching {
                        val resolver = context.contentResolver
                        val name = resolveFileName(resolver, share.uri, default = "file")
                        val bytes = resolver.openInputStream(share.uri)
                            ?.use { it.readBytes() }
                            ?: error("cannot read shared file bytes")
                        val hostedUrl = app.repo.uploadImage(
                            bytes = bytes,
                            contentType = share.mimeType,
                            fileName = name,
                        )
                        app.repo.send(whom, "[📎 $name]($hostedUrl)")
                    }.onFailure { shareFailedToast(it) }
                }
            }
        }
    }

    // Sharing Shortcut path: the user already chose the channel in
    // the system share sheet, so dispatch the share immediately and
    // open that conversation without rendering the in-app picker.
    LaunchedEffect(pendingShare, pendingShareTarget) {
        val share = pendingShare
        val target = pendingShareTarget
        if (share != null && !target.isNullOrBlank()) {
            dispatchShare(share, target)
            openWhom = target
            onShareConsumed()
        }
    }

    // TalonApplication.onCreate restores the previously-active ship
    // into the ship-scoped fields (db, repo, session) and seeds
    // activeShipFlow — our collector above syncs that into
    // loggedInShip, so no extra restore pass is needed here.

    LaunchedEffect(loggedInShip) {
        if (loggedInShip != null) {
            app.repo.start(app.session)
            app.shortcuts.start()
            TalonSyncService.start(context)
            // Backfill semantic-search embeddings only when the user
            // has opted in. Indexer is idempotent so it's safe to call
            // every launch — but if the feature is off we skip the
            // model download + CPU entirely.
            if (app.aiSettings.state.value.smartFeaturesEnabled) {
                app.embeddingIndexer.start()
            }
        }
    }

    // Latest-value refs so the callback below doesn't close over stale state.
    val openWhomState = rememberUpdatedState(openWhom)

    // Keep the shared ship-profile cache in sync with the active
    // ship's self-contact nickname. Fires whenever %contacts pushes a
    // new value through the contactMap flow.
    val me = loggedInShip
    androidx.compose.runtime.LaunchedEffect(me, contactMap) {
        if (me != null) {
            app.shipProfiles.setNickname(me, contactMap.nickname(me))
        }
    }

    // Register a single message listener with the repo for the lifetime
    // of the composition. Each new non-self message fires a notification
    // unless the user is currently viewing that exact conversation in the
    // foreground.
    DisposableEffect(Unit) {
        app.repo.messageListener = { m: MessageEntity, replyToUs: Boolean ->
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val focused = foreground && openWhomState.value == m.whom
            if (!focused) {
                // Per-whom preference filter. DB read is async; do the
                // check + fire in a coroutine rather than blocking the
                // repo's event pump.
                appScope.launch {
                    val level = app.db.notifyPrefs().levelFor(m.whom) ?: NotifyLevel.DEFAULT
                    val muted = level == NotifyLevel.NONE

                    // ── existing notification path ─────────────────────
                    val shouldFire = !muted && when (level) {
                        NotifyLevel.MENTIONS ->
                            replyToUs || isMentioned(m.contentJson, loggedInShip ?: "")
                        else -> true
                    }
                    if (shouldFire) {
                        val title = contactMap.conversationLabel(m.whom)
                        val authorLabel = contactMap.displayName(m.author)
                        val preview = StoryCache.textFor(m.id, m.contentJson)
                            .replace('\n', ' ')
                            .take(160)
                        Notifications.showMessage(
                            context = context,
                            whom = m.whom,
                            postId = m.id,
                            parentId = m.parentId,
                            title = title,
                            body = if (m.whom.startsWith("~")) preview else "$authorLabel: $preview",
                            sentMs = m.sentMs,
                        )
                    }

                    // ── watchword path (NEW) ───────────────────────────
                    val ourPatp = loggedInShip ?: ""
                    if (m.author == ourPatp) return@launch
                    if (muted) return@launch
                    if (m.whom in app.watchwords.excludes.value) return@launch
                    val terms = app.watchwords.terms.value
                    if (terms.isEmpty()) return@launch

                    val plainText = StoryCache.textFor(m.id, m.contentJson)
                    val matches = app.watchwords.evaluateLive(m, plainText)
                    if (matches.isEmpty()) return@launch
                    val notifiable = matches.filter { it.term.notify }
                    if (notifiable.isEmpty()) return@launch

                    val convoLabel = contactMap.conversationLabel(m.whom)
                    Notifications.showWatchwordHit(
                        context = context,
                        whom = m.whom,
                        postId = m.id,
                        parentId = m.parentId,
                        terms = notifiable.map { it.term.term },
                        label = convoLabel,
                        body = plainText.take(160).replace('\n', ' '),
                        sentMs = m.sentMs,
                    )
                }
            }
        }
        // New pending DM request → notify. Always fires (there's no
        // "open" state for an unaccepted request), so a brand-new DM no
        // longer arrives silently.
        app.repo.dmInviteListener = { ship ->
            appScope.launch {
                Notifications.showMessage(
                    context = context,
                    whom = ship,
                    postId = ship,
                    parentId = null,
                    title = contactMap.displayName(ship),
                    body = "wants to message you",
                    sentMs = System.currentTimeMillis(),
                )
            }
        }
        // New group invite → notify, same as a DM request. Tapping opens
        // the group (its flag doubles as the whom via the "group:" route).
        app.repo.groupInviteListener = { invite ->
            appScope.launch {
                val from = invite.inviter?.let { " from $it" } ?: ""
                Notifications.showMessage(
                    context = context,
                    whom = "group:${invite.flag}",
                    postId = invite.flag,
                    parentId = null,
                    title = invite.title ?: invite.flag,
                    body = "invited you to a group$from",
                    sentMs = System.currentTimeMillis(),
                )
            }
        }
        onDispose {
            app.repo.messageListener = null
            app.repo.dmInviteListener = null
            app.repo.groupInviteListener = null
        }
    }

    // Foreground catch-up: when the app returns to the front, force a
    // fresh SSE reconnect (the channel may have been quietly killed by
    // doze while we were away). forceReconnect cancels the current
    // session; the loop in runSessionLoop opens a new channel and
    // calls bootstrap + bootstrapActivity, so any messages that
    // landed while we were gone show up immediately.
    //
    // We used to also call catchUp() alongside this, but it raced
    // against the new session's bootstrap — same scries, same channel
    // host — and reliably lost to the 30s RPC timeout, polluting
    // logcat with `catchUp bootstrap failed: IOException: Canceled`
    // on every foreground entry. forceReconnect alone covers the gap.
    DisposableEffect(Unit) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // Foreground: a chat left open now counts as actively
                    // viewed again (gates auto-mark-read in the repo).
                    app.repo.setForeground(true)
                    app.repo.forceReconnect()
                }
                // Background: stop treating the open chat as read so DMs
                // arriving while away still badge + notify.
                Lifecycle.Event.ON_STOP -> app.repo.setForeground(false)
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Dismiss any lingering notification for the conversation we just opened.
    LaunchedEffect(openWhom) {
        openWhom?.let { Notifications.cancelAllForChat(context, it) }
    }

    // Screens handle their own window insets via Modifier.windowInsetsPadding
    // so that composers can push up with the IME while everything else stays
    // under the status bar.
    val calendarLauncher = remember(context) {
        CalendarLauncher { startMs, endMs, title ->
            // Pop the system "create event" sheet pre-filled. Falls
            // through to a generic chooser if no calendar app handles
            // ACTION_INSERT.
            val intent = android.content.Intent(android.content.Intent.ACTION_INSERT)
                .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                .putExtra(android.provider.CalendarContract.Events.TITLE, title)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
    val mapsLauncher = remember(context) {
        MapsLauncher { lat, lng ->
            // geo:lat,lng?q=lat,lng — handled by Google Maps / OsmAnd
            // / etc. directly. Falls through to the chooser if no
            // maps app handles geo://.
            val uri = android.net.Uri.parse(
                "geo:%.5f,%.5f?q=%.5f,%.5f".format(lat, lng, lat, lng),
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
    // urb:// links: check lattice is installed on our ship, offer to
    // install it (from ~ricsul-bilwyt) if not, then open the webview
    // popover. The host emits its own dialog/sheet overlays.
    val platformUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val urbLinkHandler = io.nisfeb.talon.ui.rememberUrbLinkHandler(
        http = app.ktorHttp,
        shipUrl = { app.sessionStore.active()?.shipUrl },
        cookie = {
            app.sessionStore.active()?.let { "${it.cookieName}=${it.cookieValue}" }
        },
        poke = { a, mark, body ->
            runCatching { app.repo.pokeRaw(a, mark, body) }.isSuccess
        },
    )
    val urbAwareUriHandler = remember(platformUriHandler, urbLinkHandler) {
        io.nisfeb.talon.ui.UrbAwareUriHandler(platformUriHandler, urbLinkHandler)
    }
    val urbFetcher: suspend (String) -> io.nisfeb.talon.urbit.UrbUnfurlCache.Unfurl? =
        remember(app) {
            { urbUrl ->
                val active = app.sessionStore.active()
                val s = active?.shipUrl
                val c = active?.let { "${it.cookieName}=${it.cookieValue}" }
                if (s == null || c == null) null
                else io.nisfeb.talon.urbit.UrbUnfurlCache.await(app.ktorHttp, s, c, urbUrl)
            }
        }
    val citeResolver = remember(app) { io.nisfeb.talon.ui.TalonCiteResolver(app.db, app.repo) }
    val openCitation: (io.nisfeb.talon.urbit.StoryPart.Citation) -> Unit =
        remember(citeResolver) {
            { cite ->
                appScope.launch {
                    val jump = io.nisfeb.talon.ui.resolveCiteJump(cite, citeResolver) {
                        runCatching { app.db.groups().channelGroupFor(it)?.groupFlag }.getOrNull()
                    }
                    when (jump) {
                        is io.nisfeb.talon.ui.CiteJump.Group -> {
                            openWhom = null
                            revealGroupRequest = jump.flag
                        }
                        is io.nisfeb.talon.ui.CiteJump.Message -> {
                            openConversationAction()
                            jump.groupFlag?.let { revealGroupRequest = it }
                            pendingScrollMessageId = jump.messageId
                            openWhom = jump.whom
                        }
                        is io.nisfeb.talon.ui.CiteJump.Reply -> {
                            openConversationAction()
                            jump.groupFlag?.let { revealGroupRequest = it }
                            openWhom = jump.whom
                            pendingThreadAnchor = jump.replyId
                            openThread = jump.parentId
                        }
                        null -> Unit
                    }
                }
                Unit
            }
        }
    // Root contact map so a quoted post's author resolves to the same
    // nickname / mnemonym the rest of the app shows, not the bare @p.
    val citeContacts by remember(app) {
        io.nisfeb.talon.ui.contactMapFlow(
            app.db.contacts().stream(),
            app.db.clubs().stream(),
            app.db.groups().streamGroups(),
            app.db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = io.nisfeb.talon.ui.ContactMap.EMPTY)
    val citeDisplayName: (String) -> String = remember(citeContacts) {
        { ship -> citeContacts.displayName(ship) }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        // Bind the real ExoPlayer-backed inline players for chat
        // messages. Without this, StoryRenderer falls back to a
        // tap-to-open-in-browser pill — the desktop default.
        LocalInlineMediaPlayer provides { url, kind ->
            when (kind) {
                MediaKind.AUDIO -> InlineAudioPlayer(url = url)
                MediaKind.VIDEO -> InlineVideoPlayer(url = url)
            }
        },
        LocalCalendarLauncher provides calendarLauncher,
        LocalMapsLauncher provides mapsLauncher,
        io.nisfeb.talon.ui.LocalUrbLinkHandler provides urbLinkHandler,
        io.nisfeb.talon.ui.LocalUrbFetcher provides urbFetcher,
        io.nisfeb.talon.ui.LocalShipUrl provides app.sessionStore.active()?.shipUrl,
        io.nisfeb.talon.ui.LocalShipCookie provides
            app.sessionStore.active()?.let { "${it.cookieName}=${it.cookieValue}" },
        // Route urb:// opened via Compose link handling (statuses, bios)
        // to Lattice. Also avoids an ActivityNotFoundException crash for
        // users without Lattice — they get the install prompt instead.
        androidx.compose.ui.platform.LocalUriHandler provides urbAwareUriHandler,
        io.nisfeb.talon.ui.LocalCiteResolver provides citeResolver,
        io.nisfeb.talon.ui.LocalCitationOpen provides openCitation,
        io.nisfeb.talon.ui.LocalDisplayName provides citeDisplayName,
    ) {
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { _ ->
        val mod = Modifier.fillMaxSize()

        // Android back: unwind the nav stack. OnBackPressedDispatcher
        // dispatches in REVERSE registration order — last-registered
        // wins — so deeper leaves go LATER below. The image viewer
        // overlays everything else (you can open it from a chat, which
        // means both `viewerImageUrl` and `openWhom` are non-null at
        // the same time), so its handler is registered last.
        BackHandler(enabled = editingProfile) { editingProfile = false }
        BackHandler(enabled = statusFeedOpen) { statusFeedOpen = false }
        BackHandler(enabled = bookmarksOpen) { bookmarksOpen = false }
        BackHandler(enabled = activityOpen) { activityOpen = false }
        BackHandler(enabled = contactsOpen) { contactsOpen = false }
        BackHandler(enabled = watchwordsOpen) { watchwordsOpen = false }
        BackHandler(enabled = digestOpen) { digestOpen = false }
        BackHandler(enabled = settingsOpen) { settingsOpen = false }
        BackHandler(enabled = assistantOpen) { assistantOpen = false }
        BackHandler(enabled = loopsOpen) { loopsOpen = false }
        BackHandler(enabled = sidebarSettingsOpen) { sidebarSettingsOpen = false }
        BackHandler(enabled = adminGroupFlag != null) { adminGroupFlag = null }
        BackHandler(enabled = adminListOpen && adminGroupFlag == null) {
            adminListOpen = false
        }
        BackHandler(enabled = invitesOpen) { invitesOpen = false }
        BackHandler(enabled = shareLoginQrOpen) { shareLoginQrOpen = false }
        BackHandler(enabled = notebookComposeOpen) { notebookComposeOpen = false }
        BackHandler(enabled = openNotebookPostId != null) { openNotebookPostId = null }
        BackHandler(enabled = galleryComposeOpen) { galleryComposeOpen = false }
        BackHandler(enabled = openGalleryPostId != null) { openGalleryPostId = null }
        BackHandler(enabled = openGroupFlag != null) { openGroupFlag = null }
        BackHandler(enabled = pendingShare != null) { onShareConsumed() }
        BackHandler(enabled = searchOpen) { searchOpen = false }
        BackHandler(enabled = newDmOpen) { newDmOpen = false }
        BackHandler(enabled = openThread != null) { openThread = null }
        // Phase 3 right-pane back-stack — drilldown above group-info so
        // back from a media list returns to the group-info pane, not
        // straight to the chat.
        BackHandler(enabled = groupInfoDrilldown != null) {
            closeDrilldownAction()
        }
        BackHandler(enabled = groupInfoOpenFor != null && groupInfoDrilldown == null) {
            closeRightPaneAction()
        }
        BackHandler(enabled = openThread == null && openWhom != null) { openWhom = null }
        // After the chat handler: an open note is *inside* a notes/
        // channel, so both are enabled and the later registration has to
        // be this one or back would close the whole notebook.
        BackHandler(enabled = openNoteId != null) { openNoteId = null }
        // Registered last so it wins over the chat handler when an
        // image is opened from inside a chat.
        BackHandler(enabled = viewerImageList != null) { viewerImageList = null }
        BackHandler(enabled = viewerImageUrl != null && viewerImageList == null) {
            viewerImageUrl = null
        }

        // Trace every screen swap so we can correlate logcat with
        // visual artifacts. Names the branch the when-block will
        // pick this composition; TODO drop after the
        // overlap/black-screen bug is reproduced.
        val screenTag = when {
            addingAnotherShip -> "AddShipLogin"
            loggedInShip == null -> "Login"
            viewerImageUrl != null -> "ImageViewer"
            editingProfile -> "ProfileEdit"
            statusFeedOpen -> "StatusFeed"
            bookmarksOpen -> "Bookmarks"
            activityOpen -> "Activity"
            groupInfoDrilldown != null -> "MediaList"
            groupInfoOpenFor != null -> "GroupInfo"
            watchwordsOpen -> "Watchwords"
            digestOpen -> "Today's brief"
            adminGroupFlag != null -> "GroupAdmin($adminGroupFlag)"
            adminListOpen -> "AdminList"
            invitesOpen -> "Invites"
            openGroupFlag != null -> "GroupHome($openGroupFlag)"
            sidebarSettingsOpen -> "SidebarSettings"
            assistantOpen -> "Assistant"
            loopsOpen -> "Loops"
            settingsOpen -> "Settings"
            pendingShare != null -> "ShareTarget"
            openThread != null && openWhom != null -> "Thread($openWhom/$openThread)"
            openWhom != null && openWhom!!.startsWith("diary/") -> "Diary($openWhom)"
            openWhom != null && openWhom!!.startsWith("heap/") -> "Gallery($openWhom)"
            openWhom != null -> "DmChat($openWhom)"
            searchOpen -> "Search"
            newDmOpen -> "NewDm"
            else -> "DmList"
        }
        androidx.compose.runtime.LaunchedEffect(screenTag) {
            android.util.Log.i("TalonNav", "render → $screenTag")
        }

        // Key the logged-in tree on the active ship so switching
        // resets every remember / collectAsState. Otherwise consumers
        // still hold flows from the previous ship's DB and show its
        // data even after `app.db` has been rebuilt.
        androidx.compose.runtime.key(loggedInShip) {
        // Ship-switcher drawer hoisted to TalonApp.kt level (mirroring
        // App.kt's lift in 0.10.0-rc3) so the drawer's parent is the
        // whole window. The previous home for this drawer was inside
        // DmListScreen — fine for Android phones where there's no rail,
        // but on Android tablet landscape (which uses DesktopShell's
        // wide-pane layout) the drawer's closed-state -drawer_width
        // translation overflowed the list pane and leaked over the
        // rail. Lifting here makes Android consistent with desktop.
        val drawerScope = rememberCoroutineScope()
        val drawerState = androidx.compose.material3.rememberDrawerState(
            initialValue = androidx.compose.material3.DrawerValue.Closed,
        )
        val shipNicknamesMap = app.shipProfiles.nicknames.collectAsState().value
        androidx.compose.material3.ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                if (allShips.isNotEmpty()) {
                    io.nisfeb.talon.ui.screens.ShipSwitcherDrawer(
                        ships = allShips,
                        activeShip = loggedInShip,
                        nicknames = shipNicknamesMap,
                        onPick = { picked ->
                            drawerScope.launch { drawerState.close() }
                            if (picked != loggedInShip) {
                                openWhom = null
                                switchShipAction()
                                searchOpen = false
                                newDmOpen = false
                                app.switchShip(picked)
                            }
                        },
                        onAdd = {
                            drawerScope.launch { drawerState.close() }
                            addingAnotherShip = true
                        },
                    )
                }
            },
        ) {
        when {
            shareLoginQrOpen -> io.nisfeb.talon.ui.screens.LoginQrShareScreen(
                onBack = { shareLoginQrOpen = false },
            )

            addingAnotherShip -> LoginScreen(
                session = app.session,
                onLoggedIn = { ship ->
                    addingAnotherShip = false
                    app.onShipLoggedIn(ship)
                },
                usernameAutofill = { onFill ->
                    rememberAutofillModifier(
                        types = listOf(
                            androidx.compose.ui.autofill.AutofillType.Username,
                        ),
                        onFill = onFill,
                    )
                },
                passwordAutofill = { onFill ->
                    rememberAutofillModifier(
                        types = listOf(
                            androidx.compose.ui.autofill.AutofillType.Password,
                        ),
                        onFill = onFill,
                    )
                },
                qrScanIntegration = { onResult ->
                    rememberQrLoginScanLauncher(onResult)
                },
                onOpenShareQr = { shareLoginQrOpen = true },
                updateState = app.updateState,
            )

            loggedInShip == null -> LoginScreen(
                session = app.session,
                onLoggedIn = { ship -> app.onShipLoggedIn(ship) },
                usernameAutofill = { onFill ->
                    rememberAutofillModifier(
                        types = listOf(
                            androidx.compose.ui.autofill.AutofillType.Username,
                        ),
                        onFill = onFill,
                    )
                },
                passwordAutofill = { onFill ->
                    rememberAutofillModifier(
                        types = listOf(
                            androidx.compose.ui.autofill.AutofillType.Password,
                        ),
                        onFill = onFill,
                    )
                },
                qrScanIntegration = { onResult ->
                    rememberQrLoginScanLauncher(onResult)
                },
                onOpenShareQr = { shareLoginQrOpen = true },
                updateState = app.updateState,
            )

            viewerImageList != null -> ImageViewerScreen(
                urls = viewerImageList!!.urls,
                initialIndex = viewerImageList!!.initialIndex,
                onClose = { viewerImageList = null },
                modifier = mod,
            )

            viewerImageUrl != null -> ImageViewerScreen(
                urls = listOf(viewerImageUrl!!),
                onClose = { viewerImageUrl = null },
                modifier = mod,
            )

            editingProfile -> ProfileEditScreen(
                db = app.db,
                repo = app.repo,
                ourPatp = loggedInShip ?: "",
                onBack = { editingProfile = false },
                modifier = mod,
            )

            statusFeedOpen -> StatusFeedScreen(
                db = app.db,
                repo = app.repo,
                ourPatp = loggedInShip ?: "",
                onOpenContact = { ship ->
                    profileSheetShip = ship
                },
                onBack = { statusFeedOpen = false },
                modifier = mod,
            )

            bookmarksOpen -> BookmarksScreen(
                db = app.db,
                repo = app.repo,
                onOpenConversation = { whom, postId ->
                    bookmarksOpen = false
                    pendingScrollMessageId = postId
                    openWhom = whom
                },
                onBack = { bookmarksOpen = false },
                modifier = mod,
            )

            contactsOpen -> io.nisfeb.talon.ui.screens.ContactsScreen(
                db = app.db,
                bookContacts = bookContacts,
                onAddContact = { patp, nickname ->
                    appScope.launch { runCatching { app.repo.addContact(patp, nickname) } }
                },
                onRemoveContact = { patp ->
                    appScope.launch { runCatching { app.repo.removeContact(patp) } }
                },
                onOpenContact = { patp -> profileSheetShip = patp },
                onBack = { contactsOpen = false },
                modifier = mod,
            )

            activityOpen -> ActivityFeedScreen(
                db = app.db,
                repo = app.repo,
                onOpenConversation = { whom ->
                    activityOpen = false
                    // A plain open drops any armed anchor — an
                    // unconsumed one re-fires on the next open of that
                    // chat and yanks the scroll to an old message.
                    pendingScrollMessageId = null
                    openWhom = whom
                },
                onOpenReply = { whom, parent, replyId ->
                    activityOpen = false
                    openWhom = whom
                    pendingThreadAnchor = replyId
                    openThread = parent
                },
                onOpenPost = { whom, postId ->
                    activityOpen = false
                    pendingScrollMessageId = postId
                    openWhom = whom
                },
                onBack = { activityOpen = false },
                modifier = mod,
            )

            watchwordsOpen -> WatchwordsScreen(
                db = app.db,
                watchwordsSyncEnabled = app.watchwordsSyncEnabled,
                onSetWatchwordsSyncEnabled = { app.setWatchwordsSyncEnabled(it) },
                onBack = { watchwordsOpen = false },
                onOpenConversation = { whom, postId ->
                    openWhom = whom
                    pendingScrollMessageId = postId
                    watchwordsOpen = false
                },
                modifier = mod,
            )

            digestOpen -> DailyDigestScreen(
                db = app.db,
                activeShip = loggedInShip,
                onBack = { digestOpen = false },
                onOpenMessage = { whom, postId ->
                    openWhom = whom
                    pendingScrollMessageId = postId
                    digestOpen = false
                },
                onGenerateNow = { app.dailyDigest.generateAndNotifyAsync("user_test") },
            )

            adminGroupFlag != null -> GroupAdminScreen(
                db = app.db,
                repo = app.repo,
                flag = adminGroupFlag!!,
                onBack = { adminGroupFlag = null },
                modifier = mod,
                callController = callController,
                me = loggedInShip.orEmpty(),
            )

            adminListOpen -> GroupAdminListScreen(
                repo = app.repo,
                onBack = { adminListOpen = false },
                onOpenGroup = { adminGroupFlag = it },
                modifier = mod,
            )

            invitesOpen -> GroupInvitesScreen(
                repo = app.repo,
                onBack = { invitesOpen = false },
                modifier = mod,
            )

            openGroupFlag != null -> GroupHomeScreen(
                db = app.db,
                repo = app.repo,
                flag = openGroupFlag!!,
                onBack = { openGroupFlag = null },
                onOpenChannel = { nest ->
                    openGroupFlag = null
                    openWhom = nest
                },
                modifier = mod,
            )

            // Sidebar settings drills out of Settings; both flags
            // are true while the user is in Sidebar. Order this
            // branch BEFORE `settingsOpen` so the deeper screen
            // wins (mirrors App.kt's same fix).
            sidebarSettingsOpen -> {
                SidebarSettingsScreen(
                    repo = app.repo,
                    uiSettings = app.uiSettings,
                    dailyDigestEnabled = app.dailyDigestSettings.state
                        .collectAsState().value.enabled,
                    onBack = { sidebarSettingsOpen = false },
                    modifier = mod,
                )
            }

            assistantOpen -> io.nisfeb.talon.ui.screens.AssistantScreen(
                db = app.db,
                aiSettings = app.aiSettings,
                embedder = app.searchEmbedderClient,
                repo = app.repo,
                scheduler = app.loops,
                onRunLoop = { app.loops.runOneNow(it) },
                onBack = { assistantOpen = false },
                onOpenMessage = { whom, postId, parentId ->
                    assistantOpen = false
                    openWhom = whom
                    if (parentId != null) {
                        pendingThreadAnchor = postId
                        openThread = parentId
                    } else {
                        pendingScrollMessageId = postId
                    }
                },
                modifier = mod,
            )

            loopsOpen -> io.nisfeb.talon.ui.screens.LoopsScreen(
                db = app.db,
                scheduler = app.loops,
                onRunNow = { app.loops.runOneNow(it) },
                onBack = { loopsOpen = false },
                settingsSync = app.settingsSync,
                modifier = mod,
            )

            settingsOpen -> {
                // Observe via the app-level flows so a ship switch
                // re-renders the panel with the new ship's preview.
                // Reading `app.session.shipName` / `app.sessionStore
                // .all()` directly looks like it works but doesn't —
                // those are lateinit-mutated fields Compose can't
                // track. Same trap fixed in MainActivity for the
                // theme accent.
                val multiShip = allShips.size >= 2
                val ourPatp = loggedInShip
                val profileAccentPreview = ourPatp?.let { ship ->
                    contactMap.contact(ship)?.color?.let(::parseHexColor)
                }
                val relayClient = remember(app) {
                    io.nisfeb.talon.notify.RelayClient(
                        http = app.ktorHttp,
                        endpoint = { app.relaySettings.endpoint.value },
                    )
                }
                val activeShipUrl = remember(ourPatp) {
                    ourPatp?.let { p -> app.sessionStore.all().firstOrNull { it.ship == p } }?.shipUrl
                }
                val systemProbe = remember(app) {
                    io.nisfeb.talon.notify.AndroidSystemNotificationProbe(app)
                }
                SettingsScreen(
                    aiSettings = app.aiSettings,
                    themePreference = app.themePreference,
                    uiSettings = app.uiSettings,
                    callController = callController,
                    multiShip = multiShip,
                    profileAccentPreview = profileAccentPreview,
                    notificationHealth = app.notificationHealth,
                    systemNotificationProbe = systemProbe,
                    relayConfig = io.nisfeb.talon.ui.screens.RelayPanelConfig(
                        client = relayClient,
                        settings = app.relaySettings,
                        // UnifiedPush — vendor-neutral push, no
                        // Google. The provider asks the user's
                        // installed distributor (ntfy / NextPush /
                        // …) for an endpoint URL; the relay POSTs
                        // to it. If no distributor is installed,
                        // token() returns null and the panel
                        // surfaces an "install one" message.
                        pushTokens = remember(app) {
                            io.nisfeb.talon.notify.UnifiedPushTokenProvider(app)
                        },
                        activePatp = ourPatp,
                        activeShipUrl = activeShipUrl,
                    ),
                    onBack = { settingsOpen = false },
                    dailyDigestSettings = app.dailyDigestSettings,
                    onTestDigest = { app.dailyDigest.generateAndNotifyAsync("user_test") },
                    onOpenSidebarSettings = { sidebarSettingsOpen = true },
                    onOpenShareLoginQr = { shareLoginQrOpen = true },
                    onOpenLoops = { loopsOpen = true },
                    onMnemonymNamesChanged = { on ->
                        appScope.launch {
                            runCatching { app.repo.settingsSync?.pushMnemonymNames(on) }
                        }
                    },
                    onAlwaysPatpChanged = { on ->
                        appScope.launch {
                            runCatching { app.repo.settingsSync?.pushAlwaysPatp(on) }
                        }
                    },
                    modifier = mod,
                )
            }

            pendingShare != null -> ShareTargetScreen(
                db = app.db,
                share = pendingShare,
                onPick = { whom ->
                    dispatchShare(pendingShare, whom)
                    openWhom = whom
                    onShareConsumed()
                },
                onCancel = onShareConsumed,
                modifier = mod,
            )

            // Phase 3 right-pane on phone: drilldown takes priority so
            // back-press returns to GroupInfoScreen, not the chat.
            groupInfoDrilldown != null && groupInfoOpenFor != null -> MediaListScreen(
                db = app.db,
                repo = app.repo,
                http = app.ktorHttp,
                whom = groupInfoOpenFor!!,
                category = groupInfoDrilldown!!,
                onBack = { closeDrilldownAction() },
                onOpenImageList = { urls, idx ->
                    viewerImageList = io.nisfeb.talon.ui.screens
                        .ViewerImageList(urls, idx)
                },
                modifier = mod,
            )

            groupInfoOpenFor != null -> GroupInfoScreen(
                db = app.db,
                repo = app.repo,
                whom = groupInfoOpenFor!!,
                onBack = { closeRightPaneAction() },
                onOpenCategory = { openCategoryAction(it) },
                onOpenMembers = {
                    // Resolve channel-nest → group-flag because
                    // GroupAdminScreen takes a flag, not a whom.
                    val whom = groupInfoOpenFor
                    if (whom != null) {
                        appScope.launch {
                            val flag = runCatching {
                                app.db.groups().channelGroupFor(whom)?.groupFlag
                            }.getOrNull()
                            if (flag != null) {
                                adminGroupFlag = flag
                            }
                        }
                    }
                },
                modifier = mod,
            )

            openThread != null && openWhom != null -> {
                val threadLocationProvider = rememberLocationProvider()
                // Each thread gets its own /mic ↔ recorder bridge.
                // Re-keyed on (whom, parent) so opening a different
                // thread starts a fresh trigger.
                val threadMicTrigger = remember(openWhom, openThread) {
                    kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
                        extraBufferCapacity = 1,
                    )
                }
                ThreadScreen(
                    db = app.db,
                    repo = app.repo,
                    http = app.ktorHttp,
                    drafts = app.drafts,
                    ourPatp = loggedInShip ?: "",
                    whom = openWhom!!,
                    parentId = openThread!!,
                    initialScrollReplyId = pendingThreadAnchor,
                    onScrollConsumed = { pendingThreadAnchor = null },
                    onBack = { openThread = null },
                    onOpenConversation = openConversation,
                    onOpenImage = { viewerImageUrl = it },
                    voiceComposer = { enabled, onRecorded ->
                        VoiceRecordButton(
                            enabled = enabled,
                            onRecorded = onRecorded,
                            modifier = Modifier,
                            externalTrigger = threadMicTrigger,
                        )
                    },
                    onSlashMic = { threadMicTrigger.tryEmit(Unit) },
                    locationProvider = threadLocationProvider,
                    voicePlayer = { path, sending ->
                        VoicePreviewPlayButton(path = path, enabled = !sending)
                    },
                    powerFeaturesEnabled = app.uiSettings.powerFeaturesEnabled.collectAsState().value,
                    modifier = mod,
                )
            }

            openWhom != null && openWhom!!.startsWith("diary/") -> when {
                notebookComposeOpen -> NotebookComposeScreen(
                    repo = app.repo,
                    whom = openWhom!!,
                    onBack = {
                        notebookComposeOpen = false
                        notebookEditPostId = null
                    },
                    onPosted = {
                        notebookComposeOpen = false
                        notebookEditPostId = null
                    },
                    editPostId = notebookEditPostId,
                    initialTitle = notebookEditTitle,
                    initialImage = notebookEditImage,
                    initialBody = notebookEditBody,
                    originalSentMs = notebookEditSentMs,
                    modifier = mod,
                )
                openNotebookPostId != null -> NotebookPostScreen(
                    db = app.db,
                    repo = app.repo,
                    ourPatp = loggedInShip ?: "",
                    whom = openWhom!!,
                    postId = openNotebookPostId!!,
                    onBack = { openNotebookPostId = null },
                    onEdit = { title, image, body, sent ->
                        notebookEditPostId = openNotebookPostId
                        notebookEditTitle = title
                        notebookEditImage = image
                        notebookEditBody = body
                        notebookEditSentMs = sent
                        openNotebookPostId = null
                        notebookComposeOpen = true
                    },
                    modifier = mod,
                )
                else -> NotebookListScreen(
                    db = app.db,
                    repo = app.repo,
                    whom = openWhom!!,
                    onBack = { openWhom = null },
                    onOpenPost = { openNotebookPostId = it },
                    onCompose = { notebookComposeOpen = true },
                    modifier = mod,
                )
            }

            // %notes channels. Served by the %notes agent rather than
            // %channels, so they get their own screens instead of any of
            // the chat/bulletin/gallery plumbing.
            openWhom != null && openWhom!!.startsWith("notes/") -> when {
                openNoteId != null -> NoteScreen(
                    repo = app.repo,
                    whom = openWhom!!,
                    noteId = openNoteId!!,
                    onBack = { openNoteId = null },
                    modifier = mod,
                )
                else -> NotesChannelScreen(
                    repo = app.repo,
                    whom = openWhom!!,
                    onBack = { openWhom = null },
                    onOpenNote = { id -> openNoteId = id },
                    modifier = mod,
                )
            }

            openWhom != null && openWhom!!.startsWith("heap/") -> when {
                galleryComposeOpen -> GalleryComposeScreen(
                    repo = app.repo,
                    whom = openWhom!!,
                    onBack = { galleryComposeOpen = false },
                    onPosted = { galleryComposeOpen = false },
                    modifier = mod,
                )
                openGalleryPostId != null -> GalleryPostScreen(
                    db = app.db,
                    repo = app.repo,
                    ourPatp = loggedInShip ?: "",
                    whom = openWhom!!,
                    postId = openGalleryPostId!!,
                    onBack = { openGalleryPostId = null },
                    modifier = mod,
                )
                else -> GalleryGridScreen(
                    db = app.db,
                    repo = app.repo,
                    whom = openWhom!!,
                    onBack = { openWhom = null },
                    onOpenPost = { openGalleryPostId = it },
                    onCompose = { galleryComposeOpen = true },
                    modifier = mod,
                )
            }

            openWhom != null -> {
                val locationProvider = rememberLocationProvider()
                // Bridge between the `/mic` slash command and the
                // voice button. The slash handler emits Unit; the
                // button observes the same flow and toggles its
                // recorder. One SharedFlow per chat instance —
                // re-keyed by openWhom so a new chat starts a fresh
                // bridge.
                val micTrigger = remember(openWhom) {
                    kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
                        extraBufferCapacity = 1,
                    )
                }
                // The group line this chat belongs to, and only if it
                // exists: one line per group, gated on the group's
                // admins having enabled it. Same gate as App.kt.
                val hostedRooms by (
                    callController?.rooms
                        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
                    ).collectAsState()
                val knownInvites by (
                    callController?.invites
                        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
                    ).collectAsState()
                val groupRoom by androidx.compose.runtime.produceState<
                    Pair<String, String>?
                    >(null, openWhom) {
                    value = openWhom?.let {
                        io.nisfeb.talon.call.PartyLineHost.roomFor(app.db, it)
                    }
                }
                // Ask the host once per group whether a line exists,
                // when we hold no invite. A member whose ship had no
                // %trunk when the host announced never heard about it,
                // and before this the only cure was an admin toggling
                // the line off and on. Mirrors App.kt.
                LaunchedEffect(groupRoom, knownInvites.keys, hostedRooms.keys) {
                    val (h, n) = groupRoom ?: return@LaunchedEffect
                    val key = "$h/$n"
                    // A few widening attempts, then stop. One try per
                    // group open was enough only when the host happened
                    // to be reachable at that instant; an ames round
                    // trip to a sleeping ship is not.
                    var wait = 2_000L
                    repeat(PEEK_ATTEMPTS) { attempt ->
                        if (hostedRooms.containsKey(key)) return@LaunchedEffect
                        if (knownInvites.containsKey(key)) return@LaunchedEffect
                        callController?.peekRoom(h, n)
                        if (attempt < PEEK_ATTEMPTS - 1) {
                            kotlinx.coroutines.delay(wait)
                            wait *= 3
                        }
                    }
                }
                val partyRoomHere = groupRoom?.takeIf { (h, n) ->
                    hostedRooms.containsKey("$h/$n") || knownInvites.containsKey("$h/$n")
                }
                // Live occupancy (wire 6) — see App.kt for the rationale.
                val presenceFlow = remember(callController) {
                    callController?.presence
                        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
                }
                val presence by presenceFlow.collectAsState()
                val partyPresent = partyRoomHere?.let { (h, n) -> presence["$h/$n"] } ?: 0
                LaunchedEffect(partyRoomHere) {
                    val (h, n) = partyRoomHere ?: return@LaunchedEffect
                    while (true) {
                        callController?.occupancyOf(h, n)
                        kotlinx.coroutines.delay(20_000)
                    }
                }
                val partyStateFlow = remember(partyLine) {
                    partyLine?.state
                        ?: kotlinx.coroutines.flow.MutableStateFlow(
                            io.nisfeb.talon.call.PartyState.Idle,
                        )
                }
                val partyStateNow by partyStateFlow.collectAsState()
                val onThisLine = partyStateNow is io.nisfeb.talon.call.PartyState.Live
                LaunchedEffect(partyRoomHere, onThisLine) {
                    val (h, n) = partyRoomHere ?: return@LaunchedEffect
                    val cc = callController ?: return@LaunchedEffect
                    if (!onThisLine) return@LaunchedEffect
                    try {
                        while (true) {
                            cc.enterRoom(h, n)
                            kotlinx.coroutines.delay(30_000)
                        }
                    } finally {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            cc.leaveRoom(h, n)
                        }
                    }
                }
                // Why we couldn't find out, when we couldn't. Silence
                // here is what turns "no line in this group" and "your
                // ship can't ask" into the same thing.
                val peekProblems by (
                    callController?.peekFailed
                        ?: kotlinx.coroutines.flow.MutableStateFlow(
                            emptyMap<String, String>(),
                        )
                    ).collectAsState()
                val peekProblem = groupRoom?.let { (h, n) ->
                    peekProblems["$h/$n"]
                }
                // Call recording (wire 7) — shared with the desktop root.
                val recordingControls = io.nisfeb.talon.ui.rememberPartyRecording(
                    callController = callController,
                    partyLine = partyLine,
                    partyRoomHere = partyRoomHere,
                    onThisLine = onThisLine,
                    http = app.ktorHttp,
                    aiConfig = app.aiSettings.state.value,
                    ourShip = loggedInShip.orEmpty(),
                    conversationTitle = contactMap.conversationLabel(openWhom.orEmpty()),
                    nameFor = { contactMap.displayName(it) },
                )
                DmChatScreen(
                    db = app.db,
                    repo = app.repo,
                    drafts = app.drafts,
                    http = app.ktorHttp,
                    aiSettings = app.aiSettings,
                    uiSettings = app.uiSettings,
                    ourPatp = loggedInShip ?: "",
                    whom = openWhom!!,
                    initialScrollMessageId = pendingScrollMessageId,
                    onScrollConsumed = { pendingScrollMessageId = null },
                    onBack = { openWhom = null },
                    onOpenThread = { parentId -> openThreadAction(parentId, null) },
                    onOpenThreadAt = { parent, anchor -> openThreadAction(parent, anchor) },
                    onOpenConversation = openConversation,
                    onOpenGroupInfo = {
                        openWhom?.let { openGroupInfoAction(it) }
                    },
                    onOpenImage = { viewerImageUrl = it },
                    onOpenSelfProfile = { editingProfile = true },
                    onStartCall =
                        if (callController != null && openWhom!!.startsWith("~")) {
                            { callController.placeCall(openWhom!!) }
                        } else {
                            null
                        },
                    // No line, no button — see the same gate in App.kt.
                    onPartyLine =
                        if (callController != null && partyLine != null &&
                            partyRoomHere != null
                        ) {
                            {
                                val whom = openWhom!!
                                val host = partyRoomHere!!.first
                                if (host == loggedInShip) {
                                    appScope.launch {
                                        io.nisfeb.talon.call.PartyLineHost.startLine(
                                            callController, app.repo, app.db, whom,
                                            // The room title everyone sees —
                                            // the conversation's human label,
                                            // not the raw channel key.
                                            contactMap.conversationLabel(whom),
                                        )
                                    }
                                } else {
                                    appScope.launch {
                                        io.nisfeb.talon.call.PartyLineHost
                                            .joinLine(callController, app.db, whom)
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    partyPresent = partyPresent,
                    partyLineBar = partyLine?.let { line ->
                        {
                            // Tell the app-level overlays the inline
                            // surfaces are on screen, so the floating
                            // fallbacks stay hidden exactly while this
                            // slot composes. Mirrors App.kt — including
                            // the guarded composition-time write, since
                            // DisposableEffect fires only after the
                            // frame draws and the first frame otherwise
                            // shows both surfaces at once.
                            if (!inlineCallUiShown.value) {
                                inlineCallUiShown.value = true
                            }
                            DisposableEffect(Unit) {
                                inlineCallUiShown.value = true
                                onDispose { inlineCallUiShown.value = false }
                            }
                            // Persist mutes only when the line we're on
                            // IS this chat's line: the bar renders in
                            // every chat slot, and an op moderating
                            // line A from group B's chat must not
                            // poison B's muted list. SFU-side
                            // moderation still lands either way.
                            val moderateState by line.state.collectAsState()
                            val moderateRoom =
                                (moderateState as? io.nisfeb.talon.call.PartyState.Live)?.room
                            // Our own desk relays the moderate poke; a
                            // wire-4 desk nacks it invisibly, so don't
                            // pretend to persist.
                            val moderateWire =
                                callController?.wire?.collectAsState()?.value ?: 0
                            io.nisfeb.talon.ui.PartyLineBar(
                                line,
                                nameFor = { contactMap.displayName(it) },
                                audioDevices = androidAudioDevices,
                                selfShip = loggedInShip.orEmpty(),
                                // Leave the immersive full-screen when a
                                // call rings: it is a Dialog, the ring is
                                // a Popup, and the ring lost.
                                incomingCall = callController
                                    ?.state?.collectAsState()?.value
                                    is io.nisfeb.talon.call.CallUiState.Incoming,
                                // Persist ops mutes on the host ship, so
                                // they outlive the target's connection.
                                onModerate =
                                    if (callController != null &&
                                        moderateWire >= 5 &&
                                        partyRoomHere != null &&
                                        partyRoomHere!!.second == moderateRoom
                                    ) {
                                        { who, mute ->
                                            val (h, n) = partyRoomHere!!
                                            appScope.launch {
                                                callController
                                                    .moderateMember(h, n, who, mute)
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                recording = recordingControls.recording,
                                recordedBy = recordingControls.recordedBy,
                                onToggleRecord = recordingControls.onToggleRecord,
                            )
                            // Between tapping the party icon and the
                            // host's grant the line is still Idle and
                            // the bar above renders nothing — show the
                            // ask in flight, with its only meaningful
                            // control: withdrawing. Mirrors App.kt.
                            callController?.let { c ->
                                val pendingAsk by c.pendingJoin.collectAsState()
                                val lineState by line.state.collectAsState()
                                val roomKey = groupRoom?.let { (h, n) -> "$h/$n" }
                                if (pendingAsk != null &&
                                    pendingAsk == roomKey &&
                                    lineState is io.nisfeb.talon.call.PartyState.Idle
                                ) {
                                    io.nisfeb.talon.ui.PartyLineAsking(
                                        onCancel = { c.cancelJoin() },
                                    )
                                }
                            }
                            callController?.let { c ->
                                io.nisfeb.talon.ui.CallStrip(
                                    c,
                                    nameFor = { contactMap.displayName(it) },
                                    audioDevices = androidAudioDevices,
                                )
                            }
                            // Only while the line is actually unknown.
                            // A stale failure entry next to a working
                            // line read as the line being broken.
                            if (partyRoomHere == null) {
                                peekProblem?.let { why ->
                                    io.nisfeb.talon.ui.PartyLineUnavailable(why)
                                }
                            }
                        }
                    },
                    searchEmbedder = app.searchEmbedderClient,
                    scrollState = chatScrollState,
                    scrollAnchored = chatScrollAnchored,
                    // Android-only platform widgets — desktop hosts pass null
                    // and the screen degrades gracefully. Wired via
                    // composable slots so commonMain has no Android deps.
                    voiceComposer = { enabled, onRecorded ->
                        VoiceRecordButton(
                            enabled = enabled,
                            onRecorded = onRecorded,
                            modifier = Modifier,
                            externalTrigger = micTrigger,
                        )
                    },
                    onSlashMic = {
                        // tryEmit is sync (no coroutine needed) and
                        // can't fail because the buffer has room — the
                        // VoiceRecordButton's collect picks it up on
                        // the next frame and toggles the recorder.
                        micTrigger.tryEmit(Unit)
                    },
                    locationProvider = locationProvider,
                    voicePlayer = { path, sending ->
                        // Compact play/pause-only button for the
                        // in-progress voice-preview row. The full
                        // scrubber + duration of InlineAudioPlayer is
                        // overkill here and visually heavier than the
                        // pre-Stage-F UI was. `sending` disables the
                        // control during upload, matching the old
                        // behavior where the user couldn't replay an
                        // already-uploading recording.
                        VoicePreviewPlayButton(path = path, enabled = !sending)
                    },
                    modifier = mod,
                )
            }

            searchOpen -> SearchScreen(
                db = app.db,
                aiSettings = app.aiSettings,
                uiSettings = app.uiSettings,
                embedder = app.searchEmbedderClient,
                // searchOpen stays true under the chat/thread so back
                // pops back to the highlights/results list instead of
                // home. The when-block ordering hides SearchScreen
                // while a conversation is open.
                onOpenConversation = { whom ->
                    pendingScrollMessageId = null
                    openWhom = whom
                },
                onOpenMessage = { whom, postId, parentId ->
                    openWhom = whom
                    if (parentId != null) {
                        // Reply: anchor the thread on this reply.
                        pendingThreadAnchor = postId
                        openThread = parentId
                    } else {
                        // Top-level: anchor the chat on the message.
                        pendingScrollMessageId = postId
                    }
                },
                onOpenGroup = { flag ->
                    searchOpen = false
                    openWhom = null
                    revealGroupRequest = flag
                },
                onBack = { searchOpen = false },
                modifier = mod,
            )

            newDmOpen -> NewDmScreen(
                db = app.db,
                onPickPeer = { patp ->
                    newDmOpen = false
                    openWhom = patp
                },
                onBack = { newDmOpen = false },
                onAddContact = { patp, nickname ->
                    appScope.launch { runCatching { app.repo.addContact(patp, nickname) } }
                },
                bookContacts = bookContacts,
                modifier = mod,
            )

            else -> DmListScreen(
                db = app.db,
                repo = app.repo,
                drafts = app.drafts,
                updateState = app.updateState,
                menuSeen = app.menuSeen,
                onOpenConversation = {
                    pendingScrollMessageId = null
                    openWhom = it
                },
                onOpenSearch = { searchOpen = true },
                // Opt-in + key-gated assistant entry point. Mirrors
                // App.kt's gate. isAssistantSupported is true on both
                // platforms — the embedder only enhances retrieval.
                onOpenAssistant = if (isAssistantSupported &&
                    aiState.assistantOn() &&
                    aiState.hasKey()
                ) {
                    { assistantOpen = true }
                } else null,
                onNewMessage = { newDmOpen = true },
                onOpenSelfProfile = { editingProfile = true },
                onOpenStatusFeed = { statusFeedOpen = true },
                onOpenBookmarks = { bookmarksOpen = true },
                onOpenActivity = { activityOpen = true },
                onOpenContacts = { contactsOpen = true },
                onOpenWatchwords = { watchwordsOpen = true },
                onOpenDigest = { digestOpen = true },
                digestEnabled = app.dailyDigestSettings.state.collectAsState().value.enabled,
                onOpenAdministration = { adminListOpen = true },
                onOpenInvites = { invitesOpen = true },
                onOpenSettings = { settingsOpen = true },
                onSignOut = {
                    app.signOutActive()
                    TalonSyncService.stop(context)
                    openWhom = null
                    switchShipAction()
                    searchOpen = false
                    newDmOpen = false
                },
                allShips = allShips,
                activeShip = loggedInShip,
                shipNicknames = app.shipProfiles.nicknames.collectAsState().value,
                onSwitchShip = { ship ->
                    if (ship != loggedInShip) {
                        openWhom = null
                        switchShipAction()
                        searchOpen = false
                        newDmOpen = false
                        app.switchShip(ship)
                    }
                },
                onAddShip = { addingAnotherShip = true },
                onOpenShipSwitcher = {
                    drawerScope.launch { drawerState.open() }
                },
                // Android-only OEM-killer nudge banner. Desktop hosts
                // pass null and the slot is hidden.
                batteryBanner = { BatteryExemptionBanner() },
                groupChannelOrder = app.uiSettings.groupChannelOrder
                    .collectAsState().value,
                folderItemOrder = app.uiSettings.folderItemOrder
                    .collectAsState().value,
                kebabItems = RailItem.entries.toSet(),
                revealGroupFlag = revealGroupRequest,
                onRevealGroupHandled = { revealGroupRequest = null },
                modifier = mod,
            )
        }
        } // ModalNavigationDrawer body

        // Profile sheet overlay — opened from the status feed, and from
        // any other screen that sets `profileSheetShip`. Reads the
        // live contact entity directly: contactMap suppresses status-
        // only emissions for perf, so the map's snapshot can be stale
        // for the status / bio fields the sheet displays.
        profileSheetShip?.let { ship ->
            val freshContact by remember(ship) {
                app.db.contacts().streamOne(ship)
            }.collectAsState(initial = null)
            ContactProfileSheet(
                ship = ship,
                self = ship == (loggedInShip ?: ""),
                contact = freshContact,
                onMessage = {
                    profileSheetShip = null
                    statusFeedOpen = false
                    openWhom = ship
                },
                onEditSelf = {
                    profileSheetShip = null
                    editingProfile = true
                },
                onDismiss = { profileSheetShip = null },
                isInBook = ship in bookContacts,
                onAddContact = {
                    val target = ship
                    profileSheetShip = null
                    appScope.launch { runCatching { app.repo.addContact(target) } }
                },
                onRemoveContact = {
                    val target = ship
                    profileSheetShip = null
                    appScope.launch { runCatching { app.repo.removeContact(target) } }
                },
            )
        }
        } // key(loggedInShip)
    }
    } // CompositionLocalProvider(LocalInlineMediaPlayer)
}

/**
 * Ask for RECORD_AUDIO and block until the user answers.
 *
 * The 1:1 path has had this since the first call; the party-line path
 * had no gate at all, so joining without the permission built a
 * sending link whose mic was dead — the bar said Live, the room heard
 * silence, and nothing ever said why.
 *
 * Returns whether the mic is usable. Blocking, for the same reason
 * [rememberCallEngineProvider] blocks: the caller is a non-suspending
 * factory already running on an IO dispatcher.
 */
@Composable
private fun rememberMicGate(): () -> Boolean {
    val context = LocalContext.current
    val pending = androidx.compose.runtime.remember {
        java.util.concurrent.atomic.AtomicReference<
            kotlinx.coroutines.CompletableDeferred<Boolean>?,
            >(null)
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> pending.getAndSet(null)?.complete(granted) }
    return androidx.compose.runtime.remember {
        {
            val already = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            already || run {
                val wait = kotlinx.coroutines.CompletableDeferred<Boolean>()
                pending.set(wait)
                launcher.launch(android.Manifest.permission.RECORD_AUDIO)
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(60_000L) { wait.await() } ?: false
                }
            }
        }
    }
}

/**
 * Trunkline engine factory with the mic-permission gate. If
 * RECORD_AUDIO isn't granted yet, launch the system prompt and HOLD
 * the call on its result instead of failing fast — the fail-fast
 * engine answered an incoming call with an error poke while the
 * permission dialog was still on screen, burning the user's
 * first-ever call on the prompt. Deny (or dismiss) still falls
 * through to the Unavailable engine, whose message says what to fix.
 */
@Composable
private fun rememberCallEngineProvider(): io.nisfeb.talon.call.CallEngineProvider {
    val context = LocalContext.current
    // Completed by the system dialog's result; non-null exactly while
    // a call below is holding for the answer.
    val pendingGrant = androidx.compose.runtime.remember {
        java.util.concurrent.atomic.AtomicReference<
            kotlinx.coroutines.CompletableDeferred<Boolean>?,
            >(null)
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> pendingGrant.getAndSet(null)?.complete(granted) }
    return androidx.compose.runtime.remember {
        io.nisfeb.talon.call.CallEngineProvider { ice ->
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val nowGranted = granted || run {
                val wait = kotlinx.coroutines.CompletableDeferred<Boolean>()
                pendingGrant.set(wait)
                launcher.launch(android.Manifest.permission.RECORD_AUDIO)
                // ponytail: create() runs on CallController's IO
                // dispatcher, so blocking one pool thread on the dialog
                // beats a delegating gate engine. Capped at the
                // controller's own 60s connect watchdog, past which
                // the call is over anyway.
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(60_000L) { wait.await() } ?: false
                }
            }
            if (nowGranted) {
                io.nisfeb.talon.call.AndroidCallEngine(context.applicationContext, ice)
            } else {
                io.nisfeb.talon.call.UnavailableCallEngine(
                    "Microphone access needed — grant it, then call again",
                )
            }
        }
    }
}

/** Undo MainActivity's show-over-lock-screen once the call is over. */
private fun stopShowingOverLockScreen(context: android.content.Context) {
    val activity = context as? android.app.Activity ?: return
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
        activity.setShowWhenLocked(false)
        activity.setTurnScreenOn(false)
    }
}
