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
import io.nisfeb.talon.ui.screens.StatusFeedScreen
import io.nisfeb.talon.ui.screens.ThreadScreen
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

@Composable
fun TalonApp(
    initialOpenWhom: String? = null,
    initialScrollMessageId: String? = null,
    pendingShare: ShareIntent? = null,
    onShareConsumed: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as TalonApplication
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    // Follow TalonApplication's active-ship flow so switch / logout /
    // add-ship events reshape the UI without each callback having to
    // update local state.
    val activeShip by app.activeShipFlow.collectAsState()
    val allShips by app.allShipsFlow.collectAsState()
    var loggedInShip by remember { mutableStateOf<String?>(null) }
    var addingAnotherShip by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(activeShip) {
        loggedInShip = activeShip
    }
    var openWhom by remember { mutableStateOf<String?>(initialOpenWhom) }
    // Scroll target is consumed once the chat screen actually uses it,
    // so navigating back and reopening doesn't re-snap to the same msg.
    var pendingScrollMessageId by remember { mutableStateOf(initialScrollMessageId) }
    var openThread by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var newDmOpen by remember { mutableStateOf(false) }
    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    var editingProfile by remember { mutableStateOf(false) }
    var statusFeedOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var activityOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var adminListOpen by remember { mutableStateOf(false) }
    var adminGroupFlag by remember { mutableStateOf<String?>(null) }
    var invitesOpen by remember { mutableStateOf(false) }
    var profileSheetShip by remember { mutableStateOf<String?>(null) }
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

    // Single entry point for "open this conversation target" clicks.
    // `group:<flag>` (from citation taps) opens a lightweight
    // GroupHomeScreen — a member sees the channel list, a non-member
    // sees a Join CTA. Other targets route to the usual `openWhom`.
    val openConversation: (String) -> Unit = { target ->
        if (target.startsWith("group:")) {
            openGroupFlag = target.removePrefix("group:")
            openThread = null
        } else {
            openWhom = target
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
                    val shouldFire = when (level) {
                        NotifyLevel.NONE -> false
                        NotifyLevel.MENTIONS ->
                            replyToUs || isMentioned(m.contentJson, loggedInShip ?: "")
                        else -> true
                    }
                    if (!shouldFire) return@launch
                    val title = contactMap.conversationLabel(m.whom)
                    val authorLabel = contactMap.displayName(m.author)
                    val preview = StoryCache.textFor(m.id, m.contentJson)
                        .replace('\n', ' ')
                        .take(160)
                    Notifications.showMessage(
                        context = context,
                        whom = m.whom,
                        postId = m.id,
                        title = title,
                        body = if (m.whom.startsWith("~")) preview else "$authorLabel: $preview",
                        sentMs = m.sentMs,
                    )
                }
            }
        }
        onDispose { app.repo.messageListener = null }
    }

    // Foreground catch-up: when the app returns to the front, force a
    // fresh SSE reconnect (the channel may have been quietly killed by
    // doze while we were away). The reconnect path re-scries
    // init-posts + activity, so any messages that landed while we were
    // gone show up immediately.
    DisposableEffect(Unit) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                app.repo.catchUp()
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
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { _ ->
        val mod = Modifier.fillMaxSize()

        // Android back: unwind the nav stack. Furthest leaf first.
        BackHandler(enabled = viewerImageUrl != null) { viewerImageUrl = null }
        BackHandler(enabled = editingProfile) { editingProfile = false }
        BackHandler(enabled = statusFeedOpen) { statusFeedOpen = false }
        BackHandler(enabled = bookmarksOpen) { bookmarksOpen = false }
        BackHandler(enabled = activityOpen) { activityOpen = false }
        BackHandler(enabled = settingsOpen) { settingsOpen = false }
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
        BackHandler(enabled = openThread == null && openWhom != null) { openWhom = null }

        // Key the logged-in tree on the active ship so switching
        // resets every remember / collectAsState. Otherwise consumers
        // still hold flows from the previous ship's DB and show its
        // data even after `app.db` has been rebuilt.
        androidx.compose.runtime.key(loggedInShip) {
        when {
            addingAnotherShip -> LoginScreen(
                session = app.session,
                onLoggedIn = { ship ->
                    addingAnotherShip = false
                    app.onShipLoggedIn(ship)
                },
            )

            loggedInShip == null -> LoginScreen(
                session = app.session,
                onLoggedIn = { ship -> app.onShipLoggedIn(ship) },
            )

            viewerImageUrl != null -> ImageViewerScreen(
                url = viewerImageUrl!!,
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
                onOpenContact = { ship ->
                    profileSheetShip = ship
                },
                onBack = { statusFeedOpen = false },
                modifier = mod,
            )

            bookmarksOpen -> BookmarksScreen(
                db = app.db,
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
                onBack = { activityOpen = false },
                modifier = mod,
            )

            adminGroupFlag != null -> GroupAdminScreen(
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
                flag = openGroupFlag!!,
                onBack = { openGroupFlag = null },
                onOpenChannel = { nest ->
                    openGroupFlag = null
                    openWhom = nest
                },
                modifier = mod,
            )

            settingsOpen -> SettingsScreen(
                onBack = { settingsOpen = false },
                modifier = mod,
            )

            pendingShare != null -> ShareTargetScreen(
                db = app.db,
                share = pendingShare,
                onPick = { whom ->
                    when (pendingShare) {
                        is ShareIntent.Text -> {
                            // Append to any existing draft so nothing is lost.
                            val existing = app.drafts.load(whom)
                            val merged = if (existing.isBlank()) pendingShare.text
                                else "${existing.trimEnd()}\n\n${pendingShare.text}"
                            app.drafts.save(whom, merged)
                        }
                        is ShareIntent.Image -> {
                            appScope.launch {
                                runCatching {
                                    val resolver = context.contentResolver
                                    val name = resolveFileName(
                                        resolver,
                                        pendingShare.uri,
                                        default = "image",
                                    )
                                    val bytes = resolver.openInputStream(pendingShare.uri)
                                        ?.use { it.readBytes() }
                                        ?: error("cannot read shared image bytes")
                                    val hostedUrl = app.repo.uploadImage(
                                        bytes = bytes,
                                        contentType = pendingShare.mimeType,
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
                                    val name = resolveFileName(
                                        resolver,
                                        pendingShare.uri,
                                        default = "file",
                                    )
                                    val bytes = resolver.openInputStream(pendingShare.uri)
                                        ?.use { it.readBytes() }
                                        ?: error("cannot read shared file bytes")
                                    val hostedUrl = app.repo.uploadImage(
                                        bytes = bytes,
                                        contentType = pendingShare.mimeType,
                                        fileName = name,
                                    )
                                    // Link block: markdown parses into an
                                    // inline link span that renders as a
                                    // tappable attachment on every client.
                                    app.repo.send(whom, "[📎 $name]($hostedUrl)")
                                }
                            }
                        }
                    }
                    openWhom = whom
                    onShareConsumed()
                },
                onCancel = onShareConsumed,
                modifier = mod,
            )

            openThread != null && openWhom != null -> ThreadScreen(
                db = app.db,
                repo = app.repo,
                ourPatp = loggedInShip ?: "",
                whom = openWhom!!,
                parentId = openThread!!,
                onBack = { openThread = null },
                onOpenConversation = openConversation,
                onOpenImage = { viewerImageUrl = it },
                modifier = mod,
            )

            openWhom != null && openWhom!!.startsWith("diary/") -> when {
                notebookComposeOpen -> NotebookComposeScreen(
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
                    whom = openWhom!!,
                    onBack = { openWhom = null },
                    onOpenPost = { openNotebookPostId = it },
                    onCompose = { notebookComposeOpen = true },
                    modifier = mod,
                )
            }

            openWhom != null && openWhom!!.startsWith("heap/") -> when {
                galleryComposeOpen -> GalleryComposeScreen(
                    whom = openWhom!!,
                    onBack = { galleryComposeOpen = false },
                    onPosted = { galleryComposeOpen = false },
                    modifier = mod,
                )
                openGalleryPostId != null -> GalleryPostScreen(
                    whom = openWhom!!,
                    postId = openGalleryPostId!!,
                    onBack = { openGalleryPostId = null },
                    modifier = mod,
                )
                else -> GalleryGridScreen(
                    whom = openWhom!!,
                    onBack = { openWhom = null },
                    onOpenPost = { openGalleryPostId = it },
                    onCompose = { galleryComposeOpen = true },
                    modifier = mod,
                )
            }

            openWhom != null -> DmChatScreen(
                db = app.db,
                repo = app.repo,
                ourPatp = loggedInShip ?: "",
                whom = openWhom!!,
                initialScrollMessageId = pendingScrollMessageId,
                onScrollConsumed = { pendingScrollMessageId = null },
                onBack = { openWhom = null },
                onOpenThread = { openThread = it },
                onOpenConversation = openConversation,
                onOpenImage = { viewerImageUrl = it },
                onOpenSelfProfile = { editingProfile = true },
                modifier = mod,
            )

            searchOpen -> SearchScreen(
                db = app.db,
                onOpenConversation = { whom ->
                    searchOpen = false
                    openWhom = whom
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
                onOpenConversation = { openWhom = it },
                onOpenSearch = { searchOpen = true },
                onNewMessage = { newDmOpen = true },
                onOpenSelfProfile = { editingProfile = true },
                onOpenStatusFeed = { statusFeedOpen = true },
                onOpenBookmarks = { bookmarksOpen = true },
                onOpenActivity = { activityOpen = true },
                onOpenAdministration = { adminListOpen = true },
                onOpenInvites = { invitesOpen = true },
                onOpenSettings = { settingsOpen = true },
                onSignOut = {
                    app.signOutActive()
                    TalonSyncService.stop(context)
                    openWhom = null
                    openThread = null
                    searchOpen = false
                    newDmOpen = false
                },
                allShips = allShips,
                activeShip = loggedInShip,
                shipNicknames = app.shipProfiles.nicknames.collectAsState().value,
                onSwitchShip = { ship ->
                    if (ship != loggedInShip) {
                        openWhom = null
                        openThread = null
                        searchOpen = false
                        newDmOpen = false
                        app.switchShip(ship)
                    }
                },
                onAddShip = { addingAnotherShip = true },
                modifier = mod,
            )
        }

        // Profile sheet overlay — opened from the status feed, and from
        // any other screen that sets `profileSheetShip`.
        profileSheetShip?.let { ship ->
            ContactProfileSheet(
                ship = ship,
                self = ship == (loggedInShip ?: ""),
                contact = contactMap.contact(ship),
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
}
