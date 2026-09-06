package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
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
    /** Resolve a @p to whatever this reader calls that person. */
    nameFor: (String) -> String = { it },
    /** Microphone and speaker selection. Defaults to the no-op, which
     *  renders nothing — the pane is absent on platforms where the OS
     *  owns routing rather than shown empty. */
    audioDevices: io.nisfeb.talon.call.AudioDevices =
        io.nisfeb.talon.call.AudioDevices.Noop,
    /** Clears a Failed banner. Null falls back to the line's own
     *  dismissFailure — Failed is sticky by design, and no inline call
     *  site passed one, so a refused join left a permanent red strip
     *  under the chat header whose only escape was to leave the chat,
     *  dismiss the floating copy, and come back. */
    onDismiss: (() -> Unit)? = null,
    /** Our own @p — the moderation menu is for OTHER people, so our
     *  own roster row never grows one. Empty means "unknown". */
    selfShip: String = "",
    /**
     * Persist a moderation mute/unmute on the host ship
     * (CallController.moderateMember). The SFU-side revoke/restore is
     * wired here regardless; without this the change lasts only until
     * the target rejoins. Wire 5.
     */
    onModerate: ((ship: String, mute: Boolean) -> Unit)? = null,
    /** True while WE are recording; drives the full-screen control. */
    recording: Boolean = false,
    /** Ships recording the line right now (wire 7). */
    recordedBy: Set<String> = emptySet(),
    /** A 1:1 call is ringing. The immersive full-screen is a Dialog
     *  (its own window) while the ring card is a Popup (a sub-window),
     *  so the ring was drawn BEHIND it: the tone played with no Answer
     *  or Decline anywhere, and the call timed out. */
    incomingCall: Boolean = false,
    /** Toggle recording, or null to hide the control. */
    onToggleRecord: (() -> Unit)? = null,
) {
    val state by party.state.collectAsState()
    val cameraOn by party.cameraOn.collectAsState()
    val localLink by party.localVideoLink.collectAsState()
    val videoOnShips by party.videoOn.collectAsState()
    val focused by party.focusedVideo.collectAsState()
    val videoScope = rememberCoroutineScope()
    val camera = rememberCameraPermission()
    // setCameraEnabled's false is the only report that the camera never
    // opened — no device, a refused permission, or no up link. Dropped,
    // the button was inert: the tap did nothing and said nothing.
    var cameraError by remember { mutableStateOf(false) }
    PartyLineBarContent(
        state = state,
        modifier = modifier,
        onToggleMute = { party.setMuted(it) },
        onLeave = { party.leave() },
        partyVideoSupported = isPartyVideoSupported,
        cameraOn = cameraOn,
        cameraError = cameraError,
        onToggleCamera = if (isPartyVideoSupported) {
            {
                // Ask the first time; the tap that grants isn't the tap
                // that opens the camera — how every gated control behaves.
                // Read the live value, so a fast double-tap can't desync.
                if (!camera.granted) camera.request()
                else videoScope.launch {
                    val on = !party.cameraOn.value
                    // A failed turn-off leaves nothing to warn about, and
                    // a success clears the last failure's notice.
                    cameraError = !party.setCameraEnabled(on) && on
                }
            }
        } else {
            null
        },
        localVideoLink = localLink,
        videoLinkFor = { party.videoLinkFor(it) },
        videoOnShips = videoOnShips,
        focusedShip = focused,
        onFocusVideo = { videoScope.launch { party.setFocusedVideo(it) } },
        onSwitchCamera = if (isCameraSwitchSupported) {
            { party.switchCamera() }
        } else {
            null
        },
        nameFor = nameFor,
        audioDevices = audioDevices,
        onDismiss = onDismiss ?: { party.dismissFailure() },
        selfShip = selfShip,
        onRevokeSpeaking = { ship ->
            party.revokeSpeaking(ship)
            onModerate?.invoke(ship, true)
        },
        onRestoreSpeaking = { ship ->
            party.restoreSpeaking(ship)
            onModerate?.invoke(ship, false)
        },
        recording = recording,
        recordedBy = recordedBy,
        incomingCall = incomingCall,
        onToggleRecord = onToggleRecord,
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
    modifier: Modifier = Modifier,
    onToggleMute: (Boolean) -> Unit = {},
    onLeave: () -> Unit = {},
    /** Resolve a @p to whatever this reader calls that person.
     *  Identity is the @p; the nickname is a courtesy. */
    nameFor: (String) -> String = { it },
    audioDevices: io.nisfeb.talon.call.AudioDevices =
        io.nisfeb.talon.call.AudioDevices.Noop,
    /**
     * Replaces the computed "N on the line" text.
     *
     * A 1:1 call renders through this same bar — one roster row, one
     * mute button, one hang-up — but "1 on the line" is the wrong
     * sentence for a phone call, so the caller supplies its own.
     */
    headline: String? = null,
    /**
     * Trailing "Dismiss" for a terminal notice.
     *
     * Only meaningful with [PartyState.Failed], which is how a call
     * that has ended renders: same strip as the live call it replaces,
     * rather than a differently-shaped banner appearing where the bar
     * just was.
     */
    onDismiss: (() -> Unit)? = null,
    /** Our own @p; rows matching it get no moderation menu. */
    selfShip: String = "",
    /** Ops moderation: mute [ship] for everyone on the line. The
     *  per-member menu renders only when both callbacks are non-null
     *  AND the SFU granted us op. Wire 5. */
    onRevokeSpeaking: ((String) -> Unit)? = null,
    /** Inverse of [onRevokeSpeaking]: let [ship] speak again. */
    onRestoreSpeaking: ((String) -> Unit)? = null,
    /** True while WE are recording. */
    recording: Boolean = false,
    /** Ships recording the line right now (wire 7). */
    recordedBy: Set<String> = emptySet(),
    /** A 1:1 call is ringing; step out of the immersive full-screen so
     *  the ring card is reachable. */
    incomingCall: Boolean = false,
    /** Toggle recording, or null to hide the control. */
    onToggleRecord: (() -> Unit)? = null,
    /**
     * Camera toggle, shown only when non-null.
     *
     * Null for party lines, which are audio-only — so the button
     * exists exactly where video does, rather than being greyed out
     * somewhere it can never work (CLAUDE.md #3).
     */
    onToggleCamera: (() -> Unit)? = null,
    cameraOn: Boolean = false,
    /** The last attempt to open the camera failed. Worth a line of its
     *  own: the toggle looks identical whether the camera opened or the
     *  machine has no webcam at all. */
    cameraError: Boolean = false,
    /** Video conference: render tiles in the full-screen view. */
    partyVideoSupported: Boolean = false,
    localVideoLink: io.nisfeb.talon.call.PeerLink? = null,
    videoLinkFor: (String) -> io.nisfeb.talon.call.PeerLink? = { null },
    /** Ships whose camera is on right now (authoritative on/off, from
     *  explicit signalling — a down link's track is always present). */
    videoOnShips: Set<String> = emptySet(),
    focusedShip: String? = null,
    onFocusVideo: (String?) -> Unit = {},
    onSwitchCamera: (() -> Unit)? = null,
) {
    if (state is PartyState.Idle) return
    var expanded by remember { mutableStateOf(false) }
    // On a phone the expand arrow opens a full-screen call view with
    // big controls instead of the inline roster; the compact strip and
    // "browse chats" stay one minimize-tap away. A 1:1 call (headline)
    // keeps the inline behaviour — its roster is fabricated.
    var fullScreen by remember { mutableStateOf(false) }
    LaunchedEffect(incomingCall) { if (incomingCall) fullScreen = false }
    val immersive = isImmersiveCallSupported && headline == null

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
                is PartyState.Connecting -> {
                    Text(
                        "Joining the line…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // Same way out the Live branch has. A blackholed
                    // SFU connect otherwise leaves "Joining…" with no
                    // control at all.
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

                is PartyState.Failed -> {
                    Text(
                        headline ?: "Party line: ${s.why}",
                        style = MaterialTheme.typography.bodyMedium,
                        // Error red only for an actual party-line
                        // failure. A caller supplying its own headline
                        // is framing it itself, and a call ending
                        // normally is not a fault worth colouring.
                        color = if (headline != null) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                }

                is PartyState.Live -> {
                    // Count people, then name them: in a busy line the
                    // number is what you want at a glance, and the
                    // names overflow anyway.
                    val n = s.members.size
                    // Through nameFor, not the raw @p: the roster below
                    // has always shown nicknames and this line hadn't,
                    // so the same person read two different ways in one
                    // strip.
                    val who = s.members.joinToString(", ") { nameFor(it.ship) }
                    // An open line must never be able to look quiet,
                    // so the listener count sits ahead of the names.
                    val listening = when (s.listeners) {
                        0 -> ""
                        1 -> " · 1 listening"
                        else -> " · ${s.listeners} listening"
                    }
                    // The recording mark belongs on the compact strip,
                    // which is the ONLY thing a desktop user normally
                    // sees: it used to live solely in the expanded
                    // RecordRow (unreachable on mobile) and the
                    // full-screen header, so the bar contradicted the
                    // consent dialog's promise that everyone is told.
                    val rec = if (recordedBy.isEmpty()) "" else " · ● Recording"
                    val label = headline ?: when {
                        s.canSpeak && s.media != MediaState.Live -> "Connecting audio…"
                        n == 0 -> "On the line — waiting for others$listening$rec"
                        else -> "$n on the line$listening$rec: $who"
                    }
                    // One line, not two: on a phone this bar sits on
                    // top of the conversation, and a second row costs
                    // real reading space. Ordered so that truncation
                    // eats the least important part — the count and
                    // the listener warning survive; names go first.
                    // A 1:1 call (headline != null) has no real roster —
                    // CallStrip fabricates one member whose speaking/
                    // muted never update, which read as "peer is silent
                    // and never muted". The expander survives only where
                    // it still has a job: picking audio devices.
                    val expandable = headline == null || audioDevices.supported
                    Column(
                        Modifier.weight(1f).then(
                            if (expandable) {
                                Modifier.clickable {
                                    if (immersive) fullScreen = true else expanded = !expanded
                                }
                            } else {
                                Modifier
                            },
                        ),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The topic, when an admin has set one — it is
                        // what the line is FOR, so it outranks the
                        // room's own name.
                        if (s.topic.isNotBlank()) {
                            Text(
                                s.topic,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.canSpeak) {
                            IconButton(onClick = { onToggleMute(!s.muted) }) {
                                Icon(
                                    if (s.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                    contentDescription = if (s.muted) "Unmute" else "Mute",
                                )
                            }
                        } else {
                            // A listener has no mic to toggle — a dead
                            // mute button would read as broken. Say
                            // what we are instead, quietly.
                            Text(
                                if (s.selfMutedByAdmin) "Muted by an admin" else "Listening",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (s.selfMutedByAdmin) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        // A listener has no up link, so the camera never
                        // opens — setCameraEnabled returns false before
                        // it reaches a device.
                        if (onToggleCamera != null && s.canSpeak) {
                            IconButton(onClick = {
                                // Turning the camera on blind broadcasts a
                                // framing nobody here can see: the self
                                // tile lives only in PartyVideoGrid, and
                                // neither surface opens on its own.
                                if (!cameraOn) {
                                    if (immersive) fullScreen = true
                                    else if (partyVideoSupported && headline == null) {
                                        expanded = true
                                    }
                                }
                                onToggleCamera()
                            }) {
                                Icon(
                                    if (cameraOn) Icons.Filled.Videocam
                                    else Icons.Filled.VideocamOff,
                                    contentDescription =
                                        if (cameraOn) "Turn the camera off"
                                        else "Turn the camera on",
                                )
                            }
                        }
                        if (expandable) {
                            IconButton(onClick = {
                                if (immersive) fullScreen = true else expanded = !expanded
                            }) {
                                Icon(
                                    if (expanded) Icons.Filled.ExpandLess
                                    else Icons.Filled.ExpandMore,
                                    contentDescription =
                                        if (immersive) "Open the full-screen call"
                                        else if (expanded) "Hide who's on the line"
                                        else "Who's on the line",
                                )
                            }
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
    if (cameraError) {
        Text(
            "The camera wouldn't start — no camera, or permission refused.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
    if (state is PartyState.Live && expanded) {
        // Video conference tiles (desktop, and any non-immersive
        // client): the same grid the immersive full-screen shows, so a
        // desktop user can both send and see camera video. A 1:1 call
        // (headline != null) has its own picture-in-picture, not a grid.
        if (partyVideoSupported && headline == null) {
            PartyVideoGrid(
                members = state.members,
                selfShip = selfShip,
                nameFor = nameFor,
                localVideoLink = localVideoLink,
                videoLinkFor = videoLinkFor,
                videoOnShips = videoOnShips,
                focusedShip = focusedShip,
                onFocusVideo = onFocusVideo,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            )
        }
        // No roster for a 1:1 call (headline != null): its single row
        // is fabricated and its speaking/muted flags never update.
        if (headline == null) {
            Roster(state, nameFor, selfShip, onRevokeSpeaking, onRestoreSpeaking)
        }
        // Behind the same expander as the roster: picking a headset is
        // a thing you do once, not something worth a permanent row over
        // the conversation. Renders nothing where the OS owns routing.
        AudioDeviceControls(audioDevices)
        // Recording control for non-immersive clients (desktop): the
        // immersive full-screen has its own Record button, but desktop
        // never opens it, so the control would otherwise be unreachable
        // where recording is actually supported. Party lines only.
        if (onToggleRecord != null && headline == null) {
            RecordRow(
                recording = recording,
                recordedBy = recordedBy,
                nameFor = nameFor,
                onToggleRecord = onToggleRecord,
            )
        }
    }
    }
    // Full-screen over everything, so it covers the chat regardless of
    // where the strip is mounted. onDismissRequest is the Android back
    // button — same as tapping minimize.
    if (immersive && fullScreen && state is PartyState.Live) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullScreen = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            PartyLineFullScreen(
                state = state,
                roomName = state.room,
                nameFor = nameFor,
                selfShip = selfShip,
                onToggleMute = onToggleMute,
                onLeave = { fullScreen = false; onLeave() },
                onMinimize = { fullScreen = false },
                audioDevices = audioDevices,
                onRevokeSpeaking = onRevokeSpeaking,
                onRestoreSpeaking = onRestoreSpeaking,
                recording = recording,
                recordedBy = recordedBy,
                onToggleRecord = onToggleRecord,
                partyVideoSupported = partyVideoSupported,
                cameraOn = cameraOn,
                onToggleCamera = onToggleCamera,
                localVideoLink = localVideoLink,
                videoLinkFor = videoLinkFor,
                videoOnShips = videoOnShips,
                focusedShip = focusedShip,
                onFocusVideo = onFocusVideo,
                onSwitchCamera = onSwitchCamera,
            )
        }
    }
}

/**
 * The moment between tapping the party icon and the host's answer:
 * the ask is in flight ([io.nisfeb.talon.call.CallController.pendingJoin])
 * while the line itself is still Idle, so [PartyLineBar] renders
 * nothing. One row of feedback, with the only control that means
 * anything yet — withdrawing the ask.
 */
@Composable
fun PartyLineAsking(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Text("Asking the host…", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/**
 * Anonymous listening, for admins only.
 *
 * A party line is otherwise gated by the host's membership list, so a
 * public link punches through the group's own boundary — it is opt-in,
 * and the bar says plainly that the line is open while it is on.
 */
/**
 * Who is on the line, and who is talking.
 *
 * Collapsed by default: the bar sits on top of the conversation, so
 * this only costs space when someone asks for it.
 */
@Composable
private fun Roster(
    s: PartyState.Live,
    nameFor: (String) -> String,
    selfShip: String = "",
    onRevokeSpeaking: ((String) -> Unit)? = null,
    onRestoreSpeaking: ((String) -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            for (m in s.members) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A dot rather than a moving meter: the question
                    // is "who is talking", and a meter on a strip this
                    // small reads as noise.
                    Box(
                        Modifier.size(8.dp)
                            .background(
                                if (m.speaking) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        nameFor(m.ship),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Only the muted are marked. Everyone on a line is
                    // expected to be able to talk, so a mic icon on
                    // every row would be a column of noise; the
                    // exception is the thing worth seeing.
                    if (m.mutedByAdmin) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.MicOff,
                            contentDescription = "Muted by an admin",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                    } else if (m.muted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.MicOff,
                            contentDescription = "Muted",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    // Op moderation, other people only. Wire 5. The
                    // action shown follows the target's current admin-
                    // mute state (learned over ADMIN_MUTE_KIND), so a
                    // muted person offers only "Allow speaking" and an
                    // unmuted one only "Mute for everyone".
                    if (s.ops && m.ship != selfShip &&
                        onRevokeSpeaking != null && onRestoreSpeaking != null
                    ) {
                        Box {
                            var menuOpen by remember(m.id) { mutableStateOf(false) }
                            IconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Moderate ${nameFor(m.ship)}",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                if (m.mutedByAdmin) {
                                    DropdownMenuItem(
                                        text = { Text("Allow speaking") },
                                        onClick = {
                                            menuOpen = false
                                            onRestoreSpeaking(m.ship)
                                        },
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Mute for everyone") },
                                        onClick = {
                                            menuOpen = false
                                            onRevokeSpeaking(m.ship)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (s.listeners > 0) {
                Text(
                    if (s.listeners == 1) "1 listening by link"
                    else "${s.listeners} listening by link",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The desktop expanded-bar recording control: a Record/Stop pill and,
 *  when anyone is recording, who. The immersive full-screen has its own
 *  Record button; this makes recording reachable where the full-screen
 *  never opens (desktop), gated the same way (onToggleRecord != null). */
@Composable
private fun RecordRow(
    recording: Boolean,
    recordedBy: Set<String>,
    nameFor: (String) -> String,
    onToggleRecord: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onToggleRecord,
            shape = RoundedCornerShape(8.dp),
            color = if (recording) {
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
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(12.dp)
                        .clip(if (recording) RoundedCornerShape(2.dp) else CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (recording) "Stop recording" else "Record",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (recordedBy.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(
                "Recording · " + recordedBy.joinToString(", ") { nameFor(it) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
