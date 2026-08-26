@file:OptIn(DelicateCoroutinesApi::class)

package io.nisfeb.talon.compose
import io.nisfeb.talon.util.ioDispatcher
import io.nisfeb.talon.util.isMacOsHost
import io.nisfeb.talon.util.nowMs

import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ui.parseHexColor
import io.nisfeb.talon.ai.InMemoryWatchwordsSyncSettings
import io.nisfeb.talon.ai.WatchwordsSyncSettings
import io.nisfeb.talon.notify.NoopNotifier
import io.nisfeb.talon.notify.Notifier
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.ui.InMemoryUiSettings
import io.nisfeb.talon.ui.DesktopShell
import io.nisfeb.talon.ui.ExpandedThreshold
import io.nisfeb.talon.ui.MenuBadges
import io.nisfeb.talon.ui.PlatformBackHandler
import io.nisfeb.talon.ui.RailItem
import io.nisfeb.talon.ui.RailTab
import io.nisfeb.talon.ui.invitesSnapshot
import io.nisfeb.talon.ui.isAssistantSupported
import io.nisfeb.talon.ui.isVisible
import io.nisfeb.talon.ui.RightPaneContent
import io.nisfeb.talon.ui.RightPaneState
import io.nisfeb.talon.ui.RightPaneStateReducer
import io.nisfeb.talon.ui.RightPaneHost
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.ui.screens.ActivityList
import io.nisfeb.talon.ui.screens.AssistantScreen
import io.nisfeb.talon.ui.screens.BookmarksList
import io.nisfeb.talon.ui.screens.DmChatScreen
import io.nisfeb.talon.ui.screens.DmListScreen
import io.nisfeb.talon.ui.screens.ActivityFeedScreen
import io.nisfeb.talon.ui.screens.BookmarksScreen
import io.nisfeb.talon.ui.screens.StatusFeedList
import io.nisfeb.talon.ui.screens.DailyDigestScreen
import io.nisfeb.talon.ui.screens.GalleryComposeScreen
import io.nisfeb.talon.ui.screens.GalleryGridScreen
import io.nisfeb.talon.ui.screens.GalleryPostScreen
import io.nisfeb.talon.ui.screens.GroupAdminListScreen
import io.nisfeb.talon.ui.screens.GroupAdminScreen
import io.nisfeb.talon.ui.screens.GroupHomeScreen
import io.nisfeb.talon.ui.screens.GroupInfoScreen
import io.nisfeb.talon.ui.screens.GroupInvitesScreen
import io.nisfeb.talon.ui.screens.ImageViewerScreen
import io.nisfeb.talon.ui.screens.LoginScreen
import io.nisfeb.talon.ui.screens.MediaListScreen
import io.nisfeb.talon.ui.screens.NewDmScreen
import io.nisfeb.talon.ui.screens.NotebookComposeScreen
import io.nisfeb.talon.ui.screens.NoteScreen
import io.nisfeb.talon.ui.screens.NotebookListScreen
import io.nisfeb.talon.ui.screens.NotesChannelScreen
import io.nisfeb.talon.ui.screens.NotebookPostScreen
import io.nisfeb.talon.ui.screens.ProfileEditScreen
import io.nisfeb.talon.ui.screens.SearchScreen
import io.nisfeb.talon.ui.screens.SettingsScreen
import io.nisfeb.talon.ui.screens.SidebarSettingsScreen
import io.nisfeb.talon.ui.screens.StatusFeedScreen
import io.nisfeb.talon.ui.screens.ThreadScreen
import io.nisfeb.talon.ui.screens.WatchwordsScreen
import io.nisfeb.talon.ui.theme.InMemoryThemePreference
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.ui.theme.ThemePreference
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.MediaBackfillWorker
import io.nisfeb.talon.urbit.MediaCategory
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.SettingsSync
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.Log
import io.ktor.client.HttpClient

/**
 * Top-level shared app entry point. Both Android's MainActivity and
 * desktop's Main.kt mount this. Takes process-singleton dependencies;
 * ship-scoped state (UrbitSession + TlonChatRepo) is constructed
 * inside a [key] block so sign-out + sign-in fully rebuilds it. The
 * key avoids two long-standing footguns:
 *   - TlonChatRepo.start has a `started` short-circuit that strands
 *     the second login if the same instance is re-used.
 *   - TlonChatRepo.stop calls scope.cancel which permanently dies;
 *     the rebuild gets a fresh scope per ship.
 */
@Composable
fun App(
    http: HttpClient,
    sessionStore: SessionStore,
    aiSettings: AiSettingsRepository,
    /** Builds a per-ship AppDatabase. Called inside `key(shipKey)` so each
     *  ship's data lives in its own SQLite file — without this the DM
     *  list and unread counts cross-pollinate when the user switches. */
    createDb: (shipKey: String) -> AppDatabase,
    drafts: DraftStore,
    updateState: UpdateState,
    /** Builds a SettingsSync bound to the per-ship db. Null on platforms
     *  without %settings sync wired. */
    createSettingsSync: ((AppDatabase) -> SettingsSync)? = null,
    /** Per-process daily-digest config. Null on platforms without a
     *  digest impl wired (Android composeApp today). When non-null,
     *  DmListScreen reveals the "Today's brief" drawer entry only
     *  if the user enabled the alarm. */
    dailyDigestSettings: io.nisfeb.talon.ai.DailyDigestSettings? = null,
    /** Source of truth for the "mirror watchwords to %settings" toggle.
     *  Defaults to in-memory; desktop passes a JSON-backed impl so the
     *  flag survives restart. */
    watchwordsSync: WatchwordsSyncSettings = InMemoryWatchwordsSyncSettings(),
    /** Per-device theme override (System / Light / Dark). In-memory by
     *  default; desktop passes a JSON-backed impl so the choice
     *  survives restart. */
    themePreference: ThemePreference = InMemoryThemePreference(),
    /** Trunkline call engine — platform entry points pass a real
     *  provider where isCallsSupported; null keeps calls dark. */
    callEngineProvider: io.nisfeb.talon.call.CallEngineProvider? = null,
    /** Party-line media factory (one link per SFU stream). Null keeps
     *  party lines dark even where 1:1 calls work. */
    peerLinkFactory: io.nisfeb.talon.call.PeerLinkFactory? = null,
    /** OS-level notifier. Desktop wires a tray-balloon impl; other
     *  platforms (Android composeApp) get the no-op default until
     *  their notification stories port. */
    notifier: Notifier = NoopNotifier,
    /** Per-ship UiSettings factory. Called inside the `key(shipKey)`
     *  block so the rail-visibility flow (sourced from the per-ship
     *  `rail_item_prefs` Room table) reflects the active ship. Default
     *  ignores the db arg and hands back an in-memory instance for
     *  tests. */
    createUiSettings: (AppDatabase) -> UiSettings = { InMemoryUiSettings() },
    /** Process-wide diagnostics for the Notification Health panel.
     *  Single instance shared by all consumers (repo writes,
     *  Settings reads, future relay reads). Defaults to a fresh
     *  instance for tests. */
    notificationHealth: io.nisfeb.talon.notify.NotificationHealth =
        io.nisfeb.talon.notify.NotificationHealth(),
    /** Optional factory for the on-device search embedder. Desktop
     *  passes a DJL-ONNX-backed impl that powers smart search +
     *  important-message highlights. Null means no smart features
     *  on this build (the screen falls back to substring search). */
    createSearchEmbedderClient: ((AppDatabase) -> io.nisfeb.talon.ai.SearchEmbedderClient)? = null,
    /** Saves images shown in the fullscreen viewer to the user's
     *  device. NoopImageDownloader (default) returns Unsupported and
     *  the viewer hides its download button; production hosts pass
     *  their platform-specific impl. */
    imageDownloader: io.nisfeb.talon.ui.ImageDownloader =
        io.nisfeb.talon.ui.NoopImageDownloader,
    /** Persistent relay-registration state — endpoint URL + per-ship
     *  device ids the relay assigned. In-memory default for tests;
     *  desktop/Android pass JSON / SharedPrefs impls. */
    relaySettings: io.nisfeb.talon.notify.RelaySettings =
        io.nisfeb.talon.notify.InMemoryRelaySettings(),
    /** Source of FCM/APNS/desktop-webhook push tokens. Defaults to
     *  the NoPushTokenProvider so the relay panel renders a friendly
     *  "no push token available on this build" message instead of
     *  silently doing nothing. */
    pushTokenProvider: io.nisfeb.talon.notify.PushTokenProvider =
        io.nisfeb.talon.notify.NoPushTokenProvider,
    /** OS-level system notification probe (battery / restriction /
     *  permission status). Defaults to a no-op so desktop hosts and
     *  tests don't have to wire one — Android passes
     *  AndroidSystemNotificationProbe and gets the real signals. */
    systemNotificationProbe: io.nisfeb.talon.notify.SystemNotificationProbe =
        io.nisfeb.talon.notify.NoopSystemNotificationProbe,
    /** Per-ship factory for the menu-seen store. Called inside the
     *  `key(shipKey)` block so a ship-switch yields a fresh seen-
     *  state from that ship's persisted file (or SharedPreferences
     *  on Android). Defaults to NoopMenuSeenStore for tests. */
    createMenuSeen: (ship: String) -> io.nisfeb.talon.ui.MenuSeenStore =
        { io.nisfeb.talon.ui.NoopMenuSeenStore },
    /** Per-ship "what chat was open last" memory. Wide windows seed
     *  the right pane from this so the user lands back on their
     *  conversation instead of an empty pane. Default Noop for tests. */
    lastOpenChatStore: io.nisfeb.talon.notify.LastOpenChatStore =
        io.nisfeb.talon.notify.NoopLastOpenChatStore,
    /** Opens urb:// links (Lattice handoff). Desktop passes
     *  DesktopUrbLinkLauncher, Android AndroidUrbLinkLauncher; the
     *  Noop default reports NotInstalled so tests / unwired hosts
     *  show the install prompt rather than swallowing taps. */
    urbLinkLauncher: io.nisfeb.talon.urbit.UrbLinkLauncher =
        io.nisfeb.talon.urbit.NoopUrbLinkLauncher,
) {
    // Derive the initial logged-in ship from sessionStore.active()
    // (the joined SavedSession) rather than activeShip() (just the
    // ship pointer). active() returns null when the pointer is stale
    // — i.e. when activeShip points at a ship whose entry was
    // removed but the pointer wasn't repaired. Otherwise the next
    // tryRestore() returns null while loggedInShip stays non-null
    // and repo.start crashes on session.ourPatp ("not logged in").
    var loggedInShip by remember { mutableStateOf(sessionStore.active()?.ship) }
    var showSettings by remember { mutableStateOf(false) }
    var showSidebarSettings by remember { mutableStateOf(false) }
    var showLoops by remember { mutableStateOf(false) }
    var openChat by remember { mutableStateOf<String?>(null) }
    // Optional message id to scroll-and-flash when DmChatScreen mounts /
    // re-mounts on a new whom. Set when navigating from bookmarks (or any
    // surface that points at a specific message); cleared by DmChatScreen's
    // onScrollConsumed once it finds + scrolls to the row, or on whom switch
    // so a stale anchor can't paint on the wrong chat.
    var openChatFocusMessageId by remember { mutableStateOf<String?>(null) }
    /** Login-handoff QR generator. Reachable from LoginScreen
     *  ("Helping someone else? Generate a login QR →") on cold launch
     *  and from Settings once a ship is logged in. */
    var shareLoginQrOpen by remember { mutableStateOf(false) }
    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    // Multi-image viewer state — set by the photo / gif drilldown
    // (MediaListPane) so the viewer's prev/next + arrow-key
    // navigation can step through the list. Single-image flows (chat
    // row tap, gallery, notebook) keep using viewerImageUrl above and
    // wrap into a singleton list at the call site.
    var viewerImageList by remember {
        mutableStateOf<io.nisfeb.talon.ui.screens.ViewerImageList?>(null)
    }
    var openThreadParent by remember { mutableStateOf<String?>(null) }
    var openThreadReplyAnchor by remember { mutableStateOf<String?>(null) }
    // Chat scroll position + first-anchor flag, owned here so they survive
    // DmChatScreen unmounting when a thread opens on compact (the thread is
    // its own full-screen when-branch). Keyed on openChat: preserved across
    // the thread round-trip, reset on an actual chat switch so a freshly
    // opened conversation still snaps to the newest message.
    val chatScrollState = remember(openChat) { LazyListState() }
    val chatScrollAnchored = remember(openChat) { mutableStateOf(false) }
    var groupInfoOpenFor by remember { mutableStateOf<String?>(null) }
    var groupInfoDrilldown by remember { mutableStateOf<MediaCategory?>(null) }
    // Right-pane state mutators — delegate to RightPaneStateReducer
    // so the mutual-exclusion rules (opening a thread closes group
    // info, switching ships clears everything, etc.) live in one
    // tested place. Adding a new transition is a one-touch reducer
    // change + one helper here, not a 7-site grep + write-site
    // update — that scattering pattern leaked three classes of
    // state-mutex bugs through the rc6 audit.
    val rightPaneSnapshot: () -> RightPaneState = {
        RightPaneState(
            openThreadParent = openThreadParent,
            openThreadReplyAnchor = openThreadReplyAnchor,
            groupInfoOpenFor = groupInfoOpenFor,
            groupInfoDrilldown = groupInfoDrilldown,
        )
    }
    val applyRightPaneState: (RightPaneState) -> Unit = { next ->
        openThreadParent = next.openThreadParent
        openThreadReplyAnchor = next.openThreadReplyAnchor
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
    var showSelfProfile by remember { mutableStateOf(false) }
    var showStatusFeed by remember { mutableStateOf(false) }
    var showInvites by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showActivity by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showAssistant by remember { mutableStateOf(false) }
    /** The assistant claims the whole content area; a thread or group-info
     *  pane left open would dock beside it and swallow clicks meant for it. */
    val openAssistantAction: () -> Unit = {
        closeRightPaneAction()
        showAssistant = true
    }
    var showNewDm by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var showWatchwords by remember { mutableStateOf(false) }
    var showDailyDigest by remember { mutableStateOf(false) }
    var showGroupAdminList by remember { mutableStateOf(false) }
    var openGroupAdminFlag by remember { mutableStateOf<String?>(null) }
    var openGroupHomeFlag by remember { mutableStateOf<String?>(null) }
    // Notebook overlay state. notebookComposeOpen + notebookEdit*
    // mirror production's edit flow: tap Edit on a post → close
    // the viewer, capture the existing fields into the edit-* vars,
    // open compose with that context. Compose's onPosted clears.
    var notebookComposeOpen by remember { mutableStateOf(false) }
    var openNotebookPostId by remember { mutableStateOf<String?>(null) }
    var notebookEditPostId by remember { mutableStateOf<String?>(null) }
    var notebookEditTitle by remember { mutableStateOf("") }
    var notebookEditImage by remember { mutableStateOf("") }
    var notebookEditBody by remember { mutableStateOf("") }
    var notebookEditSentMs by remember { mutableStateOf(0L) }
    // Gallery: simpler — no in-place edit on desktop yet.
    var galleryComposeOpen by remember { mutableStateOf(false) }
    var openGalleryPostId by remember { mutableStateOf<String?>(null) }
    // %notes (v12 Markdown notebooks): which note is open inside a
    // notes/ channel. Null = showing the notebook's folder tree.
    // Keyed on openChat: note ids are only unique within a notebook, so
    // an id left over from the last one would render a note that doesn't
    // exist here — an endless spinner on a freshly opened notebook.
    var openNoteId by remember(openChat) { mutableStateOf<Long?>(null) }
    var profileSheetShip by remember { mutableStateOf<String?>(null) }
    // Watchwords-sync flag. Backed by [watchwordsSync] (caller-supplied)
    // so desktop's JSON-file impl can persist across restarts and
    // production Android can wire its SharedPreferences variant in
    // when composeApp lands there.
    val watchwordsSyncEnabled = watchwordsSync.enabled
    // Hoisted at App level (not inside the key block) so it survives
    // the re-key triggered by tryRestore-failure recovery. Cleared
    // automatically once the user successfully signs back in.
    var loginNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loggedInShip) {
        if (loggedInShip != null) loginNotice = null
    }

    // Keyboard-shortcut request flags. Hoisted outside key() so the
    // onPreviewKeyEvent handler (on the Surface inside key()) can flip
    // them, and DmListScreen (also inside key()) can consume them.
    var focusSearchRequest by remember { mutableStateOf(false) }
    // Set when a group is tapped in search; consumed by DmListScreen to
    // scroll-to + expand that group on the home list.
    var revealGroupRequest by remember { mutableStateOf<String?>(null) }
    var showNewDmRequest by remember { mutableStateOf(false) }

    // Used by the onPreviewKeyEvent handler to pick the right modifier
    // key (Cmd on macOS, Ctrl everywhere else).
    val isMacHost = isMacOsHost

    // Seed openChat from the store when we (re)land on a ship — so
    // wide windows restore the user's last conversation instead of
    // showing an empty right pane. Must be AFTER `var openChat` and
    // keyed on loggedInShip so a ship-switch seeds the new ship's entry.
    val seedChat by lastOpenChatStore.state.collectAsState()
    // True once the seed has had its chance for the current ship. Guards
    // the mirror effect below so the pre-seed `openChat == null` at launch
    // doesn't clear the persisted entry before we've restored from it.
    var chatSeeded by remember(loggedInShip) { mutableStateOf(false) }
    LaunchedEffect(loggedInShip) {
        if (loggedInShip != null) {
            if (openChat == null) seedChat[loggedInShip]?.let { openChat = it }
            chatSeeded = true
        }
    }

    // Mirror openChat into the store so the next launch / ship switch
    // restores where the user actually left off: persist on open, and
    // CLEAR on back-out (openChat == null) so closing on the home list
    // reopens to the list rather than forcing the last chat back open.
    LaunchedEffect(openChat, loggedInShip) {
        val ship = loggedInShip ?: return@LaunchedEffect
        if (!chatSeeded) return@LaunchedEffect
        val whom = openChat
        if (whom != null) lastOpenChatStore.set(ship, whom)
        else lastOpenChatStore.clear(ship)
    }

    // BackHandlers live outside the key block so they observe the
    // global state without being rebuilt on ship change. LIFO — the
    // LAST registered enabled callback wins. Declare in reverse
    // render precedence (chat = lowest, settings = highest).
    PlatformBackHandler(enabled = openChat != null && openThreadParent == null) {
        openChat = null
    }
    PlatformBackHandler(enabled = openThreadParent != null) {
        openThreadParent = null
        openThreadReplyAnchor = null
    }
    // After the chat handler (LIFO): an open note sits inside a notes/
    // channel, so both predicates hold and this must win or back would
    // close the whole notebook instead of the note.
    PlatformBackHandler(enabled = openNoteId != null) {
        openNoteId = null
    }
    // Group info / media drilldown back stack (mobile / compact only —
    // wide windows render these in the right pane and dismiss via the
    // pane's close button). Drilldown predicate is mutually exclusive
    // with group-info so the right one fires regardless of registration
    // order.
    PlatformBackHandler(enabled = groupInfoDrilldown != null) {
        groupInfoDrilldown = null
    }
    PlatformBackHandler(enabled = groupInfoOpenFor != null && groupInfoDrilldown == null) {
        groupInfoOpenFor = null
    }
    PlatformBackHandler(enabled = viewerImageList != null) {
        viewerImageList = null
    }
    PlatformBackHandler(enabled = viewerImageUrl != null && viewerImageList == null) {
        viewerImageUrl = null
    }
    PlatformBackHandler(enabled = showSelfProfile) {
        showSelfProfile = false
    }
    PlatformBackHandler(enabled = showStatusFeed) {
        showStatusFeed = false
    }
    PlatformBackHandler(enabled = showInvites) {
        showInvites = false
    }
    PlatformBackHandler(enabled = profileSheetShip != null) {
        profileSheetShip = null
    }
    PlatformBackHandler(enabled = showBookmarks) { showBookmarks = false }
    PlatformBackHandler(enabled = showActivity) { showActivity = false }
    PlatformBackHandler(enabled = showAssistant) { showAssistant = false }
    PlatformBackHandler(enabled = showSearch) { showSearch = false }
    PlatformBackHandler(enabled = showNewDm) { showNewDm = false }
    PlatformBackHandler(enabled = showWatchwords) { showWatchwords = false }
    PlatformBackHandler(enabled = showContacts) { showContacts = false }
    PlatformBackHandler(enabled = showDailyDigest) { showDailyDigest = false }
    PlatformBackHandler(
        enabled = openGroupAdminFlag != null,
    ) { openGroupAdminFlag = null }
    PlatformBackHandler(
        enabled = showGroupAdminList && openGroupAdminFlag == null,
    ) { showGroupAdminList = false }
    PlatformBackHandler(enabled = openGroupHomeFlag != null) { openGroupHomeFlag = null }
    // Notebook: compose overlays the post viewer (which overlays the list).
    PlatformBackHandler(enabled = notebookComposeOpen) { notebookComposeOpen = false }
    PlatformBackHandler(
        enabled = openNotebookPostId != null && !notebookComposeOpen,
    ) { openNotebookPostId = null }
    // Gallery: same precedence.
    PlatformBackHandler(enabled = galleryComposeOpen) { galleryComposeOpen = false }
    PlatformBackHandler(
        enabled = openGalleryPostId != null && !galleryComposeOpen,
    ) { openGalleryPostId = null }
    PlatformBackHandler(enabled = showSettings) {
        showSettings = false
    }
    PlatformBackHandler(enabled = showSidebarSettings) {
        showSidebarSettings = false
    }
    PlatformBackHandler(enabled = showLoops) {
        showLoops = false
    }

    // Ship-scoped graph. Re-keyed on (loggedInShip ?: "__loggedout__")
    // so signing out and back in fully rebuilds session + repo. The
    // DisposableEffect tears down the prior repo before the next
    // composes, which is the only path that doesn't permanently kill
    // repo's scope (see KDoc above).
    val shipKey = loggedInShip ?: "__loggedout__"
    key(shipKey) {
        // Per-ship db + settingsSync. Built inside the key block so a
        // ship switch tears the prior pair down and constructs fresh
        // ones bound to the new ship's SQLite file. Without this the
        // home list keeps showing the prior ship's DMs after switch.
        val db = remember { createDb(shipKey) }
        val settingsSync = remember { createSettingsSync?.invoke(db) }
        // Per-ship UiSettings. railVisibility's read-flow is sourced
        // from the active ship's rail_item_prefs Room table, so this
        // also has to rebuild on ship switch — otherwise the rail
        // would render the prior ship's hidden-item set after a
        // switch. The other fields (accent, hideComposerButtons, etc.)
        // are per-device and are still served from the same desktop
        // JSON file / SharedPreferences regardless of which db is
        // passed; the impls just don't read those fields from db.
        val uiSettings = remember { createUiSettings(db) }
        // tryRestore() pulls the saved ship's cookie + baseUrl into
        // this fresh UrbitSession on first composition. After login,
        // sessionStore has the new entry; the next re-key picks it up.
        val session = remember {
            UrbitSession(http, sessionStore).also { s ->
                val restored = s.tryRestore()
                if (restored == null && sessionStore.activeShip() != null) {
                    // The active pointer says a ship is signed in, but
                    // tryRestore couldn't hydrate the session — corrupt
                    // shipUrl, missing entry, etc. Surface it for the
                    // support thread before the LaunchedEffect below
                    // routes us back to login.
                    Log.w(
                        "App",
                        "tryRestore null for active=${sessionStore.activeShip()}",
                    )
                }
            }
        }
        val repo = remember {
            TlonChatRepo(
                db = db,
                settingsSync = settingsSync,
                notificationHealth = notificationHealth,
            )
        }
        // Trunkline signaling — one controller per logged-in ship
        // composition; the key() re-key tears it down on ship switch.
        // Gated on a live session so the login screen doesn't spin a
        // doomed channel loop.
        val callController =
            if (io.nisfeb.talon.ui.isCallsSupported &&
                callEngineProvider != null &&
                session.shipName != null
            ) {
                remember {
                    io.nisfeb.talon.call.CallController(session, callEngineProvider)
                        .also { it.start() }
                }
            } else {
                null
            }
        val partyLine = remember(callController, peerLinkFactory) {
            if (callController != null && peerLinkFactory != null) {
                io.nisfeb.talon.call.PartyLine(http, peerLinkFactory)
                    .also { line ->
                        callController.onTicket = { line.join(it, shipKey) }
                        callController.onDenied = { name, why -> line.showRefused(name, why) }
                    }
            } else {
                null
            }
        }
        DisposableEffect(callController) {
            onDispose {
                partyLine?.leave()
                callController?.stop()
            }
        }
        callController?.let { io.nisfeb.talon.ui.CallOverlay(it) }
        // Curated contact book (from %contacts /v1/book) — gates the
        // "Add to contacts" affordances and backs the Contacts screen.
        val bookContacts by repo.bookContacts.collectAsState()
        // Per-ship menu-seen store. Constructed inside the
        // key(shipKey) block so a ship-switch starts collecting from
        // the new ship's persisted file (the host's createMenuSeen
        // factory routes to a per-ship pref / JSON file).
        val menuSeen = remember(shipKey) { createMenuSeen(shipKey) }
        // On-device sentence embedder for smart search + highlights.
        // Lazy under the hood — model load happens on the first
        // SemanticSearch / computeHighlights call. Per-ship since the
        // indexer is bound to this ship's DB.
        val searchEmbedderClient = remember(db) {
            createSearchEmbedderClient?.invoke(db)
        }

        val aiState by aiSettings.state.collectAsState()

        // Desktop loop runner. No AlarmManager on desktop, so loops run
        // via a while-open ticker (below, inside the logged-in guard) plus
        // the "Run now" button. Built here so both the ticker and the
        // LoopsScreen branch share one instance. Full tool catalog —
        // LoopRunner keeps write tools only for loops that opted in.
        // Web access belongs to the assistant: with it off both web tools
        // hard-refuse, so gate their PRESENCE (as AssistantScreen does)
        // rather than hand a scheduled run a tool it can only fail with.
        val loopWebOn = aiState.assistantOn()
        val loopBraveOn = loopWebOn && aiState.braveApiKey.isNotBlank()
        val loopRunner = remember(db, repo, searchEmbedderClient, loopWebOn, loopBraveOn) {
            val agentClient = io.nisfeb.talon.ai.AgentClient { aiSettings.state.value }
            io.nisfeb.talon.ai.LoopRunner(
                loops = db.loops(),
                runs = db.loopRuns(),
                tools = io.nisfeb.talon.ai.ToolCatalog.default(
                    repo, db, searchEmbedderClient,
                    braveSearch = if (loopBraveOn) {
                        io.nisfeb.talon.ai.BraveSearchClient { aiSettings.state.value }
                    } else {
                        null
                    },
                    urlFetcher = if (loopWebOn) {
                        io.nisfeb.talon.ai.UrlFetcher { aiSettings.state.value }
                    } else {
                        null
                    },
                ) { it },
                completer = { sys, msgs, t -> agentClient.completeWithTools(sys, msgs, t) },
                aiConfig = { aiSettings.state.value },
                // One device runs a scheduled write fire — the %settings lease
                // (SettingsSyncImpl implements LoopWriteCoordinator). Noop when
                // there's no sync channel (a write loop needs the ship anyway).
                coordinator = settingsSync ?: io.nisfeb.talon.ai.LoopWriteCoordinator.Noop,
                // loopId is dropped: the desktop Notifier (tray balloon /
                // notify-send) has no per-notification tag, so loops can't
                // group/replace like Android's id-tagged notifications. A
                // tag param on Notifier.notify is the upgrade path if it
                // matters; for now each loop run is a standalone toast.
                notify = { _, title, body -> notifier.notify(title, body) },
            )
        }
        val loopScope = rememberCoroutineScope()
        // "Run now", from both the Loops screen and the assistant's jobs pane.
        val runLoopNow: (Long) -> Unit = { loopId ->
            loopScope.launch { db.loops().get(loopId)?.let { loopRunner.runLoop(it) } }
        }

        // Kick off the embedder index as soon as ANY feature that
        // depends on it is enabled — smart search, topic clusters, or
        // important-message highlights. Previously start() only ran
        // when SearchScreen mounted, which meant the topic icon and
        // highlights would render an empty / "go to Search to index"
        // state until the user happened to open Search. start() is a
        // no-op if already running, so flipping any toggle (or cold-
        // launching with one already on) just wakes the indexer once.
        LaunchedEffect(searchEmbedderClient, aiState.smartFeaturesEnabled) {
            val client = searchEmbedderClient ?: return@LaunchedEffect
            if (aiState.smartFeaturesEnabled) runCatching { client.start() }
        }

        // Populate message_media for messages that pre-date Task 2.3's
        // ingest hook. Skipped on fresh installs (nothing to backfill).
        // Runs once per db instance; subsequent launches are no-ops.
        LaunchedEffect(db) {
            runCatching { MediaBackfillWorker.runIfNeeded(db) }
                .onFailure { Log.w("MediaBackfill", "backfill failed: $it") }
        }

        DisposableEffect(Unit) {
            onDispose {
                runCatching { repo.stop() }
                // Defer db.close by 2s so any in-flight Flow collectors
                // from the prior key composition unwind cleanly. Closing
                // the pool synchronously here would surface as
                // SQLiteException spam in the brief overlap window.
                // Matches production's TalonApplication.scheduleShipScopedTeardown.
                val dying = db
                // Fire-and-forget deferred close that must outlive this
                // composition (a re-key) — GlobalScope is deliberate, the
                // daemon Thread this replaces had the same lifetime.
                GlobalScope.launch(ioDispatcher) {
                    delay(2_000)
                    runCatching { dying.close() }
                }
            }
        }

        // tryRestore-failure recovery. If loggedInShip says a ship
        // is signed in but the session couldn't actually restore
        // (shipName stayed null), wipe the bad sessionStore entry
        // and reset loggedInShip so the next re-key lands on
        // LoginScreen — and surface a notice so the user knows
        // why they got logged out, instead of assuming the app
        // randomly forgot.
        LaunchedEffect(Unit) {
            if (loggedInShip != null && session.shipName == null) {
                val staleShip = sessionStore.activeShip()
                runCatching { staleShip?.let { sessionStore.remove(it) } }
                loginNotice = if (staleShip != null) {
                    "Couldn't restore your session for $staleShip — please sign in again."
                } else {
                    "Couldn't restore your session — please sign in again."
                }
                loggedInShip = null
            }
        }

        if (loggedInShip != null && session.shipName != null) {
            LaunchedEffect(Unit) { repo.start(session) }

            // Relay AI settings mutations to %settings on the active
            // ship's SettingsSync. Per-feature toggles push every
            // time; cloud-key fields are gated inside pushAiSettings
            // on local syncEnabled. Re-binds when the active ship
            // (and therefore settingsSync) switches; the latest bind
            // wins, so older ships' instances stop receiving relays.
            // Re-using onStateChange — single-listener model.
            //
            // Note: Android calls TalonApp.kt instead of App.kt, so
            // this LaunchedEffect never runs there. The Android
            // aiSettings.onStateChange binding lives in
            // TalonApplication.onCreate. Don't move it here without
            // also fixing that path.
            LaunchedEffect(settingsSync, aiSettings) {
                val sink = settingsSync ?: return@LaunchedEffect
                val scope = this
                aiSettings.onStateChange = { _, _ ->
                    scope.launch {
                        runCatching { sink.pushAiSettings() }
                    }
                }
            }

            // Relay daily-digest schedule changes the same way. Was
            // missing on desktop — TalonApplication wires
            // dailyDigestSettings.onChange for Android, but desktop
            // had no equivalent, so a desktop user changing the
            // schedule would never push to the ship. The user
            // reported "settings not syncing to new installs" and
            // this was one of the gaps.
            LaunchedEffect(settingsSync, dailyDigestSettings) {
                val sink = settingsSync ?: return@LaunchedEffect
                val ds = dailyDigestSettings ?: return@LaunchedEffect
                val scope = this
                ds.onChange = { _, transitionedOffSync ->
                    scope.launch {
                        runCatching {
                            if (transitionedOffSync) sink.clearDailyDigestOnShip()
                            else sink.pushDailyDigest(ds.state.value)
                        }
                    }
                }
            }

            // Desktop loop scheduler. Android arms an AlarmManager wake-up;
            // desktop has none, so loops run on a while-open ticker that
            // fires due loops once a minute. Bounded to the logged-in
            // session and cancelled on ship re-key / app exit. runDue
            // no-ops without an AI key or when nothing is due, so an idle
            // tick is cheap. (isLoopsSupported is now true on desktop.)
            LaunchedEffect(loopRunner) {
                while (true) {
                    runCatching { loopRunner.runDue() }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    kotlinx.coroutines.delay(60_000)
                }
            }

            // Desktop "is the user actually here" signal = window focus.
            // Drives the repo's auto-mark-read gate so a chat left open in
            // an unfocused / minimized window doesn't silently read its
            // incoming messages, and feeds the notification suppression
            // below. Without this, a DM open in a background window got
            // neither an unread badge nor a notification.
            val windowInfo = LocalWindowInfo.current
            LaunchedEffect(repo, windowInfo) {
                snapshotFlow { windowInfo.isWindowFocused }
                    .collect { focused -> repo.setForeground(focused) }
            }

            // New pending DM request → tray notification. Always fires
            // (an unaccepted request has no "open" state to suppress
            // against), so a brand-new DM no longer arrives silently.
            DisposableEffect(repo, notifier) {
                repo.dmInviteListener = { ship ->
                    runCatching { notifier.notify(ship, "wants to message you") }
                }
                // A new group invite is as easy to miss as a DM request —
                // toast it the same way so it doesn't sit unseen behind a
                // badge you have no reason to check.
                repo.groupInviteListener = { invite ->
                    val name = invite.title ?: invite.flag
                    val from = invite.inviter?.let { " from $it" } ?: ""
                    runCatching { notifier.notify(name, "invited you to a group$from") }
                }
                onDispose {
                    repo.dmInviteListener = null
                    repo.groupInviteListener = null
                }
            }

            // OS notifications for incoming messages. Watches the
            // per-conversation latest-message flow and fires a balloon
            // when a whom's latest id changes to something authored by
            // someone other than us, AND the chat isn't currently open
            // *and focused*, AND the chat isn't muted. Decision logic lives in
            // [diffNewMessageNotifications] — kept pure and unit-tested
            // so the seeding behavior can't silently regress.
            //
            // Notifications are suppressed while [TlonChatRepo.bootstrapping]
            // is true — otherwise a fresh ship-keyed DB plus a long
            // initial scry produces a notification per backfilled
            // message (a horrible "Talon-DOSes-your-tray" experience
            // we shipped in 0.8.1). The baseline is still kept current
            // during bootstrap so the next post-bootstrap emission
            // diffs against the loaded snapshot, not against empty.
            LaunchedEffect(notifier, loggedInShip) {
                var lastSeenIds: Map<String, String> = emptyMap()
                var seeded = false
                kotlinx.coroutines.flow.combine(
                    db.messages().conversationLatest(),
                    db.notifyPrefs().streamMutedWhoms(),
                    repo.bootstrapping,
                ) { rows, muted, bootstrapping ->
                    Triple(rows, muted.toHashSet(), bootstrapping)
                }
                    .collect { (rows, muted, bootstrapping) ->
                        if (bootstrapping || !seeded) {
                            lastSeenIds = io.nisfeb.talon.notify
                                .seedNewMessageBaseline(rows)
                            seeded = true
                            return@collect
                        }
                        val diff = io.nisfeb.talon.notify
                            .diffNewMessageNotifications(
                                rows = rows,
                                lastSeen = lastSeenIds,
                                ourPatp = loggedInShip,
                                // Only suppress for the open chat while the
                                // window is focused — an unfocused window's
                                // open chat should still notify.
                                openChat = openChat.takeIf { windowInfo.isWindowFocused },
                                mutedWhoms = muted,
                                storyText = { id, json ->
                                    io.nisfeb.talon.urbit.StoryCache.textFor(id, json)
                                },
                                // Staleness guard: only notify for
                                // messages posted in the last 5 min.
                                // The `bootstrapping` flag flips false
                                // after the shallow scry, but deep-
                                // history + late SSE init keep landing
                                // older messages (especially after a
                                // schema-bump DB wipe re-syncs from
                                // scratch) — without this, all of them
                                // fire as "new". Backlog has old sentMs
                                // and is dropped; live messages pass.
                                nowMs = nowMs(),
                                freshnessMaxAgeMs = 5L * 60_000L,
                            )
                        lastSeenIds = diff.newLastSeen
                        for (n in diff.notifications) {
                            runCatching { notifier.notify(n.title, n.body) }
                        }
                    }
            }
        }

        val themeMode by themePreference.mode.collectAsState()
        val systemDark = isSystemInDarkTheme()
        val darkTheme = when (themeMode) {
            ThemePreference.Mode.System -> systemDark
            ThemePreference.Mode.Light -> false
            ThemePreference.Mode.Dark -> true
        }

        // Effective accent: drives `colorScheme.primary` for every
        // primary-tinted surface (send icon, focused border, ship pip,
        // FilterChip selected, etc). Single override-point so adding
        // a new accent-using composable doesn't need a code change.
        //
        //   * stored Disabled or stored unset on a single-ship login
        //     → null (brand palette stays).
        //   * stored Enabled (or auto-enabled for multi-ship) and
        //     mode = Profile → active ship's contact color.
        //   * mode = Custom → user's hex.
        //   * mode = Brand → null (explicit opt-out also stays brand).
        val accentSettings by uiSettings.accentSettings.collectAsState()
        val powerFeaturesEnabled by uiSettings.powerFeaturesEnabled.collectAsState()
        val densityMode by uiSettings.density.collectAsState()
        val chatDensity = remember(densityMode) {
            io.nisfeb.talon.ui.ChatDensity.forMode(densityMode)
        }
        // User font scale (Ctrl/Cmd +/-/0). Layered on top of the
        // density preset's own multiplier below.
        val userFontScale by uiSettings.fontScale.collectAsState()
        val multiShip = remember(loggedInShip) {
            sessionStore.all().size >= 2
        }
        // Pull active ship's profile color upfront so the accent
        // computation is a pure expression below (no conditional
        // composable calls).
        val ownContactsList by remember(db) {
            db.contacts().stream()
        }.collectAsState(initial = emptyList<io.nisfeb.talon.data.ContactEntity>())
        val activeShip = session.shipName ?: loggedInShip
        val profileAccent = remember(ownContactsList, activeShip) {
            if (activeShip == null) null
            else ownContactsList.firstOrNull { it.ship == activeShip }?.color
                ?.let(::parseHexColor)
        }
        val accentEnabled = io.nisfeb.talon.ui.AccentSettings
            .isEnabled(accentSettings, multiShip)
        val accentOverride: androidx.compose.ui.graphics.Color? = remember(
            accentEnabled, accentSettings, profileAccent,
        ) {
            if (!accentEnabled) null
            else when (accentSettings.mode) {
                io.nisfeb.talon.ui.AccentMode.Brand -> null
                io.nisfeb.talon.ui.AccentMode.Custom ->
                    accentSettings.customHex?.let(::parseHexColor)
                io.nisfeb.talon.ui.AccentMode.Profile -> profileAccent
            }
        }
        TalonTheme(darkTheme = darkTheme, accentOverride = accentOverride) {
          // Scale the whole app's `sp`-based sizes by the active
          // density's font multiplier. Compose computes pixel sizes
          // for sp values as `sp * density * fontScale`, so
          // multiplying `fontScale` by 0.90 / 1.0 / 1.12 globally
          // scales every Text without touching individual styles.
          // We deliberately do NOT scale `density` itself because
          // that would shrink/grow icons + image previews + Dp-based
          // gaps that aren't part of the density story (the rail,
          // image viewer, etc.); per-component dp values stay under
          // explicit `LocalChatDensity.current` reads.
          val baseDensity = androidx.compose.ui.platform.LocalDensity.current
          val scaledDensity = remember(baseDensity, chatDensity, userFontScale) {
              androidx.compose.ui.unit.Density(
                  density = baseDensity.density,
                  fontScale = baseDensity.fontScale *
                      chatDensity.fontScaleMultiplier * userFontScale,
              )
          }
          // urb:// link handoff to Lattice. The handler resolves the
          // scheme off-thread (desktop shells to xdg-mime/xdg-open) and
          // raises the install prompt when no handler is present.
          var urbPromptUrl by remember { mutableStateOf<String?>(null) }
          val urbScope = rememberCoroutineScope()
          val urbLinkHandler: (String) -> Unit = remember(urbLinkLauncher) {
              { url ->
                  urbScope.launch {
                      val r = kotlinx.coroutines.withContext(ioDispatcher) {
                          urbLinkLauncher.open(url)
                      }
                      if (r != io.nisfeb.talon.urbit.UrbLaunchResult.Opened) urbPromptUrl = url
                  }
              }
          }
          // Wrap the platform URI handler so urb:// links opened via
          // Compose's built-in LinkAnnotation handling (statuses, bios)
          // also route to Lattice, not just the chat screens' onLinkTap.
          val platformUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
          val urbAwareUriHandler = remember(platformUriHandler, urbLinkHandler) {
              io.nisfeb.talon.ui.UrbAwareUriHandler(platformUriHandler, urbLinkHandler)
          }
          val citeScope = rememberCoroutineScope()
          val citeResolver = remember(db, repo) {
              io.nisfeb.talon.ui.TalonCiteResolver(db, repo)
          }
          val openCitation: (io.nisfeb.talon.urbit.StoryPart.Citation) -> Unit =
              remember(db, citeResolver) {
                  { cite ->
                      citeScope.launch {
                          val jump = io.nisfeb.talon.ui.resolveCiteJump(cite, citeResolver) {
                              runCatching { db.groups().channelGroupFor(it)?.groupFlag }.getOrNull()
                          }
                          when (jump) {
                              is io.nisfeb.talon.ui.CiteJump.Group -> {
                                  openChat = null
                                  revealGroupRequest = jump.flag
                              }
                              is io.nisfeb.talon.ui.CiteJump.Message -> {
                                  openConversationAction()
                                  jump.groupFlag?.let { revealGroupRequest = it }
                                  openChatFocusMessageId = jump.messageId
                                  openChat = jump.whom
                              }
                              is io.nisfeb.talon.ui.CiteJump.Reply -> {
                                  openConversationAction()
                                  jump.groupFlag?.let { revealGroupRequest = it }
                                  openChat = jump.whom
                                  openThreadParent = jump.parentId
                                  openThreadReplyAnchor = jump.replyId
                              }
                              null -> Unit
                          }
                      }
                      Unit
                  }
              }
          // Root contact map so a quoted post's author resolves to the
          // same nickname / mnemonym the rest of the app shows, rather
          // than the bare @p the renderer would emit on its own.
          val citeContacts by remember(db) {
              io.nisfeb.talon.ui.contactMapFlow(
                  db.contacts().stream(),
                  db.clubs().stream(),
                  db.groups().streamGroups(),
                  db.groups().streamChannelGroups(),
              )
          }.collectAsState(initial = io.nisfeb.talon.ui.ContactMap.EMPTY)
          val citeDisplayName: (String) -> String = remember(citeContacts) {
              { ship -> citeContacts.displayName(ship) }
          }
          // Story parsing runs outside composition (StoryCache, ingest),
          // so the naming policy is published to it here rather than
          // threaded through every call site.
          LaunchedEffect(citeContacts) {
              io.nisfeb.talon.ui.ShipNames.setResolver(citeContacts.namesVersion) { ship ->
                  citeContacts.displayName(ship)
              }
          }
          androidx.compose.runtime.CompositionLocalProvider(
              io.nisfeb.talon.ui.LocalImageDownloader provides imageDownloader,
              io.nisfeb.talon.ui.LocalChatDensity provides chatDensity,
              androidx.compose.ui.platform.LocalDensity provides scaledDensity,
              io.nisfeb.talon.ui.LocalUrbLinkHandler provides urbLinkHandler,
              androidx.compose.ui.platform.LocalUriHandler provides urbAwareUriHandler,
              io.nisfeb.talon.ui.LocalCiteResolver provides citeResolver,
              io.nisfeb.talon.ui.LocalCitationOpen provides openCitation,
              io.nisfeb.talon.ui.LocalDisplayName provides citeDisplayName,
          ) {
            urbPromptUrl?.let {
                io.nisfeb.talon.ui.InstallLatticeDialog(onDismiss = { urbPromptUrl = null })
            }
            val rootFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(rootFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val action = io.nisfeb.talon.ui.keyEventToShortcut(event, isMacHost = isMacHost)
                            ?: return@onPreviewKeyEvent false
                        when (action) {
                            io.nisfeb.talon.ui.ShortcutAction.Back -> {
                                when {
                                    openThreadParent != null -> {
                                        openThreadParent = null
                                        openThreadReplyAnchor = null
                                    }
                                    openChat != null -> openChat = null
                                    showSettings -> showSettings = false
                                    else -> return@onPreviewKeyEvent false
                                }
                            }
                            io.nisfeb.talon.ui.ShortcutAction.OpenSettings -> showSettings = true
                            io.nisfeb.talon.ui.ShortcutAction.NewDm -> showNewDmRequest = true
                            io.nisfeb.talon.ui.ShortcutAction.FocusSearch -> focusSearchRequest = true
                            io.nisfeb.talon.ui.ShortcutAction.IncreaseFontSize ->
                                uiSettings.setFontScale(
                                    userFontScale + io.nisfeb.talon.ui.FONT_SCALE_STEP,
                                )
                            io.nisfeb.talon.ui.ShortcutAction.DecreaseFontSize ->
                                uiSettings.setFontScale(
                                    userFontScale - io.nisfeb.talon.ui.FONT_SCALE_STEP,
                                )
                            io.nisfeb.talon.ui.ShortcutAction.ResetFontSize ->
                                uiSettings.setFontScale(1.0f)
                            is io.nisfeb.talon.ui.ShortcutAction.SwitchShip -> {
                                sessionStore.all().getOrNull(action.index)?.ship?.let { targetShip ->
                                    // Clear the previous ship's open chat before
                                    // sessionStore.setActive so no frame renders with
                                    // the new active ship but stale chat state.
                                    openChat = null
                                    switchShipAction()
                                    viewerImageUrl = null
                                    viewerImageList = null
                                    showSelfProfile = false
                                    showSettings = false
                                    showSidebarSettings = false
                                    sessionStore.setActive(targetShip)
                                    loggedInShip = targetShip
                                }
                            }
                        }
                        true
                    },
            ) {
                // Effective ship: the session's actual restored state.
                // Using session.shipName instead of loggedInShip avoids
                // the one-frame flash of a stale DmListScreen during
                // tryRestore-failure recovery — without this gate, the
                // composition where loggedInShip != null but
                // session.shipName == null would render DmListScreen
                // briefly before the recovery LaunchedEffect fires.
                val ship = if (session.shipName != null) loggedInShip else null
                // Ship-switcher drawer hoisted to App.kt level so it
                // wraps the entire navigation tree below (LoginScreen +
                // post-login screens + DesktopShell). Was previously
                // inside DmListScreen, which on the wide split-pane
                // layout scoped the drawer to the list pane only — its
                // closed-state -drawer_width translation then overflowed
                // the list pane's left edge and rendered over the rail
                // at column 0-64, hiding the rail icons and leaking
                // drawer content (brand mark + ship avatars).
                val drawerScope = rememberCoroutineScope()
                val drawerState = androidx.compose.material3.rememberDrawerState(
                    initialValue = androidx.compose.material3.DrawerValue.Closed,
                )
                val allShipsList = remember(loggedInShip) {
                    sessionStore.all().map { it.ship }
                }
                val shipNicknamesMap = run {
                    val nicknames = remember(loggedInShip) {
                        mutableStateOf<Map<String, String>>(emptyMap())
                    }
                    LaunchedEffect(allShipsList) {
                        val map = allShipsList.mapNotNull { s ->
                            val nick = runCatching { db.contacts().get(s)?.nickname }.getOrNull()
                            if (nick.isNullOrBlank()) null else s to nick
                        }.toMap()
                        nicknames.value = map
                    }
                    nicknames.value
                }
                val switchShip: (String) -> Unit = { newShip ->
                    openChat = null
                    switchShipAction()
                    viewerImageUrl = null
                    viewerImageList = null
                    showSelfProfile = false
                    showSettings = false
                    showSidebarSettings = false
                    sessionStore.setActive(newShip)
                    loggedInShip = newShip
                }
                val addShip: () -> Unit = {
                    openChat = null
                    switchShipAction()
                    viewerImageUrl = null
                    viewerImageList = null
                    showSelfProfile = false
                    showSettings = false
                    showSidebarSettings = false
                    loggedInShip = null
                }
                // Modal / full-screen branches short-circuit first so they
                // render at full width without entering ChatPaneScaffold.
                // Only DmList + chat-detail screens (chat, thread, notebook,
                // gallery) participate in the list/detail split.
                androidx.compose.material3.ModalNavigationDrawer(
                    drawerState = drawerState,
                    // Desktop opens the ship switcher only via the Talon
                    // logo click — the edge-swipe is a touch gesture that
                    // a mouse triggers ambiguously, so it's off there.
                    gesturesEnabled = io.nisfeb.talon.ui.isTouchSwipeNavSupported,
                    drawerContent = {
                        // Empty drawer content when no ships are logged in
                        // (LoginScreen path). The drawer trigger isn't
                        // visible there anyway — this is just defensive.
                        if (allShipsList.isNotEmpty()) {
                            io.nisfeb.talon.ui.screens.ShipSwitcherDrawer(
                                ships = allShipsList,
                                activeShip = ship,
                                nicknames = shipNicknamesMap,
                                onPick = { picked ->
                                    drawerScope.launch { drawerState.close() }
                                    switchShip(picked)
                                },
                                onAdd = {
                                    drawerScope.launch { drawerState.close() }
                                    addShip()
                                },
                            )
                        }
                    },
                ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val expanded = maxWidth >= ExpandedThreshold
                    // Mirror DesktopShell's threshold so the kebab menu makes the right
                    // navigation move at this breakpoint. Mobile / compact (<840dp):
                    // flip the existing show* flag (full-screen replace, with back
                    // arrow). Wide (>=840dp): switch the rail tab instead — the rail is
                    // visible so no full-screen replace is needed.
                    val onOpenStatusFeed: () -> Unit = {
                        if (expanded) uiSettings.setActiveRailTab(RailTab.Statuses)
                        else showStatusFeed = true
                    }
                    val onOpenBookmarks: () -> Unit = {
                        if (expanded) uiSettings.setActiveRailTab(RailTab.Bookmarks)
                        else showBookmarks = true
                    }
                    val onOpenActivity: () -> Unit = {
                        if (expanded) uiSettings.setActiveRailTab(RailTab.Activity)
                        else showActivity = true
                    }
                    // Right-pane content. Computed at render time from the
                    // flat state vars; mutual exclusion is enforced at the
                    // write sites (thread-open clears group-info and vice
                    // versa). null means no fourth column on wide; on
                    // compact the new outer-when branches handle the
                    // full-screen render.
                    val rightPaneContent: RightPaneContent? = when {
                        openThreadParent != null && openChat != null -> RightPaneContent.Thread(
                            whom = openChat!!,
                            parentId = openThreadParent!!,
                            replyAnchor = openThreadReplyAnchor,
                        )
                        groupInfoDrilldown != null && groupInfoOpenFor != null ->
                            RightPaneContent.GroupInfoDrilldown(
                                whom = groupInfoOpenFor!!,
                                category = groupInfoDrilldown!!,
                            )
                        groupInfoOpenFor != null ->
                            RightPaneContent.GroupInfo(whom = groupInfoOpenFor!!)
                        else -> null
                    }
                    // Coroutine scope used by the right-pane onOpenMembers
                    // bridge — resolving channel-nest → group-flag is a
                    // suspend DAO call.
                    val rightPaneScope = rememberCoroutineScope()
                    when {
                    shareLoginQrOpen -> io.nisfeb.talon.ui.screens.LoginQrShareScreen(
                        onBack = { shareLoginQrOpen = false },
                    )
                    ship == null -> LoginScreen(
                        session = session,
                        onLoggedIn = { loggedInShip = it },
                        notice = loginNotice,
                        // Desktop has no QR scanner (no camera to assume,
                        // keyboard is already the fast path) but the
                        // generator works — Compose Desktop can paint the
                        // QR matrix and the user shows their screen to
                        // someone scanning from a phone.
                        onOpenShareQr = { shareLoginQrOpen = true },
                    )
                    // Sidebar settings drills out of Settings; both flags
                    // are true while the user is in Sidebar. Order this
                    // branch BEFORE `showSettings` so the deeper screen
                    // wins. Back from Sidebar clears `showSidebarSettings`
                    // and falls through to the next branch — `showSettings`
                    // — which renders Settings, giving the user a
                    // breadcrumb pop instead of a full unwind to the
                    // chat list.
                    showSidebarSettings -> {
                        val dailyDigestEnabled = dailyDigestSettings
                            ?.state
                            ?.collectAsState()
                            ?.value
                            ?.enabled == true
                        SidebarSettingsScreen(
                            repo = repo,
                            uiSettings = uiSettings,
                            dailyDigestEnabled = dailyDigestEnabled,
                            onBack = { showSidebarSettings = false },
                        )
                    }
                    // Ordered before showSettings so Back from Loops pops
                    // to Settings (same breadcrumb rationale as Sidebar).
                    // Desktop has no AlarmManager, so there's no scheduler to
                    // re-arm — the while-open ticker (above) drives runs and
                    // "Run now" goes straight to loopRunner. Noop scheduler
                    // satisfies the screen's reschedule() calls.
                    showLoops -> io.nisfeb.talon.ui.screens.LoopsScreen(
                        db = db,
                        scheduler = io.nisfeb.talon.ai.LoopScheduler.Noop,
                        onRunNow = runLoopNow,
                        onBack = { showLoops = false },
                        settingsSync = settingsSync,
                    )
                    showSettings -> {
                        val relayClient = remember(http) {
                            io.nisfeb.talon.notify.RelayClient(
                                http = http,
                                endpoint = { relaySettings.endpoint.value },
                            )
                        }
                        val activeShipUrl = remember(ship) {
                            ship?.let { sessionStore.all().firstOrNull { it.ship == ship } }?.shipUrl
                        }
                        SettingsScreen(
                            aiSettings = aiSettings,
                            themePreference = themePreference,
                            uiSettings = uiSettings,
                            multiShip = multiShip,
                            profileAccentPreview = profileAccent,
                            notificationHealth = notificationHealth,
                            systemNotificationProbe = systemNotificationProbe,
                            relayConfig = io.nisfeb.talon.ui.screens.RelayPanelConfig(
                                client = relayClient,
                                settings = relaySettings,
                                pushTokens = pushTokenProvider,
                                activePatp = ship,
                                activeShipUrl = activeShipUrl,
                            ),
                            onBack = { showSettings = false },
                            dailyDigestSettings = dailyDigestSettings,
                            // onTestDigest stays null on desktop — Android
                            // wires it to dailyDigest.generateAndNotifyAsync
                            // when the production MainActivity migrates here.
                            onOpenSidebarSettings = { showSidebarSettings = true },
                            onOpenShareLoginQr = { shareLoginQrOpen = true },
                            onOpenLoops = { showLoops = true },
                            onMnemonymNamesChanged = { on ->
                                repo.pushScope.launch {
                                    runCatching { settingsSync?.pushMnemonymNames(on) }
                                }
                            },
                        )
                    }
                    showSelfProfile -> ProfileEditScreen(
                        db = db,
                        repo = repo,
                        ourPatp = ship,
                        onBack = { showSelfProfile = false },
                    )
                    showStatusFeed -> StatusFeedScreen(
                        db = db,
                        repo = repo,
                        ourPatp = ship,
                        onBack = { showStatusFeed = false },
                        onOpenContact = { other -> profileSheetShip = other },
                    )
                    showInvites -> GroupInvitesScreen(
                        repo = repo,
                        onBack = { showInvites = false },
                    )
                    showBookmarks -> BookmarksScreen(
                        db = db,
                        repo = repo,
                        onBack = { showBookmarks = false },
                        onOpenConversation = { other, postId ->
                            showBookmarks = false
                            openChatFocusMessageId = postId
                            openChat = other
                        },
                    )
                    showActivity -> ActivityFeedScreen(
                        db = db,
                        repo = repo,
                        onBack = { showActivity = false },
                        onOpenConversation = { other ->
                            showActivity = false
                            openChat = other
                        },
                        onOpenReply = { whomTarget, parentId, replyId ->
                            showActivity = false
                            openChat = whomTarget
                            openThreadParent = parentId
                            openThreadReplyAnchor = replyId
                        },
                    )
                    // showAssistant is NOT a full-screen branch — it renders
                    // inside DesktopShell's content (keeping the rail on wide;
                    // full-screen with a back arrow on narrow). See the
                    // DesktopShell `list`/`detail` override below.
                    showSearch -> SearchScreen(
                        db = db,
                        aiSettings = aiSettings,
                        uiSettings = uiSettings,
                        embedder = searchEmbedderClient,
                        onBack = { showSearch = false },
                        onOpenConversation = { other ->
                            showSearch = false
                            openChat = other
                        },
                        onOpenMessage = { whomTarget, _, parentId ->
                            showSearch = false
                            openChat = whomTarget
                            if (parentId != null) {
                                openThreadParent = parentId
                            }
                        },
                        onOpenGroup = { flag ->
                            // Back to the home list, then let DmListScreen
                            // reveal + expand the group.
                            showSearch = false
                            openChat = null
                            revealGroupRequest = flag
                        },
                    )
                    showNewDm -> NewDmScreen(
                        db = db,
                        onBack = { showNewDm = false },
                        onPickPeer = { peer ->
                            showNewDm = false
                            openChat = peer
                        },
                        onAddContact = { patp, nickname ->
                            rightPaneScope.launch {
                                runCatching { repo.addContact(patp, nickname) }
                            }
                        },
                        bookContacts = bookContacts,
                    )
                    showContacts -> io.nisfeb.talon.ui.screens.ContactsScreen(
                        db = db,
                        bookContacts = bookContacts,
                        onAddContact = { patp, nickname ->
                            rightPaneScope.launch { runCatching { repo.addContact(patp, nickname) } }
                        },
                        onRemoveContact = { patp ->
                            rightPaneScope.launch { runCatching { repo.removeContact(patp) } }
                        },
                        onOpenContact = { patp -> profileSheetShip = patp },
                        onBack = { showContacts = false },
                    )
                    showWatchwords -> WatchwordsScreen(
                        db = db,
                        watchwordsSyncEnabled = watchwordsSyncEnabled,
                        onSetWatchwordsSyncEnabled = watchwordsSync::setEnabled,
                        onBack = { showWatchwords = false },
                        onOpenConversation = { other, _ ->
                            showWatchwords = false
                            openChat = other
                        },
                    )
                    showDailyDigest -> DailyDigestScreen(
                        db = db,
                        activeShip = ship,
                        onBack = { showDailyDigest = false },
                        onOpenMessage = { whomTarget, _ ->
                            showDailyDigest = false
                            openChat = whomTarget
                        },
                        // Desktop has no AlarmManager-equivalent
                        // wired; the Android-side Generate-Now flow
                        // doesn't fire here. No-op until Stage F.
                        onGenerateNow = {},
                    )
                    openGroupAdminFlag != null -> GroupAdminScreen(
                        db = db,
                        repo = repo,
                        flag = openGroupAdminFlag!!,
                        onBack = { openGroupAdminFlag = null },
                    )
                    showGroupAdminList -> GroupAdminListScreen(
                        repo = repo,
                        onBack = { showGroupAdminList = false },
                        onOpenGroup = { flag -> openGroupAdminFlag = flag },
                    )
                    openGroupHomeFlag != null -> GroupHomeScreen(
                        db = db,
                        repo = repo,
                        flag = openGroupHomeFlag!!,
                        onBack = { openGroupHomeFlag = null },
                        onOpenChannel = { nest ->
                            openGroupHomeFlag = null
                            openChat = nest
                        },
                    )
                    viewerImageList != null -> ImageViewerScreen(
                        urls = viewerImageList!!.urls,
                        initialIndex = viewerImageList!!.initialIndex,
                        onClose = { viewerImageList = null },
                    )
                    viewerImageUrl != null -> ImageViewerScreen(
                        urls = listOf(viewerImageUrl!!),
                        onClose = { viewerImageUrl = null },
                    )
                    // Compact-only group-info back stack. Order matters:
                    // drilldown branch first so its back-arrow exits the
                    // drilldown, leaving the user on group info instead of
                    // dismissing both at once. Predicates are mutually
                    // exclusive (drilldown-without-info isn't a state we
                    // produce — opening a category requires info to be open).
                    groupInfoDrilldown != null && groupInfoOpenFor != null && !expanded -> {
                        MediaListScreen(
                            db = db,
                            repo = repo,
                            http = http,
                            whom = groupInfoOpenFor!!,
                            category = groupInfoDrilldown!!,
                            onBack = { closeDrilldownAction() },
                            onOpenImageList = { urls, idx ->
                                viewerImageList = io.nisfeb.talon.ui.screens
                                    .ViewerImageList(urls, idx)
                            },
                        )
                    }
                    groupInfoOpenFor != null && !expanded -> {
                        GroupInfoScreen(
                            db = db,
                            repo = repo,
                            whom = groupInfoOpenFor!!,
                            onBack = { closeRightPaneAction() },
                            onOpenCategory = { openCategoryAction(it) },
                            onOpenMembers = {
                                val whom = groupInfoOpenFor
                                if (whom != null) {
                                    rightPaneScope.launch {
                                        val flag = runCatching {
                                            db.groups().channelGroupFor(whom)?.groupFlag
                                        }.getOrNull()
                                        if (flag != null) {
                                            openGroupAdminFlag = flag
                                        }
                                    }
                                }
                            },
                        )
                    }
                    // Compact-only thread. Wide windows render the thread
                    // in the right pane next to the chat. Replaces the
                    // detailSlot thread branch that lived here in Phase 2.
                    openThreadParent != null && openChat != null && !expanded -> {
                        ThreadScreen(
                            db = db,
                            repo = repo,
                            http = http,
                            drafts = drafts,
                            ourPatp = ship,
                            whom = openChat!!,
                            parentId = openThreadParent!!,
                            initialScrollReplyId = openThreadReplyAnchor,
                            onScrollConsumed = { openThreadReplyAnchor = null },
                            onBack = { closeRightPaneAction() },
                            onOpenConversation = { other ->
                                openConversationAction()
                                openChat = other
                            },
                            onOpenImage = { url -> viewerImageUrl = url },
                            powerFeaturesEnabled = powerFeaturesEnabled,
                        )
                    }
                    else -> {
                        // List/detail surface. detailSlot is null when at
                        // the list root (no openChat), which causes
                        // ChatPaneScaffold to render DmListScreen full-width
                        // on narrow windows and in the right pane on wide
                        // windows (showing EmptyChatPane as placeholder).
                        val detailSlot: (@Composable () -> Unit)? = when {
                            // Notebook channels (whom prefix "diary/").
                            // Compose overlays the post viewer overlays the
                            // list — same precedence as production.
                            openChat?.startsWith("diary/") == true && notebookComposeOpen -> ({
                                NotebookComposeScreen(
                                    repo = repo,
                                    whom = openChat!!,
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
                                )
                            })
                            openChat?.startsWith("diary/") == true && openNotebookPostId != null -> ({
                                NotebookPostScreen(
                                    db = db,
                                    repo = repo,
                                    ourPatp = ship,
                                    whom = openChat!!,
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
                                )
                            })
                            openChat?.startsWith("diary/") == true -> ({
                                NotebookListScreen(
                                    db = db,
                                    repo = repo,
                                    whom = openChat!!,
                                    onBack = { openChat = null },
                                    onOpenPost = { id -> openNotebookPostId = id },
                                    onCompose = { notebookComposeOpen = true },
                                )
                            })
                            // Gallery channels (whom prefix "heap/").
                            openChat?.startsWith("heap/") == true && galleryComposeOpen -> ({
                                GalleryComposeScreen(
                                    repo = repo,
                                    whom = openChat!!,
                                    onBack = { galleryComposeOpen = false },
                                    onPosted = { galleryComposeOpen = false },
                                )
                            })
                            openChat?.startsWith("heap/") == true && openGalleryPostId != null -> ({
                                GalleryPostScreen(
                                    db = db,
                                    repo = repo,
                                    ourPatp = ship,
                                    whom = openChat!!,
                                    postId = openGalleryPostId!!,
                                    onBack = { openGalleryPostId = null },
                                )
                            })
                            openChat?.startsWith("heap/") == true -> ({
                                GalleryGridScreen(
                                    db = db,
                                    repo = repo,
                                    whom = openChat!!,
                                    onBack = { openChat = null },
                                    onOpenPost = { id -> openGalleryPostId = id },
                                    onCompose = { galleryComposeOpen = true },
                                )
                            })
                            // %notes channels (whom prefix "notes/"). Served
                            // by the %notes agent, not %channels, so they
                            // route to their own screens rather than any of
                            // the chat/bulletin/gallery plumbing.
                            openChat?.startsWith("notes/") == true && openNoteId != null -> ({
                                NoteScreen(
                                    repo = repo,
                                    whom = openChat!!,
                                    noteId = openNoteId!!,
                                    onBack = { openNoteId = null },
                                )
                            })
                            openChat?.startsWith("notes/") == true -> ({
                                NotesChannelScreen(
                                    repo = repo,
                                    whom = openChat!!,
                                    onBack = { openChat = null },
                                    onOpenNote = { id -> openNoteId = id },
                                )
                            })
                            // Thread no longer lives in detailSlot — it
                            // renders in the right pane on wide and as a
                            // dedicated outer-when branch on compact (see
                            // below). DmChatScreen stays mounted underneath
                            // so the chat list doesn't unmount when the
                            // user opens a thread.
                            openChat != null -> ({
                                DmChatScreen(
                                    db = db,
                                    repo = repo,
                                    drafts = drafts,
                                    http = http,
                                    aiSettings = aiSettings,
                                    uiSettings = uiSettings,
                                    ourPatp = ship,
                                    whom = openChat!!,
                                    initialScrollMessageId = openChatFocusMessageId,
                                    onScrollConsumed = { openChatFocusMessageId = null },
                                    onBack = { openChat = null },
                                    onOpenThread = { parentId -> openThreadAction(parentId, null) },
                                    onOpenThreadAt = { parentId, replyAnchor ->
                                        openThreadAction(parentId, replyAnchor)
                                    },
                                    onOpenConversation = { other ->
                                        openConversationAction()
                                        openChat = other
                                    },
                                    onOpenImage = { url -> viewerImageUrl = url },
                                    onOpenSelfProfile = { showSelfProfile = true },
                                    onStartCall =
                                        if (callController != null && openChat!!.startsWith("~")) {
                                            { callController.placeCall(openChat!!) }
                                        } else {
                                            null
                                        },
                                    onPartyLine =
                                        if (callController != null && partyLine != null &&
                                            io.nisfeb.talon.call.PartyLineHost
                                                .roomFor(openChat!!) != null
                                        ) {
                                            {
                                                val whom = openChat!!
                                                val host = io.nisfeb.talon.call.PartyLineHost
                                                    .roomFor(whom)!!.first
                                                if (host == ship) {
                                                    loopScope.launch {
                                                        io.nisfeb.talon.call.PartyLineHost.startLine(
                                                            callController, repo, db, whom, whom,
                                                        )
                                                    }
                                                } else {
                                                    loopScope.launch {
                                                        io.nisfeb.talon.call.PartyLineHost
                                                            .joinLine(callController, whom)
                                                    }
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                    partyLineBar = partyLine?.let { line ->
                                        { io.nisfeb.talon.ui.PartyLineBar(line) }
                                    },
                                    onOpenGroupInfo = {
                                        openChat?.let { openGroupInfoAction(it) }
                                    },
                                    searchEmbedder = searchEmbedderClient,
                                    scrollState = chatScrollState,
                                    scrollAnchored = chatScrollAnchored,
                                )
                            })
                            else -> null
                        }
                        val listFraction by uiSettings.chatPaneListFraction.collectAsState()
                        val activeRailTab by uiSettings.activeRailTab.collectAsState()
                        val railVisibility by uiSettings.railVisibility.collectAsState()
                        val railItemOrder by uiSettings.railItemOrder.collectAsState()
                        val dailyDigestEnabled = dailyDigestSettings
                            ?.state
                            ?.collectAsState()
                            ?.value
                            ?.enabled == true
                        // Opt-in assistant: only surface its rail / kebab entry
                        // once it's supported, turned on, and has a key — the
                        // same gate the old star icon used.
                        val assistantEnabled = isAssistantSupported &&
                            aiState.assistantOn() &&
                            aiState.hasKey()
                        val enabledItems: List<RailItem> = remember(
                            railVisibility, railItemOrder, dailyDigestEnabled, assistantEnabled,
                        ) {
                            railItemOrder.filter { item ->
                                // Map.isVisible enforces the Chats always-on invariant
                                // (regardless of map state) and falls back to true
                                // for absent entries.
                                val visible = railVisibility.isVisible(item)
                                val gateOk = (item != RailItem.TodaysBrief || dailyDigestEnabled) &&
                                    (item != RailItem.Assistant || assistantEnabled)
                                visible && gateOk
                            }
                        }
                        val kebabItems: Set<RailItem> = remember(expanded, enabledItems) {
                            if (expanded) {
                                // Wide: kebab is the overflow tray. Show only items NOT on
                                // the rail. Chats is always on the rail (and wouldn't make
                                // sense in the kebab anyway), so the difference is the
                                // pane-tab + modal items the user has hidden.
                                RailItem.entries.filter { it !in enabledItems }.toSet()
                            } else {
                                // Compact: rail not visible. Kebab shows everything so
                                // mobile users always reach every destination.
                                RailItem.entries.toSet()
                            }
                        }
                        // Per-ship freshness for rail badges. Mirrors the
                        // computations DmListScreen does for its kebab pip;
                        // we read the same flows here so the rail can paint
                        // a dot on Statuses / TodaysBrief / Invites without
                        // threading every data source down through DmListScreen.
                        // Two `collectAsState` subscriptions on the same Room
                        // queries is the trade-off — cheap, and lets each
                        // call site stay self-contained.
                        val menuSeenState by menuSeen.state.collectAsState()
                        // Cross-device status-seen marker (see DmListScreen).
                        val railStatusesSeenFlow = remember(repo) {
                            repo.settingsSync?.statusesSeenMs
                                ?: kotlinx.coroutines.flow.MutableStateFlow(0L)
                        }
                        val railSyncedStatusesSeenMs by railStatusesSeenFlow.collectAsState()
                        val railPendingInvites = repo.invitesFlow.collectAsState().value
                            ?: emptyList()
                        val railLatestDigest by remember(db, ship) {
                            db.dailyDigests().streamLatestForShip(ship ?: "")
                        }.collectAsState(initial = null)
                        val railStatusFeed by remember(db) {
                            db.contacts().streamStatusFeed()
                        }.collectAsState(initial = emptyList())
                        val railInvitesSnapshot = remember(railPendingInvites) {
                            invitesSnapshot(railPendingInvites.map { it.flag })
                        }
                        val railEffectiveStatusesSeenMs =
                            maxOf(menuSeenState.lastSeenStatusesMs, railSyncedStatusesSeenMs)
                        val menuBadges = remember(
                            railLatestDigest, railStatusFeed, railPendingInvites,
                            railInvitesSnapshot, menuSeenState, railEffectiveStatusesSeenMs, ship,
                        ) {
                            MenuBadges(
                                statusesFresh = railStatusFeed.any { c ->
                                    (c.statusUpdatedMs ?: 0L) > railEffectiveStatusesSeenMs &&
                                        !c.status.isNullOrBlank() &&
                                        c.ship != ship
                                },
                                digestFresh = railLatestDigest?.dateLocal?.let {
                                    it != menuSeenState.lastSeenDigestDate
                                } == true,
                                invitesPending = railPendingInvites.isNotEmpty() &&
                                    railInvitesSnapshot != menuSeenState.lastSeenInvitesSnapshot,
                            )
                        }
                        val onRailItemClicked: (RailItem) -> Unit = { item ->
                            // Leaving the assistant: it renders in the shell
                            // content with the rail still visible, so clicking
                            // ANY rail item must close it (the Assistant case
                            // below re-opens it, so clicking A is a no-op).
                            showAssistant = false
                            // Clear the rail badge for items that show
                            // freshness signals — rail clicks were missing
                            // the markXSeen calls the kebab paths in
                            // DmListScreen already had, so the dot lingered
                            // until the user opened the kebab.
                            when (item) {
                                RailItem.Statuses -> {
                                    val now = nowMs()
                                    menuSeen.markStatusesSeenAt(now)
                                    repo.pushScope.launch {
                                        runCatching { repo.settingsSync?.pushStatusesSeen(now) }
                                    }
                                }
                                RailItem.TodaysBrief ->
                                    railLatestDigest?.dateLocal?.let { menuSeen.markDigestSeen(it) }
                                RailItem.Invites ->
                                    menuSeen.markInvitesSeen(railInvitesSnapshot)
                                else -> Unit
                            }
                            item.toRailTab()?.let { tab ->
                                uiSettings.setActiveRailTab(tab)
                            } ?: when (item) {
                                RailItem.Assistant -> openAssistantAction()
                                RailItem.Profile -> showSelfProfile = true
                                RailItem.Watchwords -> showWatchwords = true
                                RailItem.TodaysBrief -> showDailyDigest = true
                                RailItem.Administration -> showGroupAdminList = true
                                RailItem.Invites -> showInvites = true
                                RailItem.Settings -> showSettings = true
                                // pane tabs handled above; never reaches here
                                RailItem.Chats, RailItem.Statuses, RailItem.Bookmarks, RailItem.Activity -> Unit
                            }
                        }
                        val railListSlot: @Composable () -> Unit = {
                            when (activeRailTab) {
                                RailTab.Chats -> {
                                    DmListScreen(
                                        db = db,
                                        repo = repo,
                                        drafts = drafts,
                                        updateState = updateState,
                                        menuSeen = menuSeen,
                                        onOpenConversation = { whom ->
                                            openConversationAction()
                                            openChat = whom
                                        },
                                        onOpenSearch = { showSearch = true },
                                        // Opt-in + key-gated, so the entry
                                        // point stays hidden by default during
                                        // rc rollout. Gated on isAssistant-
                                        // Supported (true on both platforms) —
                                        // the embedder only enhances retrieval,
                                        // it isn't required to run.
                                        onOpenAssistant = if (assistantEnabled) {
                                            openAssistantAction
                                        } else null,
                                        onNewMessage = { showNewDm = true },
                                        onSignOut = {
                                            // session.logout() already removes just
                                            // the active ship's entry (UrbitSession.kt
                                            // line 89). Adding sessionStore.clearAll()
                                            // would wipe every other saved ship too,
                                            // which is wrong for multi-ship setups
                                            // and only worked under Path A by accident.
                                            session.logout()
                                            // Reset every navigation-state var so the
                                            // next sign-in lands on DmList instead of
                                            // a stale chat from the prior ship.
                                            openChat = null
                                            switchShipAction()
                                            viewerImageUrl = null
                                            viewerImageList = null
                                            showSelfProfile = false
                                            showSettings = false
                                            showSidebarSettings = false
                                            loggedInShip = null
                                        },
                                        onOpenSelfProfile = { showSelfProfile = true },
                                        kebabItems = kebabItems,
                                        onOpenStatusFeed = onOpenStatusFeed,
                                        onOpenInvites = { showInvites = true },
                                        onOpenBookmarks = onOpenBookmarks,
                                        onOpenActivity = onOpenActivity,
                                        onOpenContacts = { showContacts = true },
                                        onOpenWatchwords = { showWatchwords = true },
                                        onOpenDigest = { showDailyDigest = true },
                                        digestEnabled = dailyDigestEnabled,
                                        onOpenAdministration = { showGroupAdminList = true },
                                        onOpenSettings = { showSettings = true },
                                        activeShip = ship,
                                        allShips = remember(loggedInShip) {
                                            sessionStore.all().map { it.ship }
                                        },
                                        shipNicknames = run {
                                            // Async lookup — replaces a runBlocking on the
                                            // composing thread that stalled the first
                                            // frame for N saved ships * one DB hit each.
                                            val savedShips = remember(loggedInShip) {
                                                sessionStore.all().map { it.ship }
                                            }
                                            val nicknames = remember(loggedInShip) {
                                                mutableStateOf<Map<String, String>>(emptyMap())
                                            }
                                            LaunchedEffect(savedShips) {
                                                val map = savedShips.mapNotNull { ship ->
                                                    val nick = runCatching {
                                                        db.contacts().get(ship)?.nickname
                                                    }.getOrNull()
                                                    if (nick.isNullOrBlank()) null else ship to nick
                                                }.toMap()
                                                nicknames.value = map
                                            }
                                            nicknames.value
                                        },
                                        onSwitchShip = { newShip ->
                                            // Clear the previous ship's open chat before
                                            // sessionStore.setActive so no frame renders with
                                            // the new active ship but stale chat state.
                                            openChat = null
                                            switchShipAction()
                                            viewerImageUrl = null
                                            viewerImageList = null
                                            showSelfProfile = false
                                            showSettings = false
                                            showSidebarSettings = false
                                            sessionStore.setActive(newShip)
                                            loggedInShip = newShip
                                        },
                                        onAddShip = {
                                            // Drop to LoginScreen without signing the current
                                            // ship out — its session entry stays in sessionStore
                                            // so the drawer can switch back after the new login.
                                            openChat = null
                                            switchShipAction()
                                            viewerImageUrl = null
                                            viewerImageList = null
                                            showSelfProfile = false
                                            showSettings = false
                                            showSidebarSettings = false
                                            loggedInShip = null
                                        },
                                        onOpenShipSwitcher = {
                                            drawerScope.launch { drawerState.open() }
                                        },
                                        groupChannelOrder = uiSettings.groupChannelOrder
                                            .collectAsState().value,
                                        folderItemOrder = uiSettings.folderItemOrder
                                            .collectAsState().value,
                                        focusSearchRequest = focusSearchRequest,
                                        onFocusSearchHandled = { focusSearchRequest = false },
                                        showNewDmRequest = showNewDmRequest,
                                        onShowNewDmHandled = { showNewDmRequest = false },
                                        revealGroupFlag = revealGroupRequest,
                                        onRevealGroupHandled = { revealGroupRequest = null },
                                    )
                                }
                                RailTab.Statuses -> StatusFeedList(
                                    db = db,
                                    repo = repo,
                                    ourPatp = ship,
                                    onOpenContact = { other -> profileSheetShip = other },
                                )
                                RailTab.Bookmarks -> BookmarksList(
                                    db = db,
                                    repo = repo,
                                    onOpenConversation = { other, postId ->
                                        showBookmarks = false
                                        openChatFocusMessageId = postId
                                        openChat = other
                                    },
                                )
                                RailTab.Activity -> ActivityList(
                                    db = db,
                                    repo = repo,
                                    onOpenConversation = { other ->
                                        showActivity = false
                                        openChat = other
                                    },
                                    onOpenReply = { whomTarget, parentId, replyId ->
                                        showActivity = false
                                        openChat = whomTarget
                                        openThreadParent = parentId
                                        openThreadReplyAnchor = replyId
                                    },
                                )
                            }
                        }
                        DesktopShell(
                            activeRailTab = activeRailTab,
                            // Assistant is a modal destination that keeps the
                            // rail — highlight its "A" (and dim the pane-tab)
                            // while it's open.
                            activeModalItem = if (showAssistant) RailItem.Assistant else null,
                            enabledItems = enabledItems,
                            onItemClicked = onRailItemClicked,
                            list = railListSlot,
                            detail = detailSlot,
                            listFraction = listFraction,
                            onListFractionChange = { uiSettings.setChatPaneListFraction(it) },
                            menuBadges = menuBadges,
                            // The assistant takes over the whole area beside the
                            // rail and manages its OWN panes (conversations/jobs
                            // list left, transcript right) — so it gets full
                            // width here instead of being crammed into the 30%
                            // list slot. Rail stays for navigation; back arrow
                            // only on narrow (where DesktopShell stacks it).
                            content = if (showAssistant) {
                                {
                                    AssistantScreen(
                                        db = db,
                                        aiSettings = aiSettings,
                                        embedder = searchEmbedderClient,
                                        repo = repo,
                                        scheduler = io.nisfeb.talon.ai.LoopScheduler.Noop,
                                        onRunLoop = runLoopNow,
                                        onBack = if (expanded) null else ({ showAssistant = false }),
                                        // Rail is showing → force the two-pane
                                        // layout so the 64dp rail can't trip the
                                        // stacked/hamburger fallback.
                                        forceExpanded = expanded,
                                        onOpenMessage = { whomTarget, postId, parentId ->
                                            showAssistant = false
                                            openChat = whomTarget
                                            if (parentId != null) {
                                                // Anchor on the cited reply, not
                                                // the top of the thread.
                                                openThreadParent = parentId
                                                openThreadReplyAnchor = postId
                                            } else {
                                                openChatFocusMessageId = postId
                                            }
                                        },
                                    )
                                }
                            } else null,
                            rightSidebar = rightPaneContent?.let { content ->
                                {
                                    RightPaneHost(
                                        content = content,
                                        db = db,
                                        repo = repo,
                                        http = http,
                                        drafts = drafts,
                                        ourPatp = ship,
                                        onClose = { closeRightPaneAction() },
                                        onOpenCategory = { openCategoryAction(it) },
                                        onLeaveCategoryDrilldown = { closeDrilldownAction() },
                                        onOpenConversation = { other ->
                                            openConversationAction()
                                            openChat = other
                                        },
                                        onOpenImage = { url -> viewerImageUrl = url },
                                        onOpenImageList = { urls, idx ->
                                            viewerImageList = io.nisfeb.talon.ui.screens
                                                .ViewerImageList(urls, idx)
                                        },
                                        onOpenMembers = { whom ->
                                            // Resolve channel-nest → group-flag
                                            // because GroupAdminScreen takes a
                                            // group flag, not a whom. Async
                                            // because the DAO call is suspend.
                                            rightPaneScope.launch {
                                                val flag = runCatching {
                                                    db.groups().channelGroupFor(whom)?.groupFlag
                                                }.getOrNull()
                                                if (flag != null) {
                                                    openGroupAdminFlag = flag
                                                }
                                            }
                                        },
                                        powerFeaturesEnabled = powerFeaturesEnabled,
                                    )
                                }
                            },
                        )
                    }
                    }
                }

                // ContactProfileSheet rendered as an overlay so it
                // floats above any of the rendered screens. Only
                // active when a ship is requested via profileSheetShip.
                profileSheetShip?.let { peer ->
                    val contact by remember(peer) {
                        db.contacts().streamOne(peer)
                    }.collectAsState(initial = null)
                    val profileScope = rememberCoroutineScope()
                    io.nisfeb.talon.ui.ContactProfileSheet(
                        ship = peer,
                        self = peer == loggedInShip,
                        contact = contact,
                        onMessage = {
                            profileSheetShip = null
                            openChat = peer
                        },
                        onEditSelf = {
                            profileSheetShip = null
                            showSelfProfile = true
                        },
                        onDismiss = { profileSheetShip = null },
                        isInBook = peer in bookContacts,
                        onAddContact = {
                            val target = peer
                            profileSheetShip = null
                            profileScope.launch {
                                runCatching { repo.addContact(target) }
                            }
                        },
                        onRemoveContact = {
                            val target = peer
                            profileSheetShip = null
                            profileScope.launch {
                                runCatching { repo.removeContact(target) }
                            }
                        },
                    )
                }
            }
                } // ModalNavigationDrawer body
          }
        }
    }
}
