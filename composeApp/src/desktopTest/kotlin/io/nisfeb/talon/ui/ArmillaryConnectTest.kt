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
import kotlinx.coroutines.launch
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
    /** The web server in front of the ship answers for it: the ship is down. */
    @Volatile private var down = false

    /** The ship's answer to a key request; null mints the key. */
    @Volatile private var mint: () -> Pair<HttpStatusCode, String>? = { null }
    @Volatile private var mintHoldMs = 0L

    /** The base the ship answers. A ship with no public_url answers its own loopback (armillary 33). */
    @Volatile private var base = "https://wex.example/apps/armillary/v1"
    private val proxy get() = """{"mode":"proxy","base_url":"$base","key":"k.s","models":["stub/alpha","stub/beta"]}"""
    /** The vendor the ship's account names; blank for a ship that never set Armillary up. */
    @Volatile private var vendor = "~nisfeb"
    private val funded get() = """{"ship":"~feb","balance":50000000,"plan":"","subscription":{"active":false},
        "lease":{},"checkouts":{},"vendor":"$vendor","self":"~feb","stale":3}"""

    private fun repo(ai: FakeAiSettings): ArmillaryRepo {
        val client = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            seen += req.method.value + " " + path
            if (down) return@MockEngine respond("<html><body>502 Bad Gateway</body></html>", HttpStatusCode.BadGateway)
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
                AiSettingsSection(ai, orrery = null, armillary = repo)
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
        waitUntil(timeoutMillis = 5_000) { shows("Connected through your vendor. 2 models.") }
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
        waitUntil(timeoutMillis = 5_000) { shows("Connected through your vendor.") }
        assertFalse(shows("Your ship refused"), "the reason goes once there is a key")
    }

    @Test
    fun `Refresh on a device with no key asks for one`() = runComposeUiTest {
        val ai = settings()
        show(ai, repo(ai))
        onNodeWithText("Refresh").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Connected through your vendor.") }
        assertEquals(1, keyAsks())
        // With a key, Refresh only reads.
        onNodeWithText("Refresh").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { seen.count { it == "GET /apps/armillary/api/inference" } >= 4 }
        assertEquals(1, keyAsks())
    }

    // A 502 while the ship restarted: the card said "Checking your ship"
    // all session, with the reason hidden.
    @Test
    fun `a ship that did not answer the first check says so, and that it asks again`() = runComposeUiTest {
        down = true
        val ai = settings()
        show(ai, repo(ai))
        onNodeWithText("Checking your ship.").assertExists()
        onNodeWithText("Your ship did not answer. Talon asks again by itself.").assertExists()
        assertFalse(shows("Bad Gateway"), "never the proxy's page")
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

    @Test
    fun `the key is on the row by the time the inference shows`() {
        val ai = settings()
        val r = repo(ai)
        val seenKeys = CopyOnWriteArrayList<String>()
        // Unconfined: runs the moment the inference is set, in the gap
        // where it used to be set before the row was written.
        val watch = CoroutineScope(Dispatchers.Unconfined).launch {
            r.inference.first { it != null }
            seenKeys += ai.rowKey()
        }
        r.setUpDevice("desktop")
        runBlocking { withTimeout(10_000) { watch.join() } }
        assertEquals(listOf("k.s"), seenKeys)
        // And a refresh that finds the key the same way.
        val ai2 = settings()
        hasKey = true
        val r2 = repo(ai2)
        assertEquals("k.s", ai2.rowKey())
        assertEquals("k.s", r2.inference.value?.key)
    }

    @Test
    fun `a second tap while it waits asks for no second key`() {
        mintHoldMs = 1_000
        val ai = settings()
        val r = repo(ai)
        r.setUpDevice("desktop")
        r.setUpDevice("desktop")
        runBlocking { withTimeout(10_000) { r.inference.first { it != null } } }
        assertEquals(1, keyAsks())
    }

    /** Settings that never set Armillary up here: this device and nothing else. */
    private fun bare() = FakeAiSettings().apply {
        applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null,
            savedProfile = AiProfile(providers = listOf(AiProvider(io.nisfeb.talon.ai.DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")))))
    }

    @Test
    fun `a device of a ship that already buys connects itself, with no tap`() {
        hasKey = true
        val ai = bare()
        repo(ai)
        runBlocking { withTimeout(10_000) { while (ai.state.value.savedProfile?.provider(ARMILLARY_PROVIDER)?.apiKey.isNullOrBlank()) delay(20) } }
        assertEquals("k.s", ai.rowKey())
    }

    @Test
    fun `a ship that never named a vendor is not connected, and nothing asks for a key`() {
        vendor = ""
        val ai = bare()
        repo(ai)
        runBlocking { delay(1_500) }
        assertEquals(null, ai.state.value.savedProfile?.provider(ARMILLARY_PROVIDER), "no row made")
        assertEquals(0, keyAsks(), "nothing reached for a vendor")
        assertTrue(seen.none { it.startsWith("POST") }, seen.toString())
    }

    @Test
    fun `a ship that is its own vendor with no public URL is reached at the address Talon signed in with`() {
        hasKey = true
        vendor = "~feb"
        base = "http://localhost:8080/apps/armillary/v1"
        val ai = settings()
        val r = repo(ai)
        assertEquals("https://ship.example/apps/armillary/v1", ai.state.value.savedProfile!!.provider(ARMILLARY_PROVIDER)!!.baseUrl)
        assertTrue(r.noPublicUrl.value, "and the owner is asked to set one")
        // Another vendor's loopback is not this ship's to rewrite.
        vendor = "~nisfeb"
        runBlocking { r.refresh() }
        assertEquals(base, ai.state.value.savedProfile!!.provider(ARMILLARY_PROVIDER)!!.baseUrl)
        assertFalse(r.noPublicUrl.value)
    }
}
