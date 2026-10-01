package io.nisfeb.talon.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import org.jetbrains.compose.resources.painterResource
import talon.composeapp.generated.resources.Res
import talon.composeapp.generated.resources.talon_logo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "ios still has no visible talon logo in the top right of the chat
 * groups list view": iOS read `icon.png` from an app bundle that never
 * carried it. It now draws the logo from Compose resources; this loads
 * it the same way. The copy into the iOS app bundle is the Compose
 * plugin's, run by Xcode, and is not exercised here.
 */
@OptIn(ExperimentalTestApi::class)
class TalonLogoResourceTest {
    @Test
    fun `the logo iOS draws is a resource that loads`() = runComposeUiTest {
        var size = Size.Unspecified
        setContent { size = painterResource(Res.drawable.talon_logo).intrinsicSize }
        waitUntil(timeoutMillis = 5_000) { size != Size.Unspecified && size.width > 0f }
        assertEquals(Size(256f, 256f), size)
    }
}
