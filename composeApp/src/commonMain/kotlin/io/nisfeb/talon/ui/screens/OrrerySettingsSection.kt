package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import io.nisfeb.talon.orrery.DecideControl
import io.nisfeb.talon.orrery.LocalModels
import io.nisfeb.talon.orrery.OrreryAvailability
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.orrery.RungStatus
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.ui.isLocalTriageSupported
import io.nisfeb.talon.ui.isTouchPrimary
import kotlinx.coroutines.launch

/**
 * Orrery under Settings > AI: the pipe's switch, and on a phone the
 * choice to leave the reading to a computer. Which model does the
 * reading is set above, under Private model, because that is a
 * question about models rather than about this one app.
 */
@Composable
fun OrrerySettingsSection(orrery: OrreryRepo) {
    val scope = rememberCoroutineScope()
    val availability by orrery.availability.collectAsState()
    val on by orrery.enabled.collectAsState()
    val lastMs by orrery.lastPushMs.collectAsState()
    val pushing by orrery.pushing.collectAsState()
    val error by orrery.error.collectAsState()
    val model by orrery.model.collectAsState()
    val download by orrery.download.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(16.dp))
    Text("Orrery", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
    Text(
        "Orrery is the model of your world on your ship. What Talon feeds it, and which model reads your messages for it, are set here. That model is separate from the AI provider above: the provider writes summaries and carries out actions; the reader here runs on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    if (availability != OrreryAvailability.PRESENT) {
        Text(
            when (availability) {
                OrreryAvailability.MISSING -> "Orrery is not on this ship. Install it from the Grubbery shell on your ship; the Apps page shows its state."
                OrreryAvailability.SIGNED_OUT -> "Signed out of the ship."
                else -> "Not asked yet whether this ship has Orrery."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Feed Orrery", style = MaterialTheme.typography.bodyLarge)
            Text(
                "The people in your contacts book, who wrote to you and on which day, and your calendar go to Orrery as facts, under a key made for this install. Nothing anyone said leaves this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Switch(
            checked = on,
            onCheckedChange = { want ->
                note = null
                scope.launch {
                    busy = true
                    (if (want) orrery.enable() else orrery.disable()).onFailure { note = it.message ?: "Orrery did not answer." }
                    busy = false
                }
            },
        )
    }
    if (on) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (lastMs != null) "Pushed ${agoLabel(lastMs!!)}." else "Not pushed yet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !pushing, onClick = { scope.launch { orrery.push() } }) { Text(if (pushing) "Pushing" else "Push now") }
        }
    }
    (note ?: error)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }

    if (isTouchPrimary) orrery.standDown?.let { sd ->
        val standing by sd.on.collectAsState()
        val yielding by orrery.yielding.collectAsState()
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Leave reading to your computer", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (standing && yielding) "A computer running Talon has been on the job in the last two hours, so this phone is leaving the reading to its bigger model. Facts still go up from here."
                    else "When a computer running Talon has been on the job in the last two hours, this phone leaves the reading to its bigger model. Facts still go up from here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = standing, onCheckedChange = { sd.set(it) })
        }
    }

    if (on) orrery.decide?.let { DecideRows(orrery, it) }

    // The ladder's answer is asked for once the section is on screen.
    LaunchedEffect(orrery) { orrery.refreshModel() }
}

/**
 * The decision model: a switch, then the gate and its threshold, which
 * the owner picks after running the check over messages already read.
 */
@Composable
private fun DecideRows(orrery: OrreryRepo, dc: DecideControl) {
    val d by dc.settings.collectAsState()
    val hasKey = remember(d) { orrery.decideHasKey() }
    // The check belongs to the repo: leaving Settings does not stop it,
    // and coming back shows how far it has got.
    val run by orrery.gateCheck.collectAsState()
    val checking = run != null && run?.result == null
    var threshold by remember(d.threshold) { mutableStateOf(d.threshold.toString()) }
    var keep by remember(d.keep) { mutableStateOf(d.keep.toString()) }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Decision model", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Each message the reader reads also goes to TypeSafe's Jev through OpenRouter, with zero data retention, on your OpenRouter key. It drops a status that is a feeling rather than a circumstance, and it can keep the reader away from messages that say nothing. Answers are free; reading costs about four cents a million tokens.",
                style = MaterialTheme.typography.labelSmall,
                color = quiet,
            )
            if (!hasKey) Text("Needs OpenRouter as the AI provider, with its key.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Switch(checked = d.on, enabled = hasKey || d.on, onCheckedChange = { dc.set(d.copy(on = it)) })
    }
    if (!d.on) return
    // Today on this install: what the gate read and skipped, what the
    // statuses and the picks came to, and what each of them cost.
    val today by orrery.decideToday.collectAsState()
    LaunchedEffect(orrery) { orrery.loadDecideToday() }
    today?.let { (day, tally) ->
        Column(Modifier.padding(top = 4.dp)) {
            if (tally == io.nisfeb.talon.orrery.DecideDay()) {
                Text("Nothing read with it yet today.", style = MaterialTheme.typography.labelSmall, color = quiet)
            } else {
                tally.lines(day).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = quiet) }
            }
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Gate the reader", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (d.gate) "Below ${d.threshold} the reader is not asked. A gate that cannot answer lets the message through."
                else "Run the check first, then pick a threshold from 0.2 to 0.4.",
                style = MaterialTheme.typography.labelSmall,
                color = quiet,
            )
        }
        Switch(checked = d.gate, onCheckedChange = { dc.set(d.copy(gate = it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = threshold,
            onValueChange = { t ->
                threshold = t
                t.toDoubleOrNull()?.takeIf { it in 0.05..0.95 }?.let { dc.set(d.copy(threshold = it)) }
            },
            label = { Text("Threshold") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (checking) TextButton(onClick = { orrery.stopGateCheck() }) { Text("Stop") }
        else TextButton(onClick = { orrery.startGateCheck() }) { Text("Check the gate") }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Choose what the reader sees", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (d.relevance) "For each message it reads, Jev picks the bodies it is about, and the reader sees those above ${d.keep}, with the sender and you. A pick that fails shows the reader the usual list."
                else "Jev picks the bodies each message is about, so the reader sees a few that matter instead of the first sixty. Run Check body picks first, then choose where to cut.",
                style = MaterialTheme.typography.labelSmall,
                color = quiet,
            )
        }
        Switch(checked = d.relevance, onCheckedChange = { dc.set(d.copy(relevance = it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = keep,
            onValueChange = { t ->
                keep = t
                t.toDoubleOrNull()?.takeIf { it in 0.05..0.95 }?.let { dc.set(d.copy(keep = it)) }
            },
            label = { Text("Keep bodies at or above") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (!checking) TextButton(onClick = { orrery.startGateCheck(limit = 50, picks = true) }) { Text("Check body picks") }
    }
    // The route is alpha and may move: it and the model are settings, not code.
    OutlinedTextField(value = d.url, onValueChange = { dc.set(d.copy(url = it.trim())) }, label = { Text("Decisions route") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = d.model, onValueChange = { dc.set(d.copy(model = it.trim())) }, label = { Text("Decision model") }, singleLine = true, modifier = Modifier.fillMaxWidth())

    run?.takeIf { it.result == null }?.let { r ->
        Text(
            if (r.total == 0) "Choosing the messages to check." else "Checked ${r.done} of ${r.total}.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (r.total > 0) LinearProgressIndicator(progress = { r.done.toFloat() / r.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
    run?.result?.fold(
        onSuccess = { c ->
            Text(
                "${c.total} messages already read. " +
                    c.readAt.joinToString("; ") { (t, n) -> "at $t, $n read and ${c.total - n} skipped" } +
                    ". The check cost ${"$"}${(kotlin.math.round(c.costUsd * 10_000) / 10_000)}" +
                    (if (c.failed > 0) "; ${c.failed} could not be asked and count as read." else ".") +
                    (if (c.keptAt.isEmpty()) "" else " Bodies the reader would see: " + c.keptAt.joinToString("; ") { (t, n) -> "at $t, ${(kotlin.math.round(n * 10) / 10)} on average" } + "."),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                c.lines.forEach { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = quiet) }
            }
        },
        onFailure = { Text(it.message ?: "The check failed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) },
    )
}
