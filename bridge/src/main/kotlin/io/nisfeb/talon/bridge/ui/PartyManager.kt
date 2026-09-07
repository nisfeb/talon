package io.nisfeb.talon.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.nisfeb.talon.bridge.BridgeRunner
import io.nisfeb.talon.bridge.Config
import io.nisfeb.talon.bridge.DEFAULT_DEVICE
import io.nisfeb.talon.bridge.Pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/**
 * The party line party manager: one window that runs the bridge, shows
 * which apps are wired to the line and the Space, and fires soundboard
 * clips. Everything audio goes through [Pulse]; the bridge itself uses
 * the system default devices and gets moved onto the virtual ones.
 */
object PartyManager {
    fun launch(configFile: File) = application {
        val runner = remember { BridgeRunner() }
        Window(
            onCloseRequest = { runner.stop(); exitApplication() },
            title = "Talon Party Manager",
            state = rememberWindowState(width = 1180.dp, height = 800.dp),
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { ManagerScreen(runner, configFile) }
            }
        }
    }
}

private data class Draft(val url: String, val code: String, val host: String, val room: String) {
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

private class Board(
    val playback: List<Pulse.Stream> = emptyList(),
    val capture: List<Pulse.Stream> = emptyList(),
    val hasDevices: Boolean = false,
    val ownWired: Boolean = false,
)

private val soundboardDir = File(System.getProperty("user.home"), ".config/talon/soundboard")

private fun clips(): List<File> =
    soundboardDir.listFiles { f -> f.extension.lowercase() in setOf("wav", "ogg", "flac") }
        ?.sortedBy { it.name }.orEmpty()

@Composable
private fun ManagerScreen(runner: BridgeRunner, configFile: File) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(Draft.load(configFile)) }
    val status by runner.status.collectAsState()
    var board by remember { mutableStateOf(Board()) }
    var pulseError by remember { mutableStateOf<String?>(null) }
    var sounds by remember { mutableStateOf(clips()) }
    val playing = remember { mutableStateListOf<Process>() }
    val ownPid = remember { ProcessHandle.current().pid() }

    suspend fun refresh() {
        val live = runner.status.value is BridgeRunner.Status.Live
        withContext(Dispatchers.IO) {
            runCatching {
                val sinks = Pulse.sinks()
                val has = Pulse.hasDevices(sinks)
                val wired = has && live && Pulse.routeOwn(ownPid)
                Board(
                    playback = Pulse.playback().filter { it.pid != ownPid },
                    capture = Pulse.capture().filter { it.pid != ownPid },
                    hasDevices = has,
                    ownWired = wired,
                )
            }
        }.onSuccess { board = it; pulseError = null }.onFailure { pulseError = it.message }
        sounds = clips()
        playing.removeAll { !it.isAlive }
    }

    fun pulse(action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(action) }.onFailure { pulseError = it.message }
            refresh()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(2_000)
        }
    }

    Row(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.width(380.dp).verticalScroll(rememberScrollState())) {
            BridgeCard(
                draft = draft,
                status = status,
                onDraft = { draft = it },
                onSave = { draft.save(configFile) },
                onStart = {
                    pulse { Pulse.ensureDevices() }
                    runner.start(draft.toConfig(), scope)
                },
                onStop = { runner.stop() },
                onMute = { runner.setMuted(it) },
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RoutingCard(
                board = board,
                status = status,
                draft = draft,
                pulseError = pulseError,
                onCreateDevices = { pulse { Pulse.ensureDevices() } },
                onMovePlayback = { s, sink -> pulse { Pulse.movePlayback(s.index, sink) } },
                onMoveCapture = { s, source -> pulse { Pulse.moveCapture(s.index, source) } },
                onSpaceApp = { s ->
                    pulse {
                        Pulse.moveCapture(s.index, Pulse.MIC_MONITOR)
                        board.playback.filter { it.app == s.app && it.target != Pulse.SPACE }
                            .forEach { Pulse.movePlayback(it.index, Pulse.SPACE) }
                    }
                },
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

@Composable
private fun BridgeCard(
    draft: Draft,
    status: BridgeRunner.Status,
    onDraft: (Draft) -> Unit,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMute: (Boolean) -> Unit,
) {
    val idle = status is BridgeRunner.Status.Idle || status is BridgeRunner.Status.Failed
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bridge", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                draft.url, { onDraft(draft.copy(url = it)) },
                label = { Text("Ship URL") }, singleLine = true, enabled = idle, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                draft.code, { onDraft(draft.copy(code = it)) },
                label = { Text("+code") }, singleLine = true, enabled = idle, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                draft.host, { onDraft(draft.copy(host = it)) },
                label = { Text("Line host (~ship)") }, singleLine = true, enabled = idle, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                draft.room, { onDraft(draft.copy(room = it)) },
                label = { Text("Room") }, singleLine = true, enabled = idle, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSave, enabled = idle) { Text("Save") }
                if (idle) {
                    Button(onClick = onStart, enabled = draft.url.isNotBlank() && draft.code.isNotBlank() && draft.host.isNotBlank() && draft.room.isNotBlank()) { Text("Join the line") }
                } else {
                    Button(onClick = onStop) { Text("Leave") }
                }
            }
            when (status) {
                BridgeRunner.Status.Idle -> Text("Not on a line.", style = MaterialTheme.typography.bodyMedium)
                is BridgeRunner.Status.Busy -> Text(status.what, style = MaterialTheme.typography.bodyMedium)
                is BridgeRunner.Status.Failed -> Text(status.why, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                is BridgeRunner.Status.Live -> {
                    Text("On ${status.host}/${status.room} as ${status.ship}", style = MaterialTheme.typography.bodyMedium)
                    FilterChip(
                        selected = !status.muted,
                        onClick = { onMute(!status.muted) },
                        label = { Text(if (status.muted) "Muted, tap to send audio" else "Sending audio to the line") },
                    )
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

private fun hears(target: String) = when (target) {
    Pulse.SPACE -> "the party hears it"
    Pulse.MIC -> "the Space hears it"
    Pulse.BOTH -> "party and Space hear it"
    else -> "normal ($target)"
}

@Composable
private fun RoutingCard(
    board: Board,
    status: BridgeRunner.Status,
    draft: Draft,
    pulseError: String?,
    onCreateDevices: () -> Unit,
    onMovePlayback: (Pulse.Stream, String) -> Unit,
    onMoveCapture: (Pulse.Stream, String) -> Unit,
    onSpaceApp: (Pulse.Stream) -> Unit,
) {
    val line = (status as? BridgeRunner.Status.Live)?.let { "${it.host}/${it.room}" }
        ?: "${draft.host.ifBlank { "?" }}/${draft.room.ifBlank { "?" }}"
    val spaceApps = board.capture.filter { it.target == Pulse.MIC_MONITOR }.map { it.app }.distinct()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Routing", style = MaterialTheme.typography.titleMedium)
            pulseError?.let { Text("PulseAudio: $it", color = MaterialTheme.colorScheme.error) }
            Text(
                "Party line $line  ⇄  " + (spaceApps.joinToString().ifEmpty { "no Space app wired yet" }),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!board.hasDevices) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("The virtual audio devices are not loaded.")
                    Button(onClick = onCreateDevices) { Text("Load devices") }
                }
            }
            Text(
                "Bridge audio: " + when {
                    board.ownWired -> "wired (plays to the Space, listens to the Space)"
                    status is BridgeRunner.Status.Live -> "waiting for the bridge's streams"
                    else -> "wires itself once the bridge is on the line"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Playing apps", style = MaterialTheme.typography.titleSmall)
            if (board.playback.isEmpty()) Text("Nothing is playing audio.", style = MaterialTheme.typography.bodySmall)
            board.playback.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.app)
                        Text(hears(s.target), style = MaterialTheme.typography.bodySmall)
                    }
                    FilterChip(s.target == Pulse.SPACE, { onMovePlayback(s, Pulse.SPACE) }, { Text("Party") })
                    FilterChip(s.target == Pulse.MIC, { onMovePlayback(s, Pulse.MIC) }, { Text("Space") })
                    FilterChip(s.target == Pulse.BOTH, { onMovePlayback(s, Pulse.BOTH) }, { Text("Both") })
                    FilterChip(s.target !in setOf(Pulse.SPACE, Pulse.MIC, Pulse.BOTH), { onMovePlayback(s, Pulse.DEFAULT_SINK) }, { Text("Normal") })
                }
            }

            Text("Recording apps", style = MaterialTheme.typography.titleSmall)
            if (board.capture.isEmpty()) Text("Nothing is capturing audio.", style = MaterialTheme.typography.bodySmall)
            board.capture.forEach { s ->
                val party = s.target == Pulse.MIC_MONITOR
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.app)
                        Text(if (party) "hears the party as its microphone" else "normal (${s.target})", style = MaterialTheme.typography.bodySmall)
                    }
                    FilterChip(party, { onSpaceApp(s) }, { Text("Space app") })
                    FilterChip(!party, { onMoveCapture(s, Pulse.DEFAULT_SOURCE) }, { Text("Normal") })
                }
            }
            Text(
                "Space app = its microphone hears the party and its playback goes to the party. " +
                    "Party = the line hears it. Space = the Space hears it. Both = everyone.",
                style = MaterialTheme.typography.bodySmall,
            )
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
            Text("${soundboardDir.path}  (wav, ogg, flac)", style = MaterialTheme.typography.bodySmall)
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
