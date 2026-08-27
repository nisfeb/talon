package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
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
    PartyLineBarContent(
        state = state,
        admin = admin,
        modifier = modifier,
        onToggleMute = { party.setMuted(it) },
        onLeave = { party.leave() },
    )
}

/**
 * The bar without a [PartyLine] behind it. Split out so its size can
 * be measured at phone width — this strip sits on top of the
 * conversation, so every row it takes is a row of chat nobody can see.
 */
@Composable
fun PartyLineBarContent(
    state: PartyState,
    admin: PartyLineAdmin?,
    modifier: Modifier = Modifier,
    onToggleMute: (Boolean) -> Unit = {},
    onLeave: () -> Unit = {},
) {
    if (state is PartyState.Idle) return

    // One node, not two siblings: the bar and the admin strip have to
    // stack vertically, and emitting them loose leaves that to whatever
    // container the caller happens to use — a Box would overlay them.
    Column(modifier.fillMaxWidth()) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                    // Count people, then name them: in a busy line the
                    // number is what you want at a glance, and the
                    // names overflow anyway.
                    val n = s.members.size
                    val who = s.members.joinToString(", ") { it.ship }
                    // An open line must never be able to look quiet,
                    // so the listener count sits ahead of the names.
                    val listening = when (s.listeners) {
                        0 -> ""
                        1 -> " · 1 listening"
                        else -> " · ${s.listeners} listening"
                    }
                    val label = when {
                        s.media != MediaState.Live -> "Connecting audio…"
                        n == 0 -> "On the line — waiting for others$listening"
                        else -> "$n on the line$listening: $who"
                    }
                    // One line, not two: on a phone this bar sits on
                    // top of the conversation, and a second row costs
                    // real reading space. Ordered so that truncation
                    // eats the least important part — the count and
                    // the listener warning survive; names go first.
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onToggleMute(!s.muted) }) {
                            Icon(
                                if (s.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (s.muted) "Unmute" else "Mute",
                            )
                        }
                        FilledIconButton(
                            onClick = onLeave,
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
    if (state is PartyState.Live && admin != null) {
        ListenControls(admin, state.listeners)
    }
    }
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
private fun ListenControls(admin: PartyLineAdmin, listeners: Int) {
    val clipboard = LocalClipboardManager.current
    // Collapsed by default. This sits above the conversation on a
    // phone, and a permanently-open panel with a switch, a URL and a
    // caveat paragraph is most of the screen. Collapsed it is one row;
    // the state that must never hide — the line being open — is
    // already on the bar above.
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        admin.listening && listeners > 0 -> "Open to listeners · $listeners"
                        admin.listening -> "Open to listeners"
                        else -> "Listen links off"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide listen settings" else "Listen settings",
                )
            }
            if (!expanded) return@Column
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (admin.listening) {
                        "Anyone with the link can listen."
                    } else {
                        "Only group members can join."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
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
                        "Expires on its own; it can't be revoked early.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
