package io.nisfeb.talon.orrery

import io.nisfeb.talon.ai.AiSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cloud rung is ready only with a key, and says so otherwise. */
class CloudRungTest {
    @Test
    fun `no key is not a rung`() = runTest {
        val rung = CloudRung { AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null) }
        assertTrue(rung.status() is RungStatus.Unavailable)
        // A key is not enough: the cloud is a rung only where it reads
        // messages, which is the owner's opt-in. Without it the on-device
        // model does, and saying the cloud was ready was not true.
        val keyedNotAsked = CloudRung { AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk", model = null) }
        assertTrue(keyedNotAsked.status() is RungStatus.Unavailable)
        val keyed = CloudRung { AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk", model = null, frontierReadsMessages = true) }
        assertEquals(RungStatus.Ready, keyed.status())
        assertTrue("Anthropic" in keyed.name)
    }

    // The status said "has no key" for every gap, so a server of your
    // own with no address was told to add a key it did not need.
    @Test
    fun `it says what is missing`() = runTest {
        fun triageOn(p: io.nisfeb.talon.ai.AiProvider, model: String = "qwen") = CloudRung {
            AiSettings.Config(
                AiSettings.Provider.Anthropic, "", null,
                savedProfile = io.nisfeb.talon.ai.AiProfile(
                    providers = listOf(p),
                    features = mapOf(io.nisfeb.talon.ai.AiFeature.OrreryTriage to io.nisfeb.talon.ai.FeatureSetting(true, io.nisfeb.talon.ai.ModelRef(p.id, model))),
                ),
            )
        }
        suspend fun why(r: CloudRung) = (r.status() as RungStatus.Unavailable).reason
        val lm = io.nisfeb.talon.ai.AiProvider("lm", io.nisfeb.talon.ai.ProviderKind.OpenAiCompatible, "LM Studio")
        assertTrue("no address" in why(triageOn(lm)))
        assertTrue("no model" in why(triageOn(lm.copy(baseUrl = "http://10.0.0.2:1234/v1"), model = "")))
        assertTrue("no key" in why(triageOn(io.nisfeb.talon.ai.AiProvider("or", io.nisfeb.talon.ai.ProviderKind.OpenRouter, "OpenRouter"))))
        assertEquals(RungStatus.Ready, triageOn(lm.copy(baseUrl = "http://10.0.0.2:1234/v1")).status())
    }
}
