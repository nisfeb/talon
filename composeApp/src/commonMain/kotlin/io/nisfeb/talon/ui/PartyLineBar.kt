package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.PartyLine
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.call.ListenLink

/**
 * The "who's on the line" strip, shown under a channel header while a
 * party line is running. Deliberately a strip and not a screen: a
 * party line is something you leave open while you keep reading the
 * channel.
 */
@Composable
fun PartyLineBar(
    party: PartyLine,
    modifier: Modifier = Modifier,
    /** Non-null when this user may administer the line — the host, or
     *  an admin of the group it belongs to. Null hides the listening
     *  controls entirely rather than showing a disabled switch. */
    admin: PartyLineAdmin? = null,
) {
    val state by party.state.collectAsState()
    if (state is PartyState.Idle) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (val s = state) {
                is PartyState.Connecting ->
                    Text("Joining the line…", style = MaterialTheme.typography.bodyMedium)

                is PartyState.Failed ->
                    Text(
                        "Party line: ${s.why}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                is PartyState.Live -> {
                    val who = s.members.joinToString(", ") { it.ship }
                    val label = when {
                        s.media != MediaState.Live -> "Connecting audio…"
                        s.members.isEmpty() -> "On the line — waiting for others"
                        else -> "On the line: $who"
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { party.setMuted(!s.muted) }) {
                            Icon(
                                if (s.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (s.muted) "Unmute" else "Mute",
                            )
                        }
                        FilledIconButton(
                            onClick = { party.leave() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Icon(Icons.Filled.CallEnd, contentDescription = "Leave the line")
                        }
                    }
                }

                PartyState.Idle -> {}
            }
        }
    }
    if (state is PartyState.Live && admin != null) ListenControls(admin)
}

/**
 * What an administrator can change about a live line. Kept as a small
 * bundle rather than plumbing a CallController down here, so the bar
 * stays a view over PartyLine.
 */
data class PartyLineAdmin(
    val listening: Boolean,
    val link: ListenLink?,
    val onSetListening: (Boolean) -> Unit,
    val onShare: () -> Unit,
    val onDismissLink: () -> Unit,
)

/**
 * Anonymous listening, for admins only.
 *
 * A party line is otherwise gated by the host's membership list, so a
 * public link punches through the group's own boundary — it is opt-in,
 * and the bar says plainly that the line is open while it is on.
 */
@Composable
private fun ListenControls(admin: PartyLineAdmin) {
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Anyone with a link can listen", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (admin.listening) {
                            "This line is open to people outside the group."
                        } else {
                            "Only group members can join."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = admin.listening, onCheckedChange = admin.onSetListening)
            }
            if (admin.listening) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val link = admin.link
                    if (link == null) {
                        TextButton(onClick = admin.onShare) { Text("Create listen link") }
                    } else {
                        Text(
                            // The token is the credential; showing the
                            // whole thing invites shoulder-surfing.
                            link.url.substringBefore("?token="),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(link.url))
                        }) { Text("Copy") }
                        TextButton(onClick = admin.onDismissLink) { Text("Done") }
                    }
                }
                if (admin.link != null) {
                    Text(
                        "The link expires on its own and can't be revoked early — " +
                            "share it the way you'd share a door key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
