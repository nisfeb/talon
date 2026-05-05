package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * List/detail layout host. Below [ExpandedThreshold] it renders the
 * stack-style "show whichever has content" behaviour Talon used
 * pre-0.9 (mobile + narrow desktop windows). At/above the threshold
 * it places [list] on the left and [detail] (or [EmptyChatPane])
 * on the right.
 *
 * Owns no navigation state — callers (today: `App.kt`) decide what
 * `detail` is by inspecting their existing flags (`openChat`,
 * `openThreadParent`, etc.). The scaffold is a layout component;
 * window-size class crossings just change how it draws.
 *
 * Phase 3 adds a drag handle between the panes that consumes
 * [listFraction]; Phase 1 fixes the fraction at 0.30 so the visible
 * split-pane lands without the resize machinery.
 */
@Composable
fun ChatPaneScaffold(
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    listFraction: Float = DEFAULT_LIST_FRACTION,
    onListFractionChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val expanded = maxWidth >= ExpandedThreshold
        if (!expanded) {
            // Stack: detail wins when present, list otherwise.
            if (detail != null) detail() else list()
            return@BoxWithConstraints
        }
        val totalWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val listWidth = maxWidth * listFraction.coerceIn(MIN_LIST_FRACTION, MAX_LIST_FRACTION)
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.width(listWidth).fillMaxHeight()) { list() }
            PaneDragHandle(onDragDelta = { deltaPx ->
                val delta = deltaPx / totalWidthPx
                onListFractionChange((listFraction + delta).coerceIn(MIN_LIST_FRACTION, MAX_LIST_FRACTION))
            })
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                if (detail != null) detail() else EmptyChatPane()
            }
        }
    }
}

@Composable
private fun PaneDragHandle(
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(6.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> onDragDelta(delta) },
            )
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) { /* visual only */ }
}

/** Material3 expanded-window threshold. Tablets in landscape +
 *  desktop windows cross it; phones never do. */
val ExpandedThreshold = 840.dp

/** Initial split — list takes 30% by default. Tuned to match Slack /
 *  Telegram Desktop, where the chat list is intentionally narrower
 *  than the active conversation. */
const val DEFAULT_LIST_FRACTION = 0.30f
const val MIN_LIST_FRACTION = 0.20f
const val MAX_LIST_FRACTION = 0.50f
