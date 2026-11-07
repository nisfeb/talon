package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.NotifyLevel
import kotlinx.coroutines.launch

/**
 * What notifies the owner, said first on the Notifications page, before
 * the delivery checks: direct messages always, a group only when they
 * are mentioned unless they choose otherwise, calls ringing. The levels
 * themselves are the per-chat and per-group ones (a chat's bell, a
 * group's info page); [onSetAllGroups], where the host wires it, sets
 * every group's at once.
 */
@Composable
internal fun NotifyRulesSection(
    onSetAllGroups: (suspend (level: String) -> Int)?,
) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What notifies you", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        RuleLine("Direct messages", "Always, unless you mute one.")
        RuleLine("Groups", "When you are mentioned, unless you choose otherwise for a group or a chat.")
        RuleLine("Replies to you", "Always, in any chat that is not off.")
        RuleLine("Calls", "Ring on this device. See Calls for when they can reach you with the app closed.")
        Text(
            "To change one chat, use the bell at the top of the chat. To change a whole group, use its info page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onSetAllGroups != null) {
            Text("Every group at once", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((level, label) in listOf(NotifyLevel.ALL to "All messages", NotifyLevel.MENTIONS to "Mentions only", NotifyLevel.NONE to "Off")) {
                    FilterChip(
                        selected = picked == level,
                        enabled = !busy,
                        onClick = {
                            busy = true
                            note = null
                            scope.launch {
                                runCatching { onSetAllGroups(level) }
                                    .onSuccess { n ->
                                        picked = level
                                        note = everyGroupNote(n)
                                    }
                                    .onFailure { note = "Couldn't set them on this device." }
                                busy = false
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** What setting every group at once did: groups already there are not counted, and a chat's own level stands. */
internal fun everyGroupNote(changed: Int): String = when (changed) {
    0 -> "Every group is already set to that."
    1 -> "Set for 1 group. A chat with a level of its own keeps it."
    else -> "Set for $changed groups. A chat with a level of its own keeps it."
}

@Composable
private fun RuleLine(what: String, rule: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(what, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        Text(rule, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = androidx.compose.ui.Modifier.weight(1f))
    }
}
