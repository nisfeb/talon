package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.mail.MailAvailability
import io.nisfeb.talon.mail.MailRepo
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nisfeb.talon.ui.CalendarRange
import io.nisfeb.talon.ui.HOME_COUNTS
import io.nisfeb.talon.ui.HOME_COLUMNS
import io.nisfeb.talon.ui.HOME_ROW_RANGE
import io.nisfeb.talon.ui.HomeLayout
import io.nisfeb.talon.ui.HomePlace
import io.nisfeb.talon.ui.HomeWidget
import io.nisfeb.talon.ui.HomeWidgetKind
import io.nisfeb.talon.ui.packRows
import io.nisfeb.talon.ui.SkyClock
import io.nisfeb.talon.ui.Solar
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.shortRelativeTime
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.util.nowMs

/** How many rows each quick-access list shows. Short on purpose: a
 *  home page that lists everything is the list it links to. */
private const val QUICK = 5

/**
 * The home page.
 *
 * Four panels, each a door rather than a destination: the conversations
 * that moved most recently, the newest mail, the time and weather, and
 * today's calendar. None of them tries to be the surface it points at —
 * five rows and a way through is the whole job.
 *
 * Two of the four are deliberately unfinished. The clock and weather
 * panel is waiting on a design, and the calendar is waiting on a
 * feature that does not exist yet. They are drawn as what they are, so
 * the page reads as a page rather than as three quarters of one, and
 * so the seam each one drops into is obvious.
 */
@Composable
fun HomeScreen(
    db: AppDatabase,
    mail: MailRepo?,
    contacts: ContactMap,
    ourShip: String,
    /** Where the dial thinks you are, or null before anyone has said. */
    place: HomePlace? = null,
    /**
     * Today's weather, or null before anything has fetched it.
     *
     * Passed in rather than fetched here. This screen is torn down
     * every time somebody looks at their messages, so state kept
     * inside it comes back empty: the dial would redraw with no
     * weather, then pop when the answer arrived. It lives above the
     * navigation instead, and comes back ready.
     */
    weather: SkyClock.Sky? = null,
    /** Ask the device where it is. Null where it cannot say, which is
     *  desktop and a refused permission alike. */
    onUseDeviceLocation: (suspend () -> Result<HomePlace>)? = null,
    /** Turn a typed place into coordinates, or null for coordinates only. */
    placeLookup: io.nisfeb.talon.ui.PlaceLookup? = null,
    onPlacePicked: (HomePlace) -> Unit = {},
    /** How the dial reads out. Set in Settings, under Home. */
    fahrenheit: Boolean = true,
    twentyFourHour: Boolean = false,
    /** Which widgets, in what order, at what size. */
    layout: HomeLayout = HomeLayout.DEFAULT,
    /** Called when the page is rearranged from the page itself. */
    onLayoutChanged: (HomeLayout) -> Unit = {},
    /** Statuses, for the status widget. */
    statuses: List<io.nisfeb.talon.data.ContactEntity> = emptyList(),
    onOpenContact: (ship: String) -> Unit = {},
    onOpenStatuses: () -> Unit = {},
    onOpenConversation: (whom: String) -> Unit,
    onOpenChats: () -> Unit,
    onOpenMailThread: (threadId: String) -> Unit,
    onOpenMail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by remember(db) { db.messages().conversationLatest() }
        .collectAsState(initial = emptyList())
    val unreads by remember(db) { db.unreads().stream() }
        .collectAsState(initial = emptyList())

    val recent = remember(latest) { latest.take(HOME_COUNTS.last()) }
    val unreadBy = remember(unreads) { unreads.associateBy { it.whom } }

    var editing by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Two columns where there is room, one where there is not. A
        // widget set to full width on a desktop is still full width on
        // a phone; it just has nothing to sit beside.
        val columns = if (maxWidth >= 820.dp) HOME_COLUMNS else 1
        val gridRows = remember(layout, columns) { packRows(layout.shown, columns) }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    greeting(ourShip, contacts),
                    style = MaterialTheme.typography.headlineSmall
                        .copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { editing = !editing }) {
                    Text(if (editing) "Done" else "Arrange")
                }
            }

            if (layout.shown.isEmpty()) {
                Text(
                    "Nothing on the home page. Settings, under Home, has the list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            gridRows.forEach { gridRow ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    gridRow.forEach { widget ->
                        Column(
                            Modifier
                                .weight(widget.span.coerceIn(1, columns).toFloat())
                                // A minimum rather than a fixed height:
                                // a list told to show ten rows in one
                                // row-unit should outgrow its box, not
                                // have the last four clipped off.
                                .heightIn(min = HOME_ROW_UNIT * widget.rows),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (editing) {
                                ArrangeBar(
                                    widget = widget,
                                    columns = columns,
                                    onChange = { onLayoutChanged(layout.with(it)) },
                                    onMove = { by -> onLayoutChanged(layout.moved(widget.kind, by)) },
                                )
                            }
                            WidgetBody(
                                widget = widget,
                                recent = recent,
                                unreadBy = unreadBy,
                                contacts = contacts,
                                ourShip = ourShip,
                                mail = mail,
                                statuses = statuses,
                                place = place,
                                weather = weather,
                                fahrenheit = fahrenheit,
                                twentyFourHour = twentyFourHour,
                                onUseDeviceLocation = onUseDeviceLocation,
                                placeLookup = placeLookup,
                                onPlacePicked = onPlacePicked,
                                onOpenConversation = onOpenConversation,
                                onOpenChats = onOpenChats,
                                onOpenMailThread = onOpenMailThread,
                                onOpenMail = onOpenMail,
                                onOpenContact = onOpenContact,
                                onOpenStatuses = onOpenStatuses,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One grid row's worth of height. Two of them is about a dial. */
private val HOME_ROW_UNIT = 168.dp

/**
 * The controls over each widget while the page is being arranged.
 *
 * Buttons rather than dragging. Dragging a resizable tile is a great
 * deal of machinery and a fiddly thing to land on a phone; four arrows
 * and a cross say the same and can be hit with a thumb.
 */
@Composable
private fun ArrangeBar(
    widget: HomeWidget,
    columns: Int,
    onChange: (HomeWidget) -> Unit,
    onMove: (Int) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title(widget.kind),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButton(onClick = { onMove(-1) }) {
                Icon(Icons.Filled.KeyboardArrowUp, "Move earlier", Modifier.size(18.dp))
            }
            IconButton(onClick = { onMove(1) }) {
                Icon(Icons.Filled.KeyboardArrowDown, "Move later", Modifier.size(18.dp))
            }
            // Only worth offering where there are two columns to span.
            if (columns > 1) {
                IconButton(
                    enabled = widget.span > 1,
                    onClick = { onChange(widget.copy(span = widget.span - 1)) },
                ) {
                    Icon(Icons.Filled.KeyboardArrowLeft, "Narrower", Modifier.size(18.dp))
                }
                IconButton(
                    enabled = widget.span < HOME_COLUMNS,
                    onClick = { onChange(widget.copy(span = widget.span + 1)) },
                ) {
                    Icon(Icons.Filled.KeyboardArrowRight, "Wider", Modifier.size(18.dp))
                }
            }
            IconButton(
                enabled = widget.rows > HOME_ROW_RANGE.first,
                onClick = { onChange(widget.copy(rows = widget.rows - 1)) },
            ) {
                Text("\u2013", style = MaterialTheme.typography.labelLarge)
            }
            Text("${widget.rows}", style = MaterialTheme.typography.labelSmall)
            IconButton(
                enabled = widget.rows < HOME_ROW_RANGE.last,
                onClick = { onChange(widget.copy(rows = widget.rows + 1)) },
            ) {
                Text("+", style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = { onChange(widget.copy(visible = false)) }) {
                Icon(Icons.Filled.Close, "Take off the home page", Modifier.size(18.dp))
            }
        }
    }
}

/** What each widget is called, wherever one needs naming. */
fun title(kind: HomeWidgetKind): String = when (kind) {
    HomeWidgetKind.CLOCK -> "Clock and weather"
    HomeWidgetKind.MESSAGES -> "Recent"
    HomeWidgetKind.MAIL -> "Mail"
    HomeWidgetKind.CALENDAR -> "Today"
    HomeWidgetKind.STATUS -> "Statuses"
}

@Composable
private fun WidgetBody(
    widget: HomeWidget,
    recent: List<io.nisfeb.talon.data.MessageEntity>,
    unreadBy: Map<String, io.nisfeb.talon.data.UnreadEntity>,
    contacts: ContactMap,
    ourShip: String,
    mail: MailRepo?,
    statuses: List<io.nisfeb.talon.data.ContactEntity>,
    place: HomePlace?,
    weather: SkyClock.Sky?,
    fahrenheit: Boolean,
    twentyFourHour: Boolean,
    onUseDeviceLocation: (suspend () -> Result<HomePlace>)?,
    placeLookup: io.nisfeb.talon.ui.PlaceLookup?,
    onPlacePicked: (HomePlace) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenChats: () -> Unit,
    onOpenMailThread: (String) -> Unit,
    onOpenMail: () -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenStatuses: () -> Unit,
) {
    when (widget.kind) {
        HomeWidgetKind.CLOCK -> ClockWeatherPanel(
            place, weather, onUseDeviceLocation, placeLookup, onPlacePicked,
            fahrenheit, twentyFourHour,
        )
        HomeWidgetKind.MESSAGES -> ChatsPanel(
            recent.take(widget.count), unreadBy, contacts, ourShip,
            onOpenConversation, onOpenChats,
        )
        HomeWidgetKind.MAIL -> MailPanel(mail, contacts, widget.count, onOpenMailThread, onOpenMail)
        HomeWidgetKind.CALENDAR -> CalendarPanel(widget.calendarRange)
        HomeWidgetKind.STATUS -> StatusPanel(
            statuses, contacts, ourShip, widget, onOpenContact, onOpenStatuses,
        )
    }
}

private fun greeting(ourShip: String, contacts: ContactMap): String {
    val who = contacts.displayName(ourShip).takeIf { it.isNotBlank() && ourShip.isNotBlank() }
    return if (who == null) "Talon" else "Hello, $who"
}

// ---- panels ------------------------------------------------------------

@Composable
private fun Panel(
    title: String,
    icon: ImageVector,
    action: Pair<String, () -> Unit>? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge
                        .copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                if (action != null) {
                    TextButton(onClick = action.second) {
                        Text(action.first, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Column(Modifier.padding(top = 2.dp)) { content() }
        }
    }
}

@Composable
private fun QuickRow(
    title: String,
    line: String,
    at: Long,
    strong: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (at > 0) {
                Text(
                    shortRelativeTime(at, nowMs()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (line.isNotBlank()) {
            Text(
                line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun ChatsPanel(
    recent: List<io.nisfeb.talon.data.MessageEntity>,
    unreadBy: Map<String, io.nisfeb.talon.data.UnreadEntity>,
    contacts: ContactMap,
    ourShip: String,
    onOpen: (String) -> Unit,
    onAll: () -> Unit,
) {
    Panel("Recent", Icons.AutoMirrored.Filled.Chat, "All chats" to onAll) {
        if (recent.isEmpty()) {
            Empty("Nothing yet.")
        } else {
            recent.forEach { m ->
                val unread = (unreadBy[m.whom]?.count ?: 0) > 0
                QuickRow(
                    title = contacts.conversationLabel(m.whom),
                    line = preview(m, contacts, ourShip),
                    at = m.sentMs,
                    strong = unread,
                    onClick = { onOpen(m.whom) },
                )
            }
        }
    }
}

private fun preview(
    m: io.nisfeb.talon.data.MessageEntity,
    contacts: ContactMap,
    ourShip: String,
): String {
    val body = StoryCache.previewFor(m)
    val who = if (m.author == ourShip) "You" else contacts.displayName(m.author)
    return if (body.isBlank()) who else "$who: $body"
}

@Composable
private fun MailPanel(
    mail: MailRepo?,
    contacts: ContactMap,
    count: Int,
    onOpen: (String) -> Unit,
    onAll: () -> Unit,
) {
    // Null where the host wires no mailbox at all; PRESENT is the ship
    // actually having the app. Anything else and the panel says so
    // rather than sitting empty as though there were no mail.
    val availability = mail?.availability?.collectAsState()?.value
    val page = mail?.page?.collectAsState()?.value
    Panel("Mail", Icons.Filled.MailOutline, ("Inbox" to onAll).takeIf { mail != null }) {
        when {
            mail == null || availability == MailAvailability.NO_GRUBBERY ||
                availability == MailAvailability.OLD_GRUBBERY ->
                Empty("This ship has no mail app yet.")

            availability == MailAvailability.SIGNED_OUT -> Empty("Signed out of the ship.")

            page == null -> Empty("Looking…")

            page.threads.isEmpty() -> Empty("No mail.")

            else -> page.threads.take(count).forEach { row ->
                QuickRow(
                    title = contacts.displayName(row.from),
                    line = row.subject.ifBlank { "(no subject)" },
                    at = row.last,
                    strong = row.unread,
                    onClick = { onOpen(row.id) },
                )
            }
        }
    }
}

/**
 * The time and the day's shape, which is the page's centrepiece.
 *
 * Ticks every ten seconds rather than every second: the dial shows
 * minutes, and waking the composition sixty times a minute to redraw
 * the same picture is how a page somebody leaves open all day becomes
 * a page that costs them battery all day.
 */
@Composable
private fun ClockWeatherPanel(
    place: HomePlace?,
    weather: SkyClock.Sky?,
    onUseDeviceLocation: (suspend () -> Result<HomePlace>)?,
    placeLookup: io.nisfeb.talon.ui.PlaceLookup?,
    onPlacePicked: (HomePlace) -> Unit,
    fahrenheit: Boolean,
    twentyFourHour: Boolean,
) {
    var picking by remember { mutableStateOf(false) }
    var nowMsState by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            nowMsState = nowMs()
        }
    }

    val sky = remember(nowMsState / 60_000, place, weather) {
        skyFor(nowMsState, place, weather)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SkyClockDial(
                sky = sky,
                fahrenheit = fahrenheit,
                twentyFourHour = twentyFourHour,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            TextButton(onClick = { picking = true }, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    place?.label ?: "Set a location",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (place == null) {
                Text(
                    "Without one the dial shows an even day and no weather.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }

    if (picking) {
        LocationPicker(
            current = place,
            onUseDevice = onUseDeviceLocation,
            lookup = placeLookup,
            onPick = { picking = false; onPlacePicked(it) },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The dial's state for a moment in time.
 *
 * Without a place the sun's times are unknowable, so it falls back to
 * an even twelve hours and says where it is by saying nothing: the
 * panel's own caption is what admits there is no location.
 */
internal fun skyFor(atMs: Long, place: HomePlace?, weather: SkyClock.Sky?): SkyClock.Sky {
    val zone = zoneFor(place?.timeZoneId ?: weather?.zoneId)
    val local = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(zone)
    val minuteOfDay = local.hour * 60 + local.minute
    val offsetMinutes = zone.offsetAt(Instant.fromEpochMilliseconds(atMs)).totalSeconds / 60

    val sun = place?.let {
        Solar.sunTimes(
            latitude = it.lat,
            longitude = it.lon,
            dayOfYear = local.date.dayOfYear,
            zoneOffsetMinutes = offsetMinutes,
            elevationMetres = it.elevationMetres ?: 0.0,
        )
    }
    val base = weather ?: SkyClock.Sky(minuteOfDay = minuteOfDay)
    return base.copy(
        minuteOfDay = minuteOfDay,
        dateLabel = dayLabel(local),
        sunriseMinute = sun?.sunriseMinute ?: base.sunriseMinute,
        sunsetMinute = sun?.sunsetMinute ?: base.sunsetMinute,
        twilight = place?.let { Solar.twilightMinutes(it.lat) } ?: base.twilight,
        polar = sun?.polar ?: false,
        polarDay = sun?.polarDay ?: false,
        // Independent of where you are: the phase is the same moon for
        // everybody, and where it sits on the dial follows from it.
        moonElongationDeg = io.nisfeb.talon.ui.Moon.phaseAt(atMs).elongationDeg,
    )
}

/** How long a forecast is good for. Finer than the model updates. */
internal const val WEATHER_MAX_AGE_MS = 30 * 60_000L

/**
 * Whether what we have is old enough to ask again.
 *
 * Never fetched counts as stale, and so does a clock that has gone
 * backwards: a machine that woke with a corrected time should refetch
 * rather than sit on an answer it now believes is from the future.
 */
internal fun weatherIsStale(fetchedAtMs: Long, nowMs: Long): Boolean =
    fetchedAtMs <= 0L || nowMs < fetchedAtMs || nowMs - fetchedAtMs >= WEATHER_MAX_AGE_MS

/**
 * The clock the dial runs on.
 *
 * The place's own, wherever it is known, and the device's otherwise.
 * This is not cosmetic: the sun's times come out of the solar geometry
 * in UTC and are shifted into a local clock, so shifting a remote
 * place's by the device's offset rotates the whole lit arc — fifteen
 * degrees of dial for every hour of error. New Zealand read on an
 * American clock is sixteen hours out and lands its daylight across
 * the bottom of the ring, very nearly upside down.
 *
 * An unknown zone id falls back rather than throwing: a dial on the
 * wrong clock is a bad dial, and a dial that crashes is no dial.
 */
internal fun zoneFor(id: String?): TimeZone =
    id?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.currentSystemDefault()

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

internal fun dayLabel(t: LocalDateTime): String {
    val d = t.dayOfMonth
    val suffix = when {
        d % 100 in 11..13 -> "th"
        d % 10 == 1 -> "st"
        d % 10 == 2 -> "nd"
        d % 10 == 3 -> "rd"
        else -> "th"
    }
    return "${MONTHS[t.monthNumber - 1]} $d$suffix"
}

/**
 * Today's calendar, pending the feature itself.
 *
 * Says which of the two it is waiting on. "Coming soon" on a panel
 * that is waiting on a design and one waiting on a feature would hide
 * the difference between a week and a quarter.
 */
@Composable
private fun CalendarPanel(range: CalendarRange) {
    Panel("Today", Icons.Filled.CalendarToday) {
        Box(
            Modifier.fillMaxWidth().height(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Calendar is not built yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The range is settable ahead of the feature, so the
                // preference is waiting when there is finally something
                // to apply it to. Naming the chosen one is the honest
                // placeholder: it shows the setting took rather than
                // implying the panel works.
                Text(
                    "Set to show: ${range.label}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Who is up to what.
 *
 * Pinned people first and always, whether or not they have said
 * anything lately, then everyone else newest first. The pin is the
 * point of the widget: a feed sorted purely by recency buries the
 * three people somebody actually watches under whoever typed last.
 */
@Composable
private fun StatusPanel(
    statuses: List<io.nisfeb.talon.data.ContactEntity>,
    contacts: ContactMap,
    ourShip: String,
    widget: HomeWidget,
    onOpenContact: (String) -> Unit,
    onAll: () -> Unit,
) {
    val rows = remember(statuses, widget.pinned, widget.count, ourShip) {
        statusRows(statuses, widget.pinned, widget.count, ourShip)
    }
    Panel("Statuses", Icons.Filled.Star, "All" to onAll) {
        if (rows.isEmpty()) {
            Empty("No statuses yet.")
        } else {
            rows.forEach { (contact, pinned) ->
                QuickRow(
                    title = contacts.displayName(contact.ship),
                    line = contact.status?.takeIf { it.isNotBlank() } ?: "No status",
                    at = contact.statusUpdatedMs ?: 0L,
                    strong = pinned,
                    onClick = { onOpenContact(contact.ship) },
                )
            }
        }
    }
}

/**
 * The status rows to show, pinned people first.
 *
 * A pinned person appears whether or not they have said anything,
 * because the silence is part of what somebody pinned them for.
 * Everybody else has to have a status to earn a row.
 */
internal fun statusRows(
    statuses: List<io.nisfeb.talon.data.ContactEntity>,
    pinned: List<String>,
    count: Int,
    ourShip: String,
): List<Pair<io.nisfeb.talon.data.ContactEntity, Boolean>> {
    val others = statuses.filter { it.ship != ourShip }
    val byShip = others.associateBy { it.ship }
    val pins = pinned.mapNotNull { byShip[it] }.map { it to true }
    val pinnedShips = pins.mapTo(mutableSetOf()) { it.first.ship }
    val rest = others
        .filter { it.ship !in pinnedShips && !it.status.isNullOrBlank() }
        .map { it to false }
    // Pins are never crowded out: they take their places first and the
    // rest fill whatever is left.
    return (pins + rest).take(count.coerceAtLeast(pins.size))
}
