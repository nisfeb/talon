package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InviteFriendCardTest {
    // sneagan, 2026-11-05: "the show qr button in invitations is a single
    // column of letters". On a phone's width every button keeps its words
    // on one line: the row wraps instead of squeezing the last.
    @Test
    fun `on a phone's width every button reads on one line`() = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                Box(Modifier.width(320.dp)) { InviteFriendCard(ship = "~sampel-palnet") }
            }
        }
        waitForIdle()
        for (label in listOf("Invite a friend", "Copy invite", "Show QR")) {
            val b = onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue(b.width > b.height * 2, "$label is ${b.width} wide and ${b.height} tall")
        }
    }
}
