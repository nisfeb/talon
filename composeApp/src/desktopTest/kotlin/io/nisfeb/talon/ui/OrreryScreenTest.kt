package io.nisfeb.talon.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.screens.OrreryScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Orrery section over a fake of the ship's orrery: its three tabs, a
 * thing opened from Coming up or Browse with Directions first, and a leave
 * alert landing straight on the thing it is about.
 */
@OptIn(ExperimentalTestApi::class)
class OrreryScreenTest {
    private val opened: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val hour = 3_600_000L
    private val soon = System.currentTimeMillis() + 2 * hour
    private fun iso(ms: Long) = kotlin.time.Instant.fromEpochMilliseconds(ms).toString()

    private val state: JsonObject = Json.parseToJsonElement(
        """{"me":"person/me","bodies":[
        {"id":"activity/fencing-lesson","kind":"activity","name":"Fencing lesson","attrs":{
            "next":{"value":"${iso(soon)}"},"location":{"value":"Fencing Club, 1 Main St"},"schedule":{"value":"weekly"},
            "participants":[{"value":{"ref":"person/me"}},{"value":{"ref":"person/kid"}}]}},
        {"id":"situation/visit","kind":"situation","name":"Grandparents visit","attrs":{"starts":{"value":"${iso(soon + 24 * hour)}"}}},
        {"id":"person/kid","kind":"person","name":"Kid","aliases":["the boy"],"attrs":{"school":{"value":"Elm Street"}}},
        {"id":"person/me","kind":"person","name":"Me","attrs":{}}
        ]}""",
    ).jsonObject
    private val plan: JsonObject = Json.parseToJsonElement(
        """{"next":{"key":"activity/fencing-lesson@$soon","leave_by":"${iso(soon - hour / 2)}","minutes":23}}""",
    ).jsonObject

    private fun orrery(openItem: String? = null, readable: Boolean = true, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        val item = mutableStateOf(openItem)
        val uris = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        setContent {
            TalonTheme(darkTheme = false) {
                CompositionLocalProvider(LocalUriHandler provides uris) {
                    OrreryScreen(
                        onBack = { did += "back" },
                        readState = { if (readable) Result.success(state) else Result.failure(IllegalStateException("502")) },
                        readPlan = { plan },
                        onRefreshActions = { did += "actions" },
                        openItem = item.value,
                        onOpenedItem = { did += "opened"; item.value = null },
                    ) { Text("The actions list") }
                }
            }
        }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `it opens on Actions, and Coming up lists what is ahead with when to leave`() = orrery {
        assertTrue(shows("The actions list"))
        onNodeWithText("Coming up").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Fencing lesson") }
        assertTrue(shows("Grandparents visit"))
        assertTrue(shows("Leave by") && shows("23 min with traffic"), "the ship's plan on the lesson it is for")
        assertTrue(shows("Fencing Club"))
    }

    @Test
    fun `a thing opened has Directions first, to its place in a maps app`() = orrery {
        onNodeWithText("Coming up").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Fencing lesson") }
        onNodeWithText("Fencing lesson").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Directions") }
        val top = { text: String -> onAllNodesWithText(text, substring = true).fetchSemanticsNodes().first().boundsInRoot.top }
        assertTrue(top("Directions") < top("Fencing lesson"), "Directions above everything else")
        onNodeWithText("Directions").performClick()
        assertEquals(listOf(mapsSearchUri("Fencing Club, 1 Main St")), opened.toList())
        assertTrue(shows("You") && shows("Kid"), "who, by name, the owner as You")
        assertTrue(shows("Leave by"))
    }

    @Test
    fun `a leave alert lands on its thing, and back goes to the list, then leaves`() = orrery(openItem = "activity/fencing-lesson") {
        waitUntil(timeoutMillis = 5_000) { shows("Directions") }
        assertTrue("opened" in did)
        // A person from it opens in turn, and back retraces.
        onNodeWithText("Kid").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Elm Street") }
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Directions") }
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Grandparents visit") }
        assertTrue(shows("Coming up"))
        onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("opened", "back"), did.filter { it != "actions" })
    }

    @Test
    fun `browse finds a thing by an alias`() = orrery {
        onNodeWithText("Browse").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("People") }
        assertTrue(shows("Activities") && shows("Situations"))
        onNode(hasSetTextAction()).performTextInput("boy")
        waitUntil(timeoutMillis = 5_000) { !shows("Fencing lesson") }
        onNodeWithText("Kid").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Elm Street") }
        assertTrue(!shows("Directions"), "nowhere to go, no Directions")
    }

    @Test
    fun `a ship that does not answer says so, and the Actions tab still works`() = orrery(readable = false) {
        onNodeWithText("Coming up").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Orrery could not be read") }
        onNodeWithText("Actions").performClick()
        assertTrue(shows("The actions list"))
    }
}
