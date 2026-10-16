package io.nisfeb.talon.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a dictation runs on: the assistant's model until it has one of its
 * own, picked on this device or named "dictation" by an Armillary vendor;
 * and it never travels, since an older install drops a profile it cannot read.
 */
class DictationModelTest {
    private val own = AiProvider("p1", ProviderKind.OpenRouter, "OpenRouter", apiKey = "sk-own")
    private fun row(
        suggested: Map<String, String> = mapOf("default" to "f/1", "assistant" to "opus/5.5"),
        tiers: Map<String, String> = mapOf("assistant" to "zdr"),
    ) = AiProvider(
        ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary", baseUrl = "https://openrouter.ai/api/v1", apiKey = "sk-or-lease",
        suggested = suggested, suggestedTiers = tiers,
    )

    private fun profile(vararg features: Pair<AiFeature, FeatureSetting>, armillary: AiProvider? = row()) = AiProfile(
        providers = listOfNotNull(own, armillary),
        defaultModel = ModelRef("p1", "own/default"),
        features = features.toMap() + (AiFeature.Assistant to (features.toMap()[AiFeature.Assistant] ?: FeatureSetting(on = true))),
    )

    @Test
    fun `with no model of its own a dictation runs where the assistant does, not on the vendor's default`() {
        val p = profile()
        assertEquals(Resolved(row(), "opus/5.5"), p.resolve(AiFeature.Assistant))
        assertEquals(p.resolve(AiFeature.Assistant), p.resolve(AiFeature.Dictation))
        assertFalse(p.followsVendor(AiFeature.Dictation))
    }

    @Test
    fun `it follows the assistant onto the owner's own model too`() {
        val p = profile(AiFeature.Assistant to FeatureSetting(on = true, model = ModelRef("p1", "mine/1"), own = true))
        assertEquals(Resolved(own, "mine/1"), p.resolve(AiFeature.Dictation))
    }

    @Test
    fun `and under the assistant's terms, ZDR where the assistant is`() {
        val p = profile()
        assertTrue(p.vendorZdr(AiFeature.Assistant))
        assertTrue(p.vendorZdr(AiFeature.Dictation), "a dictation on the assistant's ZDR model went out without the ZDR flag")
    }

    @Test
    fun `a vendor that names a dictation model is followed`() {
        val p = profile(armillary = row(mapOf("default" to "f/1", "assistant" to "opus/5.5", "dictation" to "haiku/5.5"), mapOf("dictation" to "frontier")))
        assertEquals(Resolved(row(mapOf("default" to "f/1", "assistant" to "opus/5.5", "dictation" to "haiku/5.5"), mapOf("dictation" to "frontier")), "haiku/5.5"), p.resolve(AiFeature.Dictation))
        assertTrue(p.followsVendor(AiFeature.Dictation))
        assertFalse(p.vendorZdr(AiFeature.Dictation), "its own tier, not the assistant's")
    }

    @Test
    fun `a model the owner picks wins over both`() {
        val p = profile(AiFeature.Dictation to FeatureSetting(model = ModelRef("p1", "fast/1"), own = true))
        assertEquals(Resolved(own, "fast/1"), p.resolve(AiFeature.Dictation))
        assertFalse(p.vendorZdr(AiFeature.Dictation))
    }

    @Test
    fun `picking Default goes back to the assistant's`() {
        val p = profile(AiFeature.Dictation to FeatureSetting(model = null, own = true))
        assertEquals(p.resolve(AiFeature.Assistant), p.resolve(AiFeature.Dictation))
        assertEquals(ModelRef(ARMILLARY_PROVIDER, "opus/5.5"), p.defaultFor(AiFeature.Dictation))
    }

    @Test
    fun `a dictation's model never goes to the ship`() {
        val p = profile(AiFeature.Dictation to FeatureSetting(model = ModelRef("p1", "fast/1"), own = true))
        assertNull(p.forSync().features[AiFeature.Dictation])
        // What an older install reads: no feature name it does not know.
        val wire = Json.encodeToString(AiProfile.serializer(), p.forSync())
        assertFalse("Dictation" in wire, wire)
    }

    @Test
    fun `a profile from the ship keeps this device's dictation model`() {
        val here = profile(AiFeature.Dictation to FeatureSetting(model = ModelRef("p1", "fast/1"), own = true))
        val current = AiSettings.Config(provider = AiSettings.Provider.OpenRouter, apiKey = "", model = null, savedProfile = here)
        val fromShip = profile(AiFeature.CatchUp to FeatureSetting(on = true))
        val entry = buildJsonObject { put("profile", Json.encodeToJsonElement(AiProfile.serializer(), fromShip.forSync())) }
        val after = profileAfterEntry(entry, current, current)
        assertEquals(FeatureSetting(model = ModelRef("p1", "fast/1"), own = true), after?.features?.get(AiFeature.Dictation))
    }
}
