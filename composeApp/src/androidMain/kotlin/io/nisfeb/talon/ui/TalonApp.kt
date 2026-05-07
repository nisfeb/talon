package io.nisfeb.talon.ui

import androidx.activity.compose.BackHandler
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
import io.nisfeb.talon.ui.EntityActionChips
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
    var addingAnotherShip by remember { mutableStateOf(false) }
    // Locally-owned navigation state. Initial values from the
    // notification-tap intent (or null on a normal cold launch). The
    // LaunchedEffect below routes any LATER param updates from
    // MainActivity (onNewIntent re-fires consumeIntent), which a
    // plain `remember { mutableStateOf(initialX) }` would silently
    // ignore — that was the rc23-era "tap a notification, nothing
    // happens" bug.
    var openWhom by remember { mutableStateOf<String?>(initialOpenWhom) }
    // Scroll target is consumed once the chat screen actually uses it,
    // so navigating back and reopening doesn't re-snap to the same msg.
    var pendingScrollMessageId by remember { mutableStateOf(initialScrollMessageId) }
    var openThread by remember { mutableStateOf<String?>(initialOpenThread) }
    /** When non-null, ThreadScreen anchors its initial scroll to this
     *  reply id rather than the newest one. Consumed-once after the
     *  thread screen reads it. */
    var pendingThreadAnchor by remember { mutableStateOf<String?>(initialThreadAnchor) }
    var searchOpen by remember { mutableStateOf(false) }
    var newDmOpen by remember { mutableStateOf(false) }
    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    var viewerImageList by remember {
        mutableStateOf<io.nisfeb.talon.ui.screens.ViewerImageList?>(null)
    }
    var editingProfile by remember { mutableStateOf(false) }
    var statusFeedOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var activityOpen by remember { mutableStateOf(false) }
    var watchwordsOpen by remember { mutableStateOf(false) }
    // Daily Digest screen — initialOpenDigest non-null means we
    // arrived from a notification tap, so route straight there.
    var digestOpen by remember { mutableStateOf(initialOpenDigest != null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var sidebarSettingsOpen by remember { mutableStateOf(false) }
    var adminListOpen by remember { mutableStateOf(false) }
    var adminGroupFlag by remember { mutableStateOf<String?>(null) }
    var invitesOpen by remember { mutableStateOf(false) }
    var profileSheetShip by remember { mutableStateOf<String?>(null) }

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
        if (initialOpenWhom != null) {
            openWhom = initialOpenWhom
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
    // Phase 3 right-pane state — Android phone always renders these as
    // full-screen replaces (no DesktopShell on phone). Tablet landscape
    // gets the wide-pane right-column rendering via App.kt's path; this
    // file is the phone-only entry. groupInfoDrilldown is sub-state of
    // groupInfoOpenFor — both null = pane closed.
    var groupInfoOpenFor by remember { mutableStateOf<String?>(null) }
    var groupInfoDrilldown by remember { mutableStateOf<MediaCategory?>(null) }
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
    var openGroupFlag by remember { mutableStateOf<String?>(null) }

    // Per-ship persistent store for the last-open conversation.
    // `remember(app)` keeps the same SharedPreferences-backed instance
    // across recompositions so the read happens once and the same
    // object is reused for all subsequent set() calls.
    val lastOpenChatStore = remember(app) {
        io.nisfeb.talon.notify.AndroidLastOpenChatStore(app)
    }
    val lastOpenChatState by lastOpenChatStore.state.collectAsState()
    // Seed openWhom from persisted store on first composition or ship
    // switch, so wide-screen / tablet layouts restore the last chat.
    LaunchedEffect(loggedInShip) {
        if (openWhom == null && loggedInShip != null) {
            lastOpenChatState[loggedInShip]?.let { openWhom = it }
        }
    }
    // Mirror openWhom flips back into the store. Intentionally skips
    // null so backing out of a chat doesn't erase the persisted entry.
    LaunchedEffect(openWhom, loggedInShip) {
        val ship = loggedInShip ?: return@LaunchedEffect
        val whom = openWhom
        if (whom != null) lastOpenChatStore.set(ship, whom)
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
                    }
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
                    }
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
            if (app.aiSettings.state.value.semanticSearchEnabled) {
                app.embeddingIndexer.start()
            }
        }
    }

    // Latest-value refs so the callback below doesn't close over stale state.
    val openWhomState = rememberUpdatedState(openWhom)
    val contactMap by remember {
        contactMapFlow(
            app.db.contacts().stream(),
            app.db.clubs().stream(),
            app.db.groups().streamGroups(),
            app.db.groups().streamChannelGroups(),
        )
    }.let { flow -> flow.collectAsState(initial = ContactMap.EMPTY) }

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
        onDispose { app.repo.messageListener = null }
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
            if (event == Lifecycle.Event.ON_START) {
                app.repo.forceReconnect()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Dismiss any lingering notification for the conversation we just opened.
    LaunchedEffect(openWhom) {
        openWhom?.let { Notifications.clear(context, it) }
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
        BackHandler(enabled = watchwordsOpen) { watchwordsOpen = false }
        BackHandler(enabled = digestOpen) { digestOpen = false }
        BackHandler(enabled = settingsOpen) { settingsOpen = false }
        BackHandler(enabled = sidebarSettingsOpen) { sidebarSettingsOpen = false }
        BackHandler(enabled = adminGroupFlag != null) { adminGroupFlag = null }
        BackHandler(enabled = adminListOpen && adminGroupFlag == null) {
            adminListOpen = false
        }
        BackHandler(enabled = invitesOpen) { invitesOpen = false }
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
                onOpenConversation = { whom ->
                    bookmarksOpen = false
                    openWhom = whom
                },
                onBack = { bookmarksOpen = false },
                modifier = mod,
            )

            activityOpen -> ActivityFeedScreen(
                db = app.db,
                repo = app.repo,
                onOpenConversation = { whom ->
                    activityOpen = false
                    openWhom = whom
                },
                onOpenReply = { whom, parent, replyId ->
                    activityOpen = false
                    openWhom = whom
                    pendingThreadAnchor = replyId
                    openThread = parent
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
                        http = app.http,
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
                http = app.http,
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
                    http = app.http,
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
                DmChatScreen(
                    db = app.db,
                    repo = app.repo,
                    drafts = app.drafts,
                    http = app.http,
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
                    searchEmbedder = app.searchEmbedderClient,
                    // Android-only platform widgets — desktop hosts pass null
                    // and the screen degrades gracefully. Wired via
                    // composable slots so commonMain has no Android deps.
                    entityChips = { text, m -> EntityActionChips(text, m) },
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
                modifier = mod,
            )

            else -> DmListScreen(
                db = app.db,
                repo = app.repo,
                drafts = app.drafts,
                updateState = app.updateState,
                menuSeen = app.menuSeen,
                onOpenConversation = { openWhom = it },
                onOpenSearch = { searchOpen = true },
                onNewMessage = { newDmOpen = true },
                onOpenSelfProfile = { editingProfile = true },
                onOpenStatusFeed = { statusFeedOpen = true },
                onOpenBookmarks = { bookmarksOpen = true },
                onOpenActivity = { activityOpen = true },
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
                kebabItems = RailItem.entries.toSet(),
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
            )
        }
        } // key(loggedInShip)
    }
    } // CompositionLocalProvider(LocalInlineMediaPlayer)
}
