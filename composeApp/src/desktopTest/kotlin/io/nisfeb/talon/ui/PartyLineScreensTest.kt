package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A party line as its screens show it: who is on and who is speaking,
 * and what each control asks of the line.
 */
@OptIn(ExperimentalTestApi::class)
class PartyLineScreensTest {
    private val did = mutableListOf<String>()

    private val names = mapOf("~zod" to "Zod", "~bus" to "Bus", "~nec" to "Nec")

    private fun live(muted: Boolean = false, canSpeak: Boolean = true, ops: Boolean = false, byAdmin: Boolean = false) = PartyState.Live(
        room = "garden-chat", topic = "Planting season",
        members = listOf(
            PartyMember(id = "1", ship = "~zod"),
            PartyMember(id = "2", ship = "~bus", speaking = true),
            PartyMember(id = "3", ship = "~nec", muted = true),
        ),
        muted = muted, media = MediaState.Live, listeners = 4, canSpeak = canSpeak, ops = ops, selfMutedByAdmin = byAdmin,
    )

    private fun full(state: PartyState.Live, recording: Boolean = false, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineFullScreen(
                    state = state, roomName = "Garden chat", nameFor = { names[it] ?: it }, selfShip = "~zod",
                    onToggleMute = { did += "mute:$it" }, onLeave = { did += "leave" }, onMinimize = { did += "minimize" },
                    onRevokeSpeaking = { did += "revoke:$it" }, onRestoreSpeaking = { did += "restore:$it" },
                    onMessage = { did += "message:$it" },
                    recording = recording, recordedBy = if (recording) setOf("~bus") else emptySet(),
                    onToggleRecord = { did += "record" },
                )
            }
        }
        waitForIdle()
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the line shows who is on, who is speaking and who is muted`() = full(live()) {
        for (t in listOf("Planting season", "3 people on the line · 4 listening", "Zod (you)", "Bus", "Speaking", "Nec", "Muted")) {
            assertTrue(shows(t), "shows $t")
        }
    }

    @Test
    fun `mute, leave and minimize each ask the line`() {
        full(live(muted = false)) {
            onNodeWithContentDescription("Mute").performClick()
            // The word under a button is part of it: a click there works too.
            onNodeWithText("Leave", useUnmergedTree = true).performClick()
            onNodeWithContentDescription("Minimize the call").performClick()
            waitForIdle()
        }
        assertEquals(listOf("mute:true", "leave", "minimize"), did)
        did.clear()
        full(live(muted = true)) {
            onNodeWithText("Unmute", useUnmergedTree = true).performClick()
            waitForIdle()
        }
        assertEquals(listOf("mute:false"), did)
    }

    @Test
    fun `a recording says who records, and Stop asks the line`() = full(live(), recording = true) {
        assertTrue(shows("Recording · Bus"))
        onNodeWithContentDescription("Stop").performClick()
        waitForIdle()
        assertEquals(listOf("record"), did)
    }

    @Test
    fun `an admin can take a voice or send a message from someone's options`() = full(live(ops = true)) {
        onNodeWithContentDescription("Options for Bus").performClick()
        onNodeWithText("Mute for everyone").performClick()
        waitForIdle()
        onNodeWithContentDescription("Options for Nec").performClick()
        onNodeWithText("Message").performClick()
        waitForIdle()
        assertEquals(listOf("revoke:~bus", "message:~nec"), did)
    }

    @Test
    fun `without an admin's rights, the options only message`() = full(live(ops = false)) {
        onNodeWithContentDescription("Options for Bus").performClick()
        waitForIdle()
        assertTrue(shows("Message"))
        assertTrue(!shows("Mute for everyone"), "only an admin mutes others")
    }

    @Test
    fun `a listener has no mic to switch, and is told they are listening`() = full(live(canSpeak = false)) {
        assertTrue(shows("Listening"))
        assertTrue(!shows("Mute") && !shows("Unmute"))
    }

    private fun bar(state: PartyState, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineBarContent(
                    state = state, onToggleMute = { did += "mute:$it" }, onLeave = { did += "leave" },
                    nameFor = { names[it] ?: it },
                )
            }
        }
        waitForIdle()
        block()
    }

    @Test
    fun `the bar says who is on, and its mute and leave ask the line`() = bar(live()) {
        assertTrue(onAllNodesWithText("3 on the line · 4 listening", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("Zod, Bus, Nec", substring = true).fetchSemanticsNodes().isNotEmpty(), "by name")
        onNodeWithContentDescription("Mute").performClick()
        onNodeWithContentDescription("Leave the line").performClick()
        waitForIdle()
        assertEquals(listOf("mute:true", "leave"), did)
    }

    @Test
    fun `the bar says it is joining, and says why a line failed`() {
        bar(PartyState.Connecting("garden-chat")) {
            assertTrue(onAllNodesWithText("Joining the line…").fetchSemanticsNodes().isNotEmpty())
            onNodeWithContentDescription("Leave the line").performClick() // joining can still be called off
            waitForIdle()
        }
        assertEquals(listOf("leave"), did)
        bar(PartyState.Failed("garden-chat", "the host is gone")) {
            assertTrue(onAllNodesWithText("Party line: the host is gone").fetchSemanticsNodes().isNotEmpty())
        }
    }
}
