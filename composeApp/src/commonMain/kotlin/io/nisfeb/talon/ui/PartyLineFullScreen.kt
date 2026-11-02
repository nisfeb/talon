package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import io.nisfeb.talon.call.VideoState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.nisfeb.talon.ui.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.AudioDevices
import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.ui.icons.TalonIcons

/**
 * The full-screen party-line call view for phones — big touch targets,
 * big type, one thing to look at. Opened by the bar's expand arrow on
 * mobile; the minimize chevron drops back to the compact strip so the
 * user can browse chats again (the call keeps running behind it).
 *
 * Everything here is driven by the same [PartyState.Live] and callbacks
 * the compact bar uses; this is a presentation, not a second call.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyLineFullScreen(
    state: PartyState.Live,
    roomName: String,
    /** A 1:1 call, not a party line: its single fabricated member is
     *  not a roster, so "N people on the line" is the wrong sentence.
     *  The name and duration in [roomName] already say who and how long. */
    directCall: Boolean = false,
    nameFor: (String) -> String,
    selfShip: String,
    onToggleMute: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onMinimize: () -> Unit,
    audioDevices: AudioDevices = AudioDevices.Noop,
    videoDevices: io.nisfeb.talon.call.VideoDevices = io.nisfeb.talon.call.VideoDevices.Noop,
    /** Switch to a camera by id; restarts a live camera on it. */
    onSelectCamera: ((String) -> Unit)? = null,
    onRevokeSpeaking: ((String) -> Unit)? = null,
    onRestoreSpeaking: ((String) -> Unit)? = null,
    /** Open a DM with a member. Anyone on the line can, op or not. */
    onMessage: ((String) -> Unit)? = null,
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
    /** Screen sharing, shown only when non-null (isScreenShareSupported). */
    screenShare: ScreenShareControl? = null,
    sharing: Boolean = false,
    /** Our up link, for the self-preview tile. */
    localVideoLink: io.nisfeb.talon.call.PeerLink? = null,
    /** The down link carrying a given speaker's camera. */
    videoLinkFor: (String) -> io.nisfeb.talon.call.PeerLink? = { null },
    /** Ships whose camera is on right now. */
    videoOnShips: Set<String> = emptySet(),
    /** The speaker pinned to full-resolution video, or null. */
    focusedShip: String? = null,
    /** Pin (or unpin) a speaker for full-res video. */
    onFocusVideo: (String?) -> Unit = {},
    /** Flip front/back camera, or null to hide the control. */
    onSwitchCamera: (() -> Unit)? = null,
    /** A 1:1 call's picture, rendered above the roster. The party grid
     *  can't show it — 1:1 video lives on the CallEngine, not on a
     *  PeerLink — so the caller supplies the pane instead. */
    videoPane: (@Composable () -> Unit)? = null,
    /** The meeting view: a camera or shared screen can fill the window. */
    canFillWindow: Boolean = false,
) {
    val fullScreen = LocalWindowFullScreen.current
    val meeting = isWindowFullScreenSupported && fullScreen.isFullScreen()
    // Whose picture fills the window; back to the meeting once it goes.
    var filled by remember { mutableStateOf<String?>(null) }
    var devicesOpen by remember { mutableStateOf(false) }
    if (devicesOpen) {
        DevicesDialog(audioDevices, videoDevices, onSelectCamera.takeIf { onToggleCamera != null && videoDevices.supported }) { devicesOpen = false }
    }
    val filledMember = filled?.let { s -> state.members.firstOrNull { it.ship == s && it.ship in videoOnShips } }
    LaunchedEffect(filledMember == null) { if (filledMember == null) filled = null }
    // Leaving the call view by any road gives the window back.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { fullScreen.set(false) }
    }
    // The call's buttons: under the meeting, and over a filled picture.
    val controls: @Composable (Modifier) -> Unit = { mod ->
        // ─── Controls ───
        // Mute, camera, share and leave, and the rest behind More
        // (sneagan, 2026-11-02: too many buttons). FlowRow, so a
        // narrow phone still wraps rather than squeezing them.
        FlowRow(
            modifier = mod,
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                        if (state.muted) TalonIcons.MicOff else TalonIcons.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                }
            } else {
                // A listener or admin-muted person has no mic to
                // toggle. Say which, big enough to read at a glance.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            TalonIcons.MicOff,
                            contentDescription = null,
                            tint = if (state.selfMutedByAdmin) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(26.dp),
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
                        if (cameraOn) TalonIcons.Videocam else TalonIcons.VideocamOff,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            if (screenShare != null) {
                ScreenShareMenu(screenShare, sharing) { press ->
                    ControlButton(
                        label = if (sharing) "Stop sharing" else "Share",
                        onClick = press,
                        containerColor = if (sharing) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (sharing) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        Icon(
                            if (sharing) TalonIcons.StopScreenShare else TalonIcons.ScreenShare,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
            val menu = buildList {
                val cameraChoice = onSelectCamera.takeIf { onToggleCamera != null && videoDevices.supported }
                if (hasDeviceChoices(audioDevices, videoDevices, cameraChoice)) add(CallMenuItem("Devices") { devicesOpen = true })
                if (isWindowFullScreenSupported) add(CallMenuItem(if (meeting) "Exit full screen" else "Full screen") { fullScreen.set(!meeting) })
                if (onSwitchCamera != null && cameraOn) add(CallMenuItem("Flip camera", onSwitchCamera))
                if (onToggleRecord != null) add(CallMenuItem(if (recording) "Stop recording" else "Record", onToggleRecord))
            }
            CallMenu(menu) { open ->
                ControlButton(
                    label = "More",
                    description = "Meeting options",
                    onClick = open,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, modifier = Modifier.size(26.dp))
                }
            }

            ControlButton(
                label = if (directCall) "Hang up" else "Leave",
                onClick = onLeave,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Icon(
                    TalonIcons.CallEnd,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
    val participant: @Composable (PartyMember) -> Unit = { m ->
        ParticipantRow(
            member = m,
            nameFor = nameFor,
            isSelf = m.ship == selfShip,
            showOps = state.ops && m.ship != selfShip &&
                onRevokeSpeaking != null && onRestoreSpeaking != null,
            onRevokeSpeaking = onRevokeSpeaking,
            onRestoreSpeaking = onRestoreSpeaking,
            onMessage = onMessage,
        )
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        if (filledMember != null) {
            val self = filledMember.ship == selfShip
            FilledVideo(
                member = filledMember,
                isSelf = self,
                nameFor = nameFor,
                link = if (self) localVideoLink else videoLinkFor(filledMember.ship),
                onExit = { filled = null },
                controls = controls,
            )
        } else Column(
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
                io.nisfeb.talon.ui.IconButton(tip = "Minimize the call", onClick = onMinimize, modifier = Modifier.size(48.dp)) {
                    Icon(
                        TalonIcons.ExpandMore,
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
                    if (!directCall) {
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
            val anyVideo = state.members.any { it.ship in videoOnShips }
            if (directCall && videoPane != null) {
                // A 1:1 call's picture is what there is to look at: it
                // takes the room, over the one row that names who it is
                // and offers Message.
                Box(Modifier.weight(1f).fillMaxWidth()) { videoPane() }
                state.members.forEach { participant(it) }
            } else if (partyVideoSupported && anyVideo) {
                PartyVideoGrid(
                    members = state.members,
                    selfShip = selfShip,
                    nameFor = nameFor,
                    localVideoLink = localVideoLink,
                    videoLinkFor = videoLinkFor,
                    videoOnShips = videoOnShips,
                    focusedShip = focusedShip,
                    onFocusVideo = onFocusVideo,
                    onFill = if (canFillWindow) { ship -> filled = ship } else null,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                // The roster is always present under the grid: it is the
                // only place ops can moderate, and the only listing of
                // people who aren't on camera.
                // Meeting mode: the tiles are the meeting; names live on them,
                // so the list only steals height.
                if (!meeting) LazyColumn(
                    modifier = Modifier.weight(0.6f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.members, key = { it.ship }) { participant(it) }
                }
            } else {
                videoPane?.invoke()
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.members, key = { it.ship }) { participant(it) }
                }
            }

            controls(Modifier.fillMaxWidth().padding(vertical = 20.dp))
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    /** What explore-by-touch says, where the word under it is not enough. */
    description: String = label,
    icon: @Composable () -> Unit,
) {
    // The circle and the word under it are one button: the word used to
    // sit outside the clickable, so a click on it did nothing.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick, role = Role.Button),
    ) {
        Surface(
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            // The icon carries no contentDescription; naming the circle
            // names the button on explore-by-touch.
            modifier = Modifier.size(58.dp).semantics { contentDescription = description },
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
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
    onMessage: ((String) -> Unit)?,
) {
    val speakingRing = if (member.speaking) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
    } else {
        Modifier
    }
    CallPersonRow(
        ship = member.ship,
        isSelf = isSelf,
        mutedByAdmin = member.mutedByAdmin,
        onMessage = onMessage,
        ops = showOps,
        onRevokeSpeaking = onRevokeSpeaking,
        onRestoreSpeaking = onRestoreSpeaking,
        modifier = Modifier.fillMaxWidth().height(36.dp),
    ) {
        Box(speakingRing.padding(2.dp)) {
            Avatar(label = nameFor(member.ship), url = null, size = 26.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                nameFor(member.ship) + if (isSelf) " (you)" else "",
                style = MaterialTheme.typography.bodySmall,
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
                    style = MaterialTheme.typography.labelSmall,
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
                TalonIcons.MicOff,
                contentDescription = if (member.mutedByAdmin) "Muted by an admin" else "Muted",
                tint = if (member.mutedByAdmin) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
