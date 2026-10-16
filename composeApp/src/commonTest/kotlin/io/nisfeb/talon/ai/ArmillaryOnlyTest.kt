package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Armillary is the only cloud AI (docs/superpowers/specs/2026-10-16-armillary-only-design.md):
 * every feature runs on the vendor's model, whatever the profile kept
 * from before; triage stays on this device; with no Armillary row
 * nothing runs, and an old key does not stand in.
 */
class ArmillaryOnlyTest {
    private val device = AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")
    private val own = AiProvider("or", ProviderKind.OpenRouter, "OpenRouter", apiKey = "sk-own")
    private val server = AiProvider("lm", ProviderKind.OpenAiCompatible, "LM Studio", baseUrl = "http://127.0.0.1:1234/v1")
    private val row = armillaryRow(suggested = mapOf("default" to "d/1", "catch_up" to "c/1", "assistant" to "a/1"))

    /** A profile from before: own providers, an own default, and own picks on every feature. */
    private val before = AiProfile(
        providers = listOf(own, server, device, row),
        defaultModel = ModelRef("or", "own/default"),
        defaultOwn = true,
        features = mapOf(
            AiFeature.CatchUp to FeatureSetting(true, ModelRef("lm", "qwen"), own = true),
            AiFeature.Assistant to FeatureSetting(true, ModelRef("or", "own/a"), own = true),
            AiFeature.OrreryTriage to FeatureSetting(true, ModelRef("or", "own/t"), own = true),
        ),
    )

    @Test
    fun `every feature runs on the vendor's model, whatever was picked before`() {
        assertEquals(Resolved(row, "c/1"), before.resolve(AiFeature.CatchUp))
        assertEquals(Resolved(row, "a/1"), before.resolve(AiFeature.Assistant))
        assertEquals(Resolved(row, "a/1"), before.resolve(AiFeature.Dictation), "the assistant's, where the vendor names none")
        assertEquals(Resolved(row, "d/1"), before.resolve(AiFeature.OrreryGenerator), "the vendor's default for a feature it does not name")
        listOf(AiFeature.CatchUp, AiFeature.Assistant, AiFeature.Dictation).forEach {
            assertTrue(before.followsVendor(it))
            assertTrue(before.vendorZdr(it))
        }
    }

    @Test
    fun `a vendor that names nothing runs the row's first chat model`() {
        val bare = row.copy(suggested = emptyMap(), models = listOf(ModelInfo("embed/x"), ModelInfo("chat/1")))
        val p = before.copy(providers = listOf(device, bare))
        assertEquals(Resolved(bare, "chat/1"), p.resolve(AiFeature.CatchUp))
    }

    @Test
    fun `triage stays on this device, keeping the device model it was given`() {
        val r = before.resolve(AiFeature.OrreryTriage)!!
        assertEquals(ProviderKind.ThisDevice, r.provider.kind)
        assertFalse(before.followsVendor(AiFeature.OrreryTriage))
        val named = before.copy(features = before.features + (AiFeature.OrreryTriage to FeatureSetting(true, ModelRef(DEVICE_PROVIDER, "gemma-3n"))))
        assertEquals("gemma-3n", named.resolve(AiFeature.OrreryTriage)!!.model)
        assertEquals(ProviderKind.ThisDevice, AiProfile().resolve(AiFeature.OrreryTriage)!!.provider.kind, "even with no device row")
    }

    @Test
    fun `with no Armillary row nothing runs, and an old key does not stand in`() {
        val p = before.copy(providers = listOf(own, server, device))
        assertNull(p.resolve(AiFeature.CatchUp))
        assertNull(p.resolve(AiFeature.Assistant))
        val c = AiSettings.Config(provider = AiSettings.Provider.OpenRouter, apiKey = "sk-own", model = "own/default", savedProfile = p)
        assertFalse(c.hasModelFor(AiFeature.CatchUp))
        assertEquals("", c.forFeature(AiFeature.CatchUp).apiKey, "the old key is never sent")
        // An install that never saved a profile: its old key makes no provider.
        val old = AiSettings.Config(provider = AiSettings.Provider.OpenAi, apiKey = "sk-old", model = "gpt-4o", sttApiKey = "sk-whisper", privateBaseUrl = "http://127.0.0.1:1234/v1")
        assertEquals(listOf(ProviderKind.ThisDevice), old.profile().providers.map { it.kind })
        assertFalse(old.hasModelFor(AiFeature.Assistant))
    }

    @Test
    fun `a feature's client gets the row's base, key and the vendor's model, under ZDR`() {
        val c = AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk-old", model = "claude", savedProfile = before)
        val f = c.forFeature(AiFeature.Assistant)
        assertEquals(listOf<Any?>(AiSettings.Provider.Custom, "sk-or-lease", "a/1", "https://openrouter.ai/api/v1", true, true),
            listOf(f.provider, f.apiKey, f.model, f.baseUrl, f.usageInclude, f.zdrOnly))
        assertEquals("Armillary has no key.", AiSettings.Config(provider = AiSettings.Provider.Custom, apiKey = "", model = null,
            savedProfile = before.copy(providers = listOf(device, row.copy(apiKey = "")))).modelProblem(AiFeature.CatchUp))
    }
}
