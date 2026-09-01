package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
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
    /** Resolve a @p to whatever this reader calls that person. */
    nameFor: (String) -> String = { it },
    /** Microphone and speaker selection. Defaults to the no-op, which
     *  renders nothing — the pane is absent on platforms where the OS
     *  owns routing rather than shown empty. */
    audioDevices: io.nisfeb.talon.call.AudioDevices =
        io.nisfeb.talon.call.AudioDevices.Noop,
    /** Clears a Failed banner. Defaulted so existing call sites keep
     *  compiling; the floating fallback MUST pass one — Failed is
     *  sticky, and a floated banner without it overlays every screen
     *  with no way out. */
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
) {
    val state by party.state.collectAsState()
    PartyLineBarContent(
        state = state,
        admin = admin,
        modifier = modifier,
        onToggleMute = { party.setMuted(it) },
        onLeave = { party.leave() },
        nameFor = nameFor,
        audioDevices = audioDevices,
        onDismiss = onDismiss,
        selfShip = selfShip,
        onRevokeSpeaking = { ship ->
            party.revokeSpeaking(ship)
            onModerate?.invoke(ship, true)
        },
        onRestoreSpeaking = { ship ->
            party.restoreSpeaking(ship)
            onModerate?.invoke(ship, false)
        },
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
) {
    if (state is PartyState.Idle) return
    var expanded by remember { mutableStateOf(false) }

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
                    val label = headline ?: when {
                        s.media != MediaState.Live -> "Connecting audio…"
                        n == 0 -> "On the line — waiting for others$listening"
                        else -> "$n on the line$listening: $who"
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
                                Modifier.clickable { expanded = !expanded }
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
                                "Listening",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        if (expandable) {
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(
                                    if (expanded) Icons.Filled.ExpandLess
                                    else Icons.Filled.ExpandMore,
                                    contentDescription =
                                        if (expanded) "Hide who's on the line" else "Who's on the line",
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
    if (state is PartyState.Live && expanded) {
        // No roster for a 1:1 call (headline != null): its single row
        // is fabricated and its speaking/muted flags never update.
        if (headline == null) {
            Roster(state, nameFor, selfShip, onRevokeSpeaking, onRestoreSpeaking)
        }
        // Behind the same expander as the roster: picking a headset is
        // a thing you do once, not something worth a permanent row over
        // the conversation. Renders nothing where the OS owns routing.
        AudioDeviceControls(audioDevices)
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
                    if (m.muted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.MicOff,
                            contentDescription = "Muted",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    // Op moderation, other people only: we can't know
                    // another member's permissions from here, so both
                    // actions are always offered and the SFU sorts it
                    // out. Wire 5.
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
                                DropdownMenuItem(
                                    text = { Text("Mute for everyone") },
                                    onClick = {
                                        menuOpen = false
                                        onRevokeSpeaking(m.ship)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Allow speaking") },
                                    onClick = {
                                        menuOpen = false
                                        onRestoreSpeaking(m.ship)
                                    },
                                )
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
