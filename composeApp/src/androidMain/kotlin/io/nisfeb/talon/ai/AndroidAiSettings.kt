package io.nisfeb.talon.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [AiSettingsRepository], backed by
 * [EncryptedSharedPreferences] (master key in the Android Keystore).
 *
 * The data classes ([AiSettings.Config], [AiSettings.Provider],
 * [AiSettings.Feature]) live in commonMain's `object AiSettings`;
 * this class only owns the state-management surface.
 */
class AndroidAiSettings(context: Context) : AiSettingsRepository {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "talon_ai",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _state = MutableStateFlow(read())
    override val state: StateFlow<AiSettings.Config> = _state.asStateFlow()

    @Volatile
    override var onStateChange: ((AiSettings.Config, Boolean) -> Unit)? = null

    override fun update(
        provider: AiSettings.Provider,
        apiKey: String,
        model: String?,
        baseUrl: String?,
    ) {
        prefs.edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODEL, model?.takeIf { it.isNotBlank() })
            .putString(KEY_BASE_URL, baseUrl?.takeIf { it.isNotBlank() })
            .apply()
        _state.value = _state.value.copy(
            provider = provider,
            apiKey = apiKey,
            model = model?.takeIf { it.isNotBlank() },
            baseUrl = baseUrl?.takeIf { it.isNotBlank() },
        )
        onStateChange?.invoke(_state.value, false)
    }

    override fun setFeature(feature: AiSettings.Feature, enabled: Boolean) {
        prefs.edit().putBoolean(feature.key, enabled).apply()
        _state.value = when (feature) {
            AiSettings.Feature.CatchMeUp -> _state.value.copy(catchMeUpEnabled = enabled)
            AiSettings.Feature.DailyDigest -> _state.value.copy(dailyDigestEnabled = enabled)
            AiSettings.Feature.SmartFeatures -> _state.value.copy(smartFeaturesEnabled = enabled)
            // Unified assistant — keep the legacy askUrbit flag mirrored.
            AiSettings.Feature.Agent ->
                _state.value.copy(agentEnabled = enabled, askUrbitEnabled = enabled)
        }
        onStateChange?.invoke(_state.value, false)
    }

    override fun setBraveApiKey(key: String) {
        prefs.edit().putString(KEY_BRAVE_API_KEY, key).apply()
        _state.value = _state.value.copy(braveApiKey = key)
        onStateChange?.invoke(_state.value, false)
    }

    override fun setSystemPrompt(prompt: String) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply()
        _state.value = _state.value.copy(systemPrompt = prompt)
        onStateChange?.invoke(_state.value, false)
    }

    override fun setSyncEnabled(enabled: Boolean) {
        val wasEnabled = _state.value.syncEnabled
        if (wasEnabled == enabled) return
        prefs.edit().putBoolean(KEY_SYNC, enabled).apply()
        _state.value = _state.value.copy(syncEnabled = enabled)
        onStateChange?.invoke(_state.value, wasEnabled && !enabled)
    }

    override fun applyRemote(config: AiSettings.Config) {
        prefs.edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model?.takeIf { it.isNotBlank() })
            .putString(KEY_BASE_URL, config.baseUrl?.takeIf { it.isNotBlank() })
            .putBoolean(AiSettings.Feature.CatchMeUp.key, config.catchMeUpEnabled)
            .putBoolean(AiSettings.Feature.DailyDigest.key, config.dailyDigestEnabled)
            .putBoolean(AiSettings.Feature.SmartFeatures.key, config.smartFeaturesEnabled)
            .putBoolean(AiSettings.Feature.Agent.key, config.agentEnabled)
            .putBoolean(LEGACY_ASK_URBIT_KEY, config.agentEnabled)
            .putString(KEY_BRAVE_API_KEY, config.braveApiKey)
            .putString(KEY_SYSTEM_PROMPT, config.systemPrompt)
            .putBoolean(KEY_SYNC, config.syncEnabled)
            .apply()
        _state.value = config
    }

    override fun clear() {
        val editor = prefs.edit()
            .remove(KEY_PROVIDER)
            .remove(KEY_API_KEY)
            .remove(KEY_MODEL)
            .remove(KEY_BASE_URL)
            .remove(KEY_BRAVE_API_KEY)
            .remove(KEY_SYSTEM_PROMPT)
            .remove(KEY_SYNC)
        // Remove every feature toggle — not a hand-picked few — so an
        // enabled feature (especially the opt-in AskUrbit/Agent) can't
        // survive sign-out and resurrect as enabled on the next launch.
        AiSettings.Feature.values().forEach { editor.remove(it.key) }
        editor.remove(LEGACY_ASK_URBIT_KEY) // no longer in Feature.values()
        editor.apply()
        _state.value = AiSettings.Config(AiSettings.Provider.Anthropic, "", null)
        onStateChange?.invoke(_state.value, false)
    }

    private fun read(): AiSettings.Config {
        val savedName = prefs.getString(KEY_PROVIDER, null)
        val provider = savedName
            ?.let { runCatching { AiSettings.Provider.valueOf(it) }.getOrNull() }
            ?: AiSettings.Provider.Anthropic
        val key = prefs.getString(KEY_API_KEY, "").orEmpty()
        val model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
        // Unified assistant: either the current flag or the legacy
        // "Ask your Urbit" flag means enabled (migration).
        val assistantOn = prefs.getBoolean(AiSettings.Feature.Agent.key, false) ||
            prefs.getBoolean(LEGACY_ASK_URBIT_KEY, false)
        return AiSettings.Config(
            provider = provider,
            apiKey = key,
            model = model,
            baseUrl = baseUrl,
            // Defaults match AiSettings.Config — true across the
            // board so a fresh install starts with everything on.
            // Explicit-off survives because SharedPreferences only
            // returns the default when the key is absent.
            catchMeUpEnabled = prefs.getBoolean(AiSettings.Feature.CatchMeUp.key, true),
            dailyDigestEnabled = prefs.getBoolean(AiSettings.Feature.DailyDigest.key, true),
            smartFeaturesEnabled = prefs.getBoolean(AiSettings.Feature.SmartFeatures.key, true),
            askUrbitEnabled = assistantOn,
            agentEnabled = assistantOn,
            syncEnabled = prefs.getBoolean(KEY_SYNC, true),
            braveApiKey = prefs.getString(KEY_BRAVE_API_KEY, "").orEmpty(),
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "").orEmpty(),
        )
    }

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_BRAVE_API_KEY = "brave_api_key"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_SYNC = "sync_enabled"
        // Legacy "Ask your Urbit" key, folded into the unified assistant
        // (feat_agent). Read for migration + written in lockstep.
        private const val LEGACY_ASK_URBIT_KEY = "feat_ask_urbit"
    }
}
