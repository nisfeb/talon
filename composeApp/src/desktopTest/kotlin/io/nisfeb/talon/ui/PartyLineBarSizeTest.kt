package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import io.nisfeb.talon.call.ListenLink
import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.PartyLine
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PartyState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The party-line bar has to survive a phone.
 *
 * It sits on top of the conversation, so every row it takes is a row
 * of chat the user can't see. A busy line with anonymous listeners and
 * an admin present is the worst case, and it is the case that grew by
 * accident: the listener count started as a second line and the admin
 * controls as a permanently-open panel.
 *
 * 360dp is a small-but-real phone width (Pixel-class is 360-412dp).
 */
class PartyLineBarSizeTest {

    private val phoneWidthDp = 360
    private val density = Density(2f)

    /** Render the bar at phone width and return its height in dp. */
    private fun barHeightDp(
        state: PartyState,
        admin: PartyLineAdmin?,
    ): Int {
        val widthPx = (phoneWidthDp * density.density).toInt()
        val scene = ImageComposeScene(
            width = widthPx,
            height = (900 * density.density).toInt(),
            density = density,
        )
        var heightPx = 0
        try {
            scene.setContent {
                Box(
                    Modifier.fillMaxWidth().onGloballyPositioned {
                        heightPx = it.size.height
                    },
                ) {
                    PartyLineBarContent(state = state, admin = admin)
                }
            }
            scene.render()
        } finally {
            scene.close()
        }
        return (heightPx / density.density).toInt()
    }

    @Test
    fun aBusyLineWithListenersStaysCompactOnAPhone() {
        val state = PartyState.Live(
            room = "lounge",
            members = listOf(
                PartyMember("a", "~sampel-palnet"),
                PartyMember("b", "~ridlur-figbud"),
                PartyMember("c", "~wanzod-marbud"),
                PartyMember("d", "~nomber-mocbex"),
            ),
            muted = false,
            media = MediaState.Live,
            listeners = 7,
        )
        val height = barHeightDp(state, null)
        println("bar height, 4 members + 7 listeners, no admin: ${height}dp")
        // One row of controls plus padding. Two rows of text would push
        // this past ~80dp, which is what the second line used to cost.
        assertTrue(height in 1..80, "bar grew to ${height}dp at 360dp wide")
    }

    @Test
    fun theAdminPanelIsCollapsedUntilAsked() {
        val state = PartyState.Live(
            room = "lounge",
            members = listOf(PartyMember("a", "~sampel-palnet")),
            muted = false,
            media = MediaState.Live,
            listeners = 3,
        )
        val admin = PartyLineAdmin(
            listening = true,
            link = ListenLink("lounge", "https://example.com/group/talon/x/?token=abc", 0L),
            onSetListening = {},
            onShare = {},
            onDismissLink = {},
        )
        val height = barHeightDp(state, admin)
        println("bar height with admin controls collapsed: ${height}dp")
        // Collapsed, the admin row is one small line on top of the bar.
        // Expanded it carries a switch, a URL row and a caveat — which
        // is why it does not start expanded.
        assertTrue(height in 1..120, "admin bar grew to ${height}dp at 360dp wide")
    }
}
