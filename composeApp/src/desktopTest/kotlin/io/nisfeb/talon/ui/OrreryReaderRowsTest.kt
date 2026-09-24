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
import io.nisfeb.talon.ui.screens.AiSettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    ))
    private val writes: MutableList<Pair<String, JsonObject>> = Collections.synchronizedList(mutableListOf())
    @Volatile private var answering = true

    private val http = HttpClient(MockEngine { req ->
        val doc = req.url.encodedPath.substringAfter("/apps/orrery/api/", "")
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        when {
            doc in setOf("chat", "mail") && !answering -> respond("down", HttpStatusCode.InternalServerError)
            doc in setOf("chat", "mail") && req.method == HttpMethod.Put -> {
                val sent = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                writes += doc to sent
                // The ship takes the change and answers with the whole document.
                val now = Json.parseToJsonElement(docs.getValue(doc)).jsonObject
                docs[doc] = JsonObject(now + sent).toString()
                json(docs.getValue(doc))
            }
            doc in setOf("chat", "mail") -> json(docs.getValue(doc))
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
                            AiSettingsSection(FakeAiSettings(), orrery = orrery)
                        }
                    }
                }
                waitForIdle()
                block()
            }
        } finally {
            scope.cancel()
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
}
