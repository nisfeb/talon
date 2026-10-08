package io.nisfeb.talon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Armillary's mark beside its name, drawn as its desk draws it
 * (armillary code/icon.svg, on a 64-unit square): a navy disc, the
 * sphere's three rings and a gold centre. Its own colours, not the
 * theme's: it is the app's mark. Decorative, so no description; the
 * name beside it says what it is.
 */
@Composable
fun ArmillaryLogo(size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).testTag(ARMILLARY_LOGO)) {
        val u = this.size.minDimension / 64f
        val c = center
        val ring = Stroke(width = 2.5f * u)
        drawCircle(Color(0xFF1B2A4A), radius = 31f * u, center = c)
        drawCircle(RINGS, radius = 22f * u, center = c, style = ring)
        drawOval(RINGS, topLeft = Offset(c.x - 9f * u, c.y - 22f * u), size = Size(18f * u, 44f * u), style = ring)
        drawOval(RINGS, topLeft = Offset(c.x - 22f * u, c.y - 9f * u), size = Size(44f * u, 18f * u), style = ring)
        drawCircle(Color(0xFFFFD27F), radius = 4f * u, center = c)
    }
}

private val RINGS = Color(0xFF9FC4FF)

/** The logo's test tag. */
const val ARMILLARY_LOGO = "armillary-logo"
