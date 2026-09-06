package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which endpoint and model a recording is transcribed against. Both of
 * the Custom-provider paths here were broken in ways that only showed
 * up as a failed publish after a call had already been recorded.
 */
class CallRecordingPublisherTest {

    private fun cfg(
        provider: AiSettings.Provider,
        apiKey: String = "",
        baseUrl: String? = null,
        sttApiKey: String = "",
        model: String? = null,
    ) = AiSettings.Config(
        provider = provider,
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl,
        sttApiKey = sttApiKey,
    )

    @Test
    fun theDedicatedWhisperKeyWinsWhateverTheChatProviderIs() {
        val stt = CallRecordingPublisher.sttFrom(
            cfg(AiSettings.Provider.Anthropic, apiKey = "sk-ant", sttApiKey = "sk-whisper"),
        )
        // An Anthropic user has no audio endpoint of their own; this is
        // the whole reason the separate key exists.
        assertEquals("sk-whisper", stt?.key)
        assertEquals("https://api.openai.com/v1/audio/transcriptions", stt?.endpoint)
        assertEquals("whisper-1", stt?.model)
    }

    @Test
    fun anthropicWithNoWhisperKeyHasNoEndpoint() {
        assertNull(CallRecordingPublisher.sttFrom(cfg(AiSettings.Provider.Anthropic, apiKey = "sk-ant")))
    }

    @Test
    fun customNeverPostsTheChatModelAsTheSpeechModel() {
        val stt = CallRecordingPublisher.sttFrom(
            cfg(
                AiSettings.Provider.Custom,
                apiKey = "k",
                baseUrl = "https://host.example/v1",
                model = "llama-3.1-70b",
            ),
        )
        // Posting the chat model here fails on every host.
        assertEquals("whisper-1", stt?.model)
        assertEquals("https://host.example/v1/audio/transcriptions", stt?.endpoint)
    }

    @Test
    fun aFullChatUrlIsNormalisedRatherThanConcatenated() {
        // Users paste what their provider gave them; this used to build
        // ".../chat/completions/audio/transcriptions".
        assertEquals(
            "https://host.example/v1/audio/transcriptions",
            CallRecordingPublisher.audioEndpoint("https://host.example/v1/chat/completions"),
        )
        assertEquals(
            "https://host.example/v1/audio/transcriptions",
            CallRecordingPublisher.audioEndpoint("https://host.example/v1/"),
        )
    }
}
