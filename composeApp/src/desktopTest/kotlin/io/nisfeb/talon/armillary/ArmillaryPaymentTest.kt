package io.nisfeb.talon.armillary

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.DEVICE_PROVIDER
import io.nisfeb.talon.ai.ModelCatalog
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.ui.screens.AiSettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Paying for Armillary sets the AI settings up to run on it (the owner's
 * decisions, 2026-09-28): the gaps take the strongest model the vendor
 * sells, catch-up and the assistant are readied for one tap, and Orrery's
 * generator is pointed at it where it had nothing to run on.
 */
class ArmillaryPaymentTest {
    @Volatile private var balance = 0L
    @Volatile private var keyed = true
    @Volatile private var generator = """{"enabled":false}"""
    private val puts = CopyOnWriteArrayList<Pair<String, String>>()
    /** Every request that went to OpenRouter, with whatever authorised it. */
    private val toOpenRouter = CopyOnWriteArrayList<String>()
    private val json = headersOf("Content-Type", "application/json")

    private val http = HttpClient(MockEngine { req ->
        val url = req.url.toString()
        val path = req.url.encodedPath
        val ok = { body: String -> respond(body, HttpStatusCode.OK, json) }
        if (req.url.host == "openrouter.ai") toOpenRouter += "${req.url.encodedPath} ${req.headers["Authorization"].orEmpty()}".trim()
        when {
            // OpenRouter's public list: what each model can do.
            url.startsWith("https://openrouter.ai/api/v1/models") -> ok(
                """{"data":[{"id":"big/tools","name":"Big","supported_parameters":["tools"],"context_length":200000},
                    {"id":"huge/notools","name":"Huge","supported_parameters":["temperature"]},
                    {"id":"small/tools","name":"Small","supported_parameters":["tools"]}]}""",
            )
            url.startsWith("https://openrouter.ai/api/v1/endpoints/zdr") -> ok("""{"data":[]}""")
            path.endsWith("/apps/armillary/api/inference") ->
                if (keyed) ok("""{"mode":"lease","base_url":"https://openrouter.ai/api/v1","key":"lease.key","models":["small/tools","big/tools","huge/notools"]}""")
                else respond("""{"error":"no key yet"}""", HttpStatusCode.NotFound, json)
            path.endsWith("/apps/armillary/api/keys") -> { keyed = true; ok("""{"ok":true}""") }
            path.endsWith("/apps/armillary/api/lease") -> respond("", HttpStatusCode.NotFound)
            path.endsWith("/apps/armillary/api/plans") -> ok("[]")
            // The vendor's prices say strength; the tags say what keeps nothing.
            path.endsWith("/apps/armillary/api/catalog") -> ok(
                """[{"id":"small/tools","provider":"openrouter","in":1,"out":1000000,"tags":["zdr"]},
                    {"id":"big/tools","provider":"openrouter","in":1,"out":30000000,"tags":["zdr"]},
                    {"id":"huge/notools","provider":"openrouter","in":1,"out":90000000,"tags":["zdr"]}]""",
            )
            path.endsWith("/apps/armillary/api/checkout") -> ok("""{"url":"https://pay.example/c1","nonce":"n1"}""")
            path.endsWith("/apps/armillary/api/account") -> ok(
                """{"ship":"~zod","balance":$balance,"plan":"","subscription":{"active":false},"lease":{},"checkouts":{},"vendor":"~nisfeb","self":"~zod","stale":3}""",
            )
            path.endsWith("/apps/orrery/api/settings") -> ok("""{"chat":{"enabled":true},"generator":$generator}""")
            path.endsWith("/apps/orrery/api/generator") && req.method == HttpMethod.Put -> {
                val body = req.body.toByteArray().decodeToString()
                puts += "generator" to body
                ok(body)
            }
            else -> ok("{}")
        }
    })

    private val row = AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary")
    private val device = AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")

    /** What "Set up Armillary" leaves: the row, and a blank default on it. */
    private val setUp = AiProfile(providers = listOf(device, row), defaultModel = ModelRef(ARMILLARY_PROVIDER, ""), orrery = true)

    /** A ship, an Orrery pipe on it and the Armillary repo, set up and refreshed once. */
    private inner class Rig(profile: AiProfile) : AutoCloseable {
        private val dir = createTempDirectory(prefix = "talon-armillary-paid-").toFile()
        private val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        private val scope = CoroutineScope(SupervisorJob())
        val ai = FakeAiSettings().apply {
            applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = profile))
        }
        private val orrery = OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
        val repo = ArmillaryRepo(http, scope, ai, openRouter = ModelCatalog(http), orrery = { orrery })

        init {
            repo.attach("https://ship.test", "~zod")
            runBlocking { repo.refresh() }
        }

        override fun close() {
            repo.detach()
            orrery.detach()
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            db.close()
            dir.deleteRecursively()
        }
    }

    private fun ship(profile: AiProfile, block: suspend (ArmillaryRepo, FakeAiSettings) -> Unit) =
        Rig(profile).use { r -> runBlocking { block(r.repo, r.ai) } }

    /** A checkout opened here, and the vendor's balance risen by it. */
    private suspend fun pay(repo: ArmillaryRepo) {
        repo.topUp("stripe", "five", null).getOrThrow()
        balance = 5_000_000
        repo.refresh()
    }

    @Test
    fun `a payment runs everything on the strongest model that can do the work, and asks before reading`() = ship(setUp) { repo, ai ->
        pay(repo)
        withTimeout(10_000) { while (repo.offer.value == null) delay(20) }
        val p = ai.state.value.savedProfile!!
        // The priciest lacks tools, which the assistant needs.
        val ours = ModelRef(ARMILLARY_PROVIDER, "big/tools")
        assertEquals(ours, p.defaultModel)
        assertEquals("big/tools", repo.offer.value)
        assertFalse(p.isOn(AiFeature.CatchUp) || p.isOn(AiFeature.Assistant), "they read messages: the owner's tap, not the payment")
        assertEquals(true, p.provider(ARMILLARY_PROVIDER)!!.models.first { it.id == "big/tools" }.tools, "what OpenRouter says of it is kept on the row")
        assertEquals(listOf("/api/v1/models", "/api/v1/endpoints/zdr"), toOpenRouter.toList(), "its public list, asked with no key")
        // Orrery is on and its generator had nothing to run on.
        withTimeout(10_000) { while (puts.isEmpty()) delay(20) }
        assertEquals("""{"enabled":true,"url":"https://openrouter.ai/api/v1","model":"big/tools","api_key":"lease.key"}""", puts.single().second)
        withTimeout(10_000) { while (ai.state.value.savedProfile!!.features[AiFeature.OrreryGenerator]?.model != ours) delay(20) }

        repo.acceptOffer()
        val on = ai.state.value.savedProfile!!
        assertTrue(on.isOn(AiFeature.CatchUp) && on.isOn(AiFeature.Assistant))
        assertEquals(null, repo.offer.value)
        // A refresh after keeps what was learnt of tool use.
        repo.refresh()
        assertEquals(true, ai.state.value.savedProfile!!.provider(ARMILLARY_PROVIDER)!!.models.first { it.id == "big/tools" }.tools)
    }

    @Test
    fun `a generator that runs is left alone, and nothing is offered where both are on`() {
        generator = """{"enabled":true,"model":"anthropic/claude-opus-5","api_key_set":true}"""
        val on = setUp.copy(
            features = mapOf(
                AiFeature.CatchUp to io.nisfeb.talon.ai.FeatureSetting(on = true),
                AiFeature.Assistant to io.nisfeb.talon.ai.FeatureSetting(on = true),
            ),
        )
        ship(on) { repo, ai ->
            pay(repo)
            withTimeout(10_000) { while (ai.state.value.savedProfile!!.defaultModel?.model.isNullOrBlank()) delay(20) }
            delay(500)
            assertEquals(null, repo.offer.value)
            assertTrue(puts.isEmpty(), "the owner's generator is theirs")
        }
    }

    // The row never travels, so a profile from the device that paid named
    // a provider this one did not have, and nothing ran here.
    @Test
    fun `a profile from another device running on armillary gets this device's own row and key`() {
        keyed = false
        ship(AiProfile(providers = listOf(device), defaultModel = ModelRef(ARMILLARY_PROVIDER, "big/tools"))) { _, ai ->
            withTimeout(10_000) { while (ai.state.value.savedProfile!!.provider(ARMILLARY_PROVIDER)?.apiKey.isNullOrBlank()) delay(20) }
            val p = ai.state.value.savedProfile!!
            assertEquals("lease.key", p.provider(ARMILLARY_PROVIDER)!!.apiKey)
            assertEquals(null, p.resolve(AiFeature.Assistant)?.problem(), "the default runs here now")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the card says where the messages would go, and one tap turns both on`() = Rig(setUp).use { r ->
        runBlocking {
            pay(r.repo)
            withTimeout(10_000) { while (r.repo.offer.value == null) delay(20) }
        }
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(r.ai, orrery = null, armillary = r.repo) }
                }
            }
            onNodeWithText("Catch-up and the assistant are set up on big/tools. Turn them on?", substring = true).assertExists()
            onNodeWithText("which keeps nothing (zero data retention)", substring = true).assertExists()
            onNodeWithText("Turn on").performScrollTo().performClick()
            waitForIdle()
            val p = r.ai.state.value.savedProfile!!
            assertTrue(p.isOn(AiFeature.CatchUp) && p.isOn(AiFeature.Assistant))
            assertTrue(onAllNodesWithText("Turn on").fetchSemanticsNodes().isEmpty(), "asked once")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `not now leaves both off and stops asking`() = Rig(setUp).use { r ->
        runBlocking {
            pay(r.repo)
            withTimeout(10_000) { while (r.repo.offer.value == null) delay(20) }
        }
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(r.ai, orrery = null, armillary = r.repo) }
                }
            }
            onNodeWithText("Not now").performScrollTo().performClick()
            waitForIdle()
            val p = r.ai.state.value.savedProfile!!
            assertFalse(p.isOn(AiFeature.CatchUp) || p.isOn(AiFeature.Assistant))
            assertTrue(onAllNodesWithText("Turn on").fetchSemanticsNodes().isEmpty())
        }
    }
}
