package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import io.nisfeb.talon.call.VideoState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
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
    /** True while WE are recording. */
    recording: Boolean = false,
    /** Ships recording the line right now (wire 7), for the badge
     *  everyone on the line sees. Includes us when [recording]. */
    recordedBy: Set<String> = emptySet(),
    /** Toggle recording, or null to hide the control (platform can't
     *  record, or we can't speak / aren't on the line). */
    onToggleRecord: (() -> Unit)? = null,
    /** Video conference: render tiles instead of the roster list, and
     *  show the camera control. Off for 1:1 and where unsupported. */
    partyVideoSupported: Boolean = false,
    /** Whether OUR camera is on. */
    cameraOn: Boolean = false,
    /** Toggle our camera, or null to hide the control. */
    onToggleCamera: (() -> Unit)? = null,
    /** Our up link, for the self-preview tile. */
    localVideoLink: io.nisfeb.talon.call.PeerLink? = null,
    /** The down link carrying a given speaker's camera. */
    videoLinkFor: (String) -> io.nisfeb.talon.call.PeerLink? = { null },
    /** The speaker pinned to full-resolution video, or null. */
    focusedShip: String? = null,
    /** Pin (or unpin) a speaker for full-res video. */
    onFocusVideo: (String?) -> Unit = {},
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
                    if (recordedBy.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Box(
                                Modifier.size(9.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error),
                            )
                            Spacer(Modifier.width(6.dp))
                            val who = recordedBy.joinToString(", ") { nameFor(it) }
                            Text(
                                if (recordedBy.size == 1) "Recording · $who" else "Recording · $who",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // ─── Participants ───
            if (partyVideoSupported) {
                // Conference grid: a tile per person, video where a
                // camera is on, avatar otherwise.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    gridItems(state.members, key = { it.ship }) { m ->
                        val isSelf = m.ship == selfShip
                        VideoTile(
                            member = m,
                            isSelf = isSelf,
                            nameFor = nameFor,
                            link = if (isSelf) localVideoLink else videoLinkFor(m.ship),
                            focused = m.ship == focusedShip,
                            // Tapping a remote tile pins it to full res;
                            // tapping the pinned one unpins. Self never
                            // pins (we send our own camera, not receive it).
                            onTap = if (isSelf) null else {
                                { onFocusVideo(if (m.ship == focusedShip) null else m.ship) }
                            },
                        )
                    }
                }
            } else {
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

                if (onToggleCamera != null) {
                    ControlButton(
                        label = if (cameraOn) "Camera off" else "Camera",
                        onClick = onToggleCamera,
                        containerColor = if (cameraOn) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (cameraOn) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        Icon(
                            if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                if (onToggleRecord != null) {
                    ControlButton(
                        label = if (recording) "Stop" else "Record",
                        onClick = onToggleRecord,
                        containerColor = if (recording) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (recording) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        if (recording) {
                            // A stop square.
                            Box(
                                Modifier.size(22.dp).clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.error),
                            )
                        } else {
                            // A record dot.
                            Box(
                                Modifier.size(26.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error),
                            )
                        }
                    }
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
    // selectedOutput isn't observable, so the caption is driven by local
    // state: seeded from the current route, updated on pick, and
    // re-read each time the menu opens (the route can also change from
    // outside — a headset plugged in, the bar's own picker).
    var selected by remember { mutableStateOf(audioDevices.selectedOutput) }
    LaunchedEffect(menuOpen) { if (menuOpen) selected = audioDevices.selectedOutput }
    val routeLabel = outputs.firstOrNull { it.id == selected }?.label ?: "System default"
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
                            // Reflect what actually took effect: the
                            // platform can refuse a route.
                            selected = audioDevices.selectedOutput
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            routeLabel,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One conference tile: a speaker's camera when on, their avatar
 *  otherwise, with a name label, a mic-off marker, and a ring while
 *  they're speaking. */
@Composable
private fun VideoTile(
    member: PartyMember,
    isSelf: Boolean,
    nameFor: (String) -> String,
    link: io.nisfeb.talon.call.PeerLink?,
    focused: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val videoFlow = remember(link) { link?.video ?: MutableStateFlow(VideoState()) }
    val video by videoFlow.collectAsState()
    val on = if (isSelf) video.localOn else video.remoteOn
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (link != null && on) {
            VideoSurface(link, local = isSelf, Modifier.fillMaxSize())
        } else {
            Avatar(label = nameFor(member.ship), url = null, size = 56.dp)
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (member.muted || member.mutedByAdmin) {
                Icon(
                    Icons.Filled.MicOff,
                    contentDescription = "Muted",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                nameFor(member.ship) + if (isSelf) " (you)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A pinned tile gets a distinct ring; otherwise a speaking one.
        val ring = when {
            focused -> MaterialTheme.colorScheme.tertiary
            member.speaking -> MaterialTheme.colorScheme.primary
            else -> null
        }
        if (ring != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(if (focused) 3.dp else 2.dp, ring, RoundedCornerShape(10.dp)),
            )
        }
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
