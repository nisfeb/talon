package io.nisfeb.talon.armillary

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiClient
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.ModelInfo
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.ai.forFeature
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The armillary client against a ship that has the desk. Runs only when
 * told where and with what cookie, so CI never sees it:
 *
 *   TALON_ARMILLARY_URL=http://localhost:8081 \
 *   TALON_ARMILLARY_COOKIE='<the ship's session cookie, name=value>' \
 *     ./gradlew :composeApp:desktopTest --tests '*ArmillaryLiveTest*'
 *
 * The ship named must be a customer: its `vendor.json` points at a
 * vendor ship, and that vendor has at least one enabled catalog row.
 * The same code the app runs: probe, the key, the plans, a checkout,
 * and one completion through whatever the ship handed back. Nothing
 * here writes to the vendor's settings or catalog, so both ships are
 * left as they were found, bar a minted key and the audit rings.
 */
class ArmillaryLiveTest {
    private val url = System.getenv("TALON_ARMILLARY_URL")
    private val cookie = System.getenv("TALON_ARMILLARY_COOKIE")

    /** A cookie-scoped client and a scope for one test, cleaned up after. */
    private fun live(block: suspend (HttpClient, CoroutineScope, String) -> Unit) {
        if (url.isNullOrBlank() || cookie.isNullOrBlank()) {
            println("ArmillaryLiveTest: no ship given; skipped")
            return
        }
        val owner = createAppHttpClient().config { defaultRequest { header(HttpHeaders.Cookie, cookie) } }
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking { block(owner, scope, url) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the ship answers, holds a key, lists the plans and runs a completion`() = live { owner, scope, url ->
        val api = ArmillaryApi(owner, url)
        assertEquals(ArmillaryAvailability.PRESENT, api.probe(), "armillary answers on this ship")

        val repo = ArmillaryRepo(owner, scope)
        repo.attach(url, "live test")
        val inference = repo.ensureKey("live test").getOrThrow()
        assertTrue(inference.baseUrl.isNotBlank(), "the ship said where to call")
        assertTrue(inference.key.isNotBlank(), "the ship handed over a key")
        assertTrue(inference.models.isNotEmpty(), "the vendor sells at least one model")
        println("ArmillaryLiveTest: mode ${inference.mode}, ${inference.models.size} models")

        val plans = api.plans()
        assertTrue(plans.isNotEmpty(), "the vendor has plans")
        println("ArmillaryLiveTest: plans " + plans.joinToString(", ") { it.id + " " + money(it.priceMicro) })

        val account = api.account(fresh = true)
        println("ArmillaryLiveTest: balance " + money(account.balanceMicro) + ", vendor " + account.vendor)

        // A dollar. A vendor in live mode with a card rail answers a
        // URL; one in stub mode answers its own page; one that refuses
        // says which field was wrong, and that is an answer too.
        when (val answer = api.checkout("stripe", null, 1_000_000L)) {
            is CheckoutAnswer.Url -> assertTrue(answer.url.isNotBlank(), "a checkout url came back")
            is CheckoutAnswer.Refused -> println("ArmillaryLiveTest: the vendor refused the checkout: ${answer.reason}")
            is CheckoutAnswer.Pending -> println("ArmillaryLiveTest: the checkout is queued as ${answer.nonce}")
        }

        // The completion, through exactly the settings the card writes.
        val profile = AiProfile(
            providers = listOf(
                AiProvider(
                    ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary",
                    baseUrl = inference.baseUrl, apiKey = inference.key,
                    models = inference.models.map { ModelInfo(it) },
                ),
            ),
            defaultModel = ModelRef(ARMILLARY_PROVIDER, ""),
        )
        val cfg = AiSettings.Config(
            provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = profile,
        ).forFeature(AiFeature.CatchUp)
        assertEquals(AiSettings.Provider.Custom, cfg.provider)
        assertEquals(inference.models.first(), cfg.model, "a blank ref is the first model, never nothing")
        assertTrue(cfg.usageInclude, "the cost is asked for")
        val said = AiClient { cfg }.complete(null, "hello there world", maxOutputTokens = 64)
        println("ArmillaryLiveTest: the model said " + said.take(120))
        assertTrue(said.isNotBlank(), "the model answered")
    }
}
