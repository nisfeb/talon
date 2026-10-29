package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.ModelInfo
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.orrery.DecideControl
import io.nisfeb.talon.orrery.DecideSettings
import io.nisfeb.talon.orrery.under
import io.nisfeb.talon.orrery.OrreryAvailability
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.ui.screens.OrrerySettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rows AI settings shows for orrery: triage says why it cannot run
 * where the ship has no orrery, and asks for a key when turned on; the
 * generator on the ship is pointed at a model it can reach, with that
 * provider's key; and Jev's thresholds are set under Advanced.
 */
@OptIn(ExperimentalTestApi::class)
class OrreryAiRowsTest {
    @Volatile private var probe = HttpStatusCode.OK
    @Volatile private var generator = """{"enabled":false}"""
    /** How long the ship takes to answer a generator write. */
    @Volatile private var putDelayMs = 0L
    private val sent = CopyOnWriteArrayList<JsonObject>()
    private val decided = MutableStateFlow(DecideSettings())
    /** Orrery's version, null for a ship that does not say (the evening review's row needs 130). */
    @Volatile private var version: Int? = null
    @Volatile private var reviewAt = "20:00"
    @Volatile private var rhythmStatus = HttpStatusCode.OK
    private val rhythmPuts = CopyOnWriteArrayList<String>()

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath.substringAfter("/apps/orrery")
        val json = { status: HttpStatusCode, body: String -> respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        when {
            // nginx's page, for a ship that is down or restarting.
            path == "/api/state" && probe == HttpStatusCode.BadGateway -> respond("<html><body>502 Bad Gateway</body></html>", probe)
            path == "/api/state" -> json(probe, "{}")
            path == "/api/clients" && req.method == HttpMethod.Post -> json(HttpStatusCode.InternalServerError, """{"error":"no keys today"}""")
            path == "/api/generator" && req.method == HttpMethod.Put -> {
                if (putDelayMs > 0) kotlinx.coroutines.delay(putDelayMs)
                val body = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                sent += body
                generator = JsonObject(Json.parseToJsonElement(generator).jsonObject + body).toString()
                json(HttpStatusCode.OK, generator)
            }
            path == "/api/generator" -> json(HttpStatusCode.OK, generator)
            path == "/api/generator/last" -> json(HttpStatusCode.NotFound, "")
            path == "/api/version" && version != null -> json(HttpStatusCode.OK, """{"version":$version}""")
            path == "/api/rhythm" && req.method == HttpMethod.Put -> {
                val body = req.body.toByteArray().decodeToString()
                rhythmPuts += body
                if (rhythmStatus == HttpStatusCode.OK) reviewAt = Json.parseToJsonElement(body).jsonObject["review_at"]!!.jsonPrimitive.content
                json(rhythmStatus, """{"quiet_from":"21:00","review_at":"$reviewAt"}""")
            }
            path == "/api/rhythm" -> json(HttpStatusCode.OK, """{"quiet_from":"21:00","review_at":"$reviewAt"}""")
            path == "/api/chat" || path == "/api/mail" -> json(HttpStatusCode.OK, """{"enabled":false}""")
            else -> json(HttpStatusCode.OK, "{}")
        }
    })

    private val openRouter = AiProvider(
        "or", ProviderKind.OpenRouter, "OpenRouter", apiKey = "sk-or",
        models = listOf(ModelInfo("m1"), ModelInfo("tiny", contextLength = 8_000)),
    )
    private val anthropic = AiProvider("an", ProviderKind.Anthropic, "Anthropic", apiKey = "sk-ant", models = listOf(ModelInfo("claude")))
    private val both = AiProfile(listOf(openRouter, anthropic), defaultModel = ModelRef("an", "claude"))

    private fun rows(
        profile: AiProfile = both,
        /** This install already feeds orrery, so triage's row is open. */
        fed: Boolean = false,
        content: @androidx.compose.runtime.Composable (FakeAiSettings, OrreryRepo) -> Unit = { ai, o -> OrrerySettingsSection(ai, orrery = o) },
        /** Wait for the ship to say whether orrery is there; false for a ship that has not. */
        answered: Boolean = true,
        block: ComposeUiTest.(FakeAiSettings, OrreryRepo) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-orrery-ai-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        if (fed) runBlocking { db.orreryAccounts().upsert(io.nisfeb.talon.data.OrreryAccountEntity("~zod", "c1", "c1.secret")) }
        val scope = CoroutineScope(SupervisorJob())
        val orrery = OrreryRepo(http, scope, db, "test", decide = DecideControl(decided) { decided.value = it }, bareClient = http)
            .apply { attach("https://ship.test", "~zod") }
        val ai = FakeAiSettings().apply {
            // Orrery on: its rows are on its own page, behind its switch.
            applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = profile.copy(orrery = true)))
        }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { content(ai, orrery) }
                    }
                }
                if (answered) waitUntil(timeoutMillis = 5_000) { orrery.availability.value != OrreryAvailability.UNKNOWN }
                waitForIdle()
                block(ai, orrery)
            }
        } finally {
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** A row's switch: the first below its title, since it sits level with the title and the lines under it. */
    private fun ComposeUiTest.switchBeside(label: String): SemanticsNodeInteraction {
        runCatching { onAllNodesWithText(label)[0].performScrollTo() }
        val top = onAllNodesWithText(label)[0].fetchSemanticsNode().boundsInRoot.top
        val switches = onAllNodes(isToggleable())
        val below = switches.fetchSemanticsNodes().indices
            .filter { switches[it].fetchSemanticsNode().boundsInRoot.center.y > top }
            .minBy { switches[it].fetchSemanticsNode().boundsInRoot.center.y }
        return switches[below]
    }

    @Test
    fun `the reading row says why it cannot run, by what the ship answered`() {
        probe = HttpStatusCode.NotFound
        rows { _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("Orrery is not on your ship. Install it from the Apps page.") }
            assertFalse(shows("Orrery needs an AI model"), "no model to offer where there is no orrery")
            assertFalse(shows("More Orrery settings"), "nor a page to open")
        }
        probe = HttpStatusCode.Forbidden
        rows { _, _ -> waitUntil(timeoutMillis = 5_000) { shows("Signed out of your ship.") } }
    }

    // A 502 while the ship restarted: the row said "Checking your ship for
    // Orrery." all session, with the reason hidden.
    @Test
    fun `a check that failed is said under the reading row, and that it is asked again`() {
        probe = HttpStatusCode.BadGateway
        rows(answered = false) { _, orrery ->
            waitUntil(timeoutMillis = 5_000) { shows("Your ship did not answer. Talon asks again by itself.") }
            assertTrue(shows("Checking your ship for Orrery."))
            assertFalse(shows("Bad Gateway"), "never the proxy's page")
            assertEquals(OrreryAvailability.UNKNOWN, orrery.availability.value)
        }
    }

    // No switch of its own: Use Orrery makes the key. Try again is for when that failed.
    @Test
    fun `a device whose key could not be made says why, and Try again asks again`() = rows { _, orrery ->
        waitUntil(timeoutMillis = 5_000) { shows("This device is not set up for Orrery yet.") }
        assertFalse(shows("Orrery triage"))
        onNodeWithText("Try again").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Orrery could not be read: no keys today.") }
        assertFalse(shows("HTTP 500"), "in words, not the request's own text")
        assertFalse(orrery.enabled.value)
    }

    @Test
    fun `a device that reads shows when it last sent, and no switch to stop it here`() = rows(fed = true) { _, _ ->
        // Sent already, or not yet: the first pass may have run by now.
        waitUntil(timeoutMillis = 5_000) { shows("Not sent yet.") || shows("Last sent") }
        assertTrue(shows("Send now"))
        assertFalse(shows("Try again"))
        assertFalse(shows("Leave reading to my computer"), "a phone always leaves the reading to a computer on the job")
    }

    // Jev, renamed: one switch, no Advanced, and nothing breaks without it.
    @Test
    fun `the decision model is one switch, and says what it needs where nothing offers one`() = rows { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Decision model") }
        assertFalse(shows("Jev"), "called a decision model")
        assertFalse(shows("Advanced"))
        assertTrue(shows("Your Armillary vendor offers no decision model."))
        switchBeside("Decision model").assertIsNotEnabled()
    }

    @Test
    fun `the decision model left on with nothing offering it says so, and can be turned off`() = rows(both.copy(jev = true)) { ai, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Your Armillary vendor offers no decision model now, so nothing is screened.") }
        switchBeside("Decision model").performClick()
        waitUntil(timeoutMillis = 5_000) { ai.state.value.savedProfile!!.jev == false }
    }

    @Test
    fun `a vendor that names a decision model turns it on with one switch`() = rows(
        both.copy(providers = listOf(io.nisfeb.talon.ai.armillaryRow(suggested = mapOf("default" to "a/1", "decision" to "typesafe/jev-1.13")))),
    ) { ai, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Decision model") }
        assertFalse(shows("offers no decision model"))
        switchBeside("Decision model").performClick()
        waitUntil(timeoutMillis = 5_000) { ai.state.value.savedProfile!!.jev == true }
        // On: the screen, the status check and the body picks, at the kept thresholds.
        val d = decided.value.under(ai.state.value)
        assertTrue(d.on && d.gate && d.relevance)
        assertEquals(0.3 to 0.5, d.threshold to d.keep)
    }


    @Test
    fun `triage reads on this device, whatever the profile kept from before`() = rows(
        AiProfile(
            listOf(AiProvider("lan", ProviderKind.OpenAiCompatible, "My server", baseUrl = "http://192.168.1.5:1234/v1"), AiProvider("device", ProviderKind.ThisDevice, "On this device")),
            defaultModel = ModelRef("lan", "llama"),
        ),
        fed = true,
    ) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Read calls and status lines on this device") }
        waitUntil(timeoutMillis = 5_000) { shows("Runs on this device. Nothing it reads leaves it.") }
        assertFalse(shows("llama"), "an own server from before is not offered")
    }

    // ---- The evening review's reminder (orrery 130's rhythm review_at) ----

    @Test
    fun `the evening reminder is the ship's time, set alone, and Off turns it off`() {
        version = 130
        rows { _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("Each evening at 20:00") || shows("reminded each evening at 20:00") }
            onAllNodesWithText("Change")[0].performScrollTo().performClick()
            onNodeWithText("21:30").performClick()
            waitUntil(timeoutMillis = 5_000) { rhythmPuts.isNotEmpty() }
            assertEquals(listOf("""{"review_at":"21:30"}"""), rhythmPuts.toList(), "only review_at: the PUT merges, so quiet hours are kept")
            assertTrue(shows("at 21:30"))
            onAllNodesWithText("Change")[0].performScrollTo().performClick()
            onNodeWithText("Off").performClick()
            waitUntil(timeoutMillis = 5_000) { rhythmPuts.size == 2 }
            assertEquals("""{"review_at":""}""", rhythmPuts.last())
            waitUntil(timeoutMillis = 5_000) { shows("Off. You can still open the review from Home or Orrery.") }
        }
    }

    @Test
    fun `a time the ship refuses goes back, said, and an orrery before 130 has no row`() {
        version = 130
        rhythmStatus = HttpStatusCode.InternalServerError
        rows { _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("at 20:00") }
            onAllNodesWithText("Change")[0].performScrollTo().performClick()
            onNodeWithText("21:30").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Your ship did not take the new time.") }
            assertTrue(shows("at 20:00") && !shows("at 21:30"))
        }
        version = 129
        rows { _, o ->
            waitUntil(timeoutMillis = 5_000) { o.version.value == 129 }
            waitForIdle()
            assertFalse(shows("Evening review reminder"))
        }
    }
}
