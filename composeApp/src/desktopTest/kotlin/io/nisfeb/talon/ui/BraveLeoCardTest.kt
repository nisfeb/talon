package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.ModelInfo
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.armillary.ArmillaryRepo
import io.nisfeb.talon.armillary.LEO_KEY_NOTE
import io.nisfeb.talon.ui.screens.AiSettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Use in Brave Leo" on the Armillary card: Leo's own field names, the
 * values its form takes, copy buttons that copy them, and nothing shown
 * where Leo could not run on it.
 */
@OptIn(ExperimentalTestApi::class)
class BraveLeoCardTest {
    private val jsonHeaders = headersOf("Content-Type", "application/json")
    private val copied = CopyOnWriteArrayList<String>()

    /** Every request the ship saw, as "METHOD /path". */
    private val seen = CopyOnWriteArrayList<String>()

    private val lease = """{"mode":"lease","base_url":"https://openrouter.ai/api/v1","key":"test-key",
        "models":["vendor/alpha","vendor/beta"]}"""
    private val proxy = """{"mode":"proxy","base_url":"https://wex.example/apps/armillary/v1","key":"k.s","models":["stub/alpha"]}"""
    private fun account(leaseDisabled: Boolean = false) = """{"ship":"~feb","balance":12345000,"plan":"","subscription":{"active":false},
        "lease":{"held":true,"disabled":$leaseDisabled},"checkouts":{},"vendor":"~wex","self":"~feb","stale":3}"""

    /** A ship whose `/api/inference` answers [inference], and whose other routes answer an account. */
    private fun repo(ai: FakeAiSettings, inference: () -> Pair<HttpStatusCode, String>, acct: String = account()): ArmillaryRepo {
        val client = HttpClient(MockEngine { req ->
            seen += req.method.value + " " + req.url.encodedPath
            val (status, body) = when {
                req.url.encodedPath.endsWith("/api/inference") -> inference()
                req.url.encodedPath.endsWith("/api/plans") || req.url.encodedPath.endsWith("/api/catalog") -> HttpStatusCode.OK to "[]"
                else -> HttpStatusCode.OK to acct
            }
            respond(body, status, jsonHeaders)
        })
        return ArmillaryRepo(client, CoroutineScope(Dispatchers.Default), ai).also {
            it.attach("https://ship.example", "~feb")
            runBlocking { it.refresh() }
        }
    }

    /** The Armillary row, knowing the first model's context length as a payment leaves it. */
    private fun settings() = FakeAiSettings().apply {
        val p = AiProfile(
            providers = listOf(
                AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary", models = listOf(ModelInfo("vendor/alpha", contextLength = 200_000))),
            ),
            defaultModel = ModelRef(ARMILLARY_PROVIDER, ""),
        )
        applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = p))
    }

    private fun ComposeUiTest.show(ai: FakeAiSettings, repo: ArmillaryRepo) = setContent {
        CompositionLocalProvider(
            LocalClipboardManager provides object : ClipboardManager {
                override fun getText(): AnnotatedString? = null
                override fun setText(annotatedString: AnnotatedString) { copied += annotatedString.text }
            },
        ) {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = repo, catalog = quietCatalog()) }
            }
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a lease gives Leo the endpoint, the model, the context size and the key, each copied as it is`() = runComposeUiTest {
        val ai = settings()
        val bought = repo(ai, { HttpStatusCode.OK to lease })
        show(ai, bought)
        val readsBefore = seen.count { it == "GET /apps/armillary/api/inference" }
        onNodeWithText("Use in Brave Leo").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Server endpoint") }

        // Leo's own field names, so each value is pasted into the right box.
        listOf("Label", "Model request name", "Server endpoint", "Context size", "API Key").forEach { onNodeWithText(it).assertExists() }
        onNodeWithText("vendor/alpha (Armillary)").assertExists()
        onNodeWithText("vendor/alpha").assertExists()
        onNodeWithText("https://openrouter.ai/api/v1/chat/completions").assertExists()
        onNodeWithText("200000").assertExists()
        // The key is masked on screen, copied whole, and said to be Talon's own.
        onNodeWithText("•".repeat(8) + "-key").assertExists()
        assertTrue(onAllNodesWithText("test-key").fetchSemanticsNodes().isEmpty(), "the key is never shown in full")
        onNodeWithText(LEO_KEY_NOTE).assertExists()

        // "Copy" reads "Copied" for a moment after a click, so match both.
        val copies = onAllNodes(hasText("Copy") or hasText("Copied"))
        repeat(copies.fetchSemanticsNodes().size) { copies[it].performClick() }
        waitForIdle()
        assertEquals(
            listOf("vendor/alpha (Armillary)", "vendor/alpha", "https://openrouter.ai/api/v1/chat/completions", "200000", "test-key"),
            copied.toList(),
        )

        // Opening it read the ship again, and only read: no key was minted, nothing was written.
        assertEquals(readsBefore + 1, seen.count { it == "GET /apps/armillary/api/inference" })
        assertTrue(seen.all { it.startsWith("GET ") }, seen.toString())
    }

    @Test
    fun `another of the ship's models changes the request name and the label, not the endpoint`() = runComposeUiTest {
        val ai = settings()
        show(ai, repo(ai, { HttpStatusCode.OK to lease }))
        onNodeWithText("Use in Brave Leo").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Server endpoint") }
        onNodeWithText("Another model").performClick()
        onNodeWithText("vendor/beta").performClick()
        waitForIdle()
        onNodeWithText("vendor/beta (Armillary)").assertExists()
        onNodeWithText("https://openrouter.ai/api/v1/chat/completions").assertExists()
        // Its context length is not known, so Leo's own default stands.
        assertTrue(onAllNodesWithText("Context size").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the proxy shows no endpoint and says why Leo cannot use it`() = runComposeUiTest {
        val ai = settings()
        show(ai, repo(ai, { HttpStatusCode.OK to proxy }))
        onNodeWithText("Use in Brave Leo").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Leo streams its answers") }
        assertTrue(onAllNodesWithText("Server endpoint").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithText("Copy").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a lease out of credit says the balance is why`() = runComposeUiTest {
        val ai = settings()
        show(ai, repo(ai, { HttpStatusCode.OK to proxy }, acct = account(leaseDisabled = true)))
        onNodeWithText("Use in Brave Leo").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Your balance is empty") }
        assertTrue(onAllNodesWithText("Server endpoint").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a ship that refuses, has no key, lacks the app or does not answer says which`() = runComposeUiTest {
        val ai = settings()
        var answer: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to lease }
        show(ai, repo(ai, { answer() }))

        answer = { HttpStatusCode.InternalServerError to """{"error":"boom"}""" }
        onNodeWithText("Use in Brave Leo").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Your ship did not answer for Leo: HTTP 500: boom") }
        assertTrue(onAllNodesWithText("Server endpoint").fetchSemanticsNodes().isEmpty(), "no answer is not the old one")

        // Closing and opening again asks again.
        answer = { HttpStatusCode.NotFound to """{"error":"no key yet"}""" }
        onNodeWithText("Use in Brave Leo").performClick()
        onNodeWithText("Use in Brave Leo").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Your ship did not answer for Leo: it holds no Armillary key yet") }

        answer = { HttpStatusCode.NotFound to "" }
        onNodeWithText("Use in Brave Leo").performClick()
        onNodeWithText("Use in Brave Leo").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Your ship did not answer for Leo: Armillary is not on it") }

        answer = { throw java.io.IOException("connection reset") }
        onNodeWithText("Use in Brave Leo").performClick()
        onNodeWithText("Use in Brave Leo").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Your ship did not answer for Leo: no answer from the ship") }
        assertTrue(seen.all { it.startsWith("GET ") }, seen.toString())
    }

    @Test
    fun `a ship without armillary offers no Leo setup`() = runComposeUiTest {
        val ai = settings()
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound, jsonHeaders) })
        val missing = ArmillaryRepo(client, CoroutineScope(Dispatchers.Default), ai).also {
            it.attach("https://ship.example", "~feb")
            runBlocking { it.refresh() }
        }
        show(ai, missing)
        onNodeWithText("Not on this ship. Install it from the Grubbery shell on your ship.").assertExists()
        assertTrue(onAllNodesWithText("Use in Brave Leo").fetchSemanticsNodes().isEmpty())
    }
}
