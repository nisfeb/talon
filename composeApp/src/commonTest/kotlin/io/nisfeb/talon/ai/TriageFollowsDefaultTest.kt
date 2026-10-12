package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Triage follows the default model where that is private, and stays on
 * this device where it is not: it reads every message unasked, so only a
 * cloud model picked on its own row takes it off the device (a user's
 * report, 2026-10-12: the default set to his own server did not reach
 * triage). The ship's generator follows the default once the owner picked
 * "Default" for it.
 */
class TriageFollowsDefaultTest {
    private val cloud = AiProvider("or", ProviderKind.OpenRouter, "OpenRouter", apiKey = "sk-or")
    private val server = AiProvider("lm", ProviderKind.OpenAiCompatible, "LM Studio", baseUrl = "http://192.168.1.5:1234/v1")
    private val device = AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")

    /** As the settings start it: triage on this device, nobody's pick. */
    private val started = FeatureSetting(on = true, model = ModelRef(DEVICE_PROVIDER, ""))

    private fun profile(default: ModelRef?, triage: FeatureSetting? = started) = AiProfile(
        providers = listOf(cloud, server, device),
        defaultModel = default,
        features = listOfNotNull(triage?.let { AiFeature.OrreryTriage to it }).toMap(),
    )

    private fun AiProfile.triage() = resolve(AiFeature.OrreryTriage)!!
    private fun AiProfile.cfg() = AiSettings.Config(provider = AiSettings.Provider.OpenRouter, apiKey = "", model = null, savedProfile = this)

    @Test
    fun `a cloud default leaves triage on this device, and out of the cloud`() {
        for (t in listOf(started, null, FeatureSetting(on = true), FeatureSetting(on = true, own = true))) {
            val p = profile(ModelRef("or", "m"), t)
            assertEquals(ProviderKind.ThisDevice, p.triage().provider.kind, "$t")
            assertFalse(p.cfg().triageInCloud(), "$t")
            assertNull(p.cfg().triagePrivateSlot().baseUrl, "$t")
        }
    }

    @Test
    fun `a private default is where triage reads, until it has a pick of its own`() {
        val p = profile(ModelRef("lm", "qwen3"))
        assertNull(p.pick(AiFeature.OrreryTriage), "the device it was started on is nobody's pick")
        assertEquals(Resolved(server, "qwen3"), p.triage())
        assertFalse(p.cfg().triageInCloud())
        assertEquals(AiSettings.Slot(null, "", "qwen3", server.baseUrl), p.cfg().triagePrivateSlot())
        // A default on this device's own model is private too.
        assertEquals(ProviderKind.ThisDevice, profile(ModelRef(DEVICE_PROVIDER, "")).triage().provider.kind)
    }

    @Test
    fun `a pick on triage's own row wins over the default`() {
        val here = FeatureSetting(on = true, model = ModelRef(DEVICE_PROVIDER, ""), own = true)
        assertEquals(ProviderKind.ThisDevice, profile(ModelRef("lm", "qwen3"), here).triage().provider.kind, "this device, picked by hand")
        // The opt-in that takes triage off the device, whoever set it.
        for (own in listOf(true, false)) {
            val picked = profile(ModelRef("lm", "qwen3"), FeatureSetting(on = true, model = ModelRef("or", "c"), own = own))
            assertEquals(Resolved(cloud, "c"), picked.triage())
            assertTrue(picked.cfg().triageInCloud())
        }
    }

    @Test
    fun `the device model triage was started on keeps its name`() {
        val named = FeatureSetting(on = true, model = ModelRef(DEVICE_PROVIDER, "gemma"))
        assertEquals(Resolved(device, "gemma"), profile(ModelRef("or", "m"), named).triage())
        assertEquals(ModelRef(DEVICE_PROVIDER, "gemma"), profile(ModelRef("or", "m"), named).defaultFor(AiFeature.OrreryTriage))
        assertEquals(ModelRef("lm", "q"), profile(ModelRef("lm", "q"), named).defaultFor(AiFeature.OrreryTriage))
        assertEquals(ModelRef("or", "m"), profile(ModelRef("or", "m")).defaultFor(AiFeature.CatchUp), "every other feature's default is the default")
    }

    @Test
    fun `taking the server out puts triage back on this device`() {
        val p = profile(ModelRef("lm", "qwen3")).without("lm")
        assertEquals(ProviderKind.ThisDevice, p.triage().provider.kind)
        // With the default still in the cloud, too.
        val q = profile(ModelRef("or", "m"), FeatureSetting(on = true, model = ModelRef("lm", ""))).without("lm")
        assertEquals(ProviderKind.ThisDevice, q.triage().provider.kind)
    }

    @Test
    fun `older installs are told triage reads with the default only while it is the private one`() {
        val c = AiSettings.Config(provider = AiSettings.Provider.OpenRouter, apiKey = "sk-or", model = null)
        val onServer = c.withProfile(profile(ModelRef("lm", "qwen3")), now = 1L)
        assertTrue(onServer.frontierReadsMessages, "the default they know is the server")
        val inCloud = onServer.withProfile(profile(ModelRef("or", "m")), now = 2L)
        assertFalse(inCloud.frontierReadsMessages, "the default went to the cloud and triage did not")
    }

    @Test
    fun `the generator follows the default only where the owner picked Default`() {
        fun gen(s: FeatureSetting?) = AiProfile(features = listOfNotNull(s?.let { AiFeature.OrreryGenerator to it }).toMap()).generatorFollowsDefault()
        assertTrue(gen(FeatureSetting(on = true, model = null, own = true)))
        assertFalse(gen(FeatureSetting(on = true, model = null)), "set up on the ship, maybe: left alone")
        assertFalse(gen(FeatureSetting(on = true, model = ModelRef("or", "m"), own = true)), "a model of its own")
        assertFalse(gen(null))
    }
}
