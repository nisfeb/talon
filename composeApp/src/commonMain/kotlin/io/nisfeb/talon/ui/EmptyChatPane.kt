package io.nisfeb.talon.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Right-pane placeholder used by [ChatPaneScaffold] when the
 * window is wide enough to render the split layout but the user
 * hasn't selected a chat yet (or the persisted last-open chat
 * resolved to a row that no longer exists).
 *
 * Pure presentation; takes no state. The "select a chat" copy is
 * intentionally minimal — users who got here understand the
 * concept; we don't need a tutorial.
 */
@Composable
fun EmptyChatPane(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = talonLogoPainter(),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = "Select a chat to begin",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
