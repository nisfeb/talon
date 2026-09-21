package io.nisfeb.talon.armillary

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The readers against the documents the armillary desk answers with,
 * as of phase 5, and the three answers whose meaning is in the status
 * code rather than the body.
 */
class ArmillaryApiTest {

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    /** An api whose every request is answered by [reply], with the paths it saw. */
    private fun api(
        seen: MutableList<String> = mutableListOf(),
        reply: (String) -> Pair<HttpStatusCode, String>,
    ): ArmillaryApi = ArmillaryApi(
        HttpClient(MockEngine { req ->
            val path = req.url.encodedPath + (req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
            seen += path
            val (status, body) = reply(path)
            respond(body, status, jsonHeaders)
        }),
        "https://ship.example/",
    )

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    // ── the readers ────────────────────────────────────────────────

    @Test
    fun `the inference document is the whole of what a client needs`() {
        val got = inferenceOf(
            obj(
                """{"mode":"proxy","base_url":"https://wex.example/apps/armillary/v1",
                    "key":"abc123.secret","models":["stub/alpha","stub/beta"]}""",
            ),
        )
        assertEquals("proxy", got.mode)
        assertEquals("https://wex.example/apps/armillary/v1", got.baseUrl)
        assertEquals(listOf("stub/alpha", "stub/beta"), got.models)
        // A document from a desk that never says the mode is the proxy.
        assertEquals("proxy", inferenceOf(obj("""{"base_url":"u","key":"k"}""")).mode)
        assertTrue(inferenceOf(obj("{}")).models.isEmpty())
    }

    @Test
    fun `the account is the vendor's view plus what this ship added to it`() {
        val got = accountOf(
            obj(
                """{"ship":"~feb","balance":1234567,"plan":"starter",
                    "subscription":{"active":true,"renews":"2026-10-20T00:00:00Z"},
                    "lease":{"held":true,"disabled":false},
                    "checkouts":{"n1":{"nonce":"n1","rail":"stripe","plan":"","amount":1000000,
                                       "url":"https://pay.example/s1","status":"pending"}},
                    "ledger":[],"public_url":"https://wex.example","rev":7,
                    "vendor":"~wex","self":"~feb","stale":12}""",
            ),
        )
        assertTrue(got.hasView)
        assertEquals(1_234_567L, got.balanceMicro)
        assertEquals("starter", got.plan)
        assertTrue(got.subscriptionActive)
        assertEquals("2026-10-20T00:00:00Z", got.renews)
        assertEquals("~wex", got.vendor)
        assertEquals(12L, got.stale)
        assertTrue(got.leaseHeld)
        assertFalse(got.leaseDisabled)
        assertEquals(1, got.checkouts.size)
        assertEquals(Checkout("n1", "https://pay.example/s1", "pending", "stripe", 1_000_000L), got.checkouts.first())
    }

    @Test
    fun `an account with nothing in it yet reads as zero, not as a failure`() {
        val got = accountOf(obj("""{"vendor":"","self":"~feb","stale":0}"""))
        assertFalse(got.hasView, "no balance key means the ship has read no view yet")
        assertEquals(0L, got.balanceMicro)
        assertEquals("", got.plan)
        assertFalse(got.subscriptionActive)
        assertNull(got.renews)
        assertFalse(got.leaseHeld)
        assertTrue(got.checkouts.isEmpty())
    }

    @Test
    fun `a balance the vendor put in the red still reads`() {
        assertEquals(-2500L, accountOf(obj("""{"balance":-2500}""")).balanceMicro)
    }

    @Test
    fun `the plans are the vendor's own list, top-ups and subscriptions alike`() {
        val got = plansOf(
            Json.parseToJsonElement(
                """[{"id":"five","name":"Five dollars","kind":"topup","price":5000000,"credit":5000000,
                     "interval":"","stripe_price":""},
                    {"id":"starter","name":"Starter","kind":"subscription","price":10000000,
                     "credit":12000000,"interval":"month","stripe_price":"price_123"}]""",
            ),
        )
        assertEquals(listOf("five", "starter"), got.map { it.id })
        assertEquals(Plan("five", "Five dollars", "topup", 5_000_000L, 5_000_000L, ""), got.first())
        assertEquals("month", got[1].interval)
        // A row with no id is not a plan, and nothing at all is no plans.
        assertTrue(plansOf(Json.parseToJsonElement("""[{"name":"nameless"}]""")).isEmpty())
        assertTrue(plansOf(Json.parseToJsonElement("null")).isEmpty())
    }

    @Test
    fun `the catalog carries the vendor's prices and says which rows are zdr`() {
        val got = catalogOf(
            Json.parseToJsonElement(
                """[{"id":"stub/alpha","provider":"stub","in":3900000,"out":19500000,"tags":["zdr"]},
                    {"id":"stub/beta","provider":"stub","in":1000000,"out":2000000,"tags":[]}]""",
            ),
        )
        assertEquals(2, got.size)
        assertEquals(CatalogRow("stub/alpha", "stub", 3_900_000L, 19_500_000L, listOf("zdr")), got.first())
        assertTrue(got.first().zdr)
        assertFalse(got[1].zdr)
    }

    // ── the answers that live in the status code ───────────────────

    @Test
    fun `a ship holding no key says so, and that is not the app being absent`() = runTest {
        val noKey = api { HttpStatusCode.NotFound to """{"error":{"message":"no key yet"}}""" }
        assertEquals(InferenceAnswer.NoKey, noKey.inference())
        val absent = api { HttpStatusCode.NotFound to "not found" }
        assertEquals(InferenceAnswer.Missing, absent.inference())
        val have = api { HttpStatusCode.OK to """{"mode":"lease","base_url":"b","key":"k","models":["m"]}""" }
        assertEquals("lease", (have.inference() as InferenceAnswer.Have).inference.mode)
    }

    @Test
    fun `a checkout the vendor has not answered yet is a nonce, not a failure`() = runTest {
        val pending = api { HttpStatusCode.Accepted to """{"pending":true,"nonce":"n7"}""" }
        assertEquals(CheckoutAnswer.Pending("n7"), pending.checkout("stripe", null, 5_000_000L))
        val open = api { HttpStatusCode.OK to """{"nonce":"n8","url":"https://pay.example/s","status":"pending"}""" }
        assertEquals(CheckoutAnswer.Url("https://pay.example/s", "n8"), open.checkout("stripe", null, 5_000_000L))
        val refused = api { HttpStatusCode.BadGateway to """{"error":{"message":"amount: below the minimum"}}""" }
        assertEquals(CheckoutAnswer.Refused("amount: below the minimum"), refused.checkout("stripe", null, 1L))
    }

    @Test
    fun `a mint that has not landed inside the wait answers false, and so does a desk without leases`() = runTest {
        assertTrue(api { HttpStatusCode.OK to """{"id":"k1","name":"Talon","secret":"s"}""" }.mintKey("Talon"))
        assertFalse(api { HttpStatusCode.Accepted to """{"pending":true,"nonce":"n"}""" }.mintKey("Talon"))
        assertFalse(api { HttpStatusCode.NotImplemented to """{"error":{"message":"not yet"}}""" }.lease())
        assertFalse(api { HttpStatusCode.NotFound to "nope" }.lease())
        assertTrue(api { HttpStatusCode.OK to """{"ok":true}""" }.lease())
    }

    @Test
    fun `probe reads the account route and tells absence from being signed out`() = runTest {
        assertEquals(ArmillaryAvailability.PRESENT, api { HttpStatusCode.OK to "{}" }.probe())
        assertEquals(ArmillaryAvailability.MISSING, api { HttpStatusCode.NotFound to "" }.probe())
        assertEquals(ArmillaryAvailability.SIGNED_OUT, api { HttpStatusCode.Forbidden to "" }.probe())
        val seen = mutableListOf<String>()
        api(seen) { HttpStatusCode.OK to "{}" }.probe()
        assertEquals(listOf("/apps/armillary/api/account"), seen)
    }

    @Test
    fun `a fresh read asks the ship to peek the vendor first`() = runTest {
        val seen = mutableListOf<String>()
        api(seen) { HttpStatusCode.OK to """{"balance":10}""" }.account(fresh = true)
        assertEquals(listOf("/apps/armillary/api/account?fresh=1"), seen)
    }

    // ── money ──────────────────────────────────────────────────────

    @Test
    fun `microdollars read as money, and a fraction of a cent is said in words`() {
        assertEquals("under a cent", money(0))
        assertEquals("under a cent", money(4_999))
        assertEquals("$0.01", money(5_000))
        assertEquals("$0.01", money(10_000))
        assertEquals("$1.00", money(1_000_000))
        assertEquals("$12.35", money(12_345_000))
        assertEquals("$100.00", money(100_000_000))
    }
}
