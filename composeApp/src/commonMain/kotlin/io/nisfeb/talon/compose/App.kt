package io.nisfeb.talon.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.ui.screens.DmListScreen
import io.nisfeb.talon.ui.screens.LoginScreen
import io.nisfeb.talon.ui.screens.SettingsScreen
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
                else -> DmListScreen(
                    db = db,
                    repo = repo,
                    drafts = drafts,
                    updateState = updateState,
                    onOpenConversation = { whom ->
                        // TODO(port-d5): show DmChatScreen for whom
                        println("TODO: open chat $whom")
                    },
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
                    onOpenSelfProfile = {
                        // TODO(port-d4-followup): wire self-profile screen
                        println("TODO: open self profile")
                    },
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
