package io.nisfeb.talon.armillary

import io.nisfeb.talon.ui.quietCatalog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
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
import io.nisfeb.talon.ui.screens.AiSettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Remove on the Armillary card takes the row away at once, and it stays
 * away. It waited for the ship to take the lease back first, so on a
 * busy ship the button did nothing that anyone could see.
 */
@OptIn(ExperimentalTestApi::class)
class ArmillaryRemoveTest {
    private val json = headersOf("Content-Type", "application/json")
    private val seen = CopyOnWriteArrayList<String>()
    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        seen += "${req.method.value} $path"
        val ok = { body: String -> respond(body, HttpStatusCode.OK, json) }
        when {
            // A busy ship: its answer took tens of seconds on ricsul.
            path.endsWith("/api/lease") -> { kotlinx.coroutines.delay(10_000); ok("""{"ok":true}""") }
            path.endsWith("/api/inference") ->
                ok("""{"mode":"proxy","base_url":"https://wex.example/apps/armillary/v1","key":"k.s","models":["stub/alpha"]}""")
            path.endsWith("/api/plans") -> ok("[]")
            path.endsWith("/api/catalog") -> ok("[]")
            else -> ok("""{"ship":"~feb","balance":5000000,"plan":"","subscription":{"active":false},"lease":{},"checkouts":{},"vendor":"~nisfeb","self":"~feb","stale":3}""")
        }
    })

    /** As "Let your ship handle it" leaves it: Armillary the only provider, and the default. */
    private val justAdded = AiProfile(
        providers = listOf(AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, ProviderKind.Armillary.label)),
        defaultModel = ModelRef(ARMILLARY_PROVIDER, ""),
    )

    @Test
    fun `remove takes the armillary row away at once, on a slow ship too, and nothing puts it back`() = runComposeUiTest {
        val ai = FakeAiSettings().apply {
            applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = justAdded))
        }
        val repo = ArmillaryRepo(http, CoroutineScope(SupervisorJob() + Dispatchers.Default), ai).apply {
            attach("https://ship.example", "~feb")
            runBlocking { refresh() }
        }
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = repo, catalog = quietCatalog()) }
            }
        }
        waitForIdle()
        onAllNodesWithText("Remove")[0].performScrollTo().performClick()
        val clicked = System.currentTimeMillis()
        // Real time: the lease's wait is on the repo's scope, not the clock here.
        while (ai.state.value.savedProfile?.provider(ARMILLARY_PROVIDER) != null && System.currentTimeMillis() - clicked < 5_000) Thread.sleep(20)
        assertTrue(System.currentTimeMillis() - clicked < 2_000, "the row went only when the ship answered")
        assertNull(ai.state.value.savedProfile?.provider(ARMILLARY_PROVIDER))
        // The lease is still given back, whenever the ship answers.
        while (seen.none { it == "DELETE /apps/armillary/api/lease" } && System.currentTimeMillis() - clicked < 5_000) Thread.sleep(20)
        assertTrue(seen.any { it == "DELETE /apps/armillary/api/lease" }, "the lease is given back: $seen")
        Thread.sleep(1_000)
        waitForIdle()
        assertNull(ai.state.value.savedProfile?.provider(ARMILLARY_PROVIDER), "put back: ${ai.state.value.savedProfile}")
    }
}
