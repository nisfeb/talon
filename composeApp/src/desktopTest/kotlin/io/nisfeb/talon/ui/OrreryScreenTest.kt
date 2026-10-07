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
    /** The section on screen; false is the owner leaving it. */
    private val here = mutableStateOf(true)
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

    private fun orrery(
        openItem: String? = null,
        view: io.nisfeb.talon.orrery.OrreryView? = io.nisfeb.talon.orrery.OrreryView(state, plan, System.currentTimeMillis()),
        problem: String? = null,
        turnOn: (suspend () -> Result<Unit>)? = null,
        actionsTab: @androidx.compose.runtime.Composable () -> Unit = { Text("The actions list") },
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        val item = mutableStateOf(openItem)
        val uris = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        setContent {
            TalonTheme(darkTheme = false) {
                CompositionLocalProvider(LocalUriHandler provides uris) {
                    if (here.value) OrreryScreen(
                        onBack = { did += "back" },
                        view = view,
                        refreshing = false,
                        problem = problem,
                        onOpen = { force -> did += if (force) "refresh" else "open" },
                        onRefreshActions = { did += "actions" },
                        onLeave = { did += "left" },
                        openItem = item.value,
                        onOpenedItem = { did += "opened"; item.value = null },
                        turnOn = turnOn,
                        actionsTab = actionsTab,
                    )
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
        assertTrue("opened" in did && "open" in did, "it asks the ship again behind what it shows: $did")
        // A person from it opens in turn, and back retraces.
        onNodeWithText("Kid").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Elm Street") }
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Directions") }
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Grandparents visit") }
        assertTrue(shows("Coming up"))
        onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("opened", "back"), did.filter { it == "opened" || it == "back" })
    }

    // The Actions tab's body comes and goes with the tabs. Its own read
    // and its leaving ran each time: a read per tab switch, and what
    // Orrery said to the last Tell counted seen at the first tap away.
    @Test
    fun `the actions are read on entering and seen on leaving, not at each tab`() = orrery(actionsTab = {
        io.nisfeb.talon.ui.screens.OrreryActionsScreen(
            actions = emptyList(), onBack = {}, onOpen = {}, header = false,
            onShown = { did += "tab read" }, onLeave = { did += "tab left" },
        )
    }) {
        waitUntil(timeoutMillis = 5_000) { "actions" in did }
        for (t in listOf("Coming up", "Actions", "Browse", "Actions")) {
            onNodeWithText(t).performClick()
            waitForIdle()
        }
        assertEquals(1, did.count { it == "actions" }, "$did")
        assertTrue(did.none { it == "left" || it.startsWith("tab") }, "a tab switch neither reads nor leaves: $did")
        here.value = false
        waitForIdle()
        assertEquals(1, did.count { it == "left" }, "$did")
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
    fun `a ship that never answered says so, and the Actions tab still works`() = orrery(view = null, problem = "502") {
        onNodeWithText("Coming up").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Orrery could not be read") }
        onNodeWithText("Actions").performClick()
        assertTrue(shows("The actions list"))
    }

    // "is this pane going to be ass slow with no caching or incremental loading"
    @Test
    fun `what was kept shows at once while the ship does not answer, and says how old it is`() =
        orrery(view = io.nisfeb.talon.orrery.OrreryView(state, plan, System.currentTimeMillis() - 3 * hour), problem = "no answer") {
            onNodeWithText("Coming up").performClick()
            assertTrue(shows("Fencing lesson"), "the kept answer, not Looking…")
            assertTrue(shows("The ship did not answer; this is as of"))
            onNodeWithContentDescription("Refresh").performClick()
            assertTrue("refresh" in did)
        }

    // "getting 'Orrery could not be read: This install has no orrery key
    // yet' when it absolutely does": the ship held a key for this desktop,
    // and the desktop had lost its copy weeks before.
    @Test
    fun `not on for this device, it says so and offers to turn it on`() =
        orrery(view = null, problem = "This install has no orrery key yet.", turnOn = { did += "turned on"; Result.success(Unit) }) {
            onNodeWithText("Coming up").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("isn't turned on for this device") }
            assertTrue(!shows("could not be read"), "not a failure to read")
            onNodeWithText("Turn on").performClick()
            waitUntil(timeoutMillis = 5_000) { "refresh" in did }
            assertTrue("turned on" in did, did.toString())
        }

    @Test
    fun `turning on that fails says why`() =
        orrery(view = null, turnOn = { Result.failure(IllegalStateException("Orrery is not on this ship.")) }) {
            onNodeWithText("Coming up").performClick()
            onNodeWithText("Turn on").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Orrery is not on this ship.") }
            assertTrue("refresh" !in did)
        }

    @Test
    fun `on for this device, a read that failed still says so`() = orrery(view = null, problem = "502") {
        onNodeWithText("Coming up").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Orrery could not be read") }
        assertTrue(!shows("Turn on"))
    }
}
