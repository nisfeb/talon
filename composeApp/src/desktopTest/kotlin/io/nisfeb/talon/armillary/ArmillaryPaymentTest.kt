package io.nisfeb.talon.armillary

import io.nisfeb.talon.ui.quietCatalog
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
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Paying for Armillary turns on everything it pays for, with no question
 * (sneagan, 2026-10-16: paying "should just turn everything on and call
 * it a day"), and the checkout flows that get the payment there.
 */
class ArmillaryPaymentTest {
    @Volatile private var balance = 0L
    @Volatile private var keyed = true
    @Volatile private var generator = """{"enabled":false}"""
    /** The vendor's picks, as the inference document carries them, or nothing. */
    @Volatile private var suggested = ""
    /** The ship's thirty seconds ran out before the vendor's answer came back. */
    @Volatile private var slowVendor = false
    /** The followed checkout comes back refused, with the vendor's note. */
    @Volatile private var phaseRefused = false
    /** The desk is older than the phase route: GET /checkout/<nonce> 404s. */
    @Volatile private var phaseMissing = false
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
                if (keyed) ok("""{"mode":"lease","base_url":"https://openrouter.ai/api/v1","key":"lease.key","models":["small/tools","big/tools","huge/notools"]$suggested}""")
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
            path.endsWith("/apps/armillary/api/checkout") ->
                if (slowVendor) respond("""{"pending":true,"nonce":"n9"}""", HttpStatusCode.Accepted, json)
                else ok("""{"url":"https://pay.example/c1","nonce":"n1"}""")
            path.endsWith("/apps/armillary/api/checkout/n9") -> when {
                phaseMissing -> respond("", HttpStatusCode.NotFound)
                phaseRefused -> ok("""{"phase":"answered","status":"refused","url":"","note":"Your card was declined."}""")
                else -> ok("""{"phase":"answered","status":"pending","url":"https://pay.example/late","note":""}""")
            }
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

    /** A ship and the Armillary repo, set up and refreshed once. */
    private inner class Rig(profile: AiProfile) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob())
        val ai = FakeAiSettings().apply {
            applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = profile))
        }
        val repo = ArmillaryRepo(http, scope, ai)

        init {
            repo.attach("https://ship.test", "~zod")
            runBlocking { repo.refresh() }
        }

        override fun close() {
            repo.detach()
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
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
    fun `a payment turns every feature it pays for on, and asks nothing`() = ship(setUp) { repo, ai ->
        assertFalse(ai.state.value.savedProfile!!.isOn(AiFeature.CatchUp), "off before")
        pay(repo)
        withTimeout(10_000) { while (!ai.state.value.savedProfile!!.isOn(AiFeature.CatchUp)) delay(20) }
        val p = ai.state.value.savedProfile!!
        assertTrue(listOf(AiFeature.CatchUp, AiFeature.Assistant, AiFeature.Transcription).all { p.isOn(it) })
        assertTrue(ai.state.value.assistantOn() && ai.state.value.catchMeUpEnabled, "and the toggles older builds read")
        assertEquals("lease.key", p.provider(ARMILLARY_PROVIDER)!!.apiKey)
        assertEquals(null, p.resolve(AiFeature.Assistant)?.problem(), "the assistant runs")
        assertTrue(toOpenRouter.isEmpty(), "nothing asked of OpenRouter to pick a model")
        assertTrue(puts.isEmpty(), "Orrery's generator is the ship's to follow, not written from here")
        assertEquals(p.orrery, true, "Orrery's switch is left as it was")
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
    fun `a checkout the ship answered 202 for is followed to its page, not reported as failed`() = ship(setUp) { repo, _ ->
        slowVendor = true
        assertEquals("https://pay.example/late", repo.topUp("btcpay", null, 10_000_000).getOrThrow())
    }

    @Test
    fun `a followed checkout the vendor refuses shows the vendor's note`() = ship(setUp) { repo, _ ->
        slowVendor = true; phaseRefused = true
        val r = repo.topUp("btcpay", null, 10_000_000)
        assertTrue(r.isFailure)
        assertEquals("Your card was declined.", r.exceptionOrNull()?.message)
    }

    @Test
    fun `on a desk without the phase route a 202 stays pending and warns against ordering again`() = ship(setUp) { repo, _ ->
        slowVendor = true; phaseMissing = true
        val r = repo.topUp("btcpay", null, 10_000_000)
        assertTrue(r.isFailure)
        assertTrue(
            r.exceptionOrNull()?.message?.contains("Do not order again") == true,
            "the pending message must warn against a duplicate order",
        )
    }
}
