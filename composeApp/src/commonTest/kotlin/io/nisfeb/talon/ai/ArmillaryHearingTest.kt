package io.nisfeb.talon.ai

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Where a dictation is heard: the owner's own key, else the Armillary
 * vendor's transcription model, else nowhere, exactly as before the vendor
 * offered one. Contract: lattice /project/talon/armillary-transcription-contract.
 */
class ArmillaryHearingTest {
    private val whisper = "openai/whisper-large-v3-turbo"
    private fun row(model: String? = whisper, base: String? = "https://openrouter.ai/api/v1", key: String = "sk-or-lease") = AiProvider(
        ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary", baseUrl = base, apiKey = key,
        suggested = buildMap { put("default", "anthropic/claude-haiku-5.5"); model?.let { put(TRANSCRIPTION, it) } },
    )
    private fun cfg(vararg providers: AiProvider, features: Map<AiFeature, FeatureSetting> = emptyMap()) = AiSettings.Config(
        provider = AiSettings.Provider.OpenRouter, apiKey = "", model = null,
        savedProfile = AiProfile(providers = providers.toList(), features = features),
    )

    @Test
    fun `the vendor's model hears a dictation where the owner has no key, and the blocker lifts`() {
        val c = cfg(row())
        assertEquals(Hearing.Armillary("https://openrouter.ai/api/v1", "sk-or-lease", whisper), DictationRun.hearing(c))
        assertEquals(DictationRun.NO_MODEL, DictationRun.blocker(c, hasShip = true), "past the key, on to the next thing missing")
    }

    @Test
    fun `before the vendor offers a model, nothing changes`() {
        listOf(cfg(row(model = null)), cfg(row(model = " ")), cfg(row(base = null)), cfg(row(key = "")), cfg()).forEach { c ->
            assertNull(DictationRun.hearing(c))
            assertEquals(DictationRun.NO_SPEECH_TO_TEXT, DictationRun.blocker(c, hasShip = true))
        }
    }

    @Test
    fun `the owner's own key wins over the vendor's`() {
        val openAi = AiProvider("oa", ProviderKind.OpenAi, "OpenAI", apiKey = "sk-own")
        val c = cfg(row(), openAi, features = mapOf(AiFeature.Transcription to FeatureSetting(on = true, model = ModelRef("oa", "whisper-1"))))
        val heard = DictationRun.hearing(c)
        assertIs<Hearing.Own>(heard)
        assertEquals("sk-own", heard.stt.key)
    }

    @Test
    fun `no chat list offers the transcription model`() {
        assertEquals(
            listOf("anthropic/claude-haiku-5.5", "openai/gpt-5"),
            chatModelsOf(listOf("anthropic/claude-haiku-5.5", whisper, "openai/gpt-4o-mini-transcribe", "openai/gpt-5"), mapOf(TRANSCRIPTION to whisper)),
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `the body is the contract's, the audio base64 with no data prefix, kept to zero retention`() {
        val audio = byteArrayOf(0, 1, 2, -1, 127)
        val b = ArmillaryTranscribe.body(whisper, audio, "m4a", "en")
        assertEquals(whisper, b["model"]!!.jsonPrimitive.content)
        val input = b["input_audio"]!!.jsonObject
        assertEquals("m4a", input["format"]!!.jsonPrimitive.content)
        val data = input["data"]!!.jsonPrimitive.content
        assertEquals(false, data.startsWith("data:"))
        assertEquals(audio.toList(), Base64.decode(data).toList())
        assertEquals("en", b["language"]!!.jsonPrimitive.content)
        assertEquals("true", b["provider"]!!.jsonObject["zdr"]!!.jsonPrimitive.content)
        assertNull(ArmillaryTranscribe.body(whisper, audio, "wav", "eng")["language"], "not an ISO-639-1 code")
    }
}
