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
        bar: @androidx.compose.runtime.Composable (PartyState) -> Unit = { PartyLineBarContent(state = it) },
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
                    bar(state)
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
        val height = barHeightDp(state)
        println("bar height, 4 members + 7 listeners: ${height}dp")
        // One row of controls plus padding. Two rows of text would push
        // this past ~80dp, which is what the second line used to cost.
        assertTrue(height in 1..80, "bar grew to ${height}dp at 360dp wide")
    }

    /** A phone's two routes: the speaker button shows. */
    private val phone = object : io.nisfeb.talon.call.AudioDevices {
        val routes = listOf(
            io.nisfeb.talon.call.AudioDevice("ear", "Earpiece", io.nisfeb.talon.call.AudioRouteKind.Earpiece),
            io.nisfeb.talon.call.AudioDevice("spk", "Speaker", io.nisfeb.talon.call.AudioRouteKind.Speaker),
        )
        override val supported = true
        override val unifiedRoute = true
        override fun inputs() = routes
        override fun outputs() = routes
    }

    // A speaker button joined mute, camera, the menu and hang-up on a
    // phone's 1:1 call (2026-11-22): still one row at 360dp.
    @Test
    fun aCallWithTheSpeakerAndCameraStaysOneRowOnAPhone() {
        val state = PartyState.Live(
            room = "~bus", members = listOf(PartyMember("a", "~bus")), muted = false, media = MediaState.Live,
        )
        val height = barHeightDp(state) {
            PartyLineBarContent(state = it, audioDevices = phone, headline = "On a call with ~ridlur-figbud", onToggleCamera = {})
        }
        println("bar height, a call with speaker and camera: ${height}dp")
        assertTrue(height in 1..80, "bar grew to ${height}dp at 360dp wide")
    }
}
