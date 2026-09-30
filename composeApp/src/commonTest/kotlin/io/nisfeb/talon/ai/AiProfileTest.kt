package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.openRouterKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profile derived from today's settings, row by row of the plan's
 * mapping table, and the resolver giving every feature exactly what it
 * read before. The old code is kept here as the oracle for the second.
 */
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
    fun `a private model with a url is a provider triage reads with, and without one this device is`() {
        val served = migrateProfile(cfg(privateUrl = "http://127.0.0.1:1234/v1", privateModel = "qwen3-8b"), ProfileInputs(orreryFed = true))
        assertEquals(Resolved(served.provider(PRIVATE_PROVIDER)!!, "qwen3-8b"), served.resolve(AiFeature.OrreryTriage))
        assertTrue(served.resolve(AiFeature.OrreryTriage)!!.private)
        assertTrue(served.isOn(AiFeature.OrreryTriage), "feeding orrery turns triage on")
        val device = migrateProfile(cfg())
        assertEquals(DEVICE_PROVIDER, device.resolve(AiFeature.OrreryTriage)!!.provider.id)
        assertFalse(device.isOn(AiFeature.OrreryTriage))
    }

    @Test
    fun `letting the frontier model read messages assigns triage the default model`() {
        val p = migrateProfile(cfg(frontierReads = true, privateUrl = "http://127.0.0.1:1234/v1"))
        assertEquals(MAIN_PROVIDER, p.resolve(AiFeature.OrreryTriage)!!.provider.id)
    }

    @Test
    fun `switches, the generator, jev and transcription`() {
        val p = migrateProfile(
            cfg(catchUp = false, agent = true, stt = "sk-whisper"),
            ProfileInputs(jevOn = true, generatorOn = true, generatorUrl = "https://openrouter.ai/api/v1", generatorModel = "moonshotai/kimi-k3"),
        )
        assertFalse(p.isOn(AiFeature.CatchUp))
        assertTrue(p.isOn(AiFeature.Assistant))
        assertEquals(true, p.jev)
        assertNull(migrateProfile(cfg()).jev, "never flipped")
        assertEquals(Resolved(p.provider(MAIN_PROVIDER)!!, "moonshotai/kimi-k3"), p.resolve(AiFeature.OrreryGenerator))
        assertTrue(p.isOn(AiFeature.OrreryGenerator))
        assertEquals(Resolved(p.provider(SPEECH_PROVIDER)!!, WHISPER), p.resolve(AiFeature.Transcription))
        // Without a transcription key an OpenAI chat key transcribes, and Anthropic's cannot.
        assertEquals(MAIN_PROVIDER, migrateProfile(cfg(provider = AiSettings.Provider.OpenAi)).resolve(AiFeature.Transcription)!!.provider.id)
        assertFalse(migrateProfile(cfg(provider = AiSettings.Provider.Anthropic)).isOn(AiFeature.Transcription))
    }

    // ---- the resolver gives each feature what it read before ----

    private val combos: List<AiSettings.Config> = buildList {
        for (provider in AiSettings.Provider.entries) {
            for (key in listOf("", "sk-x")) {
                for (privateUrl in listOf(null, "http://127.0.0.1:1234/v1", "https://openrouter.ai/api/v1")) {
                    for (reads in listOf(false, true)) {
                        for (stt in listOf("", "sk-stt")) {
                            add(cfg(provider = provider, key = key, baseUrl = if (provider == AiSettings.Provider.Custom) "https://llm.example/v1" else null,
                                privateUrl = privateUrl, privateKey = if (privateUrl != null) "sk-p" else "", privateModel = "m", frontierReads = reads, stt = stt))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `catch-up and the assistant see the frontier model, as before`() {
        for (c in combos.filter { it.hasKey() }) for (f in listOf(AiFeature.CatchUp, AiFeature.Assistant)) {
            val r = c.forFeature(f)
            assertEquals(listOf(c.provider, c.apiKey, c.model, c.baseUrl), listOf(r.provider, r.apiKey, r.model, r.baseUrl), "$f on $c")
        }
    }

    /** The old openRouterKey, verbatim. */
    private fun oldOpenRouterKey(cfg: AiSettings.Config): String? = when {
        cfg.provider == AiSettings.Provider.OpenRouter && cfg.apiKey.isNotBlank() -> cfg.apiKey
        cfg.privateBaseUrl?.contains("openrouter.ai") == true && cfg.privateApiKey.isNotBlank() -> cfg.privateApiKey
        else -> null
    }

    /** The old sttFrom, verbatim, as (endpoint, key). */
    private fun oldStt(cfg: AiSettings.Config): Pair<String, String>? {
        cfg.sttApiKey.takeIf { it.isNotBlank() }?.let { return CallRecordingPublisher.OPENAI_STT to it }
        return when (cfg.provider) {
            AiSettings.Provider.OpenAi -> cfg.apiKey.takeIf { it.isNotBlank() }?.let { CallRecordingPublisher.OPENAI_STT to it }
            AiSettings.Provider.Custom -> cfg.baseUrl?.takeIf { it.isNotBlank() && cfg.apiKey.isNotBlank() }?.let { CallRecordingPublisher.audioEndpoint(it) to cfg.apiKey }
            else -> null
        }
    }

    @Test
    fun `jev's key, transcription, triage's switch and its private model are what they were`() {
        for (c in combos) {
            assertEquals(oldOpenRouterKey(c), openRouterKey(c), "jev key on $c")
            assertEquals(oldStt(c), CallRecordingPublisher.sttFrom(c)?.let { it.endpoint to it.key }, "stt on $c")
            // The cloud rung was on exactly when the frontier model could
            // read messages, with one difference on purpose: a "private"
            // model at a cloud address (OpenRouter) was read through the
            // server rung with the switch off, and is now called what it
            // is, triage in the cloud. Same endpoint, key and model.
            val mainReads = c.frontierReadsMessages && c.hasKey()
            val privateInCloud = !mainReads && c.privateBaseUrl != null && !isPrivateUrl(c.privateBaseUrl)
            if (c.provider != AiSettings.Provider.Custom) assertEquals(mainReads || privateInCloud, c.triageInCloud(), "triage switch on $c")
            val slot = c.triagePrivateSlot()
            assertEquals(c.private.baseUrl, slot.baseUrl, "private url on $c")
            assertEquals(c.private.model, slot.model, "private model on $c")
            if (c.privateBaseUrl != null) assertEquals(c.private.apiKey, slot.apiKey, "private key on $c")
        }
    }

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

    // Every feature used to gate on the old key field. A server of the
    // owner's own often wants no key, so everything it could run stayed
    // hidden, and a removed key went on counting.
    @Test
    fun `a model is a model whether or not it has a key`() {
        val local = AiProvider("p1", ProviderKind.OpenAiCompatible, "Local", baseUrl = "http://localhost:1234/v1")
        val profile = AiProfile(providers = listOf(local), defaultModel = ModelRef("p1", "qwen3-8b"))
        val c = AiSettings.Config(provider = AiSettings.Provider.OpenRouter, apiKey = "", model = null, savedProfile = profile)
        assertFalse(c.hasKey(), "the old check said no")
        assertTrue(c.hasModelFor(AiFeature.CatchUp))
        assertTrue(c.hasModelFor(AiFeature.Assistant))
        // Nothing chosen is still nothing.
        assertFalse(c.copy(savedProfile = profile.copy(defaultModel = null)).hasModelFor(AiFeature.CatchUp))
    }

    // An install with no profile saved yet answers exactly as before, a
    // keyless server of its own aside: nothing else moves under anyone.
    @Test
    fun `with no profile saved the old answer stands`() {
        for (c in combos) {
            assertEquals(c.hasKey() || c.provider == AiSettings.Provider.Custom, c.hasModelFor(AiFeature.CatchUp), c.toString())
        }
    }

    // Taking out the provider a feature read messages with sent it to
    // the default model, which is usually a cloud one, with no word
    // from the owner. Triage goes to this device wherever it read;
    // catch-up and the assistant, which cannot run here, go off.
    @Test
    fun `removing a private provider keeps message reading off the cloud`() {
        val p = AiProfile(
            providers = listOf(
                AiProvider("or", ProviderKind.OpenRouter, "OpenRouter", apiKey = "sk-or"),
                AiProvider("an", ProviderKind.Anthropic, "Anthropic", apiKey = "sk-an"),
                AiProvider("lm", ProviderKind.OpenAiCompatible, "LM Studio", baseUrl = "http://127.0.0.1:1234/v1"),
                AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device"),
            ),
            defaultModel = ModelRef("or", "m"),
            features = mapOf(
                AiFeature.OrreryTriage to FeatureSetting(true, ModelRef("lm", "")),
                AiFeature.CatchUp to FeatureSetting(true, ModelRef("lm", "")),
                AiFeature.Assistant to FeatureSetting(true, ModelRef("an", "")),
            ),
        )
        val noLm = p.without("lm")
        assertEquals(ProviderKind.ThisDevice, noLm.resolve(AiFeature.OrreryTriage)?.provider?.kind)
        assertFalse(noLm.isOn(AiFeature.CatchUp), "it read on a model of the owner's own, and cannot run here: off, where the owner sees it")
        assertEquals("or", p.without("an").resolve(AiFeature.Assistant)?.provider?.id, "from one cloud to the default one, as before")
        val cloudTriage = p.copy(features = p.features + (AiFeature.OrreryTriage to FeatureSetting(true, ModelRef("an", ""))))
        assertEquals(ProviderKind.ThisDevice, cloudTriage.without("an").resolve(AiFeature.OrreryTriage)?.provider?.kind)
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
