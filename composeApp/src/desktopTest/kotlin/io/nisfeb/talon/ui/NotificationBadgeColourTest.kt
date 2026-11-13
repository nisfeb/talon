package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NotificationBadgeColourTest {
    /** The badge's fill, read off its left margin, beside the count. */
    private fun fillOf(themed: Color?): Pair<Color, Color> {
        var primary = Color.Unspecified
        var drawn = Color.Unspecified
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = false) {
                    primary = MaterialTheme.colorScheme.primary
                    CompositionLocalProvider(LocalBadgeColor provides themed) {
                        Box(Modifier.testTag("b")) { NotificationBadge(7) }
                    }
                }
            }
            waitForIdle()
            val px = onNodeWithTag("b").captureToImage().toPixelMap()
            drawn = px[2, px.height / 2]
        }
        return primary to drawn
    }

    // sneagan, 2026-11-05: "I don't like the red unread message count
    // background. either they should go back to the themed way they were
    // before or we need a way to override the red with the theme".
    @Test
    fun `an unread count is the theme's primary, as before the redesign`() {
        val (primary, drawn) = fillOf(themed = null)
        assertEquals(primary, drawn)
    }

    @Test
    fun `a theme that sets the badge colour gets it`() {
        assertEquals(Color(0xFFDC2626), fillOf(themed = Color(0xFFDC2626)).second)
    }
}
