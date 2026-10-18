package io.nisfeb.talon.armillary

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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
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
    /** The account view's checkout rows, keyed by nonce as the vendor writes them. */
    @Volatile private var checkouts = "{}"
    /** The account's view has not reached the ship yet: no balance in it. */
    @Volatile private var noView = false
    /** The account cannot be read: the proxy in front of the ship answers its 502 page. */
    @Volatile private var accountDown = false
    /** How long the ship takes over a checkout, a vendor change or a cancel. */
    @Volatile private var holdMs = 0L
    /** Writes the ship finished, which a request cancelled midway never does. */
    private val finished = CopyOnWriteArrayList<String>()
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
            path.endsWith("/apps/armillary/api/checkout") -> {
                delay(holdMs)
                finished += "checkout"
                if (slowVendor) respond("""{"pending":true,"nonce":"n9"}""", HttpStatusCode.Accepted, json)
                else ok("""{"url":"https://pay.example/c1","nonce":"n1"}""")
            }
            path.endsWith("/apps/armillary/api/vendor") -> { delay(holdMs); finished += "vendor"; ok("""{"ok":true}""") }
            path.endsWith("/apps/armillary/api/cancel-subscription") -> { delay(holdMs); finished += "cancel"; ok("""{"ok":true}""") }
            path.endsWith("/apps/armillary/api/checkout/n9") -> when {
                phaseMissing -> respond("", HttpStatusCode.NotFound)
                phaseRefused -> ok("""{"phase":"answered","status":"refused","url":"","note":"Your card was declined."}""")
                else -> ok("""{"phase":"answered","status":"pending","url":"https://pay.example/late","note":""}""")
            }
            path.endsWith("/apps/armillary/api/account") && accountDown ->
                respond("<html><body>502 Bad Gateway</body></html>", HttpStatusCode.BadGateway, headersOf("Content-Type", "text/html"))
            path.endsWith("/apps/armillary/api/account") && noView -> ok("""{"ship":"~zod","vendor":"~nisfeb","self":"~zod"}""")
            path.endsWith("/apps/armillary/api/account") -> ok(
                """{"ship":"~zod","balance":$balance,"plan":"","subscription":{"active":false},"lease":{},"checkouts":$checkouts,"vendor":"~nisfeb","self":"~zod","stale":3}""",
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
    /** What the repo told the person, as "title: body". */
    private val told = CopyOnWriteArrayList<String>()
    private val notifier = object : io.nisfeb.talon.notify.Notifier {
        override fun notify(title: String, body: String, key: String?) { told += "$title: $body" }
    }

    private inner class Rig(profile: AiProfile) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob())
        val ai = FakeAiSettings().apply {
            applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = profile))
        }
        val repo = ArmillaryRepo(http, scope, ai, notifier)

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

    // Pay on the phone and the desktop runs: the row never travels, so a
    // device of a ship that already buys from a vendor makes its own.
    @Test
    fun `a device of a ship that already buys connects itself, with its own key`() {
        keyed = false
        ship(AiProfile(providers = listOf(device))) { _, ai ->
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

    // It said the checkout would appear "under your checkouts", which no
    // screen shows, and nothing watched for it.
    @Test
    fun `on a desk without the phase route a 202 is watched, and its page offered once the vendor's row brings it`() = ship(setUp) { repo, _ ->
        slowVendor = true; phaseMissing = true
        assertEquals(null, repo.topUp("btcpay", null, 10_000_000).getOrThrow(), "no page yet, and not a failure")
        val waiting = repo.payment.value!!
        assertEquals("n9" to Payment.Phase.WAITING, waiting.nonce to waiting.phase)
        assertTrue(io.nisfeb.talon.ui.screens.paymentLine(waiting, null)!!.contains("Do not order again"))
        checkouts = """{"n9":{"nonce":"n9","rail":"btcpay","amount":10000000,"url":"https://pay.example/late","status":"pending"}}"""
        repo.refresh()
        val row = repo.account.value!!.checkouts.single()
        assertEquals("https://pay.example/late", io.nisfeb.talon.ui.screens.checkoutPage(repo.payment.value, row))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the card says to wait for a checkout with no page, then opens it once the vendor sends one`() = Rig(setUp).use { r ->
        slowVendor = true; phaseMissing = true
        runBlocking { r.repo.topUp("btcpay", null, 10_000_000).getOrThrow() }
        val opened = CopyOnWriteArrayList<String>()
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = false) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalUriHandler provides object : androidx.compose.ui.platform.UriHandler {
                            override fun openUri(uri: String) { opened += uri }
                        },
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(r.ai, orrery = null, armillary = r.repo) }
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Do not order again", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodesWithText("Open the checkout").fetchSemanticsNodes().isEmpty(), "nothing to open yet")
            checkouts = """{"n9":{"nonce":"n9","rail":"btcpay","amount":10000000,"url":"https://pay.example/late","status":"pending"}}"""
            runBlocking { r.repo.refresh() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Open the checkout").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Open the checkout").performScrollTo().performClick()
            assertEquals(listOf("https://pay.example/late"), opened.toList())
        }
    }

    // A second order while the first was on its way made a real checkout
    // nobody was sent to. The repo holds it now, so a card left and opened
    // again cannot send one either.
    @Test
    fun `a second order while the first is on its way makes no second checkout`() = ship(setUp) { repo, _ ->
        holdMs = 1_500
        val first = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { repo.topUp("stripe", null, 5_000_000L) }
        kotlinx.coroutines.withTimeout(5_000) { while (repo.asking.value == null) kotlinx.coroutines.delay(10) }
        assertEquals(CHECKOUT_ON_ITS_WAY, repo.topUp("stripe", null, 5_000_000L).exceptionOrNull()?.message)
        assertEquals("https://pay.example/c1", first.await().getOrThrow())
        assertEquals(null, repo.asking.value, "asked no longer")
        assertEquals(1, finished.count { it == "checkout" }, "one checkout made")
    }

    // "I got top up, choose a value, hit continue, and nothing happens"
    // (sneagan, 2026-10-18). On an account whose view had not come yet,
    // the card drew no payment line and no page; the wait was a small
    // grey line; a browser that would not open was swallowed.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `continue says what it is doing, keeps top up shut, and says a page that did not open`() = Rig(setUp).use { r ->
        noView = true
        holdMs = 1_500
        runBlocking { r.repo.refresh() }
        val tried = CopyOnWriteArrayList<String>()
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = false) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalUriHandler provides object : androidx.compose.ui.platform.UriHandler {
                            override fun openUri(uri: String) { tried += uri; error("no browser") }
                        },
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(r.ai, orrery = null, armillary = r.repo) }
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Top up").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Top up").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Another amount").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Another amount").performTextInput("10")
            onNodeWithText("Continue").performClick()
            waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Asking ~nisfeb for a checkout", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Top up").assertIsNotEnabled()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(io.nisfeb.talon.ui.screens.BROWSER_DID_NOT_OPEN).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("https://pay.example/c1"), tried.toList())
            assertTrue(onAllNodesWithText("Asking ~nisfeb", substring = true).fetchSemanticsNodes().isEmpty(), "asked no longer")
            assertTrue(onAllNodesWithText("Waiting for your payment").fetchSemanticsNodes().isNotEmpty(), "the payment line, with no view")
            onNodeWithText("Open the checkout").performScrollTo().performClick()
            assertEquals(2, tried.size, "the page offered again")
        }
    }

    // Measured from a balance read before the checkout opened: spending
    // since made a $5 payment $4.97, and a credit from elsewhere read as
    // this checkout paid before anyone had paid it.
    @Test
    fun `the checkout's own row says it was paid, and how much`() {
        balance = 10_000_000
        ship(setUp) { repo, _ ->
            repo.topUp("stripe", "five", null).getOrThrow()
            balance = 14_970_000
            checkouts = """{"n1":{"nonce":"n1","rail":"stripe","amount":5000000,"url":"https://pay.example/c1","status":"paid"}}"""
            repo.refresh()
            assertEquals(Payment.Phase.PAID to 5_000_000L, repo.payment.value!!.let { it.phase to it.addedMicro })
            assertEquals(listOf("Armillary: $5.00 added"), told.toList(), "the person is told what they paid")
        }
    }

    @Test
    fun `a paid row with no amount says what the balance rose by, and one that ended says so`() {
        balance = 10_000_000
        ship(setUp) { repo, _ ->
            repo.topUp("stripe", "five", null).getOrThrow()
            balance = 15_000_000
            checkouts = """{"n1":{"nonce":"n1","rail":"stripe","amount":0,"url":"https://pay.example/c1","status":"paid"}}"""
            repo.refresh()
            assertEquals(5_000_000L, repo.payment.value!!.addedMicro)
        }
        balance = 10_000_000
        // The second checkout starts from no rows: the first half's paid
        // row, read by the new repo's own background reads before the test
        // swapped in its expired one, settled it as paid.
        checkouts = "{}"
        ship(setUp) { repo, _ ->
            repo.topUp("stripe", "five", null).getOrThrow()
            checkouts = """{"n1":{"nonce":"n1","rail":"stripe","amount":5000000,"url":"https://pay.example/c1","status":"expired"}}"""
            repo.refresh()
            assertEquals(Payment.Phase.ENDED, repo.payment.value!!.phase)
        }
    }

    @Test
    fun `with no row and no rise nothing is paid yet`() {
        balance = 10_000_000
        ship(setUp) { repo, _ ->
            repo.topUp("stripe", "five", null).getOrThrow()
            repo.refresh()
            assertEquals(Payment.Phase.WAITING, repo.payment.value!!.phase)
            assertEquals(emptyList(), told.toList())
        }
    }

    // The balance before the checkout could not be read, and was taken as
    // nothing: the next read of any balance at all settled it as paid.
    @Test
    fun `a checkout opened without the balance before it is not paid by the balance there was`() {
        balance = 10_000_000
        accountDown = true
        ship(setUp) { repo, _ ->
            repo.topUp("stripe", "five", null).getOrThrow()
            accountDown = false
            repo.refresh()
            assertEquals(Payment.Phase.WAITING, repo.payment.value!!.phase, "the balance it had is not a payment")
            assertEquals(emptyList(), told.toList())
            balance = 15_000_000
            repo.refresh()
            assertEquals(Payment.Phase.PAID to 5_000_000L, repo.payment.value!!.let { it.phase to it.addedMicro }, "a rise from there is")
        }
    }

    @Test
    fun `a credit from elsewhere is not this checkout paid`() {
        balance = 10_000_000
        ship(setUp) { repo, _ ->
            repo.topUp("stripe", "five", null).getOrThrow()
            balance = 15_000_000
            checkouts = """{"n1":{"nonce":"n1","rail":"stripe","amount":5000000,"url":"https://pay.example/c1","status":"pending"}}"""
            repo.refresh()
            assertEquals(Payment.Phase.WAITING, repo.payment.value!!.phase, "its row says it is not paid")
        }
    }

    // A screen's scope: leaving Settings while the vendor answered dropped
    // the checkout it made, the vendor change, or the cancel.
    @Test
    fun `leaving the screen midway, a checkout, a vendor change and a cancel all finish`() = ship(setUp) { repo, _ ->
        holdMs = 300
        suspend fun leftMidway(write: suspend () -> Unit) {
            val screen = CoroutineScope(SupervisorJob())
            screen.launch { write() }
            delay(100)
            screen.coroutineContext.job.cancelAndJoin()
        }
        leftMidway { repo.topUp("stripe", "five", null) }
        withTimeout(10_000) { while (repo.payment.value?.nonce != "n1") delay(20) }
        assertEquals("https://pay.example/c1", repo.payment.value!!.url, "the card can still open its page")
        leftMidway { repo.setVendor("~wex", "test") }
        leftMidway { repo.cancelSubscription() }
        withTimeout(10_000) { while (!finished.containsAll(listOf("checkout", "vendor", "cancel"))) delay(20) }
    }
}
