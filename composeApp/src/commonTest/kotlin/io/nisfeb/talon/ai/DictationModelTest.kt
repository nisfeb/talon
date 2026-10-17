package io.nisfeb.talon.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a dictation runs on: the model an Armillary vendor names
 * "dictation", else the assistant's; and it never travels, since an older
 * install drops a profile it cannot read.
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
    }

    @Test
    fun `a model picked before this build is not run, the vendor's is`() {
        val p = profile(
            AiFeature.Assistant to FeatureSetting(on = true, model = ModelRef("p1", "mine/1"), own = true),
            AiFeature.Dictation to FeatureSetting(model = ModelRef("p1", "fast/1"), own = true),
        )
        assertEquals(Resolved(row(), "opus/5.5"), p.resolve(AiFeature.Dictation))
        assertTrue(p.vendorZdr(AiFeature.Dictation))
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
        assertTrue(p.vendorZdr(AiFeature.Dictation), "on the frontier tier it still asks for ZDR")
    }

}
