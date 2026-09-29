package io.nisfeb.talon.armillary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.FeatureSetting
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.ai.forSync
import io.nisfeb.talon.ai.wantsArmillaryRow
import io.nisfeb.talon.ui.screens.AiSettingsSection
import io.nisfeb.talon.ui.screens.deleteAccountWarning
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting the Armillary account from inside the app (App Review
 * guideline 5.1.1(v)): what is asked of the ship, what each answer
 * leaves, and that nothing left behind opens the account again.
 */
@OptIn(ExperimentalTestApi::class)
class ArmillaryDeleteTest {
    private val json = headersOf("Content-Type", "application/json")

    /** Every request the ship saw: method, path and body. */
    private val seen = CopyOnWriteArrayList<String>()

    @Volatile private var deleteReply: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"deleted":true}"""
    @Volatile private var deleteDelayMs = 0L

    private val funded = """{"ship":"~feb","balance":12345000,"plan":"Talon Pro","subscription":{"active":true,"renews":"2026-10-29"},
        "lease":{},"checkouts":{},"vendor":"~wex","self":"~feb","stale":3}"""

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        seen += "${req.method.value} $path ${req.body.toByteArray().decodeToString()}".trim()
        val ok = { body: String -> respond(body, HttpStatusCode.OK, json) }
        when {
            path.endsWith("/api/delete-account") -> {
                delay(deleteDelayMs)
                respond(deleteReply.second, deleteReply.first, json)
            }
            path.endsWith("/api/inference") ->
                ok("""{"mode":"proxy","base_url":"https://wex.example/apps/armillary/v1","key":"k.s","models":["stub/alpha"]}""")
            path.endsWith("/api/plans") -> ok("[]")
            path.endsWith("/api/catalog") -> ok("[]")
            else -> ok(funded)
        }
    })

    /** Armillary as the default and under triage, beside a key of the owner's own. */
    private val onArmillary = AiProfile(
        providers = listOf(
            AiProvider("main", ProviderKind.Anthropic, "Anthropic", apiKey = "sk-ant"),
            AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary"),
        ),
        defaultModel = ModelRef(ARMILLARY_PROVIDER, "stub/alpha"),
        features = mapOf(AiFeature.CatchUp to FeatureSetting(on = true, model = ModelRef(ARMILLARY_PROVIDER, "stub/alpha"))),
    )

    private fun settings() = FakeAiSettings().apply {
        applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant", model = "claude-opus-5", savedProfile = onArmillary))
    }

    private fun repo(ai: FakeAiSettings) = ArmillaryRepo(http, CoroutineScope(SupervisorJob() + Dispatchers.Default), ai).apply {
        attach("https://ship.example", "~feb")
        runBlocking { refresh() }
    }

    private val deletes get() = seen.filter { "delete-account" in it }

    @Test
    fun `the request is a bare POST to the ship's own armillary`() {
        val r = repo(settings())
        assertEquals(DeleteAnswer.Deleted, runBlocking { r.deleteAccount() }.getOrThrow())
        assertEquals(listOf("POST /apps/armillary/api/delete-account"), deletes)
    }

    @Test
    fun `each answer the ship gives means what it says`() {
        val cases = listOf(
            (HttpStatusCode.OK to """{"deleted":true}""") to DeleteAnswer.Deleted,
            (HttpStatusCode.Accepted to """{"queued":true,"nonce":"n1"}""") to DeleteAnswer.Queued,
            (HttpStatusCode.Conflict to """{"error":"vendor: not set"}""") to DeleteAnswer.NothingHeld,
            (HttpStatusCode.NotFound to """{"error":"no such route"}""") to DeleteAnswer.Unsupported,
            (HttpStatusCode.BadGateway to """{"error":"stripe: down"}""") to DeleteAnswer.Refused("stripe: down"),
        )
        for ((reply, want) in cases) {
            deleteReply = reply
            assertEquals(want, runBlocking { repo(settings()).deleteAccount() }.getOrThrow(), "$reply")
        }
        // A 409 about anything but the vendor is not "nothing held".
        deleteReply = HttpStatusCode.Conflict to """{"error":"busy"}"""
        assertTrue(runBlocking { repo(settings()).deleteAccount() }.isFailure)
    }

    // The profile travels: pointing at Armillary, another device would
    // adopt the row, ask for a key and open the account again.
    @Test
    fun `once the ship takes it, no device keeps anything that asks the vendor again`() {
        val ai = settings()
        val r = repo(ai)
        runBlocking { r.deleteAccount() }.getOrThrow()
        val p = ai.state.value.savedProfile!!
        assertNull(p.provider(ARMILLARY_PROVIDER))
        assertTrue(p.features.values.none { it.model?.provider == ARMILLARY_PROVIDER } && p.defaultModel?.provider != ARMILLARY_PROVIDER)
        assertTrue(!p.forSync().wantsArmillaryRow(), "another device would adopt the row back")
        assertNull(r.account.value)
        assertEquals(Deletion("~wex", DeleteAnswer.Deleted), r.deletion.value)
        assertEquals("sk-ant", p.provider("main")?.apiKey, "the owner's own key stays")
    }

    @Test
    fun `a ship that did not take it changes nothing here`() {
        for (reply in listOf(HttpStatusCode.NotFound to "", HttpStatusCode.BadGateway to """{"error":"stripe: down"}""")) {
            deleteReply = reply
            val ai = settings()
            val r = repo(ai)
            val before = ai.state.value.savedProfile
            runBlocking { r.deleteAccount() }
            assertEquals(before, ai.state.value.savedProfile, "$reply")
            assertTrue(before?.provider(ARMILLARY_PROVIDER) != null)
            assertNull(r.deletion.value)
        }
    }

    // Real time: the virtual clock in a compose test would hide the wait.
    @Test
    fun `leaving the screen while the ship is asked does not stop the deletion`() = runBlocking {
        deleteDelayMs = 800
        val ai = settings()
        val r = repo(ai)
        val screen = CoroutineScope(Dispatchers.Default).launch { r.deleteAccount() }
        delay(100)
        screen.cancel()
        val until = System.currentTimeMillis() + 5_000
        while (r.deletion.value == null && System.currentTimeMillis() < until) delay(50)
        assertEquals(Deletion("~wex", DeleteAnswer.Deleted), r.deletion.value)
        assertNull(ai.state.value.savedProfile!!.provider(ARMILLARY_PROVIDER))
    }

    @Test
    fun `the warning says what goes, what stops and what is lost`() {
        val a = accountOf(kotlinx.serialization.json.Json.parseToJsonElement(funded) as kotlinx.serialization.json.JsonObject)
        assertEquals(
            "~wex deletes everything it holds for your ship: the account, its ledger, its checkouts and its keys. " +
                "Your subscription stops now. The $12.35 left on it is lost. " +
                "Stripe and BTCPay Server keep their own records of your payments. This cannot be undone.",
            deleteAccountWarning(a),
        )
        val empty = a.copy(balanceMicro = 0, subscriptionActive = false)
        assertTrue("stops now" !in deleteAccountWarning(empty) && "lost" !in deleteAccountWarning(empty))
    }

    @Test
    fun `from the card, confirmed, the account goes and the screen says so`() = runComposeUiTest {
        val ai = settings()
        val r = repo(ai)
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = r) }
            }
        }
        onNodeWithText("Delete account").performScrollTo().performClick()
        onNodeWithText("Delete your Armillary account?").assertExists()
        onNodeWithText("The $12.35 left on it is lost", substring = true).assertExists()
        // Keep it asks nothing.
        onNodeWithText("Keep it").performClick()
        assertTrue(deletes.isEmpty())

        onNodeWithText("Delete account").performScrollTo().performClick()
        onAllNodesWithText("Delete account")[1].performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Your Armillary account at ~wex is deleted", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, deletes.size)
        assertTrue(onAllNodesWithText("Delete account").fetchSemanticsNodes().isEmpty(), "the card went with the row")
        assertTrue(onAllNodesWithText("Let your ship handle it").fetchSemanticsNodes().isEmpty(), "no pitch to buy again straight after")
    }

    @Test
    fun `a ship too old to delete says what to do, and the card stays`() = runComposeUiTest {
        deleteReply = HttpStatusCode.NotFound to """{"error":"no such route"}"""
        val ai = settings()
        val r = repo(ai)
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = r) }
            }
        }
        onNodeWithText("Delete account").performScrollTo().performClick()
        onAllNodesWithText("Delete account")[1].performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("cannot delete accounts yet", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("message ~wex to ask", substring = true).assertExists()
        onNodeWithText("Delete account").assertExists()
    }

    @Test
    fun `a deletion still waiting on the vendor says the ship keeps asking`() = runComposeUiTest {
        deleteReply = HttpStatusCode.Accepted to """{"queued":true,"nonce":"n1"}"""
        val ai = settings()
        val r = repo(ai)
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = r) }
            }
        }
        onNodeWithText("Delete account").performScrollTo().performClick()
        onAllNodesWithText("Delete account")[1].performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("keeps asking until it answers", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(ai.state.value.savedProfile!!.provider(ARMILLARY_PROVIDER))
    }
}
