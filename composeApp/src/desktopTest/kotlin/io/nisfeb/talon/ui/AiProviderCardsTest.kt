package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.sun.net.httpserver.HttpServer
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.ModelInfo
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.ui.screens.AiSettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The providers in AI settings, against a server of the owner's own:
 * an address is saved before its models are fetched, a refusal says
 * why, Test times it, a row comes and goes, and the default model is
 * picked from the list, narrowed by a search, or typed.
 */
@OptIn(ExperimentalTestApi::class)
class AiProviderCardsTest {
    @Volatile private var status = 200

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/models") { ex ->
            val out = (if (status == 200) """{"data":[{"id":"qwen3"},{"id":"whisper-large"},{"id":"text-embedding-3"}]}""" else "busy")
                .encodeToByteArray()
            ex.sendResponseHeaders(status, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }
    private val address = "http://127.0.0.1:${server.address.port}/v1"

    @AfterTest fun stop() = server.stop(0)

    private val home = AiProvider("srv", ProviderKind.OpenAiCompatible, "Home box")

    private fun section(vararg providers: AiProvider, default: ModelRef? = null, jev: Boolean? = null, orreryPage: Boolean = false, block: ComposeUiTest.(FakeAiSettings) -> Unit) {
        val ai = FakeAiSettings().apply {
            applyRemote(AiSettings.Config(
                provider = AiSettings.Provider.Anthropic, apiKey = "", model = null,
                savedProfile = AiProfile(providers.toList(), defaultModel = default, jev = jev, orrery = orreryPage.takeIf { it }),
            ))
        }
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        // Jev is Orrery's, on Orrery's page, with Orrery on.
                        if (orreryPage) io.nisfeb.talon.ui.screens.OrrerySettingsSection(ai, orrery = null)
                        else AiSettingsSection(ai, orrery = null)
                    }
                }
            }
            waitForIdle()
            block(ai)
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun FakeAiSettings.saved(id: String) = state.value.savedProfile!!.provider(id)

    @Test
    fun `an address is saved, then its models fetched, and a server on this machine is private`() = section(home) { ai ->
        onNode(hasSetTextAction() and hasText("Address")).performTextInput(address)
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { ai.saved("srv")!!.models.isNotEmpty() }
        val srv = ai.saved("srv")!!
        assertEquals(address, srv.baseUrl)
        assertEquals(listOf("qwen3", "whisper-large"), srv.models.map { it.id }, "what cannot chat or transcribe is left out")
        assertTrue(srv.models.single { it.id == "whisper-large" }.speech)
        waitUntil(timeoutMillis = 5_000) { shows("2 models. Private: on your own machine or network.") }
    }

    @Test
    fun `a server that will not list its models says why, and the address stays`() = section(home.copy(baseUrl = address)) { ai ->
        status = 503
        onNodeWithText("Fetch models").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Answered 503: busy") }
        assertEquals(address, ai.saved("srv")!!.baseUrl)
        assertTrue(ai.saved("srv")!!.models.isEmpty())
    }

    @Test
    fun `Test says how fast it answers, and that the key was refused`() = section(home.copy(baseUrl = address)) {
        onNodeWithText("Test").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Answers in") }
        status = 401
        onNodeWithText("Test").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("The key was refused (401).") }
    }

    @Test
    fun `a provider is removed, and this device cannot be`() =
        section(AiProvider("device", ProviderKind.ThisDevice, "This device"), home) { ai ->
            assertEquals(1, onAllNodesWithText("Remove").fetchSemanticsNodes().size)
            onNodeWithText("Remove").performClick()
            waitForIdle()
            assertEquals(listOf("device"), ai.state.value.savedProfile!!.providers.map { it.id })
        }

    @Test
    fun `the first provider added is the default, and armillary is offered once`() = section { ai ->
        onNodeWithText("Add a provider").performScrollTo().performClick()
        onNodeWithText("OpenRouter").performClick()
        waitForIdle()
        val openRouter = ai.state.value.savedProfile!!.providers.single()
        assertEquals(ProviderKind.OpenRouter, openRouter.kind)
        assertEquals(ModelRef(openRouter.id, ""), ai.state.value.savedProfile!!.defaultModel, "with no default nothing resolves")

        onNodeWithText("Add a provider").performScrollTo().performClick()
        onNodeWithText("A server of your own").performClick()
        waitForIdle()
        val own = ai.state.value.savedProfile!!.providers.last()
        assertEquals("http://localhost:1234/v1", own.baseUrl, "a desktop is where LM Studio runs")
        assertEquals(openRouter.id, ai.state.value.savedProfile!!.defaultModel!!.provider, "the first stays the default")

        onNodeWithText("Add a provider").performScrollTo().performClick()
        onNodeWithText("Armillary").performClick()
        waitForIdle()
        onNodeWithText("Add a provider").performScrollTo().performClick()
        assertTrue(onAllNodesWithText("Armillary").fetchSemanticsNodes().size == 1, "one Armillary row, and no second offered")
    }

    @Test
    fun `the default model is picked, searched for, or typed`() =
        section(home.copy(baseUrl = address, models = listOf(ModelInfo("qwen3"), ModelInfo("llama3")))) { ai ->
            onNodeWithText("None chosen").performClick()
            onNodeWithText("llama3").performClick()
            waitForIdle()
            assertEquals(ModelRef("srv", "llama3"), ai.state.value.savedProfile!!.defaultModel)

            onNodeWithText("llama3, Home box · Private").performClick()
            onNode(hasSetTextAction() and hasText("Search, or type a model's id")).performTextInput("qwe")
            waitForIdle()
            assertTrue(onAllNodesWithText("qwen3").fetchSemanticsNodes().isNotEmpty(), "what matches stays")
            assertTrue(onAllNodesWithText("llama3").fetchSemanticsNodes().isEmpty(), "a search narrows the list")
            onNode(hasSetTextAction() and hasText("qwe")).performTextInput("n9")
            onNodeWithText("Use \"qwen9\" on Home box").performClick()
            waitForIdle()
            assertEquals(ModelRef("srv", "qwen9"), ai.state.value.savedProfile!!.defaultModel, "a server may load a model it did not list")
        }

    @Test
    fun `transcription wants a speech model, and waits for a provider with one`() {
        section(AiProvider("or", ProviderKind.OpenRouter, "OpenRouter", apiKey = "k")) {
            switchBeside("Transcription").assertIsNotEnabled()
        }
        section(home.copy(baseUrl = address, models = listOf(ModelInfo("qwen3"), ModelInfo("whisper-large", speech = true)))) { ai ->
            switchBeside("Transcription").assertIsEnabled().performClick()
            waitForIdle()
            assertTrue(ai.state.value.savedProfile!!.isOn(AiFeature.Transcription))
            // The default model's picker, then this one below the switch.
            assertEquals(2, onAllNodesWithText("None chosen").fetchSemanticsNodes().size)
            onAllNodesWithText("None chosen")[1].performScrollTo().performClick()
            assertTrue(onAllNodesWithText("qwen3").fetchSemanticsNodes().isEmpty(), "a model that cannot transcribe is not offered")
            onNodeWithText("whisper-large").performClick()
            waitForIdle()
            assertEquals(ModelRef("srv", "whisper-large"), ai.state.value.savedProfile!!.features[AiFeature.Transcription]?.model)
        }
    }

    @Test
    fun `jev left on with nobody offering it says so, and can still be turned off`() =
        section(home.copy(baseUrl = address), jev = true, orreryPage = true) { ai ->
            assertTrue(shows("No provider offers Jev any more, so nothing is being gated."))
            switchBeside("Jev gating").assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(false, ai.state.value.savedProfile!!.jev)
            waitUntil(timeoutMillis = 5_000) { shows("Needs an OpenRouter provider with a key") }
        }

    /** The switch drawn nearest [label]. */
    private fun ComposeUiTest.switchBeside(label: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        runCatching { onAllNodesWithText(label)[0].performScrollTo() }
        val y = onAllNodesWithText(label)[0].fetchSemanticsNode().boundsInRoot.center.y
        val switches = onAllNodes(isToggleable())
        val nearest = switches.fetchSemanticsNodes().indices
            .minBy { kotlin.math.abs(switches[it].fetchSemanticsNode().boundsInRoot.center.y - y) }
        return switches[nearest]
    }
}
