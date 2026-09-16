package io.nisfeb.talon.ui

import androidx.compose.ui.Modifier
import sh.calvin.reorderable.ReorderableCollectionItemScope

/**
 * The drag handle for a reorderable row, for the input at hand.
 *
 * On touch a drag starts with a long press, so a finger can still
 * scroll the list; with a mouse there is no scroll to protect, and a
 * long press is a gesture nobody makes with one -- the handles read
 * as dead. So the mouse starts dragging at once.
 */
fun ReorderableCollectionItemScope.reorderHandle(
    onDragStarted: () -> Unit = {},
    onDragStopped: () -> Unit = {},
): Modifier =
    if (isTouchSwipeNavSupported) {
        Modifier.longPressDraggableHandle(
            onDragStarted = { onDragStarted() },
            onDragStopped = onDragStopped,
        )
    } else {
        Modifier.draggableHandle(
            onDragStarted = { onDragStarted() },
            onDragStopped = onDragStopped,
        )
    }
