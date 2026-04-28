package io.nisfeb.talon.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ui.screens.LoginScreen
import io.nisfeb.talon.ui.screens.SettingsScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession

/**
 * Top-level shared app entry point. Both Android's MainActivity and
 * desktop's Main.kt mount this. Shows LoginScreen until a session
 * exists; afterward shows a placeholder until D4 wires real
 * post-login screens (DmListScreen, DmChatScreen).
 *
 * D2 addition: Settings nav button on the post-login placeholder
 * opens SettingsScreen with a back button.
 */
@Composable
fun App(
    session: UrbitSession,
    sessionStore: SessionStore,
    aiSettings: AiSettingsRepository,
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
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Logged in as $ship",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { showSettings = true }) {
                                Text("Settings")
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Full UI ports in D4 (DmListScreen) and D5 (DmChatScreen).",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
