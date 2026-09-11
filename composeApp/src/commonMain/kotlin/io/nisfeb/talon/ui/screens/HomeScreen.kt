package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.mail.MailAvailability
import io.nisfeb.talon.mail.MailRepo
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
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
import io.nisfeb.talon.ui.resizedRows
import io.nisfeb.talon.ui.resizedSpan
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

/**
 * The home page.
 *
 * Widgets, each a door rather than a destination: the chats that moved
 * most recently, the newest mail, the time and weather, today's
 * calendar, who is up to what. None tries to be the surface it points
 * at — a handful of rows and a way through is the whole job.
 *
 * Which of them appear, and how many rows each carries, is set in
 * Settings under Home. Where they sit and how big they are is set
 * here: a long press on any of them starts arranging, after which
 * they can be dragged about and pulled by their corners.
 *
 * The calendar is still a placeholder. It is drawn as what it is, so
 * the page reads as a page rather than as most of one, and so the seam
 * it drops into is obvious.
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
    // Where each widget ended up on screen, so a drag can work out what
    // it is being dropped onto. Filled as they are laid out.
    val bounds = remember { mutableStateMapOf<HomeWidgetKind, Rect>() }
    var dragging by remember { mutableStateOf<HomeWidgetKind?>(null) }
    var dragBy by remember { mutableStateOf(Offset.Zero) }

    // Gesture modifiers capture their lambdas once and keep them. Keyed
    // on the layout instead, every reorder would restart the gesture
    // and drop the drag half way through; captured plainly, they would
    // go on reading the layout as it was when the drag began. Held
    // through rememberUpdatedState they stay current and the gesture
    // stays alive.
    val dropOver by rememberUpdatedState<(HomeWidgetKind, HomeWidgetKind) -> Unit> { moved, over ->
        onLayoutChanged(layout.movedTo(moved, over))
    }
    val resizeTo by rememberUpdatedState<(HomeWidget) -> Unit> { w ->
        onLayoutChanged(layout.with(w))
    }
    val isShown by rememberUpdatedState<(HomeWidgetKind) -> Boolean> { k -> layout[k].visible }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Two columns where there is room, one where there is not. A
        // widget set to full width on a desktop is still full width on
        // a phone; it just has nothing to sit beside.
        val columns = if (maxWidth >= 820.dp) HOME_COLUMNS else 1
        // Loose while arranging, so a widget can be dropped somewhere
        // it does not quite fit. Nothing about that is stored, so the
        // strict pack reflows it the moment arranging stops.
        val gridRows = remember(layout, columns, editing) {
            packRows(layout.shown, columns, loose = editing)
        }

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
                // No Arrange button. A long press on any widget starts
                // it, which is the gesture people already try on a
                // page of tiles; a permanent button for a mode nobody
                // is in most of the time is a worse trade.
                if (editing) {
                    TextButton(onClick = { editing = false }) { Text("Done") }
                }
            }

            if (layout.shown.isEmpty()) {
                Text(
                    "Nothing on the home page. Settings, under Home, has the list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val guideBand = MaterialTheme.colorScheme.primary.copy(alpha = 0.030f)
            val guideLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)

            gridRows.forEach { gridRow ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                    modifier = if (!editing) Modifier else Modifier.drawBehind {
                        // Drawn per row rather than once behind the
                        // page, because that is the only place both
                        // axes are true: the columns are this row's own
                        // widths, and a widget's height is counted from
                        // the top of the row it sits on. A lattice over
                        // the whole page would line up for the first
                        // row and lie about every one after it.
                        val gap = GRID_GAP.toPx()
                        val colWidth = (size.width - gap * (columns - 1)) / columns
                        for (i in 0 until columns) {
                            drawRect(
                                color = guideBand,
                                topLeft = Offset(i * (colWidth + gap), 0f),
                                size = Size(colWidth, size.height),
                            )
                        }
                        val unit = HOME_ROW_UNIT.toPx()
                        var y = unit
                        while (y < size.height) {
                            drawLine(
                                color = guideLine,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1f,
                            )
                            y += unit
                        }
                    },
                ) {
                    gridRow.forEach { widget ->
                        // Keyed, so Compose keeps each widget's state
                        // with the widget rather than with the position
                        // in the row. Without it a reorder handed one
                        // widget's half-finished drag to whichever one
                        // slid into its place, which is how two of them
                        // ended up drawn on top of each other.
                        key(widget.kind) {
                        val held = dragging == widget.kind
                        // A drag that is interrupted by the widget
                        // leaving the page never gets its onDragEnd, so
                        // it would stay lifted and offset forever.
                        DisposableEffect(widget.kind) {
                            onDispose {
                                if (dragging == widget.kind) {
                                    dragging = null
                                    dragBy = Offset.Zero
                                }
                            }
                        }
                        Box(
                            Modifier
                                .weight(widget.span.coerceIn(1, columns).toFloat())
                                // A minimum rather than a fixed height:
                                // a list told to show ten rows in one
                                // row-unit should outgrow its box, not
                                // have the last four clipped off.
                                .heightIn(min = HOME_ROW_UNIT * widget.rows)
                                // The one being carried draws over the
                                // rest and follows the finger.
                                .zIndex(if (held) 1f else 0f)
                                .graphicsLayer {
                                    if (held) {
                                        translationX = dragBy.x
                                        translationY = dragBy.y
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                }
                                .onGloballyPositioned { bounds[widget.kind] = it.boundsInWindow() }
                                // Catches the panel's own background,
                                // its heading and the dial. The rows
                                // inside carry their own long press,
                                // because a plain clickable fires its
                                // click on release however long it was
                                // held, which would arrange the page
                                // and then navigate away from it.
                                .then(
                                    if (editing) Modifier else Modifier.pointerInput(Unit) {
                                        detectTapGestures(onLongPress = { editing = true })
                                    }
                                )
                                .then(
                                    if (!editing) Modifier else Modifier.border(
                                        width = 1.dp,
                                        color = if (held) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                ),
                        ) {
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
                                onLongPress = { editing = true },
                            )

                            if (editing) {
                                // The move surface sits over the whole
                                // widget, and the grips sit over that.
                                // Overlapping siblings hit-test topmost
                                // first, so a grip takes the pointer
                                // outright rather than racing the move
                                // gesture for it — which is what had
                                // them doing nothing at all.
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .pointerInput(widget.kind) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    dragging = widget.kind
                                                    dragBy = Offset.Zero
                                                },
                                                onDragEnd = { dragging = null; dragBy = Offset.Zero },
                                                onDragCancel = { dragging = null; dragBy = Offset.Zero },
                                            ) { change, delta ->
                                                change.consume()
                                                dragBy += delta
                                                // Hit-tested from the
                                                // middle of what is
                                                // being carried rather
                                                // than from the finger,
                                                // so picking a widget
                                                // up by a corner still
                                                // drops it where it
                                                // looks like it is.
                                                val home = bounds[widget.kind]
                                                    ?: return@detectDragGestures
                                                val at = home.center + dragBy
                                                val over = bounds.entries.firstOrNull { (k, r) ->
                                                    k != widget.kind && r.contains(at) && isShown(k)
                                                }?.key
                                                if (over != null) {
                                                    dropOver(widget.kind, over)
                                                    // Just put where the
                                                    // finger is, so the
                                                    // offset starts from
                                                    // there again.
                                                    dragBy = Offset.Zero
                                                }
                                            }
                                        },
                                )
                                if (!held) {
                                    ResizeHandles(
                                        widget = widget,
                                        columns = columns,
                                        // Measured, not assumed: the
                                        // column is whatever width the
                                        // window gave this widget over
                                        // the columns it spans.
                                        cellWidthPx = (bounds[widget.kind]?.width ?: 0f) /
                                            widget.span.coerceAtLeast(1),
                                        onResize = resizeTo,
                                        onRemove = { resizeTo(widget.copy(visible = false)) },
                                    )
                                }
                            }
                        }
                        }
                    }
                    // The columns nobody claimed.
                    //
                    // Without this a widget alone on a row stretched to
                    // fill it, so widening from six columns to seven
                    // jumped it from half the page to all of it — which
                    // is not a finer grid, it is the same two sizes
                    // with more numbers.
                    val spare = io.nisfeb.talon.ui.rowSpare(gridRow, columns)
                    if (spare > 0) Spacer(Modifier.weight(spare.toFloat()))
                }
            }
        }
    }
}

/**
 * One grid row's worth of height.
 *
 * Small, because it is the size of the step rather than the size of a
 * widget: at 168dp the only heights on offer were 168, 336 and 504,
 * and nothing anybody wanted sat on one of them.
 */
private val HOME_ROW_UNIT = io.nisfeb.talon.ui.HOME_ROW_UNIT_DP.dp

/** The space between two widgets. Named because the grid guides have
 *  to subtract exactly the same gaps the layout adds. */
private val GRID_GAP = 14.dp

/** The most the clock panel spends on padding and its location line,
 *  above and below the dial itself. */
private val DIAL_CHROME = 34.dp

/** And the most of a short widget it may take. A flat allowance ate
 *  nearly the whole of the smallest cell, which is what left the
 *  smallest dial a dot and made the next one look twice its size. */
private const val DIAL_CHROME_SHARE = 0.22f

/**
 * How big the dial may be in a clock widget [rows] units tall.
 *
 * The dial is square, so its width is what sets the widget's height.
 * Left to fill whatever width it had, it ignored the height entirely
 * and the clock drew the same at two row units as at six.
 *
 * No floor and no ceiling. Both were fictions: the dial is also bound
 * by the width the window gives it, which slides continuously as the
 * window is dragged, so it was already being drawn at sizes arranging
 * refused to offer. The readout inside thins out to suit whatever size
 * it ends up at, which is what a floor was standing in for.
 */
internal fun dialSizeFor(rows: Int): androidx.compose.ui.unit.Dp {
    val cell = HOME_ROW_UNIT * rows
    // Proportional while the cell is short, fixed once there is room.
    // Taken flat it was most of the smallest cell, so the dial began
    // near nothing and one row unit of drag nearly doubled it: a step
    // of ninety per cent where the cell itself grew by thirty.
    val chrome = minOf(DIAL_CHROME, cell * DIAL_CHROME_SHARE)
    return (cell - chrome).coerceAtLeast(0.dp)
}

/** How big a handle has to be to be hit with a thumb. */
private val HANDLE = 26.dp

/**
 * The grips on a widget's edges while the page is being arranged.
 *
 * A right edge for width, a bottom edge for height, a corner for both,
 * and a cross to take the thing off the page. Dragging the widget
 * itself moves it; these only resize, which is why they sit on the
 * edges where nothing else wants the pointer.
 *
 * Each snaps to whole grid units. The grid is two columns by three
 * rows, so a handle that followed the finger continuously would only
 * ever be settling back onto one of a handful of positions.
 */
@Composable
private fun BoxScope.ResizeHandles(
    widget: HomeWidget,
    columns: Int,
    cellWidthPx: Float,
    onResize: (HomeWidget) -> Unit,
    onRemove: () -> Unit,
) {
    val rowUnitPx = with(LocalDensity.current) { HOME_ROW_UNIT.toPx() }
    val grip = MaterialTheme.colorScheme.primary

    // The size the widget was when this drag began.
    //
    // Load-bearing. The running total is measured from where the
    // pointer went down, so it has to be added to the size the widget
    // had at that moment. Adding it to the *current* size instead
    // compounds: cross the first snap point and the widget grows a
    // column, which becomes the new base, so the same total then reads
    // as another column, and another. A mouse moved one column wide
    // sent the widget clear across the grid.
    var startSpan by remember { mutableStateOf(widget.span) }
    var startRows by remember { mutableStateOf(widget.rows) }
    var startCell by remember { mutableStateOf(cellWidthPx) }
    fun freeze() {
        startSpan = widget.span
        startRows = widget.rows
        // Frozen too, because it is derived from the widget's own width
        // and would otherwise shift under the drag that is changing it.
        startCell = cellWidthPx
    }

    // Width. Pointless where there is only one column to have.
    if (columns > 1) {
        Grip(
            Modifier.align(Alignment.CenterEnd),
            grip,
            label = "Width of ${title(widget.kind)}",
            onStart = ::freeze,
        ) { total ->
            onResize(widget.copy(span = resizedSpan(startSpan, total.x, startCell, columns)))
        }
    }
    Grip(
        Modifier.align(Alignment.BottomCenter),
        grip,
        label = "Height of ${title(widget.kind)}",
        onStart = ::freeze,
    ) { total ->
        onResize(widget.copy(rows = resizedRows(startRows, total.y, rowUnitPx)))
    }
    Grip(
        Modifier.align(Alignment.BottomEnd),
        grip,
        corner = true,
        label = "Size of ${title(widget.kind)}",
        onStart = ::freeze,
    ) { total ->
        onResize(
            widget.copy(
                span = resizedSpan(startSpan, total.x, startCell, columns),
                rows = resizedRows(startRows, total.y, rowUnitPx),
            ),
        )
    }

    IconButton(
        onClick = onRemove,
        modifier = Modifier.align(Alignment.TopEnd).size(HANDLE),
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = "Take ${title(widget.kind)} off the home page",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One grip.
 *
 * The drag is reported as a running total from where the pointer went
 * down, and [onStart] is where the caller notes the size to add it to.
 * Both halves matter: a per-frame delta would ratchet the widget
 * across the grid on a few pixels of jitter, and a running total added
 * to a size that is itself changing compounds every snap.
 *
 * [onDrag] is held through rememberUpdatedState because pointerInput
 * keeps whatever lambda it was given when the node was made.
 */
@Composable
private fun Grip(
    modifier: Modifier,
    color: androidx.compose.ui.graphics.Color,
    label: String,
    corner: Boolean = false,
    onStart: () -> Unit = {},
    onDrag: (Offset) -> Unit,
) {
    val current by rememberUpdatedState(onDrag)
    val began by rememberUpdatedState(onStart)
    Box(
        modifier
            .size(HANDLE)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero; began() },
                    onDragEnd = { total = Offset.Zero },
                    onDragCancel = { total = Offset.Zero },
                ) { change, delta ->
                    change.consume()
                    total += delta
                    current(total)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (corner) 14.dp else 10.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
    }
}

/** What each widget is called, wherever one needs naming. */
fun title(kind: HomeWidgetKind): String = when (kind) {
    HomeWidgetKind.CLOCK -> "Clock and weather"
    HomeWidgetKind.MESSAGES -> "Chat"
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
    onLongPress: () -> Unit,
) {
    when (widget.kind) {
        HomeWidgetKind.CLOCK -> ClockWeatherPanel(
            dialSizeFor(widget.rows),
            place, weather, onUseDeviceLocation, placeLookup, onPlacePicked,
            fahrenheit, twentyFourHour,
        )
        HomeWidgetKind.MESSAGES -> ChatsPanel(
            recent.take(widget.count), unreadBy, contacts, ourShip,
            onOpenConversation, onOpenChats, onLongPress,
        )
        HomeWidgetKind.MAIL -> MailPanel(
            mail, contacts, widget.count, onOpenMailThread, onOpenMail, onLongPress,
        )
        HomeWidgetKind.CALENDAR -> CalendarPanel(widget.calendarRange)
        HomeWidgetKind.STATUS -> StatusPanel(
            statuses, contacts, ourShip, widget, onOpenContact, onOpenStatuses, onLongPress,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickRow(
    title: String,
    line: String,
    at: Long,
    strong: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // Combined rather than a long-press detector over the top:
            // a plain clickable fires its click on release no matter
            // how long it was held, so a long press on a row would
            // arrange the page and then walk off it.
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
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
    onLongPress: () -> Unit,
) {
    Panel(title(HomeWidgetKind.MESSAGES), Icons.AutoMirrored.Filled.Chat, "All chats" to onAll) {
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
                    onLongPress = onLongPress,
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
    onLongPress: () -> Unit,
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
                    onLongPress = onLongPress,
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
    maxDial: androidx.compose.ui.unit.Dp,
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
            Modifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SkyClockDial(
                sky = sky,
                fahrenheit = fahrenheit,
                twentyFourHour = twentyFourHour,
                maxSize = maxDial,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            // A line of text rather than a button. A Material button
            // carries a forty-eight dip touch target, and on the
            // shortest clock that was more of the widget than the dial
            // itself got.
            Text(
                place?.label ?: "Set a location",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable { picking = true }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
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
    onLongPress: () -> Unit,
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
                    onLongPress = onLongPress,
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
