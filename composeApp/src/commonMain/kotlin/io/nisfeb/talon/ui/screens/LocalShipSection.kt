package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import io.nisfeb.talon.ui.Button
import androidx.compose.material3.MaterialTheme
import io.nisfeb.talon.ui.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import io.nisfeb.talon.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.comet.LocalShip
import io.nisfeb.talon.comet.LocalShipState
import io.nisfeb.talon.comet.RuntimeUpdate
import kotlinx.coroutines.launch

/**
 * Settings for the comet Talon runs on this computer: what and where
 * it is, start and stop, the login code for other clients, a runtime
 * upgrade, and a dojo. Rendered only when a pier exists (the caller
 * gates on `isLocalCometSupported` and [LocalShip.pierExists]).
 */
@Composable
fun LocalShipSection(localShip: LocalShip) {
    val info = remember(localShip) { localShip.describe() } ?: return
    val state by localShip.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<io.nisfeb.talon.util.Problem?>(null) }
    var codeShown by remember { mutableStateOf(false) }
    var pierBytes by remember { mutableStateOf<Long?>(null) }
    var update by remember { mutableStateOf<RuntimeUpdate?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(localShip, state) { pierBytes = runCatching { localShip.pierBytes() }.getOrNull() }

    val running = state is LocalShipState.Ready
    val status = when (val s = state) {
        is LocalShipState.Ready -> "Running"
        is LocalShipState.Booting -> if (s.firstBoot) "Booting: ${s.detail}" else "Starting: ${s.detail}"
        is LocalShipState.Downloading -> "Downloading the runtime, ${s.bytes / 1_048_576} MB"
        is LocalShipState.Failed -> "Stopped: ${s.why.lineSequence().first()}"
        LocalShipState.Stopped -> "Stopped"
        LocalShipState.Idle -> "Not started"
    }

    Spacer(Modifier.height(8.dp))
    Column {
        Text("Local ship (beta)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "A comet that lives on this computer. Talon runs it while the app is open; " +
                "it is yours only as long as this computer keeps it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // Selectable, and the two things people paste elsewhere get a
        // Copy of their own.
        SelectionContainer {
            Column {
                InfoRow("Ship", info.ship ?: "not yet known")
                InfoRow("Status", status)
                InfoRow("Pier", info.pierPath + (pierBytes?.let { "  (${it / 1_048_576} MB)" } ?: ""))
                InfoRow("Runtime", "vere ${info.runtimeVersion}")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            info.ship?.let { ship ->
                io.nisfeb.talon.ui.CopyButton({ ship }, "Copy ship name")
            }
            if (running) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { localShip.stop() }.onFailure { error = io.nisfeb.talon.util.problemOf("Couldn't stop the ship", it) }
                            busy = false
                        }
                    },
                ) { Text("Stop") }
            } else {
                Button(
                    enabled = !busy && state !is LocalShipState.Booting,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { localShip.start() }.onFailure { error = io.nisfeb.talon.util.problemOf("Couldn't start the ship", it) }
                            busy = false
                        }
                    },
                ) { Text("Start") }
            }
            TextButton(onClick = { codeShown = !codeShown }) {
                Text(if (codeShown) "Hide login code" else "Show login code")
            }
        }
        if (codeShown) {
            val code = localShip.keptCode()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(
                        code?.let { "Login code: $it  (for signing in from another client on this computer)" }
                            ?: "No code kept yet; it is read at the first boot.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                code?.let { c ->
                    io.nisfeb.talon.ui.CopyButton({ c })
                }
            }
        }

        // ── runtime upgrade ──
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val u = update
            when {
                u != null -> {
                    Text(
                        "vere ${u.latest} is available (you have ${u.installed}).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                runCatching { localShip.upgradeRuntime(u.latest) }
                                    .onSuccess { update = null; checked = false }
                                    .onFailure { error = io.nisfeb.talon.util.problemOf("Couldn't upgrade the runtime", it) }
                                busy = false
                            }
                        },
                    ) { Text("Upgrade") }
                }
                else -> {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                runCatching { localShip.checkRuntimeUpdate() }
                                    .onSuccess { update = it; checked = true }
                                    .onFailure { error = io.nisfeb.talon.util.problemOf("Couldn't check for a runtime update", it) }
                                busy = false
                            }
                        },
                    ) { Text("Check for runtime update") }
                    if (checked) {
                        Text(
                            "The runtime is up to date.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            "Upgrading stops the ship, fetches the new runtime, and starts it again; " +
                "the pier migrates itself on the way up.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Spacer(Modifier.height(4.dp))
            io.nisfeb.talon.ui.ProblemLine(it)
        }

        // ── dojo ──
        Spacer(Modifier.height(12.dp))
        Text("Dojo", style = MaterialTheme.typography.titleSmall)
        Text(
            "The ship's terminal. Anything you would type into a dojo goes here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        DojoPanel(localShip, enabled = running)
    }
}

@Composable
private fun DojoPanel(localShip: LocalShip, enabled: Boolean) {
    val terminal by localShip.terminal.collectAsState()
    val busy by localShip.busy.collectAsState()
    // A line sent and not yet taken: dojo prints it back when it takes it.
    var waiting by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(terminal, waiting) {
        waiting?.let { (cmd, seen) -> if (takenCount(terminal, cmd) > seen) waiting = null }
    }
    val shown = remember(terminal) {
        // The last screenful, prompt-noise collapsed: vere redraws the
        // prompt line often and a linear transcript repeats it.
        terminal.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(60)
            .joinToString("\n")
    }
    val scroll = rememberScrollState()
    LaunchedEffect(shown) { scroll.scrollTo(scroll.maxValue) }
    Column(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(scroll)
            .padding(10.dp),
    ) {
        SelectionContainer {
            Text(
                shown.ifEmpty { if (enabled) "Waiting for output…" else "The ship is not running." },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    if (enabled && (busy || waiting != null)) {
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                // Not taken yet, and nothing says the ship is busy: it is
                // on its way, or about to be.
                !busy -> "Sent: ${waiting?.first}"
                waiting != null -> "Your ship is busy and will take \"${waiting?.first}\" when it's done. " + BUSY_WHY
                else -> "Your ship is busy and will answer when it's done. $BUSY_WHY"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(6.dp))
    var line by remember { mutableStateOf("") }
    fun submit() {
        val cmd = line.trim()
        if (cmd.isEmpty()) return
        waiting = cmd to takenCount(terminal, cmd)
        localShip.send(cmd)
        line = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = line,
            onValueChange = { line = it },
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("+code, |install ~zod %app, (add 2 2) …") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Button(enabled = enabled && line.isNotBlank(), onClick = { submit() }) { Text("Send") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.25f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.75f))
    }
}

private const val BUSY_WHY = "A new ship spends its first several minutes installing updates."

/** How often [cmd] has been taken: dojo prints a line back as `> cmd` once it takes it. */
internal fun takenCount(terminal: String, cmd: String): Int {
    val echo = "> $cmd"
    var n = 0
    var at = terminal.indexOf(echo)
    while (at >= 0) {
        n++
        at = terminal.indexOf(echo, at + echo.length)
    }
    return n
}
