package io.nisfeb.talon.ai

import io.nisfeb.talon.ui.screens.readingOfferLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a payment for Armillary does to the AI settings (the owner's
 * decisions, 2026-09-28): the gaps are filled with the strongest model
 * the vendor sells, what works stays, and no switch that reads
 * messages is turned.
 */
class ArmillarySetupTest {
    private val price = mapOf("big/tools" to 30L, "mid/tools" to 10L, "big/notools" to 90L, "cheap/any" to 1L, "big/kept" to 60L)

    private fun best(vararg m: ModelInfo) = armillaryBest(m.toList()) { price[it] ?: 0L }

    @Test
    fun `the strongest model that keeps nothing and uses tools is picked`() {
        assertEquals(
            "big/tools",
            best(
                ModelInfo("mid/tools", zdr = true, tools = true),
                ModelInfo("big/tools", zdr = true, tools = true),
                ModelInfo("big/notools", zdr = true, tools = false),
                ModelInfo("big/kept", zdr = false, tools = true),
                ModelInfo("whisper-1", speech = true, zdr = true, tools = true),
            ),
        )
        assertEquals("big/kept", best(ModelInfo("big/notools", zdr = true, tools = false), ModelInfo("big/kept", tools = true)), "the assistant needs tools more than anything")
        assertEquals("cheap/any", best(ModelInfo("cheap/any", zdr = true), ModelInfo("big/kept")), "a model not known to lack tools, if it keeps nothing")
        assertEquals("mid/tools", best(ModelInfo("big/tools", zdr = true), ModelInfo("mid/tools", zdr = true, tools = true)), "unknown tool use ranks after known")
        assertNull(best(ModelInfo("text-embedding-3-large"), ModelInfo("whisper-1", speech = true)))
    }

    private val armillary = AiProvider(
        ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary", baseUrl = "https://openrouter.ai/api/v1", apiKey = "lease.key",
        models = listOf(ModelInfo("big/tools", zdr = true, tools = true), ModelInfo("mid/tools", zdr = true, tools = true)),
    )
    private val device = AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")
    private val ours = ModelRef(ARMILLARY_PROVIDER, "big/tools")

    @Test
    fun `nothing set up runs on armillary, and nothing that reads messages is turned on`() {
        val p = AiProfile(
            providers = listOf(device, armillary),
            defaultModel = ModelRef(ARMILLARY_PROVIDER, ""),
            features = mapOf(AiFeature.OrreryTriage to FeatureSetting(on = true, model = ModelRef(DEVICE_PROVIDER, ""))),
        ).filledWithArmillary("big/tools")
        assertEquals(ours, p.defaultModel, "a blank pick ran whatever the ship listed first")
        assertFalse(p.isOn(AiFeature.CatchUp) || p.isOn(AiFeature.Assistant))
        assertEquals(ModelRef(DEVICE_PROVIDER, ""), p.features[AiFeature.OrreryTriage]?.model, "triage reads every message, and stays on this device")
        assertEquals(null, p.resolve(AiFeature.Assistant)?.problem(), "and the assistant can run the moment it is turned on")
    }

    @Test
    fun `the row is added where the payment came from a profile without one`() {
        val p = AiProfile(providers = listOf(device)).filledWithArmillary("big/tools")
        assertTrue(p.provider(ARMILLARY_PROVIDER) != null)
        assertEquals(ours, p.defaultModel)
    }

    private val openRouter = AiProvider(
        "main", ProviderKind.OpenRouter, "OpenRouter", apiKey = "sk-or",
        models = listOf(ModelInfo("anthropic/claude-opus-5", tools = true), ModelInfo("perplexity/sonar", tools = false)),
    )

    @Test
    fun `what the owner set up and works stays theirs`() {
        val mine = AiProfile(
            providers = listOf(device, openRouter, armillary),
            defaultModel = ModelRef("main", "anthropic/claude-opus-5"),
            features = mapOf(AiFeature.CatchUp to FeatureSetting(on = true, model = ModelRef("main", "perplexity/sonar"))),
        )
        assertEquals(mine, mine.filledWithArmillary("big/tools"))
    }

    @Test
    fun `a provider with no key, and a model gone blank, are filled`() {
        val keyless = openRouter.copy(apiKey = "")
        val p = AiProfile(
            providers = listOf(device, keyless, armillary),
            defaultModel = ModelRef("main", "anthropic/claude-opus-5"),
            features = mapOf(
                AiFeature.CatchUp to FeatureSetting(on = true, model = ModelRef("main", "perplexity/sonar")),
                AiFeature.Assistant to FeatureSetting(on = false, model = ModelRef(ARMILLARY_PROVIDER, "")),
            ),
        ).filledWithArmillary("big/tools")
        assertEquals(ours, p.defaultModel)
        assertEquals(FeatureSetting(on = true, model = ours), p.features[AiFeature.CatchUp], "its switch as it was")
        assertEquals(FeatureSetting(on = false, model = ours), p.features[AiFeature.Assistant])
    }

    // The assistant runs on tools; one following a default that has none
    // answered every request that needed a tool with an error.
    @Test
    fun `an assistant on a model without tools gets armillary's, and catch-up keeps the default`() {
        val p = AiProfile(
            providers = listOf(device, openRouter, armillary),
            defaultModel = ModelRef("main", "perplexity/sonar"),
        ).filledWithArmillary("big/tools")
        assertEquals(ModelRef("main", "perplexity/sonar"), p.defaultModel, "it works, for what needs no tools")
        assertEquals(ours, p.features[AiFeature.Assistant]?.model)
        assertNull(p.features[AiFeature.CatchUp], "catch-up needs no tools")
    }

    @Test
    fun `a profile running on armillary wants a row on a device that has none`() {
        val synced = AiProfile(providers = listOf(device), defaultModel = ours)
        assertTrue(synced.wantsArmillaryRow())
        assertFalse(synced.copy(providers = listOf(device, armillary)).wantsArmillaryRow(), "it has one")
        assertFalse(AiProfile(providers = listOf(device, openRouter), defaultModel = ModelRef("main", "")).wantsArmillaryRow(), "nothing runs on armillary")
        assertTrue(AiProfile(providers = listOf(device), features = mapOf(AiFeature.Assistant to FeatureSetting(model = ours))).wantsArmillaryRow())
    }

    @Test
    fun `the offer says where the messages go, and whether they are kept`() {
        assertEquals(
            "Catch-up and the assistant are set up on big/tools. Turn them on? When you ask for a summary or ask the assistant something, " +
                "the messages involved go to big/tools, which keeps nothing (zero data retention).",
            readingOfferLine("big/tools", zdr = true, mode = "lease", withAssistant = true),
        )
        assertTrue(readingOfferLine("m", zdr = false, mode = "lease", withAssistant = true).endsWith(", which may keep them."))
        assertTrue(readingOfferLine("m", zdr = true, mode = "proxy", withAssistant = true).endsWith("through the vendor's ship. The model does not keep them, and the ship does not store them."))
        assertTrue(readingOfferLine("m", zdr = false, mode = "proxy", withAssistant = true).endsWith("through the vendor's ship, and the model may keep them."))
        assertEquals(
            "Catch-up is set up on m. Turn it on? When you ask for a summary, the messages involved go to m, which keeps nothing (zero data retention).",
            readingOfferLine("m", zdr = true, mode = "lease", withAssistant = false),
        )
    }
}
