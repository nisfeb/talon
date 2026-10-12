package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.ModelInfo
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a model picker names Armillary's models: the vendor's default is
 * "Default · Opus 5.5", beside Armillary's logo. It read "Default: its
 * default, Armillary" (sneagan, 2026-10-09).
 */
class ModelLabelTest {
    @Test
    fun `a model id as a person says it`() {
        assertEquals("Opus 5.5", shortModelName("anthropic/claude-opus-5.5"))
        assertEquals("Opus 5.5", shortModelName("anthropic/claude-opus-5-5"))
        assertEquals("Sonnet 4.5", shortModelName("anthropic/claude-sonnet-4-5-20250929"))
        assertEquals("3.5 Sonnet", shortModelName("anthropic/claude-3-5-sonnet-20241022"))
        assertEquals("GPT-5", shortModelName("openai/gpt-5"))
        assertEquals("GPT-4o Mini", shortModelName("openai/gpt-4o-mini"))
        assertEquals("Gemini 2.5 Pro", shortModelName("google/gemini-2.5-pro"))
        assertEquals("Grok 4", shortModelName("x-ai/grok-4"))
        assertEquals("Deepseek Chat V3", shortModelName("deepseek/deepseek-chat-v3:free"))
        assertEquals("Haiku 4.5", shortModelName("claude-haiku-4.5"), "no provider prefix")
        assertEquals("GPT", shortModelName("gpt"))
    }

    private val row = AiProvider(
        ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary", apiKey = "k.s",
        models = listOf(ModelInfo("anthropic/claude-haiku-4.5"), ModelInfo("anthropic/claude-opus-5.5")),
        suggested = mapOf("default" to "anthropic/claude-opus-5.5", "catch_up" to "anthropic/claude-haiku-4.5"),
    )
    private val anthropic = AiProvider("an", ProviderKind.Anthropic, "Anthropic", apiKey = "sk-ant")
    private val profile = AiProfile(providers = listOf(row, anthropic), defaultModel = ModelRef(ARMILLARY_PROVIDER, ""))

    @Test
    fun `Armillary's default runs the vendor's default and says so`() {
        // What runs: the vendor's default, not the first the ship lists.
        assertEquals("anthropic/claude-opus-5.5", profile.resolve(ModelRef(ARMILLARY_PROVIDER, ""))?.model)
        assertEquals("anthropic/claude-haiku-4.5", profile.copy(providers = listOf(row.copy(suggested = emptyMap()))).resolve(ModelRef(ARMILLARY_PROVIDER, ""))?.model,
            "a vendor naming no default: the first it lists, as before")
        // What it says.
        assertEquals("Default · Opus 5.5", refLabel(profile, profile.defaultModel))
        assertEquals("Default · Opus 5.5", defaultLabel(profile), "no Default: in front of a Default")
        assertEquals("Default · Haiku 4.5", refLabel(profile, ModelRef(ARMILLARY_PROVIDER, "anthropic/claude-haiku-4.5"), vendorPick = true), "a feature on the vendor's pick")
        assertEquals("Haiku 4.5", refLabel(profile, ModelRef(ARMILLARY_PROVIDER, "anthropic/claude-haiku-4.5")), "picked by hand: just its name")
        assertEquals("Default · no models yet", refLabel(profile.copy(providers = listOf(row.copy(models = emptyList(), suggested = emptyMap()))), profile.defaultModel))
        // Other providers keep their labels.
        val other = profile.copy(defaultModel = ModelRef("an", "claude-opus-5"))
        assertEquals("Default: claude-opus-5, Anthropic", defaultLabel(other))
    }

    // The words the AI page uses now, plain ones (2026-10-12): "Not kept"
    // for zero data retention, and the reading line the same for every
    // provider, triage saying what it reads unasked.
    private val device = AiProvider(io.nisfeb.talon.ai.DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")
    private val server = AiProvider("lan", ProviderKind.OpenAiCompatible, "My server", baseUrl = "http://10.0.0.5:11434/v1")
    private val openRouter = AiProvider("or", ProviderKind.OpenRouter, "OpenRouter", apiKey = "k", models = listOf(ModelInfo("kept/1"), ModelInfo("safe/1", zdr = true)))

    @Test
    fun `labels name this device once, a model not kept, and no model at all`() {
        val p = AiProfile(providers = listOf(device, openRouter))
        assertEquals("This device (private)", refLabel(p, ModelRef(io.nisfeb.talon.ai.DEVICE_PROVIDER, "")))
        assertEquals("safe/1, OpenRouter · Not kept", refLabel(p, ModelRef("or", "safe/1")))
        assertEquals("kept/1, OpenRouter", refLabel(p, ModelRef("or", "kept/1")))
        assertEquals("No model yet", refLabel(p, null))
        assertEquals("No model yet. Add a provider above.", defaultLabel(p))
        assertEquals("Default: This device (private)", defaultLabel(p, ModelRef(io.nisfeb.talon.ai.DEVICE_PROVIDER, "")))
    }

    @Test
    fun `a provider's line says what it keeps in plain words`() {
        assertEquals("Save to fetch its models. Whether it keeps what you send depends on your account.", providerSummary(anthropic))
        assertEquals("2 models, 1 of them Not kept.", providerSummary(openRouter))
        assertEquals("Save to fetch its models. Private: on your own machine or network.", providerSummary(server))
        assertEquals("Save to fetch its models. Not on your network, so it is an online service.", providerSummary(server.copy(baseUrl = "https://llm.example.com/v1")))
    }

    @Test
    fun `the reading line is the same for every provider, and triage says it reads unasked`() {
        fun on(model: ModelRef, vararg ps: AiProvider) = AiProfile(
            providers = ps.toList(),
            features = mapOf(io.nisfeb.talon.ai.AiFeature.CatchUp to io.nisfeb.talon.ai.FeatureSetting(true, model), io.nisfeb.talon.ai.AiFeature.OrreryTriage to io.nisfeb.talon.ai.FeatureSetting(true, model, own = true)),
        )
        val catchUp = io.nisfeb.talon.ai.AiFeature.CatchUp
        val triage = io.nisfeb.talon.ai.AiFeature.OrreryTriage
        assertEquals("Messages you ask about go to this model and may be stored.", readingWarning(on(ModelRef("or", "kept/1"), openRouter), catchUp))
        assertEquals("Every call and status line read here goes to this model and may be stored.", readingWarning(on(ModelRef("or", "kept/1"), openRouter), triage))
        assertEquals(null, readingWarning(on(ModelRef("or", "safe/1"), openRouter), catchUp), "not kept")
        assertEquals(null, readingWarning(on(ModelRef("lan", "llama"), server), catchUp), "private")
        assertEquals("Messages you ask about go to Anthropic. Whether they are kept depends on your account.", readingWarning(on(ModelRef("an", "c"), anthropic), catchUp))
        val arm = row.copy(models = listOf(ModelInfo("z/kept"), ModelInfo("z/safe", zdr = true)), suggested = emptyMap())
        assertEquals("Messages pass through your vendor on the way to the model. The model may keep them.", readingWarning(on(ModelRef(ARMILLARY_PROVIDER, "z/kept"), arm), catchUp, "proxy"))
        assertEquals("Messages pass through your vendor on the way to the model. Nothing keeps them.", readingWarning(on(ModelRef(ARMILLARY_PROVIDER, "z/safe"), arm), catchUp, "proxy"))
        assertEquals(null, readingWarning(on(ModelRef(ARMILLARY_PROVIDER, "z/safe"), arm), catchUp, "lease"))
        assertEquals("Messages you ask about go to this model and may be stored.", readingWarning(on(ModelRef(ARMILLARY_PROVIDER, "z/kept"), arm), catchUp, "lease"))
        assertEquals(null, readingWarning(AiProfile(), catchUp), "no model, nothing to warn of")
    }
}
