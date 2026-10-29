package io.nisfeb.talon.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/**
 * A drag to the left, which a message's reply swipe is: [onDrag] gets
 * each step once the finger has gone left past the touch slop, and
 * [onEnd] or [onCancel] when it lifts or is taken. A drag that goes up
 * or down first is the list's, and one that goes right first is left
 * to the screen around the row: on iOS that is the swipe back, which a
 * row that claimed every sideways drag stopped wherever a message was.
 */
fun Modifier.leftwardDrag(
    key: Any?,
    onDrag: (dx: Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var dx = 0f
        var dy = 0f
        // Which way it goes, before anything is claimed.
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            dx += change.positionChange().x
            dy += change.positionChange().y
            if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
            if (dx > slop) return@awaitEachGesture
            if (dx < -slop) {
                change.consume()
                onDrag(dx + slop)
                break
            }
        }
        val lifted = horizontalDrag(down.id) { change ->
            onDrag(change.positionChange().x)
            change.consume()
        }
        if (lifted) onEnd() else onCancel()
    }
}
