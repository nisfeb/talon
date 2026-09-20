package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.armillary.Account
import io.nisfeb.talon.armillary.ArmillaryRepo
import io.nisfeb.talon.ui.screens.armillaryModeLine
import io.nisfeb.talon.ui.screens.balanceWarning
import io.nisfeb.talon.ui.screens.dollarsToMicro
import io.nisfeb.talon.ui.screens.planLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import io.nisfeb.talon.ui.screens.AiSettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AiSettingsSectionTest {
    @Test
    fun `the first change saves the profile the old settings made, and jev waits for openrouter`() = runComposeUiTest {
        val ai = FakeAiSettings().apply { applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant", model = "claude-opus-5")) }
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null) }
            }
        }
        onNodeWithText("Providers").assertExists()
        onNodeWithText("claude-opus-5, Anthropic").assertExists()
        onNodeWithText("Default: claude-opus-5, Anthropic").assertExists()
        onNodeWithText("Needs an OpenRouter provider with a key", substring = true).assertExists()
        assertNull(ai.state.value.savedProfile, "looking saves nothing")

        // Rows in order: catch-up first, Jev last and greyed.
        val switches = onAllNodes(isToggleable())
        switches[switches.fetchSemanticsNodes().size - 1].assertIsNotEnabled()
        switches[0].performClick()
        waitForIdle()
        val saved = ai.state.value.savedProfile!!
        assertFalse(saved.isOn(AiFeature.CatchUp))
        assertFalse(ai.state.value.catchMeUpEnabled, "the old switch follows, for older installs")
        assertEquals("sk-ant", saved.provider(saved.defaultModel!!.provider)!!.apiKey)
        assertTrue(onAllNodesWithText("Anthropic", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // ── the Armillary card ─────────────────────────────────────────

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    /** A profile holding the Armillary row, which is what makes the card show. */
    private fun withArmillary() = AiProfile(
        providers = listOf(
            AiProvider("main", ProviderKind.Anthropic, "Anthropic", apiKey = "sk-ant"),
            AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary"),
        ),
        defaultModel = ModelRef("main", "claude-opus-5"),
    )

    /** A repo over a ship that answers [reply], already refreshed once. */
    private fun repo(ai: FakeAiSettings, reply: (String) -> Pair<HttpStatusCode, String>): ArmillaryRepo {
        val client = HttpClient(MockEngine { req ->
            val (status, body) = reply(req.url.encodedPath)
            respond(body, status, jsonHeaders)
        })
        val r = ArmillaryRepo(client, CoroutineScope(Dispatchers.Default), ai)
        r.attach("https://ship.example", "~feb")
        runBlocking { r.refresh() }
        return r
    }

    private fun FakeAiSettings.withProfile(p: AiProfile): FakeAiSettings = apply {
        applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant", model = "claude-opus-5", savedProfile = p))
    }

    @Test
    fun `a ship without armillary says so on the card and offers nothing to buy`() = runComposeUiTest {
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = repo(ai) { HttpStatusCode.NotFound to "" }
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        onNodeWithText("Not on this ship. Install it from the Grubbery shell on your ship.").assertExists()
        onNodeWithText("Top up").assertIsNotEnabled()
        // No address and no key on this card: the ship holds both. The
        // one key field on the screen is the Anthropic provider's.
        assertEquals(1, onAllNodesWithText("API key", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a ship that refuses the cookie says that instead`() = runComposeUiTest {
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = repo(ai) { HttpStatusCode.Forbidden to "" }
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        onNodeWithText("Signed out of the ship.").assertExists()
    }

    @Test
    fun `an answering ship shows the balance, the mode and what there is to buy`() = runComposeUiTest {
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = repo(ai) { path ->
            HttpStatusCode.OK to when {
                path.endsWith("/api/inference") ->
                    """{"mode":"proxy","base_url":"https://wex.example/apps/armillary/v1","key":"k.s","models":["stub/alpha"]}"""
                path.endsWith("/api/plans") ->
                    """[{"id":"five","name":"Five dollars","kind":"topup","price":5000000,"credit":5000000,"interval":""},
                        {"id":"starter","name":"Starter","kind":"subscription","price":10000000,"credit":12000000,"interval":"month"}]"""
                path.endsWith("/api/catalog") ->
                    """[{"id":"stub/alpha","provider":"stub","in":3900000,"out":19500000,"tags":["zdr"]}]"""
                else ->
                    """{"ship":"~feb","balance":12345000,"plan":"","subscription":{"active":false},
                        "lease":{},"checkouts":{},"vendor":"~wex","self":"~feb","stale":3}"""
            }
        }
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        onNodeWithText("Answering on this ship.").assertExists()
        onNodeWithText("$12.35 on your account.").assertExists()
        onNodeWithText("No plan: you pay as you go.").assertExists()
        onNodeWithText("Requests go through the vendor's ship.").assertExists()
        onNodeWithText("1 models. Paid through your ship.").assertExists()
        onNodeWithText("Subscribe").assertExists()
        onNodeWithText("Top up").performClick()
        waitForIdle()
        onNodeWithText("Five dollars: $5.00 for $5.00 of credit").assertExists()
        onNodeWithText("The smallest the vendor takes is $5.00.").assertExists()
        // Nothing typed is nothing to continue with.
        onNodeWithText("Continue").assertIsNotEnabled()
    }

    // ── the card's own sentences, without a ship ───────────────────

    @Test
    fun `the balance warns before it runs out and says when it has`() {
        fun acct(micro: Long) = Account(micro, "", false, null, "~wex", 0, leaseHeld = false, leaseDisabled = false, checkouts = emptyList())
        assertEquals(null, balanceWarning(acct(5_000_000)))
        assertEquals(null, balanceWarning(acct(1_000_000)))
        assertEquals("Almost out: top up before your next request fails.", balanceWarning(acct(999_999)))
        assertEquals("Empty: requests fail until you top up.", balanceWarning(acct(0)))
        assertEquals("Empty: requests fail until you top up.", balanceWarning(acct(-137)))
    }

    @Test
    fun `the mode line says where a request actually goes`() {
        val empty = Account(0, "", false, null, "~wex", 0, leaseHeld = true, leaseDisabled = true, checkouts = emptyList())
        assertEquals("Balance is empty: requests go through the vendor's ship until you top up.", armillaryModeLine("lease", empty))
        assertEquals("Talon talks to the model provider directly with a key your ship holds.", armillaryModeLine("lease", null))
        assertEquals("Requests go through the vendor's ship.", armillaryModeLine("proxy", null))
        assertEquals("Your ship has not said yet how it reaches the model.", armillaryModeLine(null, null))
    }

    @Test
    fun `the plan line names the subscription and when it renews`() {
        val on = Account(0, "Starter", true, "2026-10-20T00:00:00Z", "~wex", 0, false, false, emptyList())
        assertEquals("Starter, renews 2026-10-20T00:00:00Z.", planLine(on))
        assertEquals("No plan: you pay as you go.", planLine(on.copy(plan = "", subscriptionActive = false, renews = null)))
    }

    @Test
    fun `a custom amount is dollars, and nonsense is no amount at all`() {
        assertEquals(5_000_000L, dollarsToMicro("5"))
        assertEquals(5_000_000L, dollarsToMicro(" $5.00 "))
        assertEquals(12_500_000L, dollarsToMicro("12.50"))
        assertNull(dollarsToMicro(""))
        assertNull(dollarsToMicro("lots"))
        assertNull(dollarsToMicro("-5"))
    }
}
