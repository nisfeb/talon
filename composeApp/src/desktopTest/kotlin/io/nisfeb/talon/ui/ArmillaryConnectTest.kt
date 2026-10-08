package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.armillary.ArmillaryRepo
import io.nisfeb.talon.ui.screens.AiSettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A device the ship holds no key for: the card says so, says why, and
 * sets it up. The first key ask used to run on the screen and drop its
 * failure, Refresh never asked again, and the card said only "No models
 * yet" beside a funded balance (sneagan's report, 2026-10-08).
 */
@OptIn(ExperimentalTestApi::class)
class ArmillaryConnectTest {
    private val jsonHeaders = headersOf("Content-Type", "application/json")

    /** Every request the ship saw, as "METHOD /path". */
    private val seen = CopyOnWriteArrayList<String>()
    @Volatile private var hasKey = false
    @Volatile private var inferenceLost = false

    /** The ship's answer to a key request; null mints the key. */
    @Volatile private var mint: () -> Pair<HttpStatusCode, String>? = { null }
    @Volatile private var mintHoldMs = 0L

    private val proxy = """{"mode":"proxy","base_url":"https://wex.example/apps/armillary/v1","key":"k.s","models":["stub/alpha","stub/beta"]}"""
    private val funded = """{"ship":"~feb","balance":50000000,"plan":"","subscription":{"active":false},
        "lease":{},"checkouts":{},"vendor":"~nisfeb","self":"~feb","stale":3}"""

    private fun repo(ai: FakeAiSettings): ArmillaryRepo {
        val client = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            seen += req.method.value + " " + path
            val (status, body) = when {
                path.endsWith("/api/inference") -> when {
                    inferenceLost -> throw java.io.IOException("connection reset")
                    hasKey -> HttpStatusCode.OK to proxy
                    else -> HttpStatusCode.NotFound to """{"error":"no key yet"}"""
                }
                path.endsWith("/api/keys") -> {
                    delay(mintHoldMs)
                    mint() ?: run { hasKey = true; HttpStatusCode.OK to "{}" }
                }
                path.endsWith("/api/lease") -> HttpStatusCode.NotFound to ""
                path.endsWith("/api/plans") || path.endsWith("/api/catalog") -> HttpStatusCode.OK to "[]"
                else -> HttpStatusCode.OK to funded
            }
            respond(body, status, jsonHeaders)
        })
        return ArmillaryRepo(client, CoroutineScope(Dispatchers.Default), ai).also {
            it.attach("https://ship.example", "~feb")
            runBlocking { it.refresh() }
        }
    }

    /** The Armillary row as adding it leaves it: no key, no models. */
    private fun settings() = FakeAiSettings().apply {
        val p = AiProfile(
            providers = listOf(AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary")),
            defaultModel = ModelRef(ARMILLARY_PROVIDER, ""),
        )
        applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = p))
    }

    private var shown by mutableStateOf(true)

    private fun ComposeUiTest.show(ai: FakeAiSettings, repo: ArmillaryRepo) = setContent {
        TalonTheme(darkTheme = false) {
            if (shown) Column(Modifier.verticalScroll(rememberScrollState())) {
                AiSettingsSection(ai, orrery = null, armillary = repo, catalog = quietCatalog())
            }
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun keyAsks() = seen.count { it == "POST /apps/armillary/api/keys" }
    private fun FakeAiSettings.rowKey() = state.value.savedProfile!!.provider(ARMILLARY_PROVIDER)!!.apiKey

    @Test
    fun `a device with no key says so beside the credit, and sets itself up from the card`() = runComposeUiTest {
        val ai = settings()
        show(ai, repo(ai))
        onNodeWithText("$50.00 credit with ~nisfeb").assertExists()
        onNodeWithText("This device isn't connected yet.").assertExists()
        assertFalse(shows("No models yet"), "the old line said nothing of why")
        assertEquals(0, keyAsks(), "looking asks for nothing")

        onNodeWithText("Set up this device").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Connected through ~nisfeb's ship. 2 models.") }
        assertEquals(1, keyAsks())
        assertFalse(shows("This device isn't connected yet."))
        assertEquals("k.s", ai.rowKey(), "the key is on the row the features read")
    }

    @Test
    fun `a refused ask says why in plain words, and asking again works`() = runComposeUiTest {
        val ai = settings()
        show(ai, repo(ai))
        mint = { HttpStatusCode.BadGateway to """{"error":"the vendor did not answer"}""" }
        onNodeWithText("Set up this device").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Your ship refused: the vendor did not answer.") }
        assertFalse(shows("HTTP 502"), "no status codes on the card")
        onNodeWithText("This device isn't connected yet.").assertExists()

        mint = { null }
        onNodeWithText("Set up this device").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Connected through ~nisfeb's ship.") }
        assertFalse(shows("Your ship refused"), "the reason goes once there is a key")
    }

    @Test
    fun `Refresh on a device with no key asks for one`() = runComposeUiTest {
        val ai = settings()
        show(ai, repo(ai))
        onNodeWithText("Refresh").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Connected through ~nisfeb's ship.") }
        assertEquals(1, keyAsks())
        // With a key, Refresh only reads.
        onNodeWithText("Refresh").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { seen.count { it == "GET /apps/armillary/api/inference" } >= 4 }
        assertEquals(1, keyAsks())
    }

    @Test
    fun `a ship that does not answer about the key says so`() = runComposeUiTest {
        inferenceLost = true
        val ai = settings()
        show(ai, repo(ai))
        onNodeWithText("This device isn't connected yet.").assertExists()
        onNodeWithText("Your ship did not answer. Try Refresh in a moment.").assertExists()
        onNodeWithText("Set up this device").assertExists()
    }

    @Test
    fun `leaving the screen while it waits does not drop the key`() = runComposeUiTest {
        mintHoldMs = 1_500
        val ai = settings()
        val r = repo(ai)
        show(ai, r)
        onNodeWithText("Set up this device").performScrollTo().performClick()
        waitForIdle()
        assertTrue(r.settingUp.value)
        shown = false
        waitForIdle()
        // Real time: the wait runs on the repo's scope, not the screen's.
        runBlocking { withTimeout(10_000) { r.inference.first { it != null } } }
        assertEquals("k.s", ai.rowKey())
        assertEquals(1, keyAsks())
    }
}
