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
    private val sent = CopyOnWriteArrayList<JsonObject>()
    private val decided = MutableStateFlow(DecideSettings())

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath.substringAfter("/apps/orrery")
        val json = { status: HttpStatusCode, body: String -> respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        when {
            path == "/api/state" -> json(probe, "{}")
            path == "/api/clients" && req.method == HttpMethod.Post -> json(HttpStatusCode.InternalServerError, """{"error":"no keys today"}""")
            path == "/api/generator" && req.method == HttpMethod.Put -> {
                val body = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                sent += body
                generator = JsonObject(Json.parseToJsonElement(generator).jsonObject + body).toString()
                json(HttpStatusCode.OK, generator)
            }
            path == "/api/generator" -> json(HttpStatusCode.OK, generator)
            path == "/api/generator/last" -> json(HttpStatusCode.NotFound, "")
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

    private fun rows(profile: AiProfile = both, block: ComposeUiTest.(FakeAiSettings, OrreryRepo) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-orrery-ai-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
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
                        Column(Modifier.verticalScroll(rememberScrollState())) { OrrerySettingsSection(ai, orrery = orrery) }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { orrery.availability.value != OrreryAvailability.UNKNOWN }
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
    fun `triage says why it cannot run, by what the ship answered`() {
        probe = HttpStatusCode.NotFound
        rows { _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("Orrery is not on this ship.") }
            switchBeside("Orrery triage").assertIsNotEnabled()
            assertFalse(shows("Orrery analysis"), "no generator to point where there is no orrery")
        }
        probe = HttpStatusCode.Forbidden
        rows { _, _ -> waitUntil(timeoutMillis = 5_000) { shows("Signed out of the ship.") } }
    }

    @Test
    fun `turning triage on asks the ship for a key, and a refusal says why`() = rows { _, orrery ->
        switchBeside("Orrery triage").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("HTTP 500: no keys today") }
        assertFalse(orrery.enabled.value)
    }

    @Test
    fun `the generator turned on with a model the ship cannot reach says so, and writes nothing`() = rows { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Orrery analysis") }
        switchBeside("Orrery analysis").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("The ship cannot reach Anthropic.") }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the generator is pointed at a model the ship can reach, with that provider's key`() {
        generator = """{"enabled":true}"""
        rows { ai, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("Orrery analysis") }
            // Catch-up's picker, the assistant's, then the generator's.
            onAllNodesWithText("Default: claude, Anthropic").let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()
            assertTrue(shows("short context"), "a model with little context is flagged")
            assertTrue(onAllNodesWithText("claude").fetchSemanticsNodes().isEmpty(), "Anthropic is not offered: the ship cannot reach it")
            onNodeWithText("m1").performClick()
            waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
            val put = sent.single()
            assertEquals("https://openrouter.ai/api/v1", put["url"]?.jsonPrimitive?.content)
            assertEquals("m1", put["model"]?.jsonPrimitive?.content)
            assertEquals("sk-or", put["api_key"]?.jsonPrimitive?.content)
            waitUntil(timeoutMillis = 5_000) { ai.state.value.savedProfile!!.features[AiFeature.OrreryGenerator]?.model == ModelRef("or", "m1") }
            waitUntil(timeoutMillis = 5_000) { shows("On the ship: m1 at https://openrouter.ai/api/v1.") }
        }
    }

    @Test
    fun `jev's thresholds are set under advanced, and nonsense is not`() = rows(both.copy(jev = true)) { _, _ ->
        onNodeWithText("Advanced").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Nothing read with it yet today.") }
        onNode(hasSetTextAction() and hasText("Gate threshold")).performTextReplacement("0.25")
        waitForIdle()
        assertEquals(0.25, decided.value.threshold)
        onNode(hasSetTextAction() and hasText("Gate threshold")).performTextReplacement("5")
        waitForIdle()
        assertEquals(0.25, decided.value.threshold, "out of range is not a threshold")
        onNode(hasSetTextAction() and hasText("Keep bodies at or above")).performTextReplacement("0.6")
        onNode(hasSetTextAction() and hasText("Jev model")).performTextReplacement(" typesafe/jev-2 ")
        waitForIdle()
        assertEquals(0.6 to "typesafe/jev-2", decided.value.keep to decided.value.model)
        onNodeWithText("Hide advanced").performClick()
        waitForIdle()
        assertFalse(shows("Gate threshold"))
    }
}
