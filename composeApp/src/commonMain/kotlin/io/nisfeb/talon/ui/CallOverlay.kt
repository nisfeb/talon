package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.CallUiState
import io.nisfeb.talon.call.TrunkInstall
import io.nisfeb.talon.call.TrunkWire
import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.VideoState
import kotlinx.coroutines.flow.MutableStateFlow
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.delay

/**
 * Trunkline call surface, floated via Popup so it renders over any
 * screen. A ringing call dims the app behind a card — modal, but
 * still recognisably your app underneath; a live call collapses to a
 * top banner so you can keep reading while you talk.
 */
@Composable
fun CallOverlay(
    controller: CallController,
    modifier: Modifier = Modifier,
    /** Resolve a @p to whatever this reader calls that person, so a
     *  call names people the same way the rest of the app does. */
    nameFor: (String) -> String = { it },
    audioDevices: io.nisfeb.talon.call.AudioDevices =
        io.nisfeb.talon.call.AudioDevices.Noop,
    /**
     * True when [CallStrip] is already being rendered somewhere in the
     * layout — under a chat's header, where the party-line bar lives.
     *
     * Otherwise the strip renders right here, and App composes this
     * overlay in a slot above every screen so it takes its own space.
     * It was a Popup once, which spanned the sidebar and everything
     * else and drew over each screen's header and back button.
     */
    stripShownInline: Boolean = false,
    /** Open a DM with the person on the other end. */
    onMessage: ((String) -> Unit)? = null,
) {
    TrunkInstallPrompt(controller)

    val state by controller.state.collectAsState()
    if (state is CallUiState.None) return

    when (val s = state) {
        is CallUiState.Incoming -> FullScreenRing(
            title = nameFor(s.peer),
            subtitle = "Incoming call",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                RoundAction(
                    icon = Icons.Filled.CallEnd,
                    label = "Decline",
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError,
                    onClick = { controller.reject() },
                )
                RoundAction(
                    icon = Icons.Filled.Call,
                    label = "Answer",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = { controller.accept() },
                )
            }
        }

        is CallUiState.Outgoing -> FullScreenRing(
            title = nameFor(s.peer),
            subtitle = "Calling…",
        ) {
            RoundAction(
                icon = Icons.Filled.CallEnd,
                label = "Cancel",
                container = MaterialTheme.colorScheme.error,
                content = MaterialTheme.colorScheme.onError,
                onClick = { controller.hangup() },
            )
        }

        is CallUiState.Active, is CallUiState.Ended -> {
            // Not a Popup: as one, this drew over whatever screen was
            // underneath, header and back button included. App composes
            // CallOverlay in a slot above the screens, so the strip
            // just takes its space there.
            if (!stripShownInline) CallStrip(controller, modifier, nameFor, audioDevices, onMessage)
        }

        CallUiState.None -> {}
    }
}

/**
 * A call in progress, or one that just ended, as a strip.
 *
 * The same composable the party line uses, so a 1:1 call and a party
 * line are one surface rather than two that drift. Renders nothing
 * unless there is a call, so it can sit unconditionally in a layout
 * slot beside the party-line bar.
 */
@Composable
fun CallStrip(
    controller: CallController,
    modifier: Modifier = Modifier,
    nameFor: (String) -> String = { it },
    audioDevices: io.nisfeb.talon.call.AudioDevices =
        io.nisfeb.talon.call.AudioDevices.Noop,
    /** Open a DM with the person on the other end. */
    onMessage: ((String) -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val engine = controller.mediaEngine
    val video by (engine?.video ?: remember { MutableStateFlow(VideoState()) })
        .collectAsState()
    val camera = rememberCameraPermission()
    when (val s = state) {
        is CallUiState.Active -> Column(modifier) {
            // Pictures above the strip, so the controls stay in the
            // same place whether or not anyone has a camera on.
            if (isVideoCallsSupported && engine != null && video.anyOn) {
                CallVideoPane(engine, video, Modifier.fillMaxWidth())
            }
            PartyLineBarContent(
            state = PartyState.Live(
                room = s.peer,
                members = listOf(PartyMember(id = s.peer, ship = s.peer)),
                muted = s.muted,
                media = s.media,
            ),
            onToggleMute = { controller.setMuted(it) },
            onLeave = { controller.hangup() },
            nameFor = nameFor,
            audioDevices = audioDevices,
            // "1 on the line" is the wrong sentence for a phone call.
            headline = when (s.media) {
                MediaState.Live -> "${nameFor(s.peer)} · ${liveDuration(s.media)}"
                else -> "Connecting to ${nameFor(s.peer)}…"
            },
            cameraOn = video.localOn,
            onToggleCamera = if (isVideoCallsSupported && engine != null) {
                {
                    // Ask the first time; the tap that grants is not
                    // the tap that turns the camera on, which is how
                    // every permission-gated control behaves.
                    if (!camera.granted) camera.request()
                    else controller.setCamera(!video.localOn)
                }
            } else null,
            videoPane = if (isVideoCallsSupported && engine != null && video.anyOn) {
                { CallVideoPane(engine, video, Modifier.fillMaxWidth()) }
            } else null,
            onMessage = onMessage,
        )
        }

        is CallUiState.Ended -> {
            PartyLineBarContent(
                state = PartyState.Failed(s.peer, s.reason),
                    modifier = modifier,
                nameFor = nameFor,
                headline = "${nameFor(s.peer)} — ${s.reason}",
                onDismiss = { controller.dismissEnded() },
            )
            LaunchedEffect(s) {
                delay(ENDED_NOTICE_MS)
                controller.dismissEnded()
            }
        }

        else -> Unit
    }
}

/** How long "call ended" lingers before clearing itself. */
private const val ENDED_NOTICE_MS = 4_000L

/**
 * Offer to fetch %trunk when the ship hasn't got it.
 *
 * Says who the desk comes from, because accepting means the ship
 * installs and then keeps auto-updating software published by another
 * ship. That is the ordinary Urbit distribution model, but it is the
 * user's call to make knowingly rather than a silent side effect of
 * tapping a phone icon.
 */
@Composable
private fun TrunkInstallPrompt(controller: CallController) {
    val install by controller.install.collectAsState()
    if (install is TrunkInstall.Hidden) return

    val installing = install is TrunkInstall.Installing
    val outdated = install as? TrunkInstall.Outdated
    AlertDialog(
        // Dismissable even mid-install. The install is a coroutine on
        // the controller's own scope, not on this composition, so
        // closing the dialog only stops watching it — the desk keeps
        // arriving. Holding a modal open for the full 120s timeout
        // with every button disabled read, correctly, as the whole app
        // freezing for the length of the install.
        onDismissRequest = { controller.dismissInstall() },
        title = {
            Text(
                if (outdated != null) "Your ship's calling app is out of date"
                else "Calling needs one more app",
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (outdated != null) {
                        // Naming the mismatch matters: without it an old
                        // desk just refuses pokes and every control
                        // looks broken for no visible reason.
                        "Your ship runs an older version of %trunk than this " +
                            "app speaks, so calls and party lines won't work " +
                            "properly until it's updated."
                    } else {
                        "Calls run through %trunk, a small app on your ship. " +
                            "It isn't installed yet."
                    },
                )
                Text(
                    (if (outdated != null) "Updating" else "Installing") +
                        " it fetches %trunk from ${TrunkWire.PUBLISHER}, " +
                        "which will also supply its updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val s = install) {
                    is TrunkInstall.Installing -> Text(
                        "Installing… this can take a minute. You can close " +
                            "this and keep using Talon; it carries on in the " +
                            "background and we'll tell you if it fails.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is TrunkInstall.Failed -> Text(
                        s.why,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {}
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { controller.installTrunk() },
                enabled = !installing,
            ) {
                Text(
                    when {
                        install is TrunkInstall.Failed -> "Try again"
                        outdated != null -> "Update"
                        else -> "Install"
                    },
                )
            }
        },
        dismissButton = {
            // Stays enabled while installing — this is the way out of a
            // two-minute wait, so disabling it is exactly backwards.
            TextButton(
                onClick = { controller.dismissInstall() },
            ) { Text(if (installing) "Close" else "Not now") }
        },
    )
}

@Composable
private fun FullScreenRing(
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit,
) {
    // A scrim over the app, not an opaque repaint of it. Filling the
    // window with `surface` meant a ringing device looked like a blank
    // white app — and since every device on a ship receives every ring,
    // that is a thing people see without having placed a call.
    Popup(alignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 32.dp),
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(subtitle, style = MaterialTheme.typography.titleMedium)
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Box(Modifier.padding(top = 28.dp)) { actions() }
                }
            }
        }
    }
}

@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            // Deliberately unreachable by keyboard: a ring arrives while
            // you are mid-sentence, and a focusable button turns the next
            // space bar into "declined" — or worse, into "answered", with
            // a live mic. Answering and hanging up are pointer-only until
            // the call UI is redesigned, at which point this should come
            // back as a deliberate key binding rather than whatever the
            // focus order happened to be.
            modifier = Modifier.size(72.dp).focusProperties { canFocus = false },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = container,
                contentColor = content,
            ),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Ticking mm:ss since this composable first saw Live. */
@Composable
private fun liveDuration(media: MediaState): String {
    var startMs by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(media) {
        if (media == MediaState.Live && startMs == 0L) startMs = nowMs()
        while (true) {
            delay(1_000)
            now = nowMs()
        }
    }
    if (startMs == 0L) return "00:00"
    val secs = ((now - startMs) / 1000).coerceAtLeast(0)
    val mm = (secs / 60).toString().padStart(2, '0')
    val ss = (secs % 60).toString().padStart(2, '0')
    return "$mm:$ss"
}

/**
 * The two pictures on a 1:1 call: them large, us inset.
 *
 * A fixed height rather than a fraction of the window — the strip
 * this sits above can be rendered inside a chat pane or floated over
 * the whole app, and a pane that resized depending on which would
 * look like a bug. 16:9-ish at the width a chat column tends to be.
 */
@Composable
private fun CallVideoPane(
    engine: io.nisfeb.talon.call.CallEngine,
    video: VideoState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(220.dp),
        color = Color.Black,
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (video.remoteOn) {
                VideoSurface(engine, local = false, modifier = Modifier.fillMaxSize())
            } else {
                // Our own camera on, theirs off: say so rather than
                // showing a black rectangle that reads as broken.
                Text(
                    "Camera off",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (video.localOn) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(width = 84.dp, height = 112.dp),
                    color = Color.DarkGray,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    VideoSurface(engine, local = true, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
