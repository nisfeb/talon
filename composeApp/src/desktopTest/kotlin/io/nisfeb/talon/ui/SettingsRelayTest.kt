package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.notify.InMemoryRelaySettings
import io.nisfeb.talon.notify.PushTokenProvider
import io.nisfeb.talon.notify.RelayClient
import io.nisfeb.talon.ui.screens.RelayPanelConfig
import io.nisfeb.talon.ui.screens.SettingsScreen
import io.nisfeb.talon.ui.theme.InMemoryThemePreference
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The push relay: this device registered with it using the ship's
 * +code, which goes to the relay and is kept nowhere here; refused, or
 * with no push distributor, it says so; unregistered on asking.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsRelayTest {
    private val asked: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val relaySettings = InMemoryRelaySettings("https://relay.test")

    private fun relay(ok: Boolean = true) = RelayClient(HttpClient(MockEngine { req ->
        asked += "${req.method.value} ${req.url} ${req.body.toByteArray().decodeToString()}"
        val body = if (ok) """{"ok":true,"deviceId":"dev-12345678-abc"}""" else """{"ok":false,"error":"bad code"}"""
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    })) { relaySettings.endpoint.value }

    private class Tokens(private val endpoint: String?) : PushTokenProvider {
        override val platform = "unifiedpush"
        override suspend fun token() = endpoint
    }

    private fun panel(
        client: RelayClient = relay(),
        tokens: PushTokenProvider = Tokens("https://push.test/abc"),
        shipPoke: (suspend (kotlinx.serialization.json.JsonElement) -> Unit)? = null,
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                SettingsScreen(
                    aiSettings = FakeAiSettings(), themePreference = InMemoryThemePreference(), uiSettings = InMemoryUiSettings(), onBack = {},
                    relayConfig = RelayPanelConfig(client, relaySettings, tokens, activePatp = "~zod", activeShipUrl = "https://zod.test", shipPoke = shipPoke),
                )
            }
        }
        onAllNodesWithText("Notifications")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Push relay") }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.register(code: String) {
        onNodeWithText("Register this device").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Register with relay") }
        onNode(hasSetTextAction() and hasText("+code")).performTextInput(code)
        onAllNodesWithText("Register").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
    }

    @Test
    fun `registering sends the ship, its push endpoint and code, and keeps only the device id`() = panel {
        assertTrue(shows("Not registered with the relay yet."))
        register("lidlut-tabwed")
        waitUntil(timeoutMillis = 5_000) { shows("Registered (deviceId=dev-1234…)") }
        val sent = asked.single()
        assertTrue(sent.startsWith("POST https://relay.test/register"), sent)
        for (part in listOf("\"patp\":\"~zod\"", "\"shipUrl\":\"https://zod.test\"", "\"pushEndpoint\":\"https://push.test/abc\"", "\"code\":\"lidlut-tabwed\"")) {
            assertTrue(part in sent, "$part in $sent")
        }
        assertEquals("dev-12345678-abc", relaySettings.deviceIdFor("~zod"))
    }

    // A push the receiver does not know shows as a new message on an
    // older app, so the relay sends "read" only to a device that said it
    // understands one, on its own route: a relay from before refuses a
    // registration carrying a field it does not know.
    @Test
    fun `a phone whose receiver understands reads says so after registering, not inside it`() = panel(tokens = object : PushTokenProvider {
        override val platform = "unifiedpush"
        override val caps = listOf("read")
        override suspend fun token() = "https://push.test/abc"
    }) {
        register("lidlut-tabwed")
        waitUntil(timeoutMillis = 5_000) { asked.size == 2 }
        assertTrue(asked[0].startsWith("POST https://relay.test/register") && "caps" !in asked[0], asked[0])
        assertEquals("""POST https://relay.test/devices/dev-12345678-abc/caps {"caps":["read"]}""", asked[1])
    }

    @Test
    fun `a refused registration says what to check, and keeps nothing`() = panel(client = relay(ok = false)) {
        register("wrong")
        waitUntil(timeoutMillis = 5_000) { shows("The relay could not sign in to your ship.") }
        assertEquals("", relaySettings.deviceIdFor("~zod"))
    }

    @Test
    fun `with no push distributor nothing is sent, and it says what to install`() = panel(tokens = Tokens(null)) {
        register("lidlut-tabwed")
        waitUntil(timeoutMillis = 5_000) { shows("No UnifiedPush distributor found.") }
        assertTrue(asked.isEmpty(), "the code never left")
    }

    @Test
    fun `a registered device is unregistered on asking`() {
        relaySettings.setDeviceIdFor("~zod", "dev-12345678-abc")
        panel {
            assertTrue(shows("Registered (deviceId=dev-1234…)"))
            onNodeWithText("Unregister").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Not registered with the relay yet.") }
            assertTrue(asked.single().startsWith("DELETE https://relay.test/devices/dev-12345678-abc"), asked.toString())
            assertEquals("", relaySettings.deviceIdFor("~zod"))
        }
    }

    @Test
    fun `the relay's address is saved as typed`() = panel {
        onNode(hasSetTextAction() and hasText("Endpoint")).performScrollTo().performTextReplacement("https://relay.example/v2")
        onNodeWithText("Save endpoint").performClick()
        assertEquals("https://relay.example/v2", relaySettings.endpoint.value)
    }

    // sneagan, on rc42: "I don't see the ricsul relay anywhere in settings",
    // then "make it more obvious and also name the ship". The line sat
    // inside the relay panel and said only "your own ship".
    @Test
    fun `a device on its ship's notifications says so first, naming the ship`() {
        val pokes = java.util.concurrent.CopyOnWriteArrayList<String>()
        relaySettings.setViaShipPush("~zod", true)
        relaySettings.setTrunkDeviceIdFor("~zod", "t-1")
        panel(shipPoke = { pokes += it.toString() }) {
            assertTrue(shows("Notifications come from ~zod"))
            val row = onNodeWithText("Notifications come from ~zod").fetchSemanticsNode().boundsInRoot.top
            val relay = onNodeWithText("Push relay").fetchSemanticsNode().boundsInRoot.top
            assertTrue(row < relay, "above the relay panel, not inside it")
            onNodeWithText("Use the relay").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Notifications come from the Talon relay") }
            assertEquals(listOf("""{"push-unregister":"t-1"}"""), pokes.toList())
            assertTrue(shows("Use my ship"))
        }
    }

    @Test
    fun `a device never on its ship's notifications shows no such row`() = panel(shipPoke = {}) {
        assertTrue(!shows("Notifications come from"))
    }
}

