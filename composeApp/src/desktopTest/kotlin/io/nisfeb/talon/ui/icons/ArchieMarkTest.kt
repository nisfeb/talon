package io.nisfeb.talon.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Archie drawn as the app draws it: the cut-outs must show the surface, or the owl is a blob. */
@OptIn(ExperimentalTestApi::class)
class ArchieMarkTest {
    private val navy = Color(0xFF101541)
    private val amber = Color(0xFFF9A804)

    @Test
    fun `the eyes, sparkles and beak are holes in an amber owl`() = runComposeUiTest {
        setContent {
            Box(Modifier.size(240.dp).background(navy)) {
                Icon(ArchieMark, contentDescription = ASSISTANT_NAME, tint = amber, modifier = Modifier.fillMaxSize())
            }
        }
        val map = onRoot().captureToImage().toPixelMap()
        // A point on the owl's 24 unit grid, as drawn.
        fun at(x: Float, y: Float) = map[(x / 24 * map.width).toInt(), (y / 24 * map.height).toInt()]
        fun near(c: Color, want: Color) = abs(c.red - want.red) + abs(c.green - want.green) + abs(c.blue - want.blue) < 0.1f
        mapOf(
            "the belly" to (12f to 17f), "a pupil" to (8.6f to 11.4f), "a tuft" to (4.4f to 3.6f), "the branch" to (12f to 22.7f),
        ).forEach { (what, p) -> assertTrue(near(at(p.first, p.second), amber), "$what is the owl: ${at(p.first, p.second)}") }
        mapOf(
            "an eye's white" to (6.0f to 10.2f), "a sparkle" to (9.8f to 9.9f), "the beak" to (12f to 13.4f),
            "a wing edge" to (5.6f to 16.4f), "beside the owl" to (1f to 12f),
        ).forEach { (what, p) -> assertTrue(near(at(p.first, p.second), navy), "$what shows the surface: ${at(p.first, p.second)}") }
    }
}
