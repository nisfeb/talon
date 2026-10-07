package io.nisfeb.talon.armillary

import io.nisfeb.talon.ai.ModelInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Brave Leo's "Bring your own model" fields, from what `/api/inference` answers. */
class BraveLeoTest {
    private val lease = Inference("lease", "https://openrouter.ai/api/v1/", "test-key", listOf("vendor/alpha", "vendor/beta"))

    @Test
    fun `a lease fills Leo's form with the chat completions url and the lease key`() {
        val s = assertIs<LeoSetup.Ready>(leoSetup(lease, leaseDisabled = false, model = null))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", s.endpoint)
        assertEquals("vendor/alpha", s.model, "the first model the ship lists")
        assertEquals("vendor/alpha (Armillary)", s.label)
        assertEquals("test-key", s.apiKey)
        assertNull(s.contextSize, "unknown is left to Leo's own default")
    }

    @Test
    fun `the chosen model is used where the ship lists it, and the first otherwise`() {
        assertEquals("vendor/beta", assertIs<LeoSetup.Ready>(leoSetup(lease, false, "vendor/beta")).model)
        assertEquals("vendor/alpha", assertIs<LeoSetup.Ready>(leoSetup(lease, false, "gone/model")).model)
    }

    @Test
    fun `the context size is the chosen model's own, capped at what Leo takes`() {
        val known = listOf(ModelInfo("vendor/alpha", "alpha", contextLength = 200_000), ModelInfo("vendor/beta", "beta", contextLength = 9_000_000))
        assertEquals(200_000, assertIs<LeoSetup.Ready>(leoSetup(lease, false, null, known)).contextSize)
        assertEquals(2_000_000, assertIs<LeoSetup.Ready>(leoSetup(lease, false, "vendor/beta", known)).contextSize)
        assertNull(assertIs<LeoSetup.Ready>(leoSetup(lease, false, null, listOf(ModelInfo("vendor/alpha", "alpha", contextLength = 0)))).contextSize)
        assertNull(assertIs<LeoSetup.Ready>(leoSetup(lease, false, null, listOf(ModelInfo("vendor/alpha")))).contextSize)
    }

    @Test
    fun `an endpoint that already ends in chat completions is left as it is`() {
        val s = assertIs<LeoSetup.Ready>(leoSetup(lease.copy(baseUrl = "https://p.example/v1/chat/completions"), false, null))
        assertEquals("https://p.example/v1/chat/completions", s.endpoint)
    }

    @Test
    fun `the proxy cannot serve Leo, since Leo streams and the proxy refuses a stream`() {
        val proxy = Inference("proxy", "https://wex.example/apps/armillary/v1", "k.s", listOf("stub/alpha"))
        val s = assertIs<LeoSetup.Cannot>(leoSetup(proxy, leaseDisabled = false, model = null))
        assertTrue("stream" in s.why && "lease" in s.why, s.why)
        // A lease out of credit answers proxy too, and the reason is the balance.
        val empty = assertIs<LeoSetup.Cannot>(leoSetup(proxy, leaseDisabled = true, model = null))
        assertTrue("balance is empty" in empty.why, empty.why)
    }

    @Test
    fun `what Brave would refuse or cannot call is said, not shown`() {
        val plain = assertIs<LeoSetup.Cannot>(leoSetup(lease.copy(baseUrl = "http://wex.example/v1"), false, null)).why
        assertTrue("https" in plain && "http://wex.example/v1" in plain, plain)
        assertTrue("no address" in assertIs<LeoSetup.Cannot>(leoSetup(lease.copy(baseUrl = " "), false, null)).why)
        assertTrue("no key" in assertIs<LeoSetup.Cannot>(leoSetup(lease.copy(key = ""), false, null)).why)
        assertTrue("no models" in assertIs<LeoSetup.Cannot>(leoSetup(lease.copy(models = emptyList()), false, null)).why)
    }
}
