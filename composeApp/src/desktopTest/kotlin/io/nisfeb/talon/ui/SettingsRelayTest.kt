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

    private fun panel(client: RelayClient = relay(), tokens: PushTokenProvider = Tokens("https://push.test/abc"), block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                SettingsScreen(
                    aiSettings = FakeAiSettings(), themePreference = InMemoryThemePreference(), uiSettings = InMemoryUiSettings(), onBack = {},
                    relayConfig = RelayPanelConfig(client, relaySettings, tokens, activePatp = "~zod", activeShipUrl = "https://zod.test"),
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

    @Test
    fun `a refused registration says what to check, and keeps nothing`() = panel(client = relay(ok = false)) {
        register("wrong")
        waitUntil(timeoutMillis = 5_000) { shows("Registration failed.") }
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
}
