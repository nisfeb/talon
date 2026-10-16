package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.openRouterKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What is left of the profile from before Armillary-only: private addresses, and the profile edits that still run. */
class AiProfileTest {
    private fun cfg(
        provider: AiSettings.Provider = AiSettings.Provider.OpenRouter,
        key: String = "sk-or",
        model: String? = "anthropic/claude-opus-4.8",
        baseUrl: String? = null,
        privateUrl: String? = null,
        privateKey: String = "",
        privateModel: String? = null,
        frontierReads: Boolean = false,
        stt: String = "",
        catchUp: Boolean = true,
        agent: Boolean = false,
    ) = AiSettings.Config(
        provider = provider, apiKey = key, model = model, baseUrl = baseUrl,
        privateApiKey = privateKey, privateModel = privateModel, privateBaseUrl = privateUrl,
        frontierReadsMessages = frontierReads, sttApiKey = stt, catchMeUpEnabled = catchUp, agentEnabled = agent,
    )

    @Test
    fun `a private url is this machine or the owner's own network`() {
        for (u in listOf("http://localhost:1234/v1", "http://127.0.0.1:11434", "http://192.168.9.197:8081", "http://10.0.0.5/v1",
            "http://172.20.1.1", "http://100.100.206.8:8081", "http://box.local:1234", "https://llm.tail1234.ts.net/v1")) {
            assertTrue(isPrivateUrl(u), u)
        }
        for (u in listOf("http://172.16.0.1", "http://172.31.255.1", "http://100.64.0.1", "http://100.127.0.1")) {
            assertTrue(isPrivateUrl(u), "the edge of its range: $u")
        }
        for (u in listOf("https://openrouter.ai/api/v1", "https://api.openai.com/v1", "http://172.40.1.1", "http://100.200.1.1", null, "",
            "http://172.15.0.1", "http://172.32.0.1", "http://100.63.0.1", "http://100.128.0.1", "http://192.169.1.1",
            // A public name that starts like a private address.
            "http://10.0.0.1.example.com/v1", "http://localhost.example.com")) {
            assertFalse(isPrivateUrl(u), u.toString())
        }
        assertNull(migrateProfile(cfg(key = "")).defaultModel, "no key, no default model")
    }

    // The old fields came back for a feature on this device, frontier
    // key and all, so a caller that did not ask hasModelFor first sent
    // the messages to the cloud.
    @Test
    fun `a feature on this device is handed no key`() {
        val cfg = AiSettings.Config(
            AiSettings.Provider.OpenRouter, "sk-or", "m",
            savedProfile = AiProfile(
                providers = listOf(AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")),
                features = mapOf(AiFeature.CatchUp to FeatureSetting(true, ModelRef(DEVICE_PROVIDER, ""))),
            ),
        )
        assertEquals("", cfg.forFeature(AiFeature.CatchUp).apiKey)
    }

    // Model lists do not travel, so a blank choice on a server of your
    // own reached the other devices with nothing to read it by, and the
    // features on it went there. Saved, it becomes the model it meant.
    @Test
    fun `a blank server model is saved as the model it stands for`() {
        val lm = AiProvider(
            "lm", ProviderKind.OpenAiCompatible, "LM Studio", baseUrl = "http://10.0.0.2:1234/v1",
            models = listOf(ModelInfo("text-embedding-nomic"), ModelInfo("qwen3-8b")),
        )
        val p = AiProfile(providers = listOf(lm), defaultModel = ModelRef("lm", ""))
        val saved = AiSettings.Config(AiSettings.Provider.OpenRouter, "", null).withProfile(p, now = 1L).savedProfile!!
        assertEquals(ModelRef("lm", "qwen3-8b"), saved.defaultModel, "the first that chats, not the embedding model")
        val none = p.copy(providers = listOf(lm.copy(models = emptyList())))
        assertEquals(ModelRef("lm", ""), none.pinningModels().defaultModel, "left blank until there is a list")
    }

    // A feature following a private default was on it as surely as one
    // that named it; left on, it went to whatever default came next.
    @Test
    fun `removing a private default takes the features that followed it along`() {
        val p = AiProfile(
            providers = listOf(
                AiProvider("lm", ProviderKind.OpenAiCompatible, "LM Studio", baseUrl = "http://127.0.0.1:1234/v1"),
                AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device"),
            ),
            defaultModel = ModelRef("lm", "qwen"),
            features = mapOf(
                AiFeature.CatchUp to FeatureSetting(true),
                AiFeature.OrreryTriage to FeatureSetting(true),
            ),
        )
        val after = p.without("lm")
        assertFalse(after.isOn(AiFeature.CatchUp))
        assertEquals(ProviderKind.ThisDevice, after.resolve(AiFeature.OrreryTriage)?.provider?.kind)
    }
}
