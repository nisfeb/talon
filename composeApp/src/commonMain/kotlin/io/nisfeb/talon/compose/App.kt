package io.nisfeb.talon.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.ui.screens.DmChatScreen
import io.nisfeb.talon.ui.screens.DmListScreen
import io.nisfeb.talon.ui.PlatformBackHandler
import io.nisfeb.talon.ui.screens.ImageViewerScreen
import io.nisfeb.talon.ui.screens.LoginScreen
import io.nisfeb.talon.ui.screens.ProfileEditScreen
import io.nisfeb.talon.ui.screens.SettingsScreen
import io.nisfeb.talon.ui.screens.ThreadScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession

/**
 * Top-level shared app entry point. Both Android's MainActivity and
 * desktop's Main.kt mount this. Shows LoginScreen until a session
 * exists; afterward shows DmListScreen.
 *
 * D4 addition: post-login placeholder replaced with DmListScreen.
 * D5 follow-up: DmChatScreen nav added once that screen ports.
 */
@Composable
fun App(
    session: UrbitSession,
    sessionStore: SessionStore,
    aiSettings: AiSettingsRepository,
    db: AppDatabase,
    repo: TlonChatRepo,
    drafts: DraftStore,
    updateState: UpdateState,
) {
    var loggedInShip by remember { mutableStateOf(sessionStore.activeShip()) }
    var showSettings by remember { mutableStateOf(false) }
    // D5: when non-null, the chat screen for [openChat] takes over. The
    // back button in the chat sets this to null to return to the list.
    var openChat by remember { mutableStateOf<String?>(null) }
    // When non-null, ImageViewerScreen overlays everything. Closing
    // returns the user to whichever screen was below.
    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    // When non-null, ThreadScreen takes over for the current chat. Both
    // openChat and openThreadParent are set together; back from the
    // thread clears just openThreadParent.
    var openThreadParent by remember { mutableStateOf<String?>(null) }
    var openThreadReplyAnchor by remember { mutableStateOf<String?>(null) }
    var showSelfProfile by remember { mutableStateOf(false) }

    // Drives the SSE drain loop. Without this, TlonChatRepo can scry
    // on demand but never receives pushed events — no live messages,
    // no presence, no reactions. Production wires this in
    // app/.../ui/TalonApp.kt:262 right after login. Keyed on
    // loggedInShip so re-login (different ship) restarts the loop.
    LaunchedEffect(loggedInShip) {
        if (loggedInShip != null) repo.start(session)
    }

    // System Back navigates up through the state machine on Android;
    // no-op on desktop. Order from innermost to outermost so the
    // first matching enabled handler wins (Compose registers them
    // LIFO via OnBackPressedDispatcher, so declaration order = stack
    // order). Image viewer overlays everything → handle it first.
    PlatformBackHandler(enabled = viewerImageUrl != null) {
        viewerImageUrl = null
    }
    PlatformBackHandler(enabled = openThreadParent != null) {
        openThreadParent = null
        openThreadReplyAnchor = null
    }
    PlatformBackHandler(enabled = openChat != null && openThreadParent == null) {
        openChat = null
    }
    PlatformBackHandler(enabled = showSelfProfile) {
        showSelfProfile = false
    }
    PlatformBackHandler(enabled = showSettings) {
        showSettings = false
    }

    TalonTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val ship = loggedInShip
            when {
                ship == null -> LoginScreen(
                    session = session,
                    onLoggedIn = { loggedInShip = it },
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
                    http = session.http,
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
                    onOpenSearch = {
                        // TODO(port-d4-followup): wire search screen
                        println("TODO: open search")
                    },
                    onNewMessage = {
                        // TODO(port-d4-followup): wire new-message screen
                        println("TODO: new message")
                    },
                    onSignOut = {
                        sessionStore.clearAll()
                        loggedInShip = null
                    },
                    onOpenSelfProfile = { showSelfProfile = true },
                    onOpenStatusFeed = {
                        // TODO(port-d4-followup): wire status feed screen
                        println("TODO: open status feed")
                    },
                    onOpenBookmarks = {
                        // TODO(port-d4-followup): wire bookmarks screen
                        println("TODO: open bookmarks")
                    },
                    onOpenActivity = {
                        // TODO(port-d4-followup): wire activity screen
                        println("TODO: open activity")
                    },
                    onOpenSettings = { showSettings = true },
                    activeShip = ship,
                )
            }
        }
    }
}
