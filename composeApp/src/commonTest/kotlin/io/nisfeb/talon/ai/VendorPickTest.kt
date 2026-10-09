package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.DecideSettings
import io.nisfeb.talon.orrery.onVendor
import io.nisfeb.talon.orrery.openRouterKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The models an Armillary vendor picks (armillary 19): a feature runs on
 * the vendor's pick until the owner picks their own, and one tap puts it
 * back; the decision gate takes the vendor's model on the lease's key;
 * web search goes through the vendor where the owner has no Brave key.
 */
class VendorPickTest {
    private val own = AiProvider("p1", ProviderKind.OpenRouter, "OpenRouter", apiKey = "sk-own")
    private val device = AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")
    private fun row(
        suggested: Map<String, String> = mapOf("default" to "f/1", "catch_up" to "z/1", "decision" to "typesafe/jev-2"),
        base: String = "https://openrouter.ai/api/v1",
        tiers: Map<String, String> = mapOf("catch_up" to "zdr", "assistant" to "frontier"),
    ) = AiProvider(
        ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary", baseUrl = base, apiKey = "sk-or-lease",
        suggested = suggested, suggestedTiers = tiers, searchUrl = "https://v.example/apps/armillary/brave", searchKey = "id.secret",
    )

    private fun profile(vararg features: Pair<AiFeature, FeatureSetting>, armillary: AiProvider? = row()) = AiProfile(
        providers = listOfNotNull(own, device, armillary),
        defaultModel = ModelRef("p1", "own/default"),
        features = features.toMap(),
    )

    private fun conf(p: AiProfile) = AiSettings.Config(provider = AiSettings.Provider.OpenRouter, apiKey = "", model = null, savedProfile = p)

    @Test
    fun `a feature runs on the vendor's pick, its own name first and then the default`() {
        val p = profile(AiFeature.CatchUp to FeatureSetting(on = true, model = ModelRef("p1", "own/old")))
        assertEquals(Resolved(row(), "z/1"), p.resolve(AiFeature.CatchUp), "an existing pick is driven too")
        assertEquals(Resolved(row(), "f/1"), p.resolve(AiFeature.Assistant))
        assertTrue(p.followsVendor(AiFeature.CatchUp))
    }

    @Test
    fun `triage and transcription are never the vendor's`() {
        val p = profile(AiFeature.OrreryTriage to FeatureSetting(on = true, model = ModelRef("p1", "t/1")))
        assertEquals(Resolved(own, "t/1"), p.resolve(AiFeature.OrreryTriage))
        assertNull(p.vendorModel(AiFeature.Transcription))
    }

    @Test
    fun `a model picked by hand is the owner's, and one tap follows the vendor again`() {
        val picked = FeatureSetting(on = true, model = ModelRef("p1", "mine/1"), own = true)
        val p = profile(AiFeature.Assistant to picked)
        assertEquals(Resolved(own, "mine/1"), p.resolve(AiFeature.Assistant))
        assertFalse(p.followsVendor(AiFeature.Assistant))
        val back = profile(AiFeature.Assistant to picked.followingVendor())
        assertEquals(Resolved(row(), "f/1"), back.resolve(AiFeature.Assistant))
        assertTrue(back.isOn(AiFeature.Assistant), "following keeps the switch")
    }

    @Test
    fun `a private model chosen before counts as the owner's own, until one tap`() {
        val local = FeatureSetting(on = true, model = ModelRef(DEVICE_PROVIDER, "qwen"))
        val p = profile(AiFeature.CatchUp to local)
        assertEquals(Resolved(device, "qwen"), p.resolve(AiFeature.CatchUp))
        assertFalse(p.followsVendor(AiFeature.CatchUp))
        assertEquals(Resolved(row(), "z/1"), profile(AiFeature.CatchUp to local.followingVendor()).resolve(AiFeature.CatchUp))
    }

    @Test
    fun `with no Armillary row, or one the vendor says nothing on, nothing changes`() {
        val s = FeatureSetting(on = true, model = ModelRef("p1", "own/1"))
        assertEquals(Resolved(own, "own/1"), profile(AiFeature.CatchUp to s, armillary = null).resolve(AiFeature.CatchUp))
        assertEquals(Resolved(own, "own/1"), profile(AiFeature.CatchUp to s, armillary = row(emptyMap())).resolve(AiFeature.CatchUp))
    }

    @Test
    fun `a followed feature's client gets the row's base, key and the vendor's model`() {
        val c = conf(profile())
        val got = c.forFeature(AiFeature.Assistant)
        assertEquals("https://openrouter.ai/api/v1", got.baseUrl)
        assertEquals("sk-or-lease", got.apiKey)
        assertEquals("f/1", got.model)
    }

    @Test
    fun `the decision gate takes the vendor's model on the lease's key, and only under a lease`() {
        val leased = conf(profile())
        assertEquals("sk-or-lease", openRouterKey(leased))
        assertEquals("typesafe/jev-2", DecideSettings().onVendor(leased).model)
        val proxied = conf(profile(armillary = row(base = "https://v.example/apps/armillary/v1")))
        assertNull(proxied.profile().vendorDecision())
        assertEquals("typesafe/jev-1.13", DecideSettings().onVendor(proxied).model)
        val unnamed = conf(profile(armillary = row(mapOf("default" to "f/1"))))
        assertEquals("typesafe/jev-1.13", DecideSettings().onVendor(unnamed).model, "no fallback to a chat model")
    }

    @Test
    fun `web search uses the owner's own Brave key, else the vendor's search`() {
        val vendor = conf(profile())
        assertEquals("https://v.example/apps/armillary/brave" to "id.secret", vendor.braveSearch())
        assertEquals(BRAVE_API to "brave-own", vendor.copy(braveApiKey = " brave-own ").braveSearch())
        assertNull(conf(profile(armillary = null)).braveSearch())
    }

    @Test
    fun `a feature on the vendor's ZDR tier asks for ZDR endpoints, and only while it follows`() {
        val c = conf(profile())
        assertTrue(c.forFeature(AiFeature.CatchUp).zdrOnly)
        assertFalse(c.forFeature(AiFeature.Assistant).zdrOnly, "the frontier tier asks nothing")
        val own = conf(profile(AiFeature.CatchUp to FeatureSetting(on = true, model = ModelRef("p1", "mine/1"), own = true)))
        assertFalse(own.forFeature(AiFeature.CatchUp).zdrOnly, "the owner's own pick is theirs to route")
        assertFalse(conf(profile(armillary = null)).forFeature(AiFeature.CatchUp).zdrOnly)
    }

    @Test
    fun `an own pick travels, and the vendor's row and picks never do`() {
        val p = profile(AiFeature.Assistant to FeatureSetting(on = true, model = ModelRef("p1", "mine/1"), own = true))
        val sent = p.forSync()
        assertTrue(sent.features[AiFeature.Assistant]!!.own)
        assertNull(sent.armillary())
    }
}
