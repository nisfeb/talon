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
    /** How long the ship takes to answer a generator write. */
    @Volatile private var putDelayMs = 0L
    private val sent = CopyOnWriteArrayList<JsonObject>()
    private val decided = MutableStateFlow(DecideSettings())

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath.substringAfter("/apps/orrery")
        val json = { status: HttpStatusCode, body: String -> respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        when {
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

    // It was a bare box: one slip from an id that exists, and nothing said so.
    @Test
    fun `the jev model offers the provider's models as it is typed`() = rows(
        both.copy(jev = true, providers = listOf(openRouter.copy(models = openRouter.models + ModelInfo("typesafe/jev-1.14", "TypeSafe: Jev 1.14", zdr = true), offersJev = true), anthropic)),
    ) { _, _ ->
        onNodeWithText("Advanced").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Nothing read with it yet today.") }
        onNode(hasSetTextAction() and hasText("Jev model")).performTextReplacement("jv114")
        waitUntil(timeoutMillis = 5_000) { shows("TypeSafe: Jev 1.14") }
        onNodeWithText("TypeSafe: Jev 1.14").performClick()
        waitForIdle()
        assertEquals("typesafe/jev-1.14", decided.value.model)
        assertFalse(shows("TypeSafe: Jev 1.14"), "picked: the list closes")
    }

    // A user setting up Talon and Orrery for the first time found the AI he
    // set up in Talon did not carry over: the ship's generator had no model,
    // and nothing said so (2026-10-09).
    private val openRouterDefault = AiProfile(listOf(openRouter, anthropic), defaultModel = ModelRef("or", "m1"))

    @Test
    fun `a ship with no model for Orrery is offered the default, sent with its key in one tap`() = rows(openRouterDefault) { ai, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Orrery has no model on your ship yet") }
        assertTrue(shows("sends your OpenRouter key to your ship"), "it says what the tap sends")
        onNodeWithText("Use m1, OpenRouter").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
        assertEquals(
            Json.parseToJsonElement("""{"enabled":true,"url":"${io.nisfeb.talon.ai.ModelCatalog.OPENROUTER}","model":"m1","api_key":"sk-or"}"""),
            sent.single(),
        )
        waitUntil(timeoutMillis = 5_000) { !shows("Orrery has no model on your ship yet") }
        assertEquals(null, ai.state.value.savedProfile!!.features[AiFeature.OrreryGenerator]?.model, "Orrery follows the default model")
    }

    @Test
    fun `a default the ship cannot reach is said so, and nothing is sent`() = rows { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Orrery has no model on your ship yet") }
        assertTrue(shows("The ship cannot reach Anthropic."))
        assertFalse(shows("Use claude"), "no one-tap for a model the ship cannot run")
        assertTrue(sent.isEmpty(), sent.toString())
    }

    @Test
    fun `a ship that has a model and key for Orrery is offered nothing`() {
        generator = """{"enabled":false,"model":"m1","api_key_set":true}"""
        rows(openRouterDefault) { _, _ ->
            waitForIdle()
            assertFalse(shows("Orrery has no model on your ship yet"))
        }
    }

    @Test
    fun `not now puts the offer away and sends nothing`() = rows(openRouterDefault) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Orrery has no model on your ship yet") }
        onNodeWithText("Not now").performScrollTo().performClick()
        waitForIdle()
        assertFalse(shows("Orrery has no model on your ship yet"))
        assertTrue(sent.isEmpty(), sent.toString())
    }

    // From the 2026-10-10 review.
    private val openAi = AiProvider("oa", ProviderKind.OpenAi, "OpenAI", apiKey = "sk-oa")

    @Test
    fun `a default with no model named offers and sends the model Talon runs there, not none`() = rows(AiProfile(listOf(openAi), defaultModel = ModelRef("oa", ""))) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Use gpt-4o-mini") }
        onNodeWithText("Use gpt-4o-mini", substring = true).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
        assertEquals("gpt-4o-mini", sent.single()["model"]?.jsonPrimitive?.content, "a blank model made Orrery run its own kimi-k3 against OpenAI")
    }

    @Test
    fun `a server with no key is not offered, and the card says why`() {
        val local = AiProvider("lan", ProviderKind.OpenAiCompatible, "My server", baseUrl = "http://10.0.0.5:11434/v1", models = listOf(ModelInfo("llama")))
        rows(AiProfile(listOf(local), defaultModel = ModelRef("lan", "llama"))) { _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("has no key in Talon") }
            assertFalse(shows("Use llama"), "Orrery would refuse it: the generator has no key")
            assertTrue(sent.isEmpty(), sent.toString())
        }
    }

    @Test
    fun `a ship that names a model but holds no key is told it lacks the key`() {
        generator = """{"enabled":false,"model":"moonshotai/kimi-k3","api_key_set":false}"""
        rows(openRouterDefault) { _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("Orrery on your ship has no key for its AI yet") }
            assertFalse(shows("Orrery has no model on your ship yet"))
        }
    }

    @Test
    fun `the generator turns off even when the default is out of the ship's reach`() {
        generator = """{"enabled":true,"model":"m1","api_key_set":true}"""
        rows { _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("Orrery analysis") }
            switchBeside("Orrery analysis").performClick()
            waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
            assertEquals(Json.parseToJsonElement("""{"enabled":false}"""), sent.single())
        }
    }

    // A user set his default model to a server of his own and found triage
    // and analysis did not follow it (2026-10-12).
    private val lan = AiProvider("lan", ProviderKind.OpenAiCompatible, "My server", baseUrl = "http://10.0.0.5:11434/v1", models = listOf(ModelInfo("llama")))
    private val device = AiProvider(io.nisfeb.talon.ai.DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")
    private val following = openRouterDefault.copy(features = mapOf(AiFeature.OrreryGenerator to io.nisfeb.talon.ai.FeatureSetting(on = true, own = true)))
    private val onTheShip = """{"enabled":true,"url":"${io.nisfeb.talon.ai.ModelCatalog.OPENROUTER}","model":"m1","api_key_set":true}"""

    @Test
    fun `triage under a cloud default reads on this device, and says so`() = rows(both.copy(providers = both.providers + device), fed = true) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Your default model is not private, so triage reads on this device.") }
        assertTrue(shows("Default: This device's own model, On this device"), "Default on triage's row is this device, not the cloud default")
    }

    @Test
    fun `triage under a private default reads with it`() = rows(AiProfile(listOf(lan, device), defaultModel = ModelRef("lan", "llama")), fed = true) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Orrery triage") }
        waitUntil(timeoutMillis = 5_000) { shows("Default: llama, My server") }
        assertFalse(shows("Your default model is not private"))
    }

    @Test
    fun `analysis on Default with a default the ship cannot run says so before anything is pressed`() {
        generator = onTheShip
        rows { _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("The ship cannot reach Anthropic.") }
            assertTrue(sent.isEmpty(), sent.toString())
        }
    }

    @Test
    fun `analysis set to Default moves with the default, with its key, and nothing else moves it`() {
        generator = onTheShip
        rows(following) { _, orrery ->
            waitUntil(timeoutMillis = 5_000) { orrery.generatorSettings.value?.model == "m1" }
            val tiny = following.copy(defaultModel = ModelRef("or", "tiny"))
            // Nothing the ship is told changed: a model list fetched again.
            orrery.followDefault(following, following.copy(providers = following.providers.map { it.copy(models = emptyList()) }))
            // Never picked as Default: it may be set up on the ship itself.
            orrery.followDefault(openRouterDefault, tiny.copy(features = emptyMap()))
            // A model of its own.
            orrery.followDefault(following, tiny.copy(features = mapOf(AiFeature.OrreryGenerator to io.nisfeb.talon.ai.FeatureSetting(true, ModelRef("or", "m1"), own = true))))
            // A default the ship cannot run.
            orrery.followDefault(following, following.copy(defaultModel = ModelRef("an", "claude")))
            orrery.followDefault(tiny, tiny.copy(providers = listOf(openRouter, anthropic, lan), defaultModel = ModelRef("lan", "llama")))
            orrery.followDefault(following, tiny)
            waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
            assertEquals(Json.parseToJsonElement("""{"enabled":true,"url":"${io.nisfeb.talon.ai.ModelCatalog.OPENROUTER}","model":"tiny","api_key":"sk-or"}"""), sent.single())
            // A new key for the same model goes too.
            orrery.followDefault(tiny, tiny.copy(providers = listOf(openRouter.copy(apiKey = "sk-or-2"), anthropic)))
            waitUntil(timeoutMillis = 5_000) { sent.size == 2 }
            assertEquals("sk-or-2", sent[1]["api_key"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `analysis that is off, or has no key on the ship, is not moved by the default`() {
        for (ship in listOf("""{"enabled":false,"model":"m1","api_key_set":true}""", """{"enabled":true,"model":"m1","api_key_set":false}""", """{"enabled":true}""")) {
            generator = ship
            rows(following) { _, orrery ->
                waitUntil(timeoutMillis = 5_000) { orrery.generatorSettings.value != null }
                orrery.followDefault(following, following.copy(defaultModel = ModelRef("or", "tiny")))
                Thread.sleep(300)
                assertTrue(sent.isEmpty(), "$ship: $sent")
            }
        }
    }

    @Test
    fun `picking a new default in AI settings moves analysis, even after leaving the screen`() {
        generator = onTheShip
        putDelayMs = 500
        val shown = androidx.compose.runtime.mutableStateOf(true)
        rows(following, content = { ai, o -> if (shown.value) io.nisfeb.talon.ui.screens.AiSettingsSection(ai, orrery = o, catalog = quietCatalog()) }) { ai, orrery ->
            waitUntil(timeoutMillis = 5_000) { orrery.generatorSettings.value?.model == "m1" }
            onNodeWithText("m1, OpenRouter").performScrollTo().performClick()
            onNodeWithText("tiny").performClick()
            shown.value = false
            waitForIdle()
            assertEquals(ModelRef("or", "tiny"), ai.state.value.savedProfile!!.defaultModel)
            waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
            assertEquals(Json.parseToJsonElement("""{"enabled":true,"url":"${io.nisfeb.talon.ai.ModelCatalog.OPENROUTER}","model":"tiny","api_key":"sk-or"}"""), sent.single())
        }
    }

    @Test
    fun `picking Default for analysis is what makes it follow`() {
        generator = onTheShip
        rows(openRouterDefault) { ai, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("Orrery analysis") }
            onAllNodesWithText("Default: m1, OpenRouter").let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()
            onAllNodesWithText("Default: m1, OpenRouter").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            waitUntil(timeoutMillis = 5_000) { ai.state.value.savedProfile!!.generatorFollowsDefault() }
        }
    }
}
