package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.nisfeb.talon.ui.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.orrery.LeaveBy
import io.nisfeb.talon.orrery.OrreryAction
import io.nisfeb.talon.orrery.OrreryItem
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.orrery.Upcoming
import io.nisfeb.talon.orrery.browse
import io.nisfeb.talon.orrery.comingUp
import io.nisfeb.talon.orrery.leaveByOf
import io.nisfeb.talon.orrery.orreryItems
import io.nisfeb.talon.ui.PlatformBackHandler
import io.nisfeb.talon.ui.mapsSearchUri
import io.nisfeb.talon.util.formatClock
import io.nisfeb.talon.util.formatWeekdayMonthDay
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class OrreryTab(val label: String) { ACTIONS("Actions"), COMING_UP("Coming up"), BROWSE("Browse") }

/**
 * The Orrery section, wired to the ship: what orrery proposes, what is
 * coming up, and everything it knows. Both shells show this one.
 */
@Composable
fun OrreryRepoScreen(
    orreryRepo: OrreryRepo,
    actions: List<OrreryAction>,
    twentyFourHour: Boolean,
    onBack: () -> Unit,
    onOpenAction: (OrreryAction) -> Unit,
    modifier: Modifier = Modifier,
    openItem: String? = null,
    onOpenedItem: () -> Unit = {},
) {
    val told by orreryRepo.told.collectAsState()
    val failed by orreryRepo.failed.collectAsState()
    val answerProblem by orreryRepo.answerProblem.collectAsState()
    val error by orreryRepo.error.collectAsState()
    val generator by orreryRepo.generator.collectAsState()
    val on by orreryRepo.enabled.collectAsState()
    val store = orreryRepo.view
    OrreryScreen(
        onBack = onBack,
        view = store.view.collectAsState().value,
        refreshing = store.refreshing.collectAsState().value,
        problem = store.problem.collectAsState().value,
        onOpen = store::open,
        onRefreshActions = { orreryRepo.opened() },
        modifier = modifier,
        openItem = openItem,
        onOpenedItem = onOpenedItem,
        turnOn = if (on) null else orreryRepo::enable,
    ) {
        OrreryActionsScreen(
            actions = actions,
            onBack = onBack,
            onShown = { orreryRepo.opened() },
            told = told,
            onLeave = { orreryRepo.toldSeen() },
            failed = failed,
            onDecide = { a, status, why -> orreryRepo.answer(a.id, status, why) },
            problem = answerProblem ?: error,
            generator = generator?.let {
                io.nisfeb.talon.orrery.generatorLine(it, nowMs(), kotlinx.datetime.TimeZone.currentSystemDefault())
            },
            twentyFourHour = twentyFourHour,
            header = false,
            onOpen = onOpenAction,
        )
    }
}

/**
 * Orrery: Actions, Coming up and Browse, and one thing opened from any of
 * them. It shows [view] as it is, kept from the last answer, while
 * [onOpen] asks the ship again behind it ([io.nisfeb.talon.orrery.OrreryViewStore]).
 * [openItem] (a leave alert's thing) opens on arrival, under Coming up,
 * and [onOpenedItem] says it was taken.
 */
@Composable
fun OrreryScreen(
    onBack: () -> Unit,
    /** Orrery's state and the leave plan, as last answered; null before any answer. */
    view: io.nisfeb.talon.orrery.OrreryView?,
    refreshing: Boolean,
    /** Why the last ask got nothing. */
    problem: String?,
    /** Opened (false) or Refresh (true): ask the ship if it is due. */
    onOpen: (Boolean) -> Unit,
    onRefreshActions: suspend () -> Unit,
    modifier: Modifier = Modifier,
    openItem: String? = null,
    onOpenedItem: () -> Unit = {},
    /** Non-null where Orrery is not on for this install (no key of its own):
     *  the screen offers to turn it on, as Settings does, rather than
     *  failing to read with "This install has no orrery key yet." */
    turnOn: (suspend () -> Result<Unit>)? = null,
    actionsTab: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(OrreryTab.ACTIONS) }
    val state = view?.state
    val plan = remember(view?.plan) { leaveByOf(view?.plan) }
    // Things opened one from another; back goes to the one before.
    var stack by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) { onOpen(false) }
    LaunchedEffect(openItem) {
        if (openItem != null) {
            tab = OrreryTab.COMING_UP
            stack = listOf(openItem)
            onOpenedItem()
        }
    }
    val items = remember(state) { state?.let(::orreryItems).orEmpty() }
    val byId = remember(items) { items.associateBy { it.id } }
    val me = (state?.get("me") as? JsonPrimitive)?.contentOrNull
    fun back() { stack = stack.dropLast(1) }
    PlatformBackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // On a thing, back to where it was opened from: the shell's
            // menu button would open the drawer instead.
            if (stack.isNotEmpty()) {
                io.nisfeb.talon.ui.IconButton(tip = "Back", onClick = ::back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                io.nisfeb.talon.ui.NavIcon(onBack = onBack)
            }
            Text(
                "Orrery",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            if (refreshing) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(20.dp))
            } else {
                io.nisfeb.talon.ui.IconButton(tip = "Refresh", onClick = { scope.launch { runCatching { onRefreshActions() } }; onOpen(true) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }
        TabRow(selectedTabIndex = tab.ordinal) {
            OrreryTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t; stack = emptyList() }, text = { Text(t.label) })
            }
        }
        // Kept data on show and the ship not answering: say how old it is.
        if (problem != null && view != null && tab != OrreryTab.ACTIONS) {
            Text(
                "The ship did not answer; this is as of ${formatClock(view.atMs)}.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val open = stack.lastOrNull()
        when {
            open != null -> ItemPage(open, byId[open], loaded = state != null, byId, me, plan) { stack = stack + it }
            tab == OrreryTab.ACTIONS -> actionsTab()
            state == null && turnOn != null -> NotOnHere(turnOn) { onOpen(true) }
            state == null -> Text(
                problem?.let { "Orrery could not be read: $it" } ?: "Looking…",
                style = MaterialTheme.typography.bodySmall,
                color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            tab == OrreryTab.COMING_UP -> ComingUpList(comingUp(items, nowMs()), plan) { stack = listOf(it) }
            else -> BrowseList(items) { stack = listOf(it) }
        }
    }
}

@Composable
private fun ComingUpList(upcoming: List<Upcoming>, plan: LeaveBy?, onOpen: (String) -> Unit) {
    if (upcoming.isEmpty()) {
        Text("Nothing coming up.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        // A heading for each day, as an agenda reads.
        upcoming.groupBy { formatWeekdayMonthDay(it.startMs) }.forEach { (day, onDay) ->
            item(key = "day:$day") {
                Text(day, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            }
            items(onDay, key = { it.item.id + "@" + it.startMs }) { u ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(u.item.id) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(u.item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(timeSpan(u.startMs, u.endMs), u.item.where?.lineSequence()?.first()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    plan?.takeIf { it.itemId == u.item.id }?.let { Text(leaveLine(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
private fun BrowseList(items: List<OrreryItem>, onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Search") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val groups = browse(items, query)
        if (groups.isEmpty()) Text("Nothing by that name.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            groups.forEach { (heading, inKind) ->
                item(key = "kind:$heading") {
                    Text("$heading (${inKind.size})", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                }
                items(inKind, key = { it.id }) { i ->
                    Text(
                        i.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(i.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** The attributes the page shows in their own places; the rest are listed under it. */
private val SHOWN = setOf("next", "starts", "ends", "location", "address", "participants", "organizer", "drop-off", "pick-up")

/**
 * One thing orrery knows. Directions first, for the alert that sent the
 * owner here: it says to leave. Then when, where, who, and the rest.
 */
@Composable
private fun ItemPage(
    id: String,
    item: OrreryItem?,
    loaded: Boolean,
    byId: Map<String, OrreryItem>,
    me: String?,
    plan: LeaveBy?,
    onOpen: (String) -> Unit,
) {
    if (item == null) {
        Text(
            if (loaded) "Orrery has nothing under $id now." else "Looking…",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item.where?.let { where ->
            Button(onClick = { runCatching { uri.openUri(mapsSearchUri(where)) } }, modifier = Modifier.fillMaxWidth()) { Text("Directions") }
        }
        Text(item.name, style = MaterialTheme.typography.titleLarge)
        Text(kindLabel(item.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan?.takeIf { it.itemId == item.id }?.let { Text(leaveLine(it), color = MaterialTheme.colorScheme.primary) }
        val start = item.ms("next") ?: item.ms("starts")
        start?.let { s ->
            Field(if (item.kind == "activity") "Next" else "When", formatWeekdayMonthDay(s) + ", " + timeSpan(s, item.ms("ends")))
        }
        item.where?.let { Field("Where", it) }
        listOf("participants" to "Who", "organizer" to "Organizer", "drop-off" to "Drop-off", "pick-up" to "Pick-up").forEach { (attr, label) ->
            val refs = item.refs(attr)
            if (refs.isNotEmpty()) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                refs.forEach { r ->
                    Text(
                        if (r == me) "You" else byId[r]?.name ?: r.substringAfter('/'),
                        color = if (byId[r] != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.then(if (byId[r] != null) Modifier.clickable { onOpen(r) } else Modifier).padding(vertical = 2.dp),
                    )
                }
            }
        }
        val rest = item.values.filterKeys { it !in SHOWN }
        if (rest.isNotEmpty()) {
            HorizontalDivider()
            rest.entries.sortedBy { it.key }.forEach { (attr, values) ->
                Field(attr.replace('-', ' ').replaceFirstChar(Char::uppercase), values.joinToString(", ") { v ->
                    ((v as? JsonObject)?.get("ref") as? JsonPrimitive)?.contentOrNull?.let { r -> if (r == me) "You" else byId[r]?.name ?: r }
                        ?: (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                })
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private val KIND_LABELS = mapOf(
    "situation" to "Situation", "activity" to "Activity", "person" to "Person", "place" to "Place",
    "org" to "Organization", "thing" to "Thing", "note" to "Note",
)

private fun kindLabel(kind: String): String = KIND_LABELS[kind] ?: kind.replaceFirstChar(Char::uppercase)

private fun timeSpan(startMs: Long, endMs: Long?): String =
    formatClock(startMs) + (endMs?.let { "–" + formatClock(it) } ?: "")

private fun leaveLine(p: LeaveBy): String =
    "Leave by ${formatClock(p.leaveByMs)}" + (if (p.minutes > 0) " · ${p.minutes} min with traffic" else "")

/**
 * Orrery is on the ship but not on for this install: reading it needs a
 * key of this install's own, which is what turning it on makes. Said as
 * that, with the switch's own words, not as a failure to read.
 */
@Composable
private fun NotOnHere(turnOn: suspend () -> Result<Unit>, onOn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Orrery isn't turned on for this device.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Turning it on gives this device a key of its own: it reads Orrery here, and sends Orrery the facts from your calls and contacts' status lines. Your ship reads your chats, calendar and mail itself. The same switch is in Settings, under AI.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(enabled = !busy, onClick = {
            why = null
            busy = true
            scope.launch {
                turnOn().onSuccess { onOn() }.onFailure { why = it.message ?: "Orrery did not answer." }
                busy = false
            }
        }) { Text(if (busy) "Turning on…" else "Turn on") }
        why?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

