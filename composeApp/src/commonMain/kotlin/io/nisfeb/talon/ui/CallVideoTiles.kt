package io.nisfeb.talon.ui

import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.focusable
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PeerLink
import io.nisfeb.talon.ui.icons.TalonIcons

/**
 * The conference pictures, shared by the mobile full-screen view and
 * the desktop expanded bar so both show the same video.
 *
 * Speaker view: one picture is front and centre, whoever is pinned,
 * else whoever spoke last, else the first camera on. Everyone else's
 * camera is a row of small tiles underneath; tapping one pins it,
 * tapping the pinned one lets go. [gallery]: every camera the same
 * size, as large as fills the space (sneagan, 2026-11-03). Our own
 * camera is front and centre only when nobody else's is on.
 *
 * Each tile is its picture's shape, so the ring round a speaker borders
 * the picture, not the space it sits in.
 */
@Composable
internal fun PartyVideoGrid(
    members: List<PartyMember>,
    selfShip: String,
    nameFor: (String) -> String,
    localVideoLink: PeerLink?,
    videoLinkFor: (String) -> PeerLink?,
    videoOnShips: Set<String>,
    focusedShip: String?,
    onFocusVideo: (String?) -> Unit,
    /** The meeting view's Fill the window, on the big picture; null hides it. */
    onFill: ((String) -> Unit)? = null,
    gallery: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Who spoke last, kept until somebody else does: the level flag
    // flickers with every pause, and a picture that swapped on each
    // one would be unwatchable.
    var lastSpeaker by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(members) {
        members.firstOrNull { it.ship != selfShip && it.speaking && it.ship in videoOnShips }
            ?.let { lastSpeaker = it.ship }
    }
    val onCamera = members.filter { it.ship in videoOnShips }
    val featured = featuredVideo(onCamera.map { it.ship }, selfShip, focusedShip, lastSpeaker)
        ?: return
    // Each picture's shape, as its frames report it; assumed until then.
    val aspects = remember { mutableStateMapOf<String, Float>() }
    fun aspectOf(ship: String) = aspects[ship] ?: ASSUMED_ASPECT
    @Composable
    fun tile(m: PartyMember, focused: Boolean, onTap: (() -> Unit)?, fill: Boolean, shape: Modifier) {
        val isSelf = m.ship == selfShip
        VideoTile(
            member = m,
            isSelf = isSelf,
            nameFor = nameFor,
            link = if (isSelf) localVideoLink else videoLinkFor(m.ship),
            videoOn = true,
            focused = focused,
            onTap = onTap.takeUnless { isSelf },
            onFill = if (fill) onFill?.let { f -> { f(m.ship) } } else null,
            onFrameAspect = { aspects[m.ship] = it },
            modifier = shape,
        )
    }
    if (gallery) {
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val (cols, rows) = galleryShape(onCamera.size, maxWidth, maxHeight, TILE_GAP)
            val cellWidth = (maxWidth - TILE_GAP * (cols - 1)) / cols
            val cellHeight = (maxHeight - TILE_GAP * (rows - 1)) / rows
            Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                onCamera.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                        row.forEach { m ->
                            Box(Modifier.size(cellWidth, cellHeight), contentAlignment = Alignment.Center) {
                                tile(m, focused = false, onTap = null, fill = true, Modifier.size(fitVideoPane(cellWidth, aspectOf(m.ship), cellHeight)))
                            }
                        }
                    }
                }
            }
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        onCamera.firstOrNull { it.ship == featured }?.let { m ->
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                tile(
                    m,
                    focused = m.ship == focusedShip,
                    onTap = { onFocusVideo(if (m.ship == focusedShip) null else m.ship) },
                    fill = true,
                    Modifier.size(fitVideoPane(maxWidth, aspectOf(m.ship), maxHeight)),
                )
            }
        }
        val rest = onCamera.filter { it.ship != featured }
        if (rest.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                items(rest, key = { it.ship }) { m ->
                    tile(m, focused = false, onTap = { onFocusVideo(m.ship) }, fill = false, Modifier.height(SMALL_TILE).width(SMALL_TILE * aspectOf(m.ship)))
                }
            }
        }
    }
}

/** A picture's shape until its frames say (iOS's never do). */
private const val ASSUMED_ASPECT = 16f / 9f
private val TILE_GAP = 6.dp
/** A small tile's height in speaker view; its width follows the picture. */
private val SMALL_TILE = 96.dp

/**
 * Columns and rows for [count] equal tiles of [aspect] (width over
 * height) in [width] by [height], [gap] apart: whichever makes the
 * tiles largest.
 */
internal fun galleryShape(count: Int, width: Dp, height: Dp, gap: Dp, aspect: Float = ASSUMED_ASPECT): Pair<Int, Int> {
    if (count <= 1) return 1 to 1
    return (1..count).map { cols ->
        val rows = (count + cols - 1) / cols
        val tileWidth = minOf(
            (width.value - gap.value * (cols - 1)) / cols,
            (height.value - gap.value * (rows - 1)) / rows * aspect,
        )
        Triple(cols, rows, tileWidth)
    }.maxBy { it.third }.let { (cols, rows, _) -> cols to rows }
}

/**
 * Which camera goes front and centre: the pinned one, else the last
 * to speak, else the first that is not our own, else our own. Null
 * when nobody has a camera on.
 */
internal fun featuredVideo(
    onCamera: List<String>,
    selfShip: String,
    focusedShip: String?,
    lastSpeaker: String?,
): String? =
    focusedShip?.takeIf { it in onCamera }
        ?: lastSpeaker?.takeIf { it in onCamera }
        ?: onCamera.firstOrNull { it != selfShip }
        ?: onCamera.firstOrNull()

/** One conference tile: camera when on, avatar otherwise, with a name,
 *  mic-off marker, and a ring while speaking or pinned. */
@Composable
internal fun VideoTile(
    member: PartyMember,
    isSelf: Boolean,
    nameFor: (String) -> String,
    link: PeerLink?,
    videoOn: Boolean = false,
    focused: Boolean = false,
    onTap: (() -> Unit)? = null,
    onFill: (() -> Unit)? = null,
    /** The picture's shape whenever its frames change it. */
    onFrameAspect: ((Float) -> Unit)? = null,
    /** The caller's shape; the renderers fit the picture into it. */
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1f),
) {
    // Camera on/off is signalled explicitly (videoOn), not inferred from
    // the track: a down link always carries an empty video transceiver,
    // so track presence would light every tile up as "on".
    val on = videoOn
    Box(
        modifier
            .testTag("video-tile")
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (link != null && on) {
            VideoSurface(link, local = isSelf, Modifier.fillMaxSize(), onFrameAspect = onFrameAspect)
        } else {
            Avatar(label = nameFor(member.ship), url = null, size = 56.dp)
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (member.muted || member.mutedByAdmin) {
                Icon(
                    TalonIcons.MicOff,
                    contentDescription = "Muted",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                nameFor(member.ship) + if (isSelf) " (you)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onFill != null) {
            IconButton(
                tip = "Fill the window",
                onClick = onFill,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Icon(TalonIcons.OpenInFull, contentDescription = "Fill the window", tint = Color.White)
            }
        }
        val ring = when {
            focused -> MaterialTheme.colorScheme.tertiary
            member.speaking -> MaterialTheme.colorScheme.primary
            else -> null
        }
        if (ring != null) {
            Box(
                Modifier.matchParentSize()
                    .border(if (focused) 3.dp else 2.dp, ring, RoundedCornerShape(10.dp)),
            )
        }
    }
}

/** How long the call's controls stay over a filled picture once the mouse stops. */
internal const val CONTROLS_FADE_MS = 2_500L

/**
 * One camera or shared screen over the whole window: the meeting view's
 * Fill the window. The call's [controls] and an X show while the mouse
 * moves and fade [CONTROLS_FADE_MS] after it stops, but not while it
 * rests on the controls, so a menu opened from them stays open. Escape
 * or the X goes back to the meeting.
 */
@Composable
internal fun FilledVideo(
    member: PartyMember,
    isSelf: Boolean,
    nameFor: (String) -> String,
    link: PeerLink?,
    onExit: () -> Unit,
    controls: @Composable (Modifier) -> Unit,
) {
    var moves by remember { mutableStateOf(0) }
    var onBar by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(moves, onBar) {
        shown = true
        if (!onBar) {
            delay(CONTROLS_FADE_MS)
            shown = false
        }
    }
    // Where the bar is, to tell whether the mouse last moved over it.
    var origin by remember { mutableStateOf(Offset.Zero) }
    var bar by remember { mutableStateOf(Rect.Zero) }
    var top by remember { mutableStateOf(Rect.Zero) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("filled-video")
            .onGloballyPositioned { origin = it.positionInWindow() }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.Escape -> { onExit(); true }
                    // Any other key brings the controls back, for a keyboard.
                    else -> { moves++; false }
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type != PointerEventType.Move && e.type != PointerEventType.Press && e.type != PointerEventType.Enter) continue
                        // The bar keeps its last bounds while hidden, so a pointer
                        // that comes to rest on it keeps the controls up.
                        onBar = e.changes.any { (origin + it.position).let { p -> bar.contains(p) || top.contains(p) } }
                        moves++
                        // A menu from the bar takes focus; a press brings Escape back here.
                        if (e.type == PointerEventType.Press) runCatching { focus.requestFocus() }
                    }
                }
            },
    ) {
        if (link != null) {
            VideoSurface(link, local = isSelf, Modifier.fillMaxSize())
        } else {
            Box(Modifier.align(Alignment.Center)) { Avatar(label = nameFor(member.ship), url = null, size = 96.dp) }
        }
        AnimatedVisibility(shown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth()
                        .onGloballyPositioned { top = it.boundsInWindow() }
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        nameFor(member.ship) + if (isSelf) " (you)" else "",
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(tip = "Back to the meeting", onClick = onExit) {
                        Icon(Icons.Filled.Close, contentDescription = "Back to the meeting", tint = Color.White)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                        .onGloballyPositioned { bar = it.boundsInWindow() },
                ) {
                    controls(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
    }
}
