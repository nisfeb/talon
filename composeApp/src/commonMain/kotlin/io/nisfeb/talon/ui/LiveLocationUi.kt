package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import io.nisfeb.talon.orrery.GROUP_SHARE_MAX
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
    val asking = shares?.asking(ship) == true
    if (inbound == null && out == null && !asking) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (asking) AskRow(ship, who)
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
    if (shares?.asking(ship) == true) AskRow(ship, who)
    shares?.from(ship)?.let { InboundRow(it, who) }
    val out = shares?.outTo(ship)
    if (out != null) OutboundRow(out)
    else if (live.canShare) TextButton(onClick = { asking = true }) { Text("Share my location") }
    ShareProblem(live.shareProblems.collectAsState().value[ship])
    if (asking) ShareLocationDialog(listOf(ship), "Share where you are with $who", exactFirst = true, onDone = { asking = false })
}

/**
 * A group's Info: share where we are with its members, each their own
 * grant, the area by default; and the members we share with, with Stop.
 * Only for a group of up to [GROUP_SHARE_MAX] members.
 */
@Composable
fun GroupLiveLocationRows(members: List<String>, us: String) {
    val live = LocalLiveLocation.current ?: return
    val shares by live.shares.collectAsState()
    LaunchedEffect(Unit) { live.refresh() }
    val others = members.filter { it != us }
    val sharedWith = shares?.out.orEmpty().map { it.ship }.filter { it in others }
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    if (sharedWith.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("You're sharing where you are with ${sharedWith.size} of its members", style = MaterialTheme.typography.bodyMedium)
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        TextButton(enabled = shares?.out.orEmpty().none { it.sending && it.ship in others }, onClick = {
            problem = null
            scope.launch { live.stop(sharedWith).onFailure { problem = shareProblem("stop", it) } }
        }) { Text("Stop") }
    } else if (live.canShare && others.isNotEmpty() && others.size <= GROUP_SHARE_MAX) {
        TextButton(onClick = { asking = true }) { Text("Share my location with the group") }
    }
    val problems by live.shareProblems.collectAsState()
    ShareProblem(others.firstNotNullOfOrNull { problems[it] })
    if (asking) ShareLocationDialog(others, "Share where you are with the group's ${others.size} members", exactFirst = false, onDone = { asking = false })
}

/** Settings' list of the shares this ship runs, each with Stop. */
@Composable
fun SharingWithList() {
    val live = LocalLiveLocation.current ?: return
    val shares by live.shares.collectAsState()
    LaunchedEffect(Unit) { live.refresh() }
    val out = shares?.out.orEmpty()
    // Asks first: a group's member who shared lands here, with no chat to show it in.
    shares?.asks.orEmpty().forEach { AskRow(it, shipHandle(it)) }
    if (out.isEmpty()) return
    Text("Sharing where you are with", style = MaterialTheme.typography.labelLarge)
    out.forEach { s -> OutboundRow(s, label = shipHandle(s.ship)) }
}

/** Why a share the dialog handed over did not start: it closed on the tap, so the reason shows where Share is. */
@Composable
private fun ShareProblem(e: Throwable?) {
    if (e != null) Text(shareProblem("share", e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** A ship this orrery does not know wants to share where they are: nothing of theirs shows until accepted. */
@Composable
private fun AskRow(ship: String, who: String) {
    val live = LocalLiveLocation.current ?: return
    val scope = rememberCoroutineScope()
    var problem by remember(ship) { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$who wants to share where they are", style = MaterialTheme.typography.bodyMedium)
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        TextButton(onClick = {
            problem = null
            scope.launch { live.decline(ship).onFailure { problem = shareProblem("decline", it) } }
        }) { Text("Decline") }
        TextButton(onClick = {
            problem = null
            scope.launch { live.accept(ship).onFailure { problem = shareProblem("accept", it) } }
        }) { Text("Accept") }
    }
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
    val open = rememberOpenInMaps()
    var mapOpen by remember(s.ship) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$who is sharing where they are", style = MaterialTheme.typography.bodyMedium)
            Text(inboundDetail(s, nowMs()), style = MaterialTheme.typography.bodySmall, color = if (s.lateByMs != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // A map in Talon follows each fix; elsewhere the point goes to the maps app.
        if (isLiveMapSupported) TextButton(onClick = { mapOpen = true }) { Text("Map") }
        else TextButton(onClick = { open(s.lat, s.lon) }) { Text("Open map") }
    }
    if (mapOpen) LiveMapDialog(s.ship, who, onDone = { mapOpen = false })
}

/** The point to the platform's maps app, or OpenStreetMap's site in the browser. */
@Composable
private fun rememberOpenInMaps(): (Double, Double) -> Unit {
    val maps = LocalMapsLauncher.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    return { lat, lon -> runCatching { if (maps != null) maps.launch(lat, lon) else uri.openUri(osmViewerUrl(lat, lon)) } }
}

/** Their share on a map, following each fix as the shares are read again; gone when the share is. */
@Composable
private fun LiveMapDialog(ship: String, who: String, onDone: () -> Unit) {
    val live = LocalLiveLocation.current ?: return
    val shares by live.shares.collectAsState()
    val s = shares?.from(ship)
    LaunchedEffect(s == null) { if (s == null) onDone() }
    if (s == null) return
    val open = rememberOpenInMaps()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Where $who is") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveMapView(s.lat, s.lon, s.accuracyM, Modifier.fillMaxWidth().height(360.dp))
                Text(inboundDetail(s, nowMs()), style = MaterialTheme.typography.bodySmall, color = if (s.lateByMs != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
        dismissButton = { TextButton(onClick = { open(s.lat, s.lon) }) { Text("Open in Maps") } },
    )
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
        // Not while the ship has yet to take it: a stop could land first, and the share after.
        TextButton(enabled = !s.sending, onClick = {
            problem = null
            scope.launch { live.stop(listOf(s.ship)).onFailure { problem = shareProblem("stop", it) } }
        }) { Text("Stop") }
    }
}

@Composable
private fun ShareLocationDialog(ships: List<String>, title: String, exactFirst: Boolean, onDone: () -> Unit) {
    val live = LocalLiveLocation.current ?: return
    val sending = rememberLocationSharing()
    val scope = rememberCoroutineScope()
    var length by remember { mutableStateOf(ShareLength.Hour) }
    var exact by remember { mutableStateOf(exactFirst) }
    // A check-in (orrery 142): home by a time, or they are told, by the ship.
    var checkIn by remember { mutableStateOf(false) }
    var byMin by remember { mutableStateOf(((localMinuteOfDay() / 15 + 1) * 15 + 60) % (24 * 60)) }
    // With a Mapbox token the ship works the time out from the drive home (orrery 143): the default there.
    var autoBy by remember { mutableStateOf(live.canAutoCheckIn) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
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
                // Gated, not faked: an older orrery would take the time and tell no one.
                if (length == ShareLength.Home && live.canCheckIn) {
                    Row(Modifier.fillMaxWidth().clickable { checkIn = !checkIn }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Check in")
                            Text(
                                if (checkIn) "If you're not home by then, ${if (ships.size == 1) "they're" else "each is"} told, even if your phone is off."
                                else "Tell them if you're not home by a time.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = checkIn, onCheckedChange = { checkIn = it })
                    }
                    if (checkIn && live.canAutoCheckIn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(selected = autoBy, onClick = { autoBy = true }, label = { Text("Automatic") })
                        androidx.compose.material3.FilterChip(selected = !autoBy, onClick = { autoBy = false }, label = { Text("Set a time") })
                    }
                    if (checkIn && live.canAutoCheckIn && autoBy) Text(
                        "Your ship works it out from the drive home, with traffic, plus your travel buffer.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else if (checkIn) ClockTimeButton(byMin, { byMin = it }, label = "Home by")
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
                if (busy) Text("Waiting for location access…", style = MaterialTheme.typography.bodySmall)
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
                        problem = "Groundwire needs location access all the time to send where you are."
                        busy = false
                        return@launch
                    }
                    // The ship's answer lands where the share shows: no waiting on it here.
                    val checking = checkIn && length == ShareLength.Home && live.canCheckIn
                    val auto = checking && live.canAutoCheckIn && autoBy
                    live.share(ships, length, exact, byMs = if (checking && !auto) io.nisfeb.talon.orrery.nextAtMinute(byMin, nowMs()) else null, autoBy = auto)
                    onDone()
                }
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** "Updated 3m ago · until 2:30 PM", and "area only" for a share cut to a kilometre. */
internal fun inboundDetail(s: InShare, nowMs: Long): String = listOfNotNull(
    s.lateByMs?.let { "Not home yet, meant to be by ${formatClock(it)}" },
    s.atMs?.let { "Updated ${shortRelativeTime(it, nowMs)}" },
    s.untilMs?.let { "until ${formatClock(it)}" } ?: if (s.home) "until they're home" else null,
    if ((s.accuracyM ?: 0.0) >= 1000.0) "area only" else null,
).joinToString(" · ")

internal fun outboundDetail(s: OutShare): String = listOfNotNull(
    if (s.sending) "Sending to your ship" else null,
    s.untilMs?.let { "until ${formatClock(it)}" } ?: "until you're home",
    s.byMs?.let { "check in by ${formatClock(it)}" },
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
