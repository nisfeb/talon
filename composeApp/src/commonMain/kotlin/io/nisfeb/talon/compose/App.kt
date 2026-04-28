package io.nisfeb.talon.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.screens.LoginScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession

/**
 * Top-level shared app entry point. Both Android's MainActivity and
 * desktop's Main.kt mount this. Shows LoginScreen until a session
 * exists; afterward shows a placeholder until D2/D4 wires real
 * post-login screens.
 */
@Composable
fun App(
    session: UrbitSession,
    sessionStore: SessionStore,
) {
    var loggedInShip by remember {
        mutableStateOf(sessionStore.activeShip())
    }

    TalonTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val ship = loggedInShip
            if (ship == null) {
                LoginScreen(
                    session = session,
                    onLoggedIn = { newShip -> loggedInShip = newShip },
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Logged in as $ship\n\nFull UI ports in D2 (DmListScreen) " +
                            "and D5 (DmChatScreen).",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
