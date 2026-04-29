package io.nisfeb.talon.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.ui.PlatformBackHandler
import io.nisfeb.talon.ui.screens.DmChatScreen
import io.nisfeb.talon.ui.screens.DmListScreen
import io.nisfeb.talon.ui.screens.ActivityFeedScreen
import io.nisfeb.talon.ui.screens.BookmarksScreen
import io.nisfeb.talon.ui.screens.GroupInvitesScreen
import io.nisfeb.talon.ui.screens.ImageViewerScreen
import io.nisfeb.talon.ui.screens.LoginScreen
import io.nisfeb.talon.ui.screens.NewDmScreen
import io.nisfeb.talon.ui.screens.ProfileEditScreen
import io.nisfeb.talon.ui.screens.SearchScreen
import io.nisfeb.talon.ui.screens.SettingsScreen
import io.nisfeb.talon.ui.screens.StatusFeedScreen
import io.nisfeb.talon.ui.screens.ThreadScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.Log
import okhttp3.OkHttpClient

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
    http: OkHttpClient,
    sessionStore: SessionStore,
    aiSettings: AiSettingsRepository,
    db: AppDatabase,
    drafts: DraftStore,
    updateState: UpdateState,
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
    var openChat by remember { mutableStateOf<String?>(null) }
    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    var openThreadParent by remember { mutableStateOf<String?>(null) }
    var openThreadReplyAnchor by remember { mutableStateOf<String?>(null) }
    var showSelfProfile by remember { mutableStateOf(false) }
    var showStatusFeed by remember { mutableStateOf(false) }
    var showInvites by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showActivity by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showNewDm by remember { mutableStateOf(false) }
    var profileSheetShip by remember { mutableStateOf<String?>(null) }
    // Hoisted at App level (not inside the key block) so it survives
    // the re-key triggered by tryRestore-failure recovery. Cleared
    // automatically once the user successfully signs back in.
    var loginNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loggedInShip) {
        if (loggedInShip != null) loginNotice = null
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
    PlatformBackHandler(enabled = viewerImageUrl != null) {
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
    PlatformBackHandler(enabled = showSearch) { showSearch = false }
    PlatformBackHandler(enabled = showNewDm) { showNewDm = false }
    PlatformBackHandler(enabled = showSettings) {
        showSettings = false
    }

    // Ship-scoped graph. Re-keyed on (loggedInShip ?: "__loggedout__")
    // so signing out and back in fully rebuilds session + repo. The
    // DisposableEffect tears down the prior repo before the next
    // composes, which is the only path that doesn't permanently kill
    // repo's scope (see KDoc above).
    val shipKey = loggedInShip ?: "__loggedout__"
    key(shipKey) {
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
        val repo = remember { TlonChatRepo(db = db) }

        DisposableEffect(Unit) {
            onDispose { runCatching { repo.stop() } }
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
        }

        TalonTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                // Effective ship: the session's actual restored state.
                // Using session.shipName instead of loggedInShip avoids
                // the one-frame flash of a stale DmListScreen during
                // tryRestore-failure recovery — without this gate, the
                // composition where loggedInShip != null but
                // session.shipName == null would render DmListScreen
                // briefly before the recovery LaunchedEffect fires.
                val ship = if (session.shipName != null) loggedInShip else null
                when {
                    ship == null -> LoginScreen(
                        session = session,
                        onLoggedIn = { loggedInShip = it },
                        notice = loginNotice,
                    )
                    showSettings -> SettingsScreen(
                        aiSettings = aiSettings,
                        onBack = { showSettings = false },
                    )
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
                        onOpenConversation = { other ->
                            showBookmarks = false
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
                    showSearch -> SearchScreen(
                        db = db,
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
                    )
                    showNewDm -> NewDmScreen(
                        db = db,
                        onBack = { showNewDm = false },
                        onPickPeer = { peer ->
                            showNewDm = false
                            openChat = peer
                        },
                    )
                    viewerImageUrl != null -> ImageViewerScreen(
                        url = viewerImageUrl!!,
                        onClose = { viewerImageUrl = null },
                    )
                    openChat != null && openThreadParent != null -> ThreadScreen(
                        db = db,
                        repo = repo,
                        ourPatp = ship,
                        whom = openChat!!,
                        parentId = openThreadParent!!,
                        initialScrollReplyId = openThreadReplyAnchor,
                        onScrollConsumed = { openThreadReplyAnchor = null },
                        onBack = {
                            openThreadParent = null
                            openThreadReplyAnchor = null
                        },
                        onOpenConversation = { other ->
                            openThreadParent = null
                            openThreadReplyAnchor = null
                            openChat = other
                        },
                        onOpenImage = { url -> viewerImageUrl = url },
                    )
                    openChat != null -> DmChatScreen(
                        db = db,
                        repo = repo,
                        drafts = drafts,
                        http = http,
                        aiSettings = aiSettings,
                        ourPatp = ship,
                        whom = openChat!!,
                        onBack = { openChat = null },
                        onOpenThread = { parentId ->
                            openThreadReplyAnchor = null
                            openThreadParent = parentId
                        },
                        onOpenThreadAt = { parentId, replyAnchor ->
                            openThreadReplyAnchor = replyAnchor
                            openThreadParent = parentId
                        },
                        onOpenConversation = { other -> openChat = other },
                        onOpenImage = { url -> viewerImageUrl = url },
                        onOpenSelfProfile = { showSelfProfile = true },
                    )
                    else -> DmListScreen(
                        db = db,
                        repo = repo,
                        drafts = drafts,
                        updateState = updateState,
                        onOpenConversation = { whom -> openChat = whom },
                        onOpenSearch = { showSearch = true },
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
                            openThreadParent = null
                            openThreadReplyAnchor = null
                            viewerImageUrl = null
                            showSelfProfile = false
                            showSettings = false
                            loggedInShip = null
                        },
                        onOpenSelfProfile = { showSelfProfile = true },
                        onOpenStatusFeed = { showStatusFeed = true },
                        onOpenInvites = { showInvites = true },
                        onOpenBookmarks = { showBookmarks = true },
                        onOpenActivity = { showActivity = true },
                        onOpenSettings = { showSettings = true },
                        activeShip = ship,
                    )
                }

                // ContactProfileSheet rendered as an overlay so it
                // floats above any of the rendered screens. Only
                // active when a ship is requested via profileSheetShip.
                profileSheetShip?.let { peer ->
                    val contact by remember(peer) {
                        db.contacts().streamOne(peer)
                    }.collectAsState(initial = null)
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
                    )
                }
            }
        }
    }
}
