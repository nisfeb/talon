package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.orrery.InShare
import io.nisfeb.talon.orrery.LocalLiveLocation
import io.nisfeb.talon.orrery.OrreryError
import io.nisfeb.talon.orrery.OutShare
import io.nisfeb.talon.orrery.ShareLength
import io.nisfeb.talon.util.formatClock
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A 1:1 chat's live location, under its header: where [ship] is, while
 * they share it with us, and our own share with them, with Stop. Nothing
 * when neither runs, or where the ship has no orrery.
 */
@Composable
fun LiveLocationStrip(ship: String, who: String) {
    val live = LocalLiveLocation.current ?: return
    val shares by live.shares.collectAsState()
    WatchShares(ship)
    val inbound = shares?.from(ship)
    val out = shares?.outTo(ship)
    if (inbound == null && out == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        inbound?.let { InboundRow(it, who) }
        out?.let { OutboundRow(it) }
    }
}

/** The profile sheet's: the same two rows, and Share my location where this device can send one. */
@Composable
fun LiveLocationProfileRows(ship: String, who: String) {
    val live = LocalLiveLocation.current ?: return
    val shares by live.shares.collectAsState()
    WatchShares(ship)
    var asking by remember(ship) { mutableStateOf(false) }
    shares?.from(ship)?.let { InboundRow(it, who) }
    val out = shares?.outTo(ship)
    if (out != null) OutboundRow(out)
    else if (live.canShare) TextButton(onClick = { asking = true }) { Text("Share my location") }
    if (asking) ShareLocationDialog(ship, who, onDone = { asking = false })
}

/** Settings' list of the shares this ship runs, each with Stop. */
@Composable
fun SharingWithList() {
    val live = LocalLiveLocation.current ?: return
    val shares by live.shares.collectAsState()
    LaunchedEffect(Unit) { live.refresh() }
    val out = shares?.out.orEmpty()
    if (out.isEmpty()) return
    Text("Sharing where you are with", style = MaterialTheme.typography.labelLarge)
    out.forEach { s -> OutboundRow(s, label = shipHandle(s.ship)) }
}

/** Read on open, then each minute while a share with [ship] runs either way. Real time: a test's virtual clock would spin it. */
@Composable
private fun WatchShares(ship: String) {
    val live = LocalLiveLocation.current ?: return
    LaunchedEffect(ship) {
        live.refresh()
        withContext(Dispatchers.Default) {
            while (true) {
                delay(LIVE_REREAD_MS)
                val s = live.shares.value
                if (s?.from(ship) != null || s?.outTo(ship) != null) live.refresh()
            }
        }
    }
}

@Composable
private fun InboundRow(s: InShare, who: String) {
    val maps = LocalMapsLauncher.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$who is sharing where they are", style = MaterialTheme.typography.bodyMedium)
            Text(inboundDetail(s, nowMs()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = {
            runCatching { if (maps != null) maps.launch(s.lat, s.lon) else uri.openUri(osmViewerUrl(s.lat, s.lon)) }
        }) { Text("Open map") }
    }
}

@Composable
private fun OutboundRow(s: OutShare, label: String? = null) {
    val live = LocalLiveLocation.current ?: return
    val scope = rememberCoroutineScope()
    var problem by remember(s.ship) { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label ?: "You're sharing where you are", style = MaterialTheme.typography.bodyMedium)
            Text(outboundDetail(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        TextButton(onClick = {
            problem = null
            scope.launch { live.stop(s.ship).onFailure { problem = shareProblem("stop", it) } }
        }) { Text("Stop") }
    }
}

@Composable
private fun ShareLocationDialog(ship: String, who: String, onDone: () -> Unit) {
    val live = LocalLiveLocation.current ?: return
    val sending = rememberLocationSharing()
    val scope = rememberCoroutineScope()
    var length by remember { mutableStateOf(ShareLength.Hour) }
    var exact by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Share where you are with $who") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShareLength.entries.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = length == l, onClick = { length = l }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = length == l, onClick = null)
                        Text(l.label, Modifier.padding(start = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { exact = !exact }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Exact")
                        Text(
                            if (exact) "Where you are, to the metre." else "The area only, to about a kilometre.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = exact, onCheckedChange = { exact = it })
                }
                Text(
                    "Your ship sends where you are each minute while this runs. Stop it any time.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                problem = null
                scope.launch {
                    // The phone sends only while "Send where I am" is on: turned on here, its prompts with it.
                    if (sending != null && !sending.on.value && !sending.start()) {
                        problem = "Talon needs location access all the time to send where you are."
                        busy = false
                        return@launch
                    }
                    live.share(ship, length, exact)
                        .onSuccess { onDone() }
                        .onFailure { problem = shareProblem("share", it); busy = false }
                }
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** "Updated 3m ago · until 2:30 PM", and "area only" for a share cut to a kilometre. */
internal fun inboundDetail(s: InShare, nowMs: Long): String = listOfNotNull(
    s.atMs?.let { "Updated ${shortRelativeTime(it, nowMs)}" },
    s.untilMs?.let { "until ${formatClock(it)}" } ?: if (s.home) "until they're home" else null,
    if ((s.accuracyM ?: 0.0) >= 1000.0) "area only" else null,
).joinToString(" · ")

internal fun outboundDetail(s: OutShare): String = listOfNotNull(
    s.untilMs?.let { "until ${formatClock(it)}" } ?: "until you're home",
    if (s.exact) "exact" else "area only",
).joinToString(" · ")

/** What a failed share or stop says: the ship's reason when it gave one, never an exception's text. */
internal fun shareProblem(what: String, e: Throwable): String = when (e) {
    is OrreryError.Refused -> "Couldn't $what: ${e.reason.ifBlank { "the ship said no (${e.status})" }}"
    is OrreryError.Unreachable -> "Couldn't $what: your ship didn't answer. Try again."
    else -> "Couldn't $what. Try again."
}

/** How often a shown share is read again: the phone sends a fix a minute. */
const val LIVE_REREAD_MS = 60_000L
