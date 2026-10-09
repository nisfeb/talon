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
}
