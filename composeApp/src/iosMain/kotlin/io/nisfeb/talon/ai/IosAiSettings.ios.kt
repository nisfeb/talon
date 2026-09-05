package io.nisfeb.talon.ai

import io.nisfeb.talon.util.IosFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import platform.Foundation.NSUUID

/** iOS assistant-settings persistence — the whole [AiSettings.Config]
 *  as JSON under Documents, with a stable device id minted on first
 *  load. No legacy-key migration (iOS has no prior schema). */
fun createAiSettings(): AiSettingsRepository = IosAiSettings()

private const val AI_FILE = "ai_settings.json"

class IosAiSettings : AiSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(loadOrDefault())
    override val state: StateFlow<AiSettings.Config> = _state.asStateFlow()
    override var onStateChange: ((AiSettings.Config, Boolean) -> Unit)? = null

    private fun loadOrDefault(): AiSettings.Config {
        val loaded = IosFiles.read(AI_FILE)?.let {
            runCatching { json.decodeFromString<AiSettings.Config>(it) }.getOrNull()
        }
        val cfg = loaded ?: AiSettings.Config(
            provider = AiSettings.Provider.Anthropic,
            apiKey = "",
            model = null,
        )
        return if (cfg.deviceId.isBlank()) {
            cfg.copy(deviceId = NSUUID().UUIDString).also { persist(it) }
        } else {
            cfg
        }
    }

    private fun persist(cfg: AiSettings.Config) {
        IosFiles.write(AI_FILE, json.encodeToString(AiSettings.Config.serializer(), cfg))
    }

    private fun commit(new: AiSettings.Config, fireChange: Boolean) {
        val old = _state.value
        _state.value = new
        persist(new)
        if (fireChange) {
            val transitionedOff = old.syncEnabled && !new.syncEnabled
            onStateChange?.invoke(new, transitionedOff)
        }
    }

    override fun update(
        provider: AiSettings.Provider,
        apiKey: String,
        model: String?,
        baseUrl: String?,
    ) {
        commit(
            _state.value.copy(provider = provider, apiKey = apiKey, model = model, baseUrl = baseUrl),
            fireChange = true,
        )
    }

    override fun setFeature(feature: AiSettings.Feature, enabled: Boolean) {
        val cfg = _state.value
        val new = when (feature) {
            AiSettings.Feature.CatchMeUp -> cfg.copy(catchMeUpEnabled = enabled)
            AiSettings.Feature.DailyDigest -> cfg.copy(dailyDigestEnabled = enabled)
            AiSettings.Feature.SmartFeatures -> cfg.copy(smartFeaturesEnabled = enabled)
            AiSettings.Feature.Agent -> cfg.copy(agentEnabled = enabled)
        }
        commit(new, fireChange = true)
    }

    override fun setSyncEnabled(enabled: Boolean) {
        commit(_state.value.copy(syncEnabled = enabled), fireChange = true)
    }

    override fun setBraveApiKey(key: String) {
        commit(_state.value.copy(braveApiKey = key), fireChange = true)
    }

    override fun setSttApiKey(key: String) {
        commit(_state.value.copy(sttApiKey = key), fireChange = true)
    }

    override fun setPrompt(kind: AiSettings.PromptKind, value: String) {
        commit(_state.value.withPrompt(kind, value), fireChange = true)
    }

    override fun applyRemote(config: AiSettings.Config) {
        // Remote config shouldn't clobber our stable device id, and this
        // path never re-fires onStateChange (mirrors desktop).
        val merged =
            if (config.deviceId.isBlank()) config.copy(deviceId = _state.value.deviceId) else config
        _state.value = merged
        persist(merged)
    }

    override fun clear() {
        commit(
            AiSettings.Config(
                provider = AiSettings.Provider.Anthropic,
                apiKey = "",
                model = null,
                deviceId = _state.value.deviceId,
            ),
            fireChange = false,
        )
    }
}
