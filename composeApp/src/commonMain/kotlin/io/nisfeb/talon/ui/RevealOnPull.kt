package io.nisfeb.talon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * Something above a list that stays tucked away until it is pulled for:
 * a pull past the list's top (a finger, or the wheel at the top) slides
 * it out, up to [maxPx]; scrolling the list on folds it away before the
 * list moves. Let go part way, and it settles open or shut. Put it on a
 * parent of the list with `Modifier.nestedScroll`, and draw the thing
 * [shownPx] tall. The chat list's search bar (sneagan, 2026-11-05: it
 * "needs to hide when not in use. maybe scroll down to access it").
 */
class RevealOnPull(private val maxPx: Float) : NestedScrollConnection {
    /** How much of it shows, 0 (tucked away) to [maxPx]. */
    var shownPx by mutableFloatStateOf(0f)
        private set

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // The list moving on (content going up): fold it first.
        if (available.y >= 0f || shownPx <= 0f) return Offset.Zero
        val used = maxOf(available.y, -shownPx)
        shownPx += used
        return Offset(0f, used)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Past the top, what the list could not take: unfold it.
        if (available.y <= 0f || shownPx >= maxPx) return Offset.Zero
        val used = minOf(available.y, maxPx - shownPx)
        shownPx += used
        return Offset(0f, used)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        settle()
        return Velocity.Zero
    }

    /** Open or shut, whichever it is nearer: never left half drawn. */
    fun settle() {
        shownPx = if (shownPx >= maxPx / 2) maxPx else 0f
    }
}
