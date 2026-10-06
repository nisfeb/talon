package io.nisfeb.talon.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.notify.Enrollment
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The prompt an iPhone shows after signing in, and from Settings. */
@OptIn(ExperimentalTestApi::class)
class NotificationSetupDialogTest {
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun prompt(code: String?, answer: Enrollment, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                NotificationSetupDialog(
                    code = code,
                    enroll = { c -> did += "enroll $c"; answer },
                    onDone = { did += "done" },
                    onNotNow = { did += "not now" },
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { shows("Get notifications on this iPhone?") }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `after signing in, yes uses the code just typed`() = prompt("lidlut-tabwed", Enrollment.On("dev-1", alerts = true)) {
        assertTrue(onAllNodesWithText("+code").fetchSemanticsNodes().isEmpty(), "no code asked for")
        onNodeWithText("Turn on").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Notifications are on") }
        assertEquals(listOf("enroll lidlut-tabwed"), did)
        onNodeWithText("OK").performClick()
        waitForIdle()
        assertEquals(listOf("enroll lidlut-tabwed", "done"), did)
    }

    @Test
    fun `from Settings it asks for the code`() = prompt(null, Enrollment.On("dev-1", alerts = true)) {
        onNode(hasSetTextAction()).performTextInput("  sampel-code  ")
        onNodeWithText("Turn on").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Notifications are on") }
        assertEquals("enroll sampel-code", did.first())
    }

    @Test
    fun `refused notifications are said, not hidden`() = prompt("c", Enrollment.On("dev-1", alerts = false)) {
        onNodeWithText("Turn on").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("won't alert until you allow notifications") }
    }

    @Test
    fun `a relay that says no says why, and the prompt stays`() = prompt("c", Enrollment.Refused("The relay could not sign in to your ship.")) {
        onNodeWithText("Turn on").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("could not sign in") }
        assertTrue(shows("Turn on") && "done" !in did)
    }

    @Test
    fun `not now is the owner's answer`() = prompt("c", Enrollment.On("dev-1", alerts = true)) {
        onNodeWithText("Not now").performClick()
        waitForIdle()
        assertEquals(listOf("not now"), did)
    }
}
