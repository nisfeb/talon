package io.nisfeb.talon.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.nisfeb.talon.bridge.BridgeRunner
import io.nisfeb.talon.bridge.Config
import io.nisfeb.talon.bridge.DEFAULT_DEVICE
import io.nisfeb.talon.bridge.DefaultClips
import io.nisfeb.talon.bridge.LevelMeter
import io.nisfeb.talon.bridge.Pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/**
 * The party line party manager: one window that runs the bridge, shows
 * the Space link with live meters in both directions, routes any app's
 * audio to the party, the Space or both, and fires soundboard clips.
 * Everything audio goes through [Pulse]; the bridge itself uses the
 * system default devices and gets moved onto the virtual ones.
 */
object PartyManager {
    fun launch(configFile: File) = application {
        val runner = remember { BridgeRunner() }
        Window(
            onCloseRequest = { runner.stop(); exitApplication() },
            title = "Talon Party Manager",
            state = rememberWindowState(width = 1200.dp, height = 860.dp),
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { ManagerScreen(runner, configFile) }
            }
        }
    }
}

private data class Draft(val url: String, val code: String, val host: String, val room: String) {
    val canConnect get() = url.isNotBlank() && code.isNotBlank()
    val savedLine get() = "${host.trim()}/${room.trim()}"

    fun toConfig() = Config(
        shipUrl = url.trim(),
        shipCode = code.trim(),
        host = host.trim().let { if (it.startsWith("~")) it else "~$it" },
        room = room.trim(),
        audioIn = DEFAULT_DEVICE,
        audioOut = DEFAULT_DEVICE,
        play = null,
        loop = false,
        record = null,
    )

    fun save(file: File) = Config.save(
        file,
        mapOf(
            "talon.bridge.ship.url" to url.trim(),
            "talon.bridge.ship.code" to code.trim(),
            "talon.bridge.host" to host.trim(),
            "talon.bridge.room" to room.trim(),
        ),
    )

    companion object {
        fun load(file: File): Draft {
            val p = Properties().apply { if (file.isFile) file.inputStream().use { load(it) } }
            fun g(k: String) = p.getProperty("talon.bridge.$k").orEmpty()
            return Draft(g("ship.url"), g("ship.code"), g("host"), g("room"))
        }
    }
}

/** One pactl snapshot, minus the bridge's own streams. */
private class Board(
    val playback: List<Pulse.Stream> = emptyList(),
    val capture: List<Pulse.Stream> = emptyList(),
    val hasDevices: Boolean = false,
    val ownWired: Boolean = false,
) {
    /** Apps whose microphone is the party: the Space apps. */
    val spaceApps: List<String> get() = capture.filter { it.target == Pulse.MIC_MONITOR }.map { it.app }.distinct()

    /** Space apps whose playback also reaches the party. */
    val twoWayApps: List<String> get() = spaceApps.filter { app ->
        playback.any { it.app == app && it.target in setOf(Pulse.SPACE, Pulse.BOTH) }
    }
}

private val soundboardDir = File(System.getProperty("user.home"), ".config/talon/soundboard")

private fun clips(): List<File> =
    soundboardDir.listFiles { f -> f.extension.lowercase() in setOf("wav", "ogg", "flac") }
        ?.sortedBy { it.name }.orEmpty()

@Composable
private fun ManagerScreen(runner: BridgeRunner, configFile: File) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(Draft.load(configFile)) }
    val status by runner.status.collectAsState()
    val lines by runner.lines.collectAsState()
    var pick by remember { mutableStateOf<BridgeRunner.LineInfo?>(null) }
    var board by remember { mutableStateOf(Board()) }
    var pulseError by remember { mutableStateOf<String?>(null) }
    var sounds by remember { mutableStateOf(clips()) }
    var tick by remember { mutableStateOf(0) }
    val playing = remember { mutableStateListOf<Process>() }
    val ownPid = remember { ProcessHandle.current().pid() }
    remember { runCatching { DefaultClips.ensure(soundboardDir) } }

    suspend fun refresh() {
        val live = runner.status.value is BridgeRunner.Status.Live
        withContext(Dispatchers.IO) {
            runCatching {
                val sinks = Pulse.sinks()
                val has = Pulse.hasDevices(sinks)
                val wired = has && live && Pulse.routeOwn(ownPid)
                // Our own streams (the bridge) and helpers (meters, clips) are not "apps".
                val helpers = setOf("parec", "paplay")
                Board(
                    playback = Pulse.playback().filter { it.pid != ownPid && it.app !in helpers },
                    capture = Pulse.capture().filter { it.pid != ownPid && it.app !in helpers },
                    hasDevices = has,
                    ownWired = wired,
                )
            }
        }.onSuccess { board = it; pulseError = null }.onFailure { pulseError = it.message }
        sounds = clips()
        playing.removeAll { !it.isAlive }
        tick++
    }

    fun pulse(action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(action) }.onFailure { pulseError = it.message }
            refresh()
        }
    }

    LaunchedEffect(Unit) {
        if (draft.canConnect) {
            pulse { Pulse.ensureDevices() }
            runner.connect(draft.toConfig(), scope)
        }
        while (true) {
            refresh()
            delay(1_000)
        }
    }
    LaunchedEffect(lines) {
        if (pick == null || lines.none { it.key == pick?.key }) {
            pick = lines.firstOrNull { it.key == draft.savedLine } ?: lines.firstOrNull()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(status, pick, board)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                BridgeCard(
                    draft = draft,
                    status = status,
                    lines = lines,
                    pick = pick,
                    onDraft = { draft = it },
                    onPick = { pick = it },
                    onConnect = {
                        draft.save(configFile)
                        pulse { Pulse.ensureDevices() }
                        runner.connect(draft.toConfig(), scope)
                    },
                    onJoin = {
                        pick?.let { l ->
                            draft = draft.copy(host = l.host, room = l.name)
                            draft.save(configFile)
                            runner.join(l.host, l.name)
                        }
                    },
                    onLeave = { runner.leave() },
                    onDisconnect = { runner.stop() },
                    onMute = { runner.setMuted(it) },
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SpaceCard(
                    board = board,
                    status = status,
                    tick = tick,
                    pulseError = pulseError,
                    onCreateDevices = { pulse { Pulse.ensureDevices() } },
                    onSpaceApp = { s ->
                        pulse {
                            Pulse.moveCapture(s.index, Pulse.MIC_MONITOR)
                            board.playback.filter { it.app == s.app && it.target != Pulse.SPACE }
                                .forEach { Pulse.movePlayback(it.index, Pulse.SPACE) }
                        }
                    },
                    onUnwire = { s ->
                        pulse {
                            Pulse.moveCapture(s.index, Pulse.DEFAULT_SOURCE)
                            board.playback.filter { it.app == s.app }
                                .forEach { Pulse.movePlayback(it.index, Pulse.DEFAULT_SINK) }
                        }
                    },
                )
                AppsCard(
                    board = board,
                    onMovePlayback = { s, sink -> pulse { Pulse.movePlayback(s.index, sink) } },
                )
                SoundboardCard(
                    sounds = sounds,
                    hasDevices = board.hasDevices,
                    playing = playing.size,
                    onPlay = { f, sink -> playing += Pulse.play(f, sink) },
                    onStop = { playing.forEach { it.destroy() }; playing.clear() },
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Header(status: BridgeRunner.Status, pick: BridgeRunner.LineInfo?, board: Board) {
    val cs = MaterialTheme.colorScheme
    val live = status as? BridgeRunner.Status.Live
    Card {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Party line", style = MaterialTheme.typography.labelMedium)
                Text(
                    live?.title?.ifBlank { null } ?: live?.let { "${it.host}/${it.room}" } ?: pick?.label ?: "none picked",
                    style = MaterialTheme.typography.titleLarge,
                )
                (live?.let { "${it.host}/${it.room}" } ?: pick?.key)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            when (status) {
                is BridgeRunner.Status.Live -> Pill("Live · ${status.members.size} on the line", cs.primaryContainer)
                is BridgeRunner.Status.Busy -> Pill(status.what, cs.tertiaryContainer)
                is BridgeRunner.Status.Connected -> Pill("Off the line", cs.surfaceVariant)
                is BridgeRunner.Status.Failed -> Pill("Failed", cs.errorContainer)
                BridgeRunner.Status.Idle -> Pill("Not connected", cs.surfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text("X Space", style = MaterialTheme.typography.labelMedium)
                Text(
                    board.spaceApps.firstOrNull()?.let { "in $it" } ?: "not wired",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    when {
                        board.spaceApps.isEmpty() -> "pick the app your Space runs in below"
                        board.twoWayApps.isNotEmpty() -> "both directions wired"
                        else -> "the Space hears the party, not the other way"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                board.twoWayApps.isNotEmpty() && board.ownWired -> Pill("Two-way", cs.primaryContainer)
                board.twoWayApps.isNotEmpty() -> Pill("Wired, bridge off", cs.tertiaryContainer)
                board.spaceApps.isNotEmpty() -> Pill("One-way", cs.tertiaryContainer)
                else -> Pill("Not wired", cs.surfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinePicker(lines: List<BridgeRunner.LineInfo>, pick: BridgeRunner.LineInfo?, onPick: (BridgeRunner.LineInfo) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = pick?.label ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Party line") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            lines.forEach { l ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(l.label)
                            Text(l.key, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = { onPick(l); open = false },
                )
            }
        }
    }
}

@Composable
private fun BridgeCard(
    draft: Draft,
    status: BridgeRunner.Status,
    lines: List<BridgeRunner.LineInfo>,
    pick: BridgeRunner.LineInfo?,
    onDraft: (Draft) -> Unit,
    onPick: (BridgeRunner.LineInfo) -> Unit,
    onConnect: () -> Unit,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onDisconnect: () -> Unit,
    onMute: (Boolean) -> Unit,
) {
    val idle = status is BridgeRunner.Status.Idle || status is BridgeRunner.Status.Failed
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bridge", style = MaterialTheme.typography.titleMedium)
            Text(
                "The bridge logs in as your ship, joins a party line you can reach, and carries audio between the line and this computer.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                draft.url, { onDraft(draft.copy(url = it)) },
                label = { Text("Ship URL") }, singleLine = true, enabled = idle, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                draft.code, { onDraft(draft.copy(code = it)) },
                label = { Text("+code") }, singleLine = true, enabled = idle, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
            when (status) {
                BridgeRunner.Status.Idle, is BridgeRunner.Status.Failed -> {
                    Button(onClick = onConnect, enabled = draft.canConnect) { Text("Connect") }
                    (status as? BridgeRunner.Status.Failed)?.let {
                        Text(it.why, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is BridgeRunner.Status.Busy -> {
                    Text(status.what, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                is BridgeRunner.Status.Connected -> {
                    Text("Connected as ${status.ship}", style = MaterialTheme.typography.bodyMedium)
                    if (lines.isEmpty()) {
                        Text("No party lines yet. Host one in Talon or get invited to one, and it shows up here.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinePicker(lines, pick, onPick)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onJoin, enabled = pick != null) { Text("Join the line") }
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    }
                    status.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is BridgeRunner.Status.Live -> {
                    Text("On the line as ${status.ship}", style = MaterialTheme.typography.bodyMedium)
                    FilterChip(
                        selected = !status.muted,
                        onClick = { onMute(!status.muted) },
                        label = { Text(if (status.muted) "Muted, tap to send audio" else "Sending audio to the line") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onLeave) { Text("Leave the line") }
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    }
                    Text("${status.members.size} on the line", style = MaterialTheme.typography.labelLarge)
                    status.members.forEach { m ->
                        Text(
                            listOfNotNull(m.ship, "speaking".takeIf { m.speaking }, "muted".takeIf { m.muted }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Meter(meter: LevelMeter?, tick: Int) {
    val level by (meter?.level?.collectAsState() ?: remember { mutableStateOf(0f) })
    val ago = meter?.heardAgoMs ?: -1L
    @Suppress("UNUSED_EXPRESSION") tick
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(progress = { level }, modifier = Modifier.weight(1f).height(10.dp))
        Text(
            when {
                meter == null -> "no meter"
                level > 0.2f -> "audio now"
                ago < 0 -> "silent so far"
                else -> "heard ${ago / 1000}s ago"
            },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun SpaceCard(
    board: Board,
    status: BridgeRunner.Status,
    tick: Int,
    pulseError: String?,
    onCreateDevices: () -> Unit,
    onSpaceApp: (Pulse.Stream) -> Unit,
    onUnwire: (Pulse.Stream) -> Unit,
) {
    var toSpace by remember { mutableStateOf<LevelMeter?>(null) }
    var toParty by remember { mutableStateOf<LevelMeter?>(null) }
    DisposableEffect(board.hasDevices) {
        if (board.hasDevices) {
            toSpace = LevelMeter(Pulse.MIC_MONITOR).start()
            toParty = LevelMeter(Pulse.SPACE_MONITOR).start()
        }
        onDispose {
            toSpace?.close(); toParty?.close()
            toSpace = null; toParty = null
        }
    }
    val live = status is BridgeRunner.Status.Live
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("X Space", style = MaterialTheme.typography.titleMedium)
            pulseError?.let { Text("PulseAudio: $it", color = MaterialTheme.colorScheme.error) }
            if (!board.hasDevices) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("The virtual audio devices are not loaded yet.")
                    Button(onClick = onCreateDevices) { Text("Load devices") }
                }
            }
            Text(
                "Open your Space in a browser, then mark that browser as the Space app. " +
                    "Its microphone becomes the party and its sound goes to the party.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (board.capture.isEmpty()) {
                Text("No app is using a microphone right now, so there is nothing to wire. Start the Space first.")
            }
            board.capture.forEach { s ->
                val wired = s.target == Pulse.MIC_MONITOR
                val playsToParty = board.playback.any { it.app == s.app && it.target in setOf(Pulse.SPACE, Pulse.BOTH) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.app, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                wired && playsToParty -> "Space app: hears the party, and the party hears it"
                                wired -> "hears the party as its microphone, but its sound stays local"
                                else -> "uses your real microphone"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FilterChip(wired, { onSpaceApp(s) }, { Text("Space app") })
                    FilterChip(!wired, { onUnwire(s) }, { Text("Normal") })
                }
            }

            Text("Audio flow", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Party → Space", Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.weight(1f)) { Meter(toSpace, tick) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Space → Party", Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.weight(1f)) { Meter(toParty, tick) }
            }
            Text(
                when {
                    !live -> "The bridge is off the line, so nothing flows yet. Join the line on the left."
                    board.ownWired -> "The bridge is on the line and wired. When someone on the party talks the top bar moves and the Space hears them; when the Space talks the bottom bar moves and the party hears it."
                    else -> "The bridge is on the line; waiting for its audio streams to appear."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun hears(target: String) = when (target) {
    Pulse.SPACE -> "the party hears it"
    Pulse.MIC -> "the Space hears it"
    Pulse.BOTH -> "party and Space hear it"
    else -> "plays here on your speakers"
}

@Composable
private fun AppsCard(
    board: Board,
    onMovePlayback: (Pulse.Stream, String) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Apps playing audio", style = MaterialTheme.typography.titleMedium)
            Text(
                "Send Spotify, a video, or anything else to the party, the Space, or both. Normal puts it back on your speakers.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (board.playback.isEmpty()) Text("Nothing is playing audio.", style = MaterialTheme.typography.bodySmall)
            board.playback.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.app + if (s.corked) " (paused)" else "")
                        Text(hears(s.target), style = MaterialTheme.typography.bodySmall)
                    }
                    FilterChip(s.target == Pulse.SPACE, { onMovePlayback(s, Pulse.SPACE) }, { Text("Party") })
                    FilterChip(s.target == Pulse.MIC, { onMovePlayback(s, Pulse.MIC) }, { Text("Space") })
                    FilterChip(s.target == Pulse.BOTH, { onMovePlayback(s, Pulse.BOTH) }, { Text("Both") })
                    FilterChip(s.target !in setOf(Pulse.SPACE, Pulse.MIC, Pulse.BOTH), { onMovePlayback(s, Pulse.DEFAULT_SINK) }, { Text("Normal") })
                }
            }
        }
    }
}

@Composable
private fun SoundboardCard(
    sounds: List<File>,
    hasDevices: Boolean,
    playing: Int,
    onPlay: (File, String) -> Unit,
    onStop: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Soundboard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onStop, enabled = playing > 0) { Text("Stop all") }
            }
            Text(
                "Clips in ${soundboardDir.path} (wav, ogg, flac). Party plays it to the line, Space to the Space, Both to everyone.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (sounds.isEmpty()) Text("Drop clips into that folder and they show up here.")
            if (!hasDevices) Text("Load the virtual devices first.", color = MaterialTheme.colorScheme.error)
            sounds.forEach { f ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.nameWithoutExtension, modifier = Modifier.weight(1f))
                    Button({ onPlay(f, Pulse.SPACE) }, enabled = hasDevices) { Text("Party") }
                    Button({ onPlay(f, Pulse.MIC) }, enabled = hasDevices) { Text("Space") }
                    Button({ onPlay(f, Pulse.BOTH) }, enabled = hasDevices) { Text("Both") }
                }
            }
        }
    }
}
