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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import io.nisfeb.talon.ui.HomePlace
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
    /** Today's weather for a place. Null leaves the dial showing the
     *  day and saying nothing about the temperature. */
    weatherFor: io.nisfeb.talon.ui.WeatherLookup? = null,
    /** Ask the device where it is. Null where it cannot say, which is
     *  desktop and a refused permission alike. */
    onUseDeviceLocation: (suspend () -> Result<HomePlace>)? = null,
    /** Turn a typed place into coordinates, or null for coordinates only. */
    placeLookup: io.nisfeb.talon.ui.PlaceLookup? = null,
    onPlacePicked: (HomePlace) -> Unit = {},
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

    val recent = remember(latest) { latest.take(QUICK) }
    val unreadBy = remember(unreads) { unreads.associateBy { it.whom } }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoColumns = maxWidth >= 820.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                greeting(ourShip, contacts),
                style = MaterialTheme.typography.headlineSmall
                    .copy(fontWeight = FontWeight.SemiBold),
            )

            if (twoColumns) {
                // The dial takes its own column. It is the thing people
                // leave this page open for, and stacking the three lists
                // beside it keeps it whole rather than squaring it off
                // against a panel of five rows.
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.weight(1f)) {
                        ClockWeatherPanel(
                            place, weatherFor, onUseDeviceLocation, placeLookup, onPlacePicked,
                        )
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ChatsPanel(recent, unreadBy, contacts, ourShip, onOpenConversation, onOpenChats)
                        MailPanel(mail, contacts, onOpenMailThread, onOpenMail)
                        CalendarPanel()
                    }
                }
            } else {
                ClockWeatherPanel(
                    place, weatherFor, onUseDeviceLocation, placeLookup, onPlacePicked,
                )
                ChatsPanel(recent, unreadBy, contacts, ourShip, onOpenConversation, onOpenChats)
                MailPanel(mail, contacts, onOpenMailThread, onOpenMail)
                CalendarPanel()
            }
        }
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

            else -> page.threads.take(QUICK).forEach { row ->
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
    weatherFor: io.nisfeb.talon.ui.WeatherLookup?,
    onUseDeviceLocation: (suspend () -> Result<HomePlace>)?,
    placeLookup: io.nisfeb.talon.ui.PlaceLookup?,
    onPlacePicked: (HomePlace) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var weather by remember { mutableStateOf<SkyClock.Sky?>(null) }
    var nowMsState by remember { mutableStateOf(nowMs()) }

    // Refetched when the place changes and then every half hour, which
    // is finer than the forecast grid updates. A page somebody leaves
    // open all day should not be a page that talks to a server all day.
    LaunchedEffect(place, weatherFor) {
        val look = weatherFor
        if (place == null || look == null) {
            weather = null
            return@LaunchedEffect
        }
        while (true) {
            look(place).onSuccess { weather = it }
            delay(30 * 60_000L)
        }
    }

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
                fahrenheit = true,
                twentyFourHour = false,
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
    val zone = TimeZone.currentSystemDefault()
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
private fun CalendarPanel() {
    Panel("Today", Icons.Filled.CalendarToday) {
        Box(
            Modifier.fillMaxWidth().height(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Calendar is not built yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
