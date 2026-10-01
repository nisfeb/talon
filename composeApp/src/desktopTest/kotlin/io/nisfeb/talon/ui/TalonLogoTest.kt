package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "is it possible to make the logo match the theme?", and "the talon logo
 * has hard corners and the orange talon part isn't centered". Also
 * "ios still has no visible talon logo": every platform now draws this
 * one, from Compose resources (iOS's copy into its bundle is the Compose
 * plugin's, run by Xcode, and is not exercised here).
 */
@OptIn(ExperimentalTestApi::class)
class TalonLogoTest {
    /** The logo's opaque pixels and their bounds, once it has loaded. */
    private fun ComposeUiTest.drawn(): Pair<List<Color>, IntArray> {
        var opaque = emptyList<Color>()
        var bounds = IntArray(4)
        waitUntil(timeoutMillis = 5_000) {
            val map = onNodeWithTag("logo").captureToImage().toPixelMap()
            val seen = mutableListOf<Color>()
            var l = map.width; var t = map.height; var r = -1; var b = -1
            for (y in 0 until map.height) for (x in 0 until map.width) {
                val c = map[x, y]
                if (c.alpha > 0.5f) { l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y) }
                if (c.alpha > 0.95f) seen += c
            }
            opaque = seen
            bounds = intArrayOf(l, map.width - 1 - r, t, map.height - 1 - b)
            seen.isNotEmpty()
        }
        return opaque to bounds
    }

    private fun close(a: Color, b: Color) =
        abs(a.red - b.red) < 0.02f && abs(a.green - b.green) < 0.02f && abs(a.blue - b.blue) < 0.02f

    private fun inTheme(dark: Boolean?, accent: Color?) = runComposeUiTest {
        var primary = Color.Unspecified
        setContent {
            val logo = @androidx.compose.runtime.Composable {
                primary = MaterialTheme.colorScheme.primary
                TalonLogo(contentDescription = "Talon", modifier = Modifier.size(64.dp).testTag("logo"))
            }
            if (accent != null) MaterialTheme(colorScheme = lightColorScheme(primary = accent)) { logo() }
            else TalonTheme(darkTheme = dark!!) { logo() }
        }
        val (opaque, margins) = drawn()
        assertTrue(opaque.all { close(it, primary) }, "every solid pixel is the theme's primary $primary")
        val (left, right, top, bottom) = margins.toList()
        assertTrue(abs(left - right) <= 2 && abs(top - bottom) <= 2, "centred: left $left right $right top $top bottom $bottom")
    }

    @Test
    fun `the logo is the light theme's primary colour, centred`() = inTheme(dark = false, accent = null)

    @Test
    fun `the logo is the dark theme's primary colour`() = inTheme(dark = true, accent = null)

    @Test
    fun `the logo follows a theme's own accent`() = inTheme(dark = null, accent = Color(0xFF2962FF))
}
