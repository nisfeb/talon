package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.AudioDevices
import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PartyState

/**
 * The full-screen party-line call view for phones — big touch targets,
 * big type, one thing to look at. Opened by the bar's expand arrow on
 * mobile; the minimize chevron drops back to the compact strip so the
 * user can browse chats again (the call keeps running behind it).
 *
 * Everything here is driven by the same [PartyState.Live] and callbacks
 * the compact bar uses; this is a presentation, not a second call.
 */
@Composable
fun PartyLineFullScreen(
    state: PartyState.Live,
    roomName: String,
    nameFor: (String) -> String,
    selfShip: String,
    onToggleMute: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onMinimize: () -> Unit,
    audioDevices: AudioDevices = AudioDevices.Noop,
    onRevokeSpeaking: ((String) -> Unit)? = null,
    onRestoreSpeaking: ((String) -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
        ) {
            // ─── Header: minimize, title, headcount ───
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMinimize, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = "Minimize the call",
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.topic.ifBlank { roomName },
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val n = state.members.size
                    val listening = when (state.listeners) {
                        0 -> ""
                        1 -> " · 1 listening"
                        else -> " · ${state.listeners} listening"
                    }
                    Text(
                        "${if (n == 1) "1 person" else "$n people"} on the line$listening",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ─── Participants ───
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.members, key = { it.ship }) { m ->
                    ParticipantRow(
                        member = m,
                        nameFor = nameFor,
                        isSelf = m.ship == selfShip,
                        showOps = state.ops && m.ship != selfShip &&
                            onRevokeSpeaking != null && onRestoreSpeaking != null,
                        onRevokeSpeaking = onRevokeSpeaking,
                        onRestoreSpeaking = onRestoreSpeaking,
                    )
                }
            }

            // ─── Controls ───
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                if (state.canSpeak) {
                    ControlButton(
                        label = if (state.muted) "Unmute" else "Mute",
                        onClick = { onToggleMute(!state.muted) },
                        containerColor = if (state.muted) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (state.muted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    ) {
                        Icon(
                            if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                } else {
                    // A listener or admin-muted person has no mic to
                    // toggle. Say which, big enough to read at a glance.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.MicOff,
                                contentDescription = null,
                                tint = if (state.selfMutedByAdmin) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (state.selfMutedByAdmin) "Muted by an admin" else "Listening",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (audioDevices.supported) {
                    SpeakerControl(audioDevices)
                }

                ControlButton(
                    label = "Leave",
                    onClick = onLeave,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    icon: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SpeakerControl(audioDevices: AudioDevices) {
    var menuOpen by remember { mutableStateOf(false) }
    val outputs = remember(menuOpen) { audioDevices.outputs() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Surface(
                onClick = { menuOpen = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "Audio",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (out in outputs) {
                    DropdownMenuItem(
                        text = { Text(out.label) },
                        onClick = {
                            menuOpen = false
                            audioDevices.selectOutput(out.id)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Speaker", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ParticipantRow(
    member: PartyMember,
    nameFor: (String) -> String,
    isSelf: Boolean,
    showOps: Boolean,
    onRevokeSpeaking: ((String) -> Unit)?,
    onRestoreSpeaking: ((String) -> Unit)?,
) {
    val speakingRing = if (member.speaking) {
        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(speakingRing.padding(2.dp)) {
            Avatar(label = nameFor(member.ship), url = null, size = 52.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                nameFor(member.ship) + if (isSelf) " (you)" else "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val status = when {
                member.mutedByAdmin -> "Muted by an admin"
                member.muted -> "Muted"
                member.speaking -> "Speaking"
                else -> ""
            }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (member.mutedByAdmin) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (member.mutedByAdmin || member.muted) {
            Icon(
                Icons.Filled.MicOff,
                contentDescription = if (member.mutedByAdmin) "Muted by an admin" else "Muted",
                tint = if (member.mutedByAdmin) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
        if (showOps) {
            var menuOpen by remember(member.id) { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Moderate ${nameFor(member.ship)}",
                        modifier = Modifier.size(24.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (member.mutedByAdmin) {
                        DropdownMenuItem(
                            text = { Text("Allow speaking") },
                            onClick = { menuOpen = false; onRestoreSpeaking?.invoke(member.ship) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Mute for everyone") },
                            onClick = { menuOpen = false; onRevokeSpeaking?.invoke(member.ship) },
                        )
                    }
                }
            }
        }
    }
}
