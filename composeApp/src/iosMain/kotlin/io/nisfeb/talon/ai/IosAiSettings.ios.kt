package io.nisfeb.talon.ai

import io.nisfeb.talon.util.IosFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import platform.Foundation.NSUUID

/** iOS assistant-settings persistence — the whole [AiSettings.Config]
 *  as JSON under Application Support, with a stable device id minted on first
 *  load. No legacy-key migration (iOS has no prior schema). */
fun createAiSettings(): AiSettingsRepository = IosAiSettings()

private const val AI_FILE = "ai_settings.json"

class IosAiSettings : AiSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Whether the file was read. It is written with complete
     * protection, so it cannot be read while the device is locked, and
     * this app can be started locked by a call. A failed read used to
     * become defaults, and minting a device id then wrote those
     * defaults straight over the keys. Nothing is written until a read
     * has succeeded or the file has been found to be absent.
     *
     * Declared before the state it guards: Kotlin runs initialisers in
     * order, and below the state this was set back to false the moment
     * after the read set it, so nothing was ever written again.
     */
    private var readTheFile = false
    private val _state = MutableStateFlow(loadOrDefault())
    override val state: StateFlow<AiSettings.Config> = _state.asStateFlow()
    override var onStateChange: ((AiSettings.Config, Boolean) -> Unit)? = null

    private fun loadOrDefault(): AiSettings.Config {
        val raw = IosFiles.read(AI_FILE)
        val loaded = raw?.let {
            runCatching { json.decodeFromString<AiSettings.Config>(it) }.getOrNull()
        }
        // Absent is a fresh install and safe to write. Present but
        // unreadable, or present and unparseable, is not.
        readTheFile = loaded != null || !IosFiles.exists(AI_FILE)
        val cfg = loaded ?: AiSettings.Config(
            provider = AiSettings.Provider.Anthropic,
            apiKey = "",
            model = null,
        )
        return if (cfg.deviceId.isBlank() && readTheFile) {
            cfg.copy(deviceId = NSUUID().UUIDString).also { persist(it) }
        } else {
            cfg
        }
    }

    private fun persist(cfg: AiSettings.Config) {
        // Never write over a file we could not read: that is how the
        // keys went, permanently, with no copy anywhere.
        if (!readTheFile) {
            io.nisfeb.talon.util.Log.w("IosAiSettings", "not writing over a config that could not be read")
            return
        }
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
            AiSettings.Feature.SmartFeatures -> cfg.copy(smartFeaturesEnabled = enabled)
            AiSettings.Feature.Agent -> cfg.copy(agentEnabled = enabled)
        }
        commit(new, fireChange = true)
    }

    override fun setSyncEnabled(enabled: Boolean) {
        commit(_state.value.copy(syncEnabled = enabled), fireChange = true)
    }

    override fun setBraveApiKey(key: String) {
        commit(_state.value.withBraveKey(key, io.nisfeb.talon.util.nowMs()), fireChange = true)
    }

    override fun setSttApiKey(key: String) {
        commit(
            io.nisfeb.talon.util.nowMs().let { now ->
                _state.value.copy(
                    sttApiKey = key,
                    sttApiKeyRemovedAtMs = if (key.isBlank()) now else 0L,
                    sttApiKeySetAtMs = if (key.isBlank()) _state.value.sttApiKeySetAtMs else now,
                )
            },
            fireChange = true,
        )
    }

    override fun setPrivateModel(baseUrl: String?, model: String?, apiKey: String) {
        commit(
            _state.value.copy(
                privateBaseUrl = baseUrl?.trim()?.takeIf { it.isNotEmpty() },
                privateModel = model?.trim()?.takeIf { it.isNotEmpty() },
                privateApiKey = apiKey.trim(),
            ),
            fireChange = true,
        )
    }

    override fun setFrontierReadsMessages(on: Boolean) {
        commit(_state.value.copy(frontierReadsMessages = on), fireChange = true)
    }

    override fun setPrompt(kind: AiSettings.PromptKind, value: String) {
        commit(_state.value.withPrompt(kind, value), fireChange = true)
    }

    override fun setProfile(profile: AiProfile) {
        commit(_state.value.withProfile(profile, io.nisfeb.talon.util.nowMs()), fireChange = true)
    }

    override fun applyRemote(config: AiSettings.Config) {
        // Remote config shouldn't clobber our stable device id, and this
        // path never re-fires onStateChange (mirrors desktop).
        // A credential this device holds is never dropped by arriving
        // state. See keepingCredentials: the rule lives here so that no
        // future caller can lose a key by accident.
        val merged = (if (config.deviceId.isBlank()) config.copy(deviceId = _state.value.deviceId) else config)
            .keepingCredentials(_state.value)
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
