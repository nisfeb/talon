package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.DecideSettings
import io.nisfeb.talon.orrery.onVendor
import io.nisfeb.talon.orrery.decisionKey
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
    fun `a followed feature's client gets the row's base, key and the vendor's model`() {
        val c = conf(profile())
        val got = c.forFeature(AiFeature.Assistant)
        assertEquals("https://openrouter.ai/api/v1", got.baseUrl)
        assertEquals("sk-or-lease", got.apiKey)
        assertEquals("f/1", got.model)
    }

    @Test
    fun `the decision gate takes the vendor's model and route, under a lease or through the proxy`() {
        val leased = conf(profile())
        assertEquals("sk-or-lease", decisionKey(leased))
        val onLease = DecideSettings().onVendor(leased)
        assertEquals("typesafe/jev-2" to "https://openrouter.ai/api/alpha/decisions", onLease.model to onLease.url, "OpenRouter's own route")
        // Armillary 34: the proxy asks the vendor's route, on this device's Armillary key.
        val proxied = conf(profile(armillary = row(base = "https://v.example/apps/armillary/v1/")))
        val onProxy = DecideSettings().onVendor(proxied)
        assertEquals("typesafe/jev-2" to "https://v.example/apps/armillary/alpha/decisions", onProxy.model to onProxy.url)
        assertEquals("sk-or-lease", decisionKey(proxied))
        val unnamed = conf(profile(armillary = row(mapOf("default" to "f/1"))))
        assertNull(unnamed.profile().vendorDecision())
        assertEquals("typesafe/jev-1.13", DecideSettings().onVendor(unnamed).model, "no fallback to a chat model")
    }

    @Test
    fun `the decisions route is the base with a trailing v1 made alpha decisions`() {
        assertEquals("https://openrouter.ai/api/alpha/decisions", decisionsUrl("https://openrouter.ai/api/v1"))
        assertEquals("https://zod.example/apps/armillary/alpha/decisions", decisionsUrl(" https://zod.example/apps/armillary/v1/ "))
        assertEquals("http://box.lan:8080/apps/armillary/alpha/decisions", decisionsUrl("http://box.lan:8080/apps/armillary"), "no /v1 to take off")
    }

    @Test
    fun `web search uses the owner's own Brave key, else the vendor's search`() {
        val vendor = conf(profile())
        assertEquals("https://v.example/apps/armillary/brave" to "id.secret", vendor.braveSearch())
        assertEquals("https://v.example/apps/armillary/brave" to "id.secret", vendor.copy(braveApiKey = " brave-own ").braveSearch(), "an own key left from before is not used")
        assertNull(conf(profile(armillary = null)).braveSearch())
    }

    @Test
    fun `every call on Armillary asks for ZDR endpoints, on any tier`() {
        val c = conf(profile())
        assertTrue(c.forFeature(AiFeature.CatchUp).zdrOnly)
        assertTrue(c.forFeature(AiFeature.Assistant).zdrOnly, "the frontier tier went out without the ZDR flag")
        assertTrue(conf(profile(armillary = row(tiers = emptyMap()))).forFeature(AiFeature.CatchUp).zdrOnly, "a vendor that names no tiers")
        val own = conf(profile(AiFeature.CatchUp to FeatureSetting(on = true, model = ModelRef("p1", "mine/1"), own = true)))
        assertTrue(own.forFeature(AiFeature.CatchUp).zdrOnly, "a pick from before runs on Armillary, under its terms")
        assertFalse(conf(profile(armillary = null)).forFeature(AiFeature.CatchUp).zdrOnly)
    }

}
