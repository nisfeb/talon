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
            AiSettings.Feature.EmojiReact -> _state.value.copy(emojiReactEnabled = enabled)
            AiSettings.Feature.DailyDigest -> _state.value.copy(dailyDigestEnabled = enabled)
            AiSettings.Feature.EntityActions -> _state.value.copy(entityActionsEnabled = enabled)
            AiSettings.Feature.SemanticSearch -> _state.value.copy(semanticSearchEnabled = enabled)
            AiSettings.Feature.TopicClusters -> _state.value.copy(topicClustersEnabled = enabled)
            AiSettings.Feature.ImportantMessages -> _state.value.copy(importantMessagesEnabled = enabled)
        }
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
            .putBoolean(AiSettings.Feature.EmojiReact.key, config.emojiReactEnabled)
            .putBoolean(AiSettings.Feature.DailyDigest.key, config.dailyDigestEnabled)
            .putBoolean(AiSettings.Feature.EntityActions.key, config.entityActionsEnabled)
            .putBoolean(AiSettings.Feature.SemanticSearch.key, config.semanticSearchEnabled)
            .putBoolean(AiSettings.Feature.TopicClusters.key, config.topicClustersEnabled)
            .putBoolean(AiSettings.Feature.ImportantMessages.key, config.importantMessagesEnabled)
            .putBoolean(KEY_SYNC, config.syncEnabled)
            .apply()
        _state.value = config
    }

    override fun clear() {
        prefs.edit()
            .remove(KEY_PROVIDER)
            .remove(KEY_API_KEY)
            .remove(KEY_MODEL)
            .remove(KEY_BASE_URL)
            .remove(AiSettings.Feature.CatchMeUp.key)
            .remove(AiSettings.Feature.EmojiReact.key)
            .remove(AiSettings.Feature.DailyDigest.key)
            .remove(KEY_SYNC)
            .apply()
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
        return AiSettings.Config(
            provider = provider,
            apiKey = key,
            model = model,
            baseUrl = baseUrl,
            catchMeUpEnabled = prefs.getBoolean(AiSettings.Feature.CatchMeUp.key, true),
            emojiReactEnabled = prefs.getBoolean(AiSettings.Feature.EmojiReact.key, true),
            dailyDigestEnabled = prefs.getBoolean(AiSettings.Feature.DailyDigest.key, true),
            entityActionsEnabled = prefs.getBoolean(AiSettings.Feature.EntityActions.key, false),
            semanticSearchEnabled = prefs.getBoolean(AiSettings.Feature.SemanticSearch.key, false),
            topicClustersEnabled = prefs.getBoolean(AiSettings.Feature.TopicClusters.key, false),
            importantMessagesEnabled = prefs.getBoolean(AiSettings.Feature.ImportantMessages.key, false),
            syncEnabled = prefs.getBoolean(KEY_SYNC, false),
        )
    }

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SYNC = "sync_enabled"
    }
}
