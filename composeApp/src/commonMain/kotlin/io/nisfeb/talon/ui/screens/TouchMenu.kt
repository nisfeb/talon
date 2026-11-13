package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.ReactionPalette

/*
 * A message's menu on a phone. It opened under the whole message, so on
 * a long post the reactions were a hand's width from the finger, and it
 * listed fourteen small rows (sneagan, 2026-11-07). Here it opens at the
 * finger, with the reactions on the side nearest it, and the rarer
 * actions are a level down, which leaves room for bigger targets.
 * Desktop keeps its compact dropdown.
 */

/** Whether a tap on a message opens its menu, and so whether it opens at the finger. A test turns it on. */
internal val LocalTapOpensMenu = staticCompositionLocalOf { io.nisfeb.talon.ui.isTapToOpenMenuSupported }

/** Which side of the finger the touch menu opened on; null in desktop's dropdown. */
internal val LocalTouchMenuSide = staticCompositionLocalOf<TouchMenuSide?> { null }

internal enum class TouchMenuSide { Above, Below }

internal sealed interface TouchEntry

/** One of a menu's actions; [danger] draws it in the error colour. */
internal class MenuAct(val label: String, val onClick: () -> Unit, val danger: Boolean = false) : TouchEntry

/** Actions a level down, behind one row. */
internal class MenuGroup(val label: String, val items: List<MenuAct>) : TouchEntry

/** A group of one is just its item: a level for one choice is a wasted tap. */
internal fun group(label: String, items: List<MenuAct>): List<TouchEntry> =
    if (items.size > 1) listOf(MenuGroup(label, items)) else items

/**
 * The quick reactions: the most used first, the palette filling the
 * rest, each once. A used ❤️ and the palette's :heart: are one.
 */
internal fun suggestedReactions(used: List<String>, count: Int): List<String> {
    val seen = mutableSetOf<String>()
    return (used + ReactionPalette.picker.map { it.first }).filter { seen.add(ReactionPalette.normalize(it)) }.take(count)
}

@Composable
internal fun rememberSuggestedReactions(db: AppDatabase, count: Int): List<String> {
    val topUsage by remember { db.reactionUsage().streamTop(8) }.collectAsState(initial = emptyList())
    return remember(topUsage, count) { suggestedReactions(topUsage.map { it.shortcode }, count) }
}

/** Where the last press on a row landed, so its menu opens there. */
internal class TapPoint {
    var coords: LayoutCoordinates? = null
    var local: Offset = Offset.Zero
    fun inWindow(): Offset = coords?.takeIf { it.isAttached }?.localToWindow(local) ?: Offset.Zero
}

/**
 * Notes each press on the row without taking it: the words, links and
 * chips under the finger still get their taps. Both see the same
 * coordinates, so they stay side by side in the chain.
 */
internal fun Modifier.tapPoint(p: TapPoint): Modifier = this
    .onGloballyPositioned { p.coords = it }
    .pointerInput(p) {
        awaitEachGesture {
            p.local = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
        }
    }

/** Above a tap in the lower half, below one in the upper, so the menu has the larger room. */
internal fun opensAbove(tapY: Float, windowHeight: Int): Boolean = tapY > windowHeight / 2f

/**
 * Where the menu goes: its near edge [gap] from the finger, centred on
 * it, and kept [marginX] and [marginY] inside the window. One too tall
 * for its side slides back over the finger rather than flipping, so the
 * reactions stay the nearest thing.
 */
internal fun placeTouchMenu(
    tap: IntOffset, above: Boolean, window: IntSize, popup: IntSize,
    gap: Int, marginX: Int, marginY: Int,
): IntOffset {
    val x = (tap.x - popup.width / 2).coerceIn(marginX, maxOf(marginX, window.width - marginX - popup.width))
    val y = (if (above) tap.y - gap - popup.height else tap.y + gap)
        .coerceIn(marginY, maxOf(marginY, window.height - marginY - popup.height))
    return IntOffset(x, y)
}

/** Wide enough for six reactions and the browser at 44 dp each, and narrower than any phone. */
private val TouchMenuWidth = 320.dp

/** The menu at the finger, for [tap]'s row. [content] reads its side from [LocalTouchMenuSide]. */
@Composable
internal fun TouchMenuPopup(tap: TapPoint, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val at = remember { tap.inWindow() }
    val side = if (opensAbove(at.y, LocalWindowInfo.current.containerSize.height)) TouchMenuSide.Above else TouchMenuSide.Below
    val density = LocalDensity.current
    val position = remember(at, side, density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset = with(density) {
                // The tap is in window coordinates already: the anchor is not needed.
                placeTouchMenu(
                    IntOffset(at.x.toInt(), at.y.toInt()), side == TouchMenuSide.Above, windowSize, popupContentSize,
                    gap = 8.dp.roundToPx(), marginX = 8.dp.roundToPx(), marginY = 48.dp.roundToPx(),
                )
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        CompositionLocalProvider(LocalTouchMenuSide provides side) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 6.dp,
            ) { content() }
        }
    }
}

/**
 * The touch menu's body: [reactions] on the finger's side of [entries],
 * and a group opened in place, with a row back.
 */
@Composable
internal fun TouchMenu(side: TouchMenuSide, entries: List<TouchEntry>, reactions: (@Composable () -> Unit)?) {
    var open by remember { mutableStateOf<MenuGroup?>(null) }
    Column(Modifier.width(TouchMenuWidth).padding(vertical = 4.dp)) {
        val g = open
        if (g != null) {
            TouchRow(g.label, onClick = { open = null }, lead = {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
            })
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            g.items.forEach { TouchRow(it.label, it.onClick, it.danger) }
            return@Column
        }
        if (side == TouchMenuSide.Below && reactions != null) {
            reactions()
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
        }
        entries.forEach { e ->
            when (e) {
                is MenuAct -> TouchRow(e.label, e.onClick, e.danger)
                is MenuGroup -> TouchRow(e.label, onClick = { open = e }, trail = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
                })
            }
        }
        if (side == TouchMenuSide.Above && reactions != null) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            reactions()
        }
    }
}

@Composable
private fun TouchRow(
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    lead: (@Composable () -> Unit)? = null,
    trail: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        lead?.invoke()
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current,
            modifier = Modifier.weight(1f),
        )
        trail?.invoke()
    }
}

/** Six reactions and the emoji browser, each a 44 dp target. */
@Composable
internal fun TouchReactions(db: AppDatabase, onPick: (String) -> Unit, onBrowse: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        rememberSuggestedReactions(db, 6).forEach { code ->
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { onPick(code) },
                contentAlignment = Alignment.Center,
            ) {
                Text(ReactionPalette.display(code), fontFamily = io.nisfeb.talon.ui.EmojiFontFamily, fontSize = 26.sp)
            }
        }
        io.nisfeb.talon.ui.IconButton(tip = "Browse emojis", onClick = onBrowse, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.Search, contentDescription = "Browse emojis", modifier = Modifier.size(24.dp))
        }
    }
}
