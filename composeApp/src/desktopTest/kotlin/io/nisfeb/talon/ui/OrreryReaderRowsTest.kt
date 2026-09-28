package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ui.screens.OrrerySettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ship's own readers, as AI settings shows them: whether the ship
 * reads the chats and the mail and sends approved DMs. Each switch asks
 * the ship, and what it answers is what shows.
 */
@OptIn(ExperimentalTestApi::class)
class OrreryReaderRowsTest {
    private val docs = Collections.synchronizedMap(mutableMapOf(
        "chat" to """{"enabled":true,"dms":[],"channels":[],"send_dms":false}""",
        "mail" to """{"enabled":false}""",
        "read/settings" to """{"enabled":true}""",
        "policy" to """{"auto":["task"],"push":"proposed","todo_calendar":"","event_calendar":"home"}""",
        "schema" to SCHEMA_59,
    ))
    /** Merged into on a write, as the ship does; the rest are replaced whole. */
    private val merged = setOf("chat", "mail", "read/settings")
    private val writes: MutableList<Pair<String, JsonObject>> = java.util.concurrent.CopyOnWriteArrayList()
    @Volatile private var answering = true
    /** Whether the ship answers the whole page in one request, as orrery 60 does. */
    @Volatile private var whole = false
    private val asked: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun wholePage() = """{"chat":${docs["chat"]},"mail":${docs["mail"]},"read":${docs["read/settings"]},"policy":${docs["policy"]},""" +
        """"schema":${docs["schema"]},"chat_last":{},"mail_last":{},"read_last":{},"generator":{"enabled":false},"generator_last":{},""" +
        """"chat_lists":{"dms":{"items":[{"id":"~bus","name":"Bus"}],"note":""},"channels":{"items":[],"note":"no groups desk"}}}"""

    private val http = HttpClient(MockEngine { req ->
        val doc = req.url.encodedPath.substringAfter("/apps/orrery/api/", "")
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        if (req.method == HttpMethod.Get) asked += doc
        when {
            req.url.encodedPath == "/apps/calendar/calendars.json" ->
                json("""[{"id":"home","name":"Home","kind":"local"},{"id":"work","name":"Work","kind":"google"}]""")
            doc == "settings" && whole -> json(wholePage())
            doc in docs && !answering -> respond("down", HttpStatusCode.InternalServerError)
            doc in docs && req.method == HttpMethod.Put -> {
                val sent = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                writes += doc to sent
                // The ship takes the change and answers with the whole document.
                val now = Json.parseToJsonElement(docs.getValue(doc)).jsonObject
                docs[doc] = (if (doc in merged) JsonObject(now + sent) else sent).toString()
                json(docs.getValue(doc))
            }
            doc in docs -> json(docs.getValue(doc))
            else -> json("{}")
        }
    })

    private fun settings(block: ComposeUiTest.() -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-orrery-rows-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val orrery = OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            OrrerySettingsSection(
                                FakeAiSettings().apply {
                                    applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = io.nisfeb.talon.ai.AiProfile(orrery = true)))
                                },
                                orrery = orrery,
                            )
                        }
                    }
                }
                waitForIdle()
                block()
            }
        } finally {
            // Wait for the repo's work to stop before closing: a query still
            // running in native SQLite when the database closes is a crash.
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** The switch drawn nearest [label]. */
    private fun ComposeUiTest.switchBeside(label: String): SemanticsNodeInteraction {
        runCatching { onAllNodesWithText(label)[0].performScrollTo() }
        val y = onAllNodesWithText(label)[0].fetchSemanticsNode().boundsInRoot.center.y
        val switches = onAllNodes(isToggleable())
        val nearest = switches.fetchSemanticsNodes().indices
            .minBy { kotlin.math.abs(switches[it].fetchSemanticsNode().boundsInRoot.center.y - y) }
        return switches[nearest]
    }

    @Test
    fun `the readers show as the ship has them`() = settings {
        waitUntil(timeoutMillis = 5_000) { shows("The ship reads my chats") && shows("The ship reads my mail") }
        switchBeside("The ship reads my chats").assertIsOn()
        switchBeside("The ship sends my approved DMs").assertIsOff()
        switchBeside("The ship reads my mail").assertIsOff()
    }

    // Orrery 58: a chat reader with no DM picked reads every DM. Saying it
    // read "the chats picked below" when none were picked hid that.
    @Test
    fun `a reader with no DM picked is said to read every DM, and one with some only those`() {
        settings {
            waitUntil(timeoutMillis = 5_000) { shows("The ship reads my chats") }
            assertTrue(shows("Your ship reads every DM, since none are picked"))
        }
        docs["chat"] = """{"enabled":true,"dms":["~bus"],"channels":[],"send_dms":false}"""
        settings {
            waitUntil(timeoutMillis = 5_000) { shows("The ship reads my chats") }
            assertTrue(shows("Your ship reads the DMs and channels picked below") && !shows("every DM, since none"))
        }
    }

    @Test
    fun `sending approved DMs is asked of the ship, and its answer shows`() = settings {
        waitUntil(timeoutMillis = 5_000) { shows("The ship sends my approved DMs") }
        switchBeside("The ship sends my approved DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
        assertEquals("chat" to """{"send_dms":true}""", writes.single().let { it.first to it.second.toString() })
        waitUntil(timeoutMillis = 5_000) { runCatching { switchBeside("The ship sends my approved DMs").assertIsOn() }.isSuccess }
    }

    @Test
    fun `the mail reader is switched on by asking the ship`() = settings {
        waitUntil(timeoutMillis = 5_000) { shows("The ship reads my mail") }
        switchBeside("The ship reads my mail").performClick()
        waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
        assertEquals("mail" to """{"enabled":true}""", writes.single().let { it.first to it.second.toString() })
        waitUntil(timeoutMillis = 5_000) { runCatching { switchBeside("The ship reads my mail").assertIsOn() }.isSuccess }
    }

    // Orrery 60 answers the whole page at once; a request per row took
    // the owner's ship most of a minute.
    @Test
    fun `a ship with one answer for the page is asked once, and every row fills from it`() {
        whole = true
        settings {
            waitUntil(timeoutMillis = 5_000) {
                shows("The ship reads my chats") && shows("The ship reads my mail") && shows("Never before nine") &&
                    shows("The ship reads what it is handed") && shows("Events go on")
            }
            waitForIdle()
            val routes = io.nisfeb.talon.orrery.OrreryApi.SETTINGS + io.nisfeb.talon.orrery.OrreryApi.LISTS
            assertEquals(listOf("settings"), asked.filter { it in routes })
        }
    }

    // Orrery 59's read channel: what the assistant hands the ship is
    // dropped while it is off, and nothing in Talon said so.
    @Test
    fun `the ship reading what it is handed is switched by asking the ship`() = settings {
        waitUntil(timeoutMillis = 5_000) { shows("The ship reads what it is handed") }
        switchBeside("The ship reads what it is handed").assertIsOn()
        switchBeside("The ship reads what it is handed").performClick()
        waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
        assertEquals("read/settings" to """{"enabled":false}""", writes.single().let { it.first to it.second.toString() })
        waitUntil(timeoutMillis = 5_000) { shows("dropped while this is off") }
    }

    // Orrery 60's executor files a todo and an event on the calendars the
    // policy names. The ship replaces the policy whole, so it is read
    // again before the change: a copy from when the page opened would put
    // back what another device changed since.
    @Test
    fun `approved things go on the calendars picked, and the rest of the policy stays`() {
        whole = true
        settings {
            waitUntil(timeoutMillis = 5_000) { shows("Events go on") && shows("Home") }
            docs["policy"] = """{"auto":["task"],"push":"all","todo_calendar":"","event_calendar":"home"}"""
            onAllNodesWithText("The calendar's default")[0].performScrollTo().performClick()
            onAllNodesWithText("Work")[0].performClick()
            waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
            assertEquals(
                "policy" to """{"auto":["task"],"push":"all","todo_calendar":"work","event_calendar":"home"}""",
                writes.single().let { it.first to it.second.toString() },
            )
            waitUntil(timeoutMillis = 5_000) { !shows("The calendar's default") }
        }
    }

    // Orrery 60's owner step: until the schema has the new kinds and the
    // family attributes, the generator never proposes a fix and family
    // ties are dropped. Said, asked, then written whole.
    @Test
    fun `a schema behind orrery 60 is updated whole, once the owner says so`() {
        whole = true
        settings {
            waitUntil(timeoutMillis = 5_000) { shows("Your schema is behind Orrery") }
            assertTrue(shows("Orrery may propose: correct, fact, merge, preference.") && shows("People may have: spouse, children, parents, siblings."))
            onNodeWithText("Update the schema").performScrollTo().performClick()
            assertTrue(writes.isEmpty(), "asked first")
            onNodeWithText("Update").performClick()
            waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
            val (doc, sent) = writes.single()
            assertEquals("schema", doc)
            assertEquals(io.nisfeb.talon.orrery.withVersion60(Json.parseToJsonElement(SCHEMA_59).jsonObject).first, sent)
            assertEquals("Short.", sent["style"].toString().trim('"'), "the owner's own words stay")
            waitUntil(timeoutMillis = 5_000) { !shows("Your schema is behind Orrery") }
        }
    }

    @Test
    fun `a ship that does not answer is said so, and asked again`() {
        answering = false
        settings {
            waitUntil(timeoutMillis = 5_000) { shows("Your ship did not say how its chat reader is set.") }
            assertTrue(!shows("The ship reads my chats"), "no switch to guess at")
            answering = true
            onAllNodesWithText("Try again")[0].performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { shows("The ship reads my chats") }
        }
    }

    private companion object {
        /** A schema as a ship before orrery 60 keeps it, the owner's style and preferences in it. */
        const val SCHEMA_59 = """{"style":"Short.","preferences":["Never before nine"],"actions":["task","message"],""" +
            """"payloads":{"message":{"to":"required: a body id","text":"required: the message; never an exclamation mark"}},""" +
            """"kinds":{"person":{"attrs":["name","ship"],"notes":{"ship":"their @p"}}},"multi":["likes"]}"""
    }
}
