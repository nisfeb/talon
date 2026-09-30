package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.CallEngineProvider
import io.nisfeb.talon.ui.theme.InMemoryThemePreference
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Who may call this ship and through which servers: each change goes to
 * %trunk, and the screen shows the policy the ship answers with.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsCallsTest {
    private val ship = FakeShip("~zod").apply {
        scries["trunk/policy"] = """{"mode":"allow","allow":["~dopzod-bitnux"],"block":[]}"""
        scries["trunk/version"] = """{"wire":9}"""
        scries["trunk/ice"] = """[{"url":"stun:stun.example:3478","user":"","cred":""}]"""
    }

    private fun calls(block: ComposeUiTest.(CallController) -> Unit) = runComposeUiTest {
        val controller = CallController(
            UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") },
            CallEngineProvider { error("no media in these tests") },
        )
        controller.start()
        try {
            waitUntil(timeoutMillis = 10_000) { controller.policy.value != null }
            setContent {
                TalonTheme(darkTheme = false) {
                    io.nisfeb.talon.ui.screens.SettingsScreen(
                        aiSettings = FakeAiSettings(), themePreference = InMemoryThemePreference(), uiSettings = InMemoryUiSettings(),
                        onBack = {}, callController = controller,
                    )
                }
            }
            onAllNodesWithText("Calls")[0].performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Who can call you") }
            block(controller)
        } finally {
            controller.stop()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** The ship's policy after a change, as %trunk announces it. */
    private fun policy(json: String) = runBlocking { ship.emit("""{"id":1,"response":"diff","json":{"policy":$json}}""") }

    private fun sent(): List<String> = ship.pokesTo("trunk").map { it.json.toString() }

    private fun ComposeUiTest.addTo(list: Int, who: String) {
        val boxes = onAllNodes(hasSetTextAction() and hasText("~sampel-palnet, or a word name"))
        boxes[list].performScrollTo().performTextInput(who)
        onAllNodesWithText("Add")[list].performScrollTo().performClick()
    }

    @Test
    fun `with only my list on, the list shows, and a ship is allowed and removed`() = calls {
        assertTrue(shows("~dopzod-bitnux") && shows("Everyone else's calls are ignored."))
        addTo(0, "~ridlur-figbud")
        waitUntil(timeoutMillis = 5_000) { sent().any { "\"allow\":\"~ridlur-figbud\"" in it } }
        policy("""{"mode":"allow","allow":["~dopzod-bitnux","~ridlur-figbud"],"block":[]}""")
        waitUntil(timeoutMillis = 5_000) { shows("~ridlur-figbud") }
        onAllNodesWithText("Remove")[0].performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { sent().any { "\"unallow\":\"~dopzod-bitnux\"" in it } }
    }

    @Test
    fun `switching the list off opens calls to everyone not blocked`() = calls {
        onAllNodes(isToggleable())[0].performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { sent().any { "set-call-mode" in it } }
        policy("""{"mode":"open","allow":[],"block":[]}""")
        waitUntil(timeoutMillis = 5_000) { shows("Anyone can ring you, except people you've blocked.") }
        assertTrue(!shows("Allowed"), "no allow list while open")
    }

    @Test
    fun `a ship is blocked`() = calls {
        addTo(1, "~sampel-palnet")
        waitUntil(timeoutMillis = 5_000) { sent().any { "\"block\":\"~sampel-palnet\"" in it } }
    }

    @Test
    fun `a call server is added with its login, and only stun or turn will do`() = calls {
        waitUntil(timeoutMillis = 5_000) { shows("stun:stun.example:3478") }
        val url = onNode(hasSetTextAction() and hasText("stun:… or turn:…"))
        url.performScrollTo().performTextInput("https://not.a.server")
        onAllNodesWithText("Add").let { it[it.fetchSemanticsNodes().size - 1] }.assertIsNotEnabled()
        url.performTextReplacement("turn:turn.example:3478")
        onNode(hasSetTextAction() and hasText("User")).performTextInput("me")
        onNode(hasSetTextAction() and hasText("Password")).performTextInput("secret")
        onAllNodesWithText("Add").let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { sent().any { "set-ice" in it } }
        val ice = sent().last { "set-ice" in it }
        assertTrue("stun:stun.example:3478" in ice && "turn:turn.example:3478" in ice && "\"user\":\"me\"" in ice && "\"cred\":\"secret\"" in ice, ice)
    }
}
