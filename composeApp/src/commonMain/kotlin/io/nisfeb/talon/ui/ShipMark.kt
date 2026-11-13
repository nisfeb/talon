package io.nisfeb.talon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A ship's own Groundwire mark: the grounding symbol (branding/
 * groundwire-mark.svg) in white on a disc of the ship's colour, so each
 * signed-in ship tells apart at a glance in the switcher. The colour
 * comes from the ship's name, so a newly added ship gets one of its own
 * at random, and the same one on every device and every launch, with
 * nothing stored.
 */
@Composable
fun ShipMark(ship: String, size: Dp = 24.dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val disc = shipMarkColor(ship)
    Canvas(
        modifier = modifier.size(size).then(
            if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier,
        ),
    ) {
        val k = this.size.minDimension / 509f
        drawCircle(disc, radius = 254.5f * k, center = Offset(254.5f * k, 254.5f * k))
        val w = 22f * k
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(Color.White, Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), strokeWidth = w, cap = StrokeCap.Round)
        line(254f, 105f, 254f, 273f)
        line(425f, 275f, 84f, 275f)
        line(362f, 339f, 147f, 339f)
        line(297f, 403f, 211f, 403f)
    }
}

/** Disc colours a ship can get: deep enough for the white symbol to read on each. */
internal val SHIP_MARK_COLORS = listOf(
    0xFF1E1B4B, 0xFF7A1F2B, 0xFF0F5E4E, 0xFF8A4B08, 0xFF3B2A7A, 0xFF1F4E8C,
    0xFF6B2C70, 0xFF2E5A1E, 0xFF8C2F5A, 0xFF0B5563, 0xFF5A3A1A, 0xFF33363D,
).map { Color(it) }

/** [ship]'s disc colour: picked from its name, so stable, and spread across ships. */
fun shipMarkColor(ship: String): Color {
    // FNV-1a over the name: String.hashCode clusters for @ps that share syllables.
    var h = 0x811C9DC5.toInt()
    for (ch in ship.lowercase()) { h = (h xor ch.code) * 0x01000193 }
    return SHIP_MARK_COLORS[(h.toLong() and 0xFFFFFFFFL).rem(SHIP_MARK_COLORS.size).toInt()]
}
