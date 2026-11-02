package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The chat beside a desktop meeting (sneagan, 2026-11-02: chat in the
 * line's group's channels without leaving the meeting view). [content]
 * is the app's open chat, moved here while the pane shows, so a chat is
 * never on screen twice. [channels] are what the picker offers: the
 * group's chats, or a 1:1 call's peer, which needs no picker.
 */
@Composable
fun MeetingChatPane(
    channels: List<String>,
    labelFor: (String) -> String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(modifier.width(380.dp).fillMaxHeight(), tonalElevation = 1.dp) {
        Column {
            if (channels.size > 1) {
                CallMenu(channels.map { CallMenuItem(labelFor(it)) { onPick(it) } }) { open ->
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Channels", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
