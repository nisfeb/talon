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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.nisfeb.talon.bridge.BridgeRunner
import io.nisfeb.talon.bridge.Config
import io.nisfeb.talon.bridge.DEFAULT_DEVICE
import io.nisfeb.talon.bridge.DefaultClips
import io.nisfeb.talon.bridge.Ducker
import io.nisfeb.talon.bridge.HostMic
import io.nisfeb.talon.bridge.LevelMeter
import io.nisfeb.talon.bridge.NowPlaying
import io.nisfeb.talon.bridge.Preset
import io.nisfeb.talon.bridge.PresetFile
import io.nisfeb.talon.bridge.Presets
import io.nisfeb.talon.bridge.AppRoute
import io.nisfeb.talon.bridge.Pulse
import io.nisfeb.talon.bridge.ShowRecorder
import io.nisfeb.talon.bridge.notify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/**
 * The party line party manager: one window that runs the bridge, shows
 * the Space link with live meters in both directions, routes any app's
 * audio to the party, the Space or both, ducks music under speech,
 * keeps routing presets, records the show and fires soundboard clips.
 * Everything audio goes through [Pulse]; the bridge itself uses the
 * system default devices and gets moved onto the virtual ones.
 */
object PartyManager {
    fun launch(configFile: File) = application {
        val runner = remember { BridgeRunner() }
        val hostMic = remember { HostMic() }
        Window(
            onCloseRequest = { runner.stop(); hostMic.disarm(); exitApplication() },
            title = "Talon Party Manager",
            icon = androidx.compose.ui.res.painterResource("party-manager.png"),
            state = rememberWindowState(width = 1240.dp, height = 900.dp),
            onKeyEvent = { e ->
                // Push to talk on the host mic: hold F8 while this window is focused.
                if (e.key == Key.F8 && hostMic.module != null) {
                    val down = e.type == KeyEventType.KeyDown
                    if (hostMic.talking != down) {
                        hostMic.talking = down
                        Thread { runCatching { hostMic.sync(Pulse.playback()) } }.start()
                    }
                    true
                } else false
            },
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { ManagerScreen(runner, hostMic, configFile) }
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

/** One pactl snapshot, minus the bridge's own streams and helpers. */
private class Board(
    val playback: List<Pulse.Stream> = emptyList(),
    val capture: List<Pulse.Stream> = emptyList(),
    val hasDevices: Boolean = false,
    val ownWired: Boolean = false,
    /** The bridge's playout: what the Space hears. */
    val ownPlayback: Pulse.Stream? = null,
    /** The bridge's capture: what the party hears from the Space. */
    val ownCapture: Pulse.Stream? = null,
    /** Real microphones on this machine. */
    val mics: List<Pulse.Device> = emptyList(),
) {
    /** Capture streams whose microphone is the party: the Space apps. */
    val spaceStreams: List<Pulse.Stream> get() = capture.filter { it.target == Pulse.MIC_MONITOR }
    val spaceApps: List<String> get() = spaceStreams.map { it.appName }.distinct()

    /** Space apps whose playback also reaches the party. */
    val twoWayApps: List<String> get() = spaceStreams.filter { s -> playsToParty(s) }.map { it.appName }.distinct()

    fun playsToParty(s: Pulse.Stream): Boolean =
        playback.any { it.sameApp(s) && it.target in Pulse.partyTargets }

    fun isSpaceApp(s: Pulse.Stream) = spaceStreams.any { it.sameApp(s) }

    /** Music: anything on a virtual device that is not a Space app. */
    val music: List<Pulse.Stream> get() = playback.filter { it.target in Pulse.virtualTargets && !isSpaceApp(it) }
}

private val home = File(System.getProperty("user.home"))
private val soundboardDir = File(home, ".config/talon/soundboard")
private val recordingsDir = File(home, ".config/talon/recordings")

private fun clips(): List<File> =
    soundboardDir.listFiles { f -> f.extension.lowercase() in setOf("wav", "ogg", "flac") }
        ?.sortedBy { it.name }.orEmpty()

private class Meters(val toSpace: LevelMeter, val spaceVoice: LevelMeter) {
    fun close() { toSpace.close(); spaceVoice.close() }
}

@Composable
private fun ManagerScreen(runner: BridgeRunner, hostMic: HostMic, configFile: File) {
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
    val ducker = remember { Ducker() }
    val recorder = remember { ShowRecorder(recordingsDir) }
    var presets by remember { mutableStateOf(Presets.load()) }
    var track by remember { mutableStateOf<NowPlaying.Track?>(null) }
    var alerts by remember { mutableStateOf<List<String>>(emptyList()) }
    var meters by remember { mutableStateOf<Meters?>(null) }
    val seen = remember { mutableSetOf<Int>() }
    remember { runCatching { DefaultClips.ensure(soundboardDir) } }

    DisposableEffect(board.hasDevices) {
        if (board.hasDevices) meters = Meters(LevelMeter(Pulse.MIC_MONITOR).start(), LevelMeter(Pulse.SPACE_IN_MONITOR).start())
        onDispose { meters?.close(); meters = null }
    }

    suspend fun refresh() {
        val live = runner.status.value is BridgeRunner.Status.Live
        val active = presets.presets.firstOrNull { it.name == presets.active }
        withContext(Dispatchers.IO) {
            runCatching {
                val sinks = Pulse.sinks()
                val has = Pulse.hasDevices(sinks)
                val wired = has && live && Pulse.routeOwn(ownPid)
                var playback = Pulse.playback()
                var capture = Pulse.capture()
                hostMic.sync(playback)
                // Our own streams (the bridge), helpers (meters, clips, loopbacks)
                // and the combine sink's internal feeds are not "apps".
                val helpers = setOf("parec", "paplay", "pacat", Pulse.LOOP_APP, Pulse.HOST_MIC_APP)
                fun Pulse.Stream.isApp() =
                    pid != ownPid && app !in helpers && !app.startsWith("Simultaneous output")
                // A stream we have not seen before (a restarted browser, a new
                // player) gets the active preset's routing for its app.
                val fresh = (playback + capture).filter { it.isApp() && it.index !in seen }
                if (active != null && fresh.isNotEmpty()) {
                    Presets.apply(active, playback.filter { it in fresh }, capture.filter { it in fresh })
                    playback = Pulse.playback()
                    capture = Pulse.capture()
                }
                seen += (playback + capture).map { it.index }
                Board(
                    playback = playback.filter { it.isApp() },
                    capture = capture.filter { it.isApp() },
                    hasDevices = has,
                    ownWired = wired,
                    ownPlayback = playback.firstOrNull { it.pid == ownPid },
                    ownCapture = capture.firstOrNull { it.pid == ownPid },
                    mics = Pulse.sources().filter { !it.name.endsWith(".monitor") },
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

    fun applyPreset(p: Preset) {
        pulse {
            Presets.apply(p, board.playback, board.capture)
            board.ownPlayback?.let { Pulse.setPlaybackVolume(it.index, p.toSpaceVolume) }
            board.ownCapture?.let { Pulse.setCaptureVolume(it.index, p.toPartyVolume) }
        }
        ducker.enabled = p.duck
        ducker.percent = p.duckPercent
        presets = presets.copy(active = p.name).also { Presets.save(it) }
    }

    fun savePreset(name: String) {
        val p = Preset(
            name = name,
            apps = board.playback.filter { it.target in Pulse.virtualTargets }
                .associate { it.appName to AppRoute(it.target, ducker.originalVolume(it)) },
            spaceApps = board.spaceApps,
            toSpaceVolume = board.ownPlayback?.volume ?: 100,
            toPartyVolume = board.ownCapture?.volume ?: 100,
            duck = ducker.enabled,
            duckPercent = ducker.percent,
        )
        presets = PresetFile(active = name, presets = presets.presets.filter { it.name != name } + p).also { Presets.save(it) }
    }

    LaunchedEffect(Unit) {
        if (draft.canConnect) {
            pulse { Pulse.ensureDevices() }
            runner.connect(draft.toConfig(), scope)
        }
        var n = 0
        while (true) {
            refresh()
            if (n++ % 3 == 0) track = withContext(Dispatchers.IO) { NowPlaying.read() }
            delay(1_000)
        }
    }
    // Ducking runs faster than the board refresh so music dips as soon as someone speaks.
    LaunchedEffect(Unit) {
        while (true) {
            val live = status as? BridgeRunner.Status.Live
            val partyTalking = live?.members?.any { it.ship != live.ship && it.speaking } == true
            val spaceTalking = (meters?.spaceVoice?.level?.value ?: 0f) > 0.2f
            val music = board.music
            withContext(Dispatchers.IO) { ducker.tick(partyTalking || spaceTalking, music) }
            delay(250)
        }
    }
    LaunchedEffect(lines) {
        if (pick == null || lines.none { it.key == pick?.key }) {
            pick = lines.firstOrNull { it.key == draft.savedLine } ?: lines.firstOrNull()
        }
    }
    // Watchdog: things that silently break a show, shown in the header and sent as a desktop notification.
    LaunchedEffect(tick) {
        val live = status is BridgeRunner.Status.Live
        val now = listOfNotNull(
            "No Space app is wired, so the Space is not on the line.".takeIf { live && board.spaceStreams.isEmpty() },
            "The Space app's audio is paused.".takeIf { live && board.spaceStreams.any { s -> board.playback.any { it.sameApp(s) && it.corked } } },
            "The bridge's audio is not on the virtual devices yet.".takeIf { live && !board.ownWired },
            "Nothing has been heard in either direction for five minutes.".takeIf {
                live && meters?.let { m -> m.toSpace.heardAgoMs > 300_000 && m.spaceVoice.heardAgoMs > 300_000 } == true
            },
        )
        (now - alerts.toSet()).forEach { notify("Party Manager", it) }
        alerts = now
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(status, pick, board, track, alerts)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.width(380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BridgeCard(
                    draft = draft,
                    status = status,
                    lines = lines,
                    pick = pick,
                    runner = runner,
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
                RecorderCard(recorder, tick, board.hasDevices)
                HostMicCard(hostMic, board, tick, onPulse = ::pulse)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SpaceCard(
                    board = board,
                    status = status,
                    meters = meters,
                    ducker = ducker,
                    tick = tick,
                    pulseError = pulseError,
                    onCreateDevices = { pulse { Pulse.ensureDevices() } },
                    onSpaceApp = { s ->
                        pulse {
                            Pulse.moveCapture(s.index, Pulse.MIC_MONITOR)
                            board.playback.filter { it.sameApp(s) && it.target != Pulse.SPACE_IN }
                                .forEach { Pulse.movePlayback(it.index, Pulse.SPACE_IN) }
                        }
                    },
                    onUnwire = { s ->
                        pulse {
                            Pulse.moveCapture(s.index, Pulse.DEFAULT_SOURCE)
                            board.playback.filter { it.sameApp(s) }
                                .forEach { Pulse.movePlayback(it.index, Pulse.DEFAULT_SINK) }
                        }
                    },
                    onToSpaceVolume = { v -> board.ownPlayback?.let { s -> pulse { Pulse.setPlaybackVolume(s.index, v) } } },
                    onToPartyVolume = { v -> board.ownCapture?.let { s -> pulse { Pulse.setCaptureVolume(s.index, v) } } },
                )
                PresetsCard(presets, onApply = ::applyPreset, onSave = ::savePreset, onDelete = { name ->
                    presets = PresetFile(
                        active = presets.active.takeIf { it != name },
                        presets = presets.presets.filter { it.name != name },
                    ).also { Presets.save(it) }
                })
                AppsCard(
                    board = board,
                    ducker = ducker,
                    onMovePlayback = { s, sink -> pulse { Pulse.movePlayback(s.index, sink) } },
                    onVolume = { s, v -> pulse { Pulse.setPlaybackVolume(s.index, v) } },
                )
                SoundboardCard(
                    sounds = sounds,
                    hasDevices = board.hasDevices,
                    playing = playing.size,
                    onPlay = { f, sink, v -> playing += Pulse.play(f, sink, v) },
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
private fun Header(status: BridgeRunner.Status, pick: BridgeRunner.LineInfo?, board: Board, track: NowPlaying.Track?, alerts: List<String>) {
    val cs = MaterialTheme.colorScheme
    val live = status as? BridgeRunner.Status.Live
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Text(
                track?.let { "Now ${it.status.lowercase()} in ${it.player}: ${it.text}" } ?: "Nothing is playing in a media player.",
                style = MaterialTheme.typography.bodyMedium,
            )
            alerts.forEach { a ->
                Surface(shape = RoundedCornerShape(8.dp), color = cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(a, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, options: List<String>, value: String, onPick: (Int) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onPick(i); open = false })
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
    runner: BridgeRunner,
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
                        Picker("Party line", lines.map { "${it.label}  (${it.key})" }, pick?.label ?: "", onPick = { onPick(lines[it]) })
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull(m.ship, "speaking".takeIf { m.speaking }, "muted".takeIf { m.muted && !m.mutedByAdmin }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (m.ship != status.ship) {
                                FilterChip(
                                    selected = m.mutedByAdmin,
                                    onClick = { runner.setMemberMuted(m.ship, !m.mutedByAdmin) },
                                    label = { Text(if (m.mutedByAdmin) "Muted by host" else "Mute") },
                                )
                            }
                        }
                    }
                    val linkFlow = remember(status.host, status.room) { runner.listenLink ?: MutableStateFlow(null) }
                    val link by linkFlow.collectAsState()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { runner.requestListenLink() }) { Text("Listen link") }
                        Text(
                            link?.url ?: "an anonymous listen link, if the line allows listeners",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 0–150 %: Pulse boosts above 100. Applies when the drag ends. */
@Composable
private fun VolumeSlider(percent: Int, enabled: Boolean = true, max: Float = 150f, onSet: (Int) -> Unit) {
    var v by remember(percent) { mutableStateOf(percent.toFloat()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onSet(v.toInt()) },
            valueRange = 0f..max,
            enabled = enabled,
            modifier = Modifier.width(180.dp),
        )
        Text("${v.toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
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
    meters: Meters?,
    ducker: Ducker,
    tick: Int,
    pulseError: String?,
    onCreateDevices: () -> Unit,
    onSpaceApp: (Pulse.Stream) -> Unit,
    onUnwire: (Pulse.Stream) -> Unit,
    onToSpaceVolume: (Int) -> Unit,
    onToPartyVolume: (Int) -> Unit,
) {
    val live = status is BridgeRunner.Status.Live
    var duckOn by remember { mutableStateOf(ducker.enabled) }
    @Suppress("UNUSED_EXPRESSION") tick
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
                val playsToParty = board.playsToParty(s)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.appName, style = MaterialTheme.typography.bodyLarge)
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
                Column(Modifier.weight(1f)) { Meter(meters?.toSpace, tick) }
                VolumeSlider(board.ownPlayback?.volume ?: 100, enabled = board.ownPlayback != null, onSet = onToSpaceVolume)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Space → Party", Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.weight(1f)) { Meter(meters?.spaceVoice, tick) }
                VolumeSlider(board.ownCapture?.volume ?: 100, enabled = board.ownCapture != null, onSet = onToPartyVolume)
            }
            Text(
                when {
                    !live -> "The bridge is off the line, so nothing flows yet. Join the line on the left."
                    board.ownWired -> "The top bar is everything the Space hears, the bottom bar is the Space's own voice. The sliders set how loud each direction is."
                    else -> "The bridge is on the line; waiting for its audio streams to appear."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Ducking", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = duckOn,
                    onClick = { duckOn = !duckOn; ducker.enabled = duckOn; if (!duckOn) Thread { ducker.release() }.start() },
                    label = { Text(if (duckOn) "Music ducks when people talk" else "Duck music when people talk") },
                )
                Text("to", style = MaterialTheme.typography.bodySmall)
                VolumeSlider(ducker.percent, max = 100f) { ducker.percent = it }
                Text(
                    when {
                        !duckOn -> ""
                        ducker.ducking -> "ducking now"
                        board.music.isEmpty() -> "no music on the party or the Space"
                        else -> "listening"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "Voice is anyone on the party speaking or the Space's own voice above the meter's threshold; music is every other app on the party or the Space.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PresetsCard(presets: PresetFile, onApply: (Preset) -> Unit, onSave: (String) -> Unit, onDelete: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Preset?>(null) }
    val current = chosen?.let { c -> presets.presets.firstOrNull { it.name == c.name } } ?: presets.presets.firstOrNull { it.name == presets.active }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Presets", style = MaterialTheme.typography.titleMedium)
            Text(
                "A preset is the whole routing: which app is the Space, where each app plays and how loud, the direction levels and ducking. " +
                    "The active preset is re-applied to an app that comes back with new streams, like a restarted browser.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (presets.presets.isEmpty()) {
                        Text("No presets saved yet.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Picker("Preset", presets.presets.map { it.name }, current?.name ?: "", onPick = { chosen = presets.presets[it] })
                    }
                }
                Button(onClick = { current?.let(onApply) }, enabled = current != null) { Text("Apply") }
                OutlinedButton(onClick = { current?.let { onDelete(it.name) }; chosen = null }, enabled = current != null) { Text("Delete") }
            }
            Text(presets.active?.let { "Active: $it" } ?: "No preset is active.", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, label = { Text("Save the current routing as") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { onSave(name.trim()); name = "" }, enabled = name.isNotBlank()) { Text("Save") }
            }
        }
    }
}

private fun hears(target: String) = when (target) {
    Pulse.SPACE -> "the party hears it"
    Pulse.SPACE_IN -> "the party hears it, as the Space's voice"
    Pulse.MIC -> "the Space hears it"
    Pulse.BOTH -> "party and Space hear it"
    else -> "plays here on your speakers"
}

@Composable
private fun AppsCard(
    board: Board,
    ducker: Ducker,
    onMovePlayback: (Pulse.Stream, String) -> Unit,
    onVolume: (Pulse.Stream, Int) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Apps playing audio", style = MaterialTheme.typography.titleMedium)
            Text(
                "Send Spotify, a video, or anything else to the party, the Space, or both. Normal puts it back on your speakers. The slider is that app's volume.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (board.playback.isEmpty()) Text("Nothing is playing audio.", style = MaterialTheme.typography.bodySmall)
            board.playback.forEach { s ->
                val partyTarget = if (board.isSpaceApp(s)) Pulse.SPACE_IN else Pulse.SPACE
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.appName + if (s.corked) " (paused)" else "")
                        Text(hears(s.target) + if (ducker.ducking && s in board.music) ", ducked" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    VolumeSlider(ducker.originalVolume(s)) { onVolume(s, it) }
                    FilterChip(s.target in setOf(Pulse.SPACE, Pulse.SPACE_IN), { onMovePlayback(s, partyTarget) }, { Text("Party") })
                    FilterChip(s.target == Pulse.MIC, { onMovePlayback(s, Pulse.MIC) }, { Text("Space") })
                    FilterChip(s.target == Pulse.BOTH, { onMovePlayback(s, Pulse.BOTH) }, { Text("Both") })
                    FilterChip(s.target !in Pulse.virtualTargets, { onMovePlayback(s, Pulse.DEFAULT_SINK) }, { Text("Normal") })
                }
            }
        }
    }
}

@Composable
private fun RecorderCard(recorder: ShowRecorder, tick: Int, hasDevices: Boolean) {
    @Suppress("UNUSED_EXPRESSION") tick
    val on = recorder.recording
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Record the show", style = MaterialTheme.typography.titleMedium)
            Text(
                "A stereo WAV in ${recordingsDir.path}: left is what the Space hears, right is what the party hears from here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (on) {
                    Button(onClick = { recorder.stop() }) { Text("Stop") }
                    val s = recorder.elapsedMs / 1000
                    Text("● %d:%02d".format(s / 60, s % 60), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                } else {
                    Button(onClick = { recorder.start() }, enabled = hasDevices) { Text("Record") }
                }
            }
            recorder.file?.let { Text(it.name, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun HostMicCard(hostMic: HostMic, board: Board, tick: Int, onPulse: (() -> Unit) -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    var mic by remember { mutableStateOf<Pulse.Device?>(null) }
    var target by remember { mutableStateOf(Pulse.MIC) }
    if (mic == null || board.mics.none { it.name == mic?.name }) mic = board.mics.firstOrNull()
    val armed = hostMic.module != null
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Host mic", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your real microphone straight into the Space, the party, or both, without going through Talon. Hold F8 in this window to talk, or switch Talk on.",
                style = MaterialTheme.typography.bodySmall,
            )
            Picker("Microphone", board.mics.map { it.description.ifBlank { it.name } }, mic?.description ?: "", onPick = { mic = board.mics[it] }, enabled = !armed)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(target == Pulse.MIC, { target = Pulse.MIC }, { Text("Space") }, enabled = !armed)
                FilterChip(target == Pulse.SPACE, { target = Pulse.SPACE }, { Text("Party") }, enabled = !armed)
                FilterChip(target == Pulse.BOTH, { target = Pulse.BOTH }, { Text("Both") }, enabled = !armed)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (armed) {
                    OutlinedButton(onClick = { onPulse { hostMic.disarm() } }) { Text("Disarm") }
                    FilterChip(
                        selected = hostMic.talking,
                        onClick = { hostMic.talking = !hostMic.talking; onPulse { hostMic.sync(Pulse.playback()) } },
                        label = { Text(if (hostMic.talking) "Talking" else "Talk") },
                    )
                } else {
                    Button(onClick = { mic?.let { m -> onPulse { hostMic.arm(m.name, target) } } }, enabled = mic != null && board.hasDevices) { Text("Arm") }
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
    onPlay: (File, String, Int) -> Unit,
    onStop: () -> Unit,
) {
    var level by remember { mutableStateOf(100) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Soundboard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("Clip level", style = MaterialTheme.typography.labelMedium)
                VolumeSlider(level) { level = it }
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
                    Button({ onPlay(f, Pulse.SPACE, level) }, enabled = hasDevices) { Text("Party") }
                    Button({ onPlay(f, Pulse.MIC, level) }, enabled = hasDevices) { Text("Space") }
                    Button({ onPlay(f, Pulse.BOTH, level) }, enabled = hasDevices) { Text("Both") }
                }
            }
        }
    }
}
