package io.nisfeb.talon.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores the user's AI-provider configuration. Uses
 * EncryptedSharedPreferences so the API key doesn't sit in plaintext
 * on disk; the master key is backed by the Android Keystore.
 *
 * AI features throughout the app check [isConfigured] and stay hidden
 * when it's false so there's no clutter for users who haven't opted in.
 */
class AiSettings(context: Context) {

    enum class Provider(val label: String) {
        Anthropic("Anthropic (Claude)"),
        OpenRouter("OpenRouter"),
        OpenAi("OpenAI"),
        // Custom OpenAI-compatible endpoint — user supplies baseUrl.
        // Works with anything that speaks OpenAI's /chat/completions
        // schema (local llama.cpp, LiteLLM, vLLM, etc).
        Custom("Custom (OpenAI endpoint)"),
    }

    data class Config(
        val provider: Provider,
        val apiKey: String,
        // Optional model override. Each provider has a sensible default.
        val model: String?,
        // Only used when provider = Custom. User-supplied OpenAI-
        // compatible base URL (e.g. "https://api.example.com/v1").
        val baseUrl: String? = null,
        // Cloud-AI features — default on once a key is configured (if
        // the user set up cloud AI, they probably want them). Each
        // can be flipped off individually.
        val catchMeUpEnabled: Boolean = true,
        val emojiReactEnabled: Boolean = true,
        // On-device-AI features — all default OFF so users opt in
        // explicitly. None require an API key. Toggles + state mirror
        // to %settings when syncEnabled is true.
        val entityActionsEnabled: Boolean = false,
        val semanticSearchEnabled: Boolean = false,
        val topicClustersEnabled: Boolean = false,
        val importantMessagesEnabled: Boolean = false,
        // Opt-in: mirror provider/model/key/toggles into %settings so
        // they follow across devices. Off by default because it stores
        // the API key on the ship.
        val syncEnabled: Boolean = false,
    ) {
        fun hasKey(): Boolean = apiKey.isNotBlank()
    }

    /**
     * Hook wired by TalonApplication after SettingsSync exists. AiSettings
     * calls this on any state change; the wiring pushes to %settings when
     * syncEnabled is true or clears the ship's bucket when it transitions
     * off. Stays null until wired so that bootstrap reads don't re-trigger
     * pokes back at the ship.
     */
    @Volatile var onStateChange: ((Config, transitionedOffSync: Boolean) -> Unit)? = null

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
    val state: StateFlow<Config> = _state.asStateFlow()

    val isConfigured: Boolean get() = _state.value.hasKey()

    fun update(provider: Provider, apiKey: String, model: String?, baseUrl: String? = null) {
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

    fun setFeature(feature: Feature, enabled: Boolean) {
        prefs.edit().putBoolean(feature.key, enabled).apply()
        _state.value = when (feature) {
            Feature.CatchMeUp -> _state.value.copy(catchMeUpEnabled = enabled)
            Feature.EmojiReact -> _state.value.copy(emojiReactEnabled = enabled)
            Feature.EntityActions -> _state.value.copy(entityActionsEnabled = enabled)
            Feature.SemanticSearch -> _state.value.copy(semanticSearchEnabled = enabled)
            Feature.TopicClusters -> _state.value.copy(topicClustersEnabled = enabled)
            Feature.ImportantMessages -> _state.value.copy(importantMessagesEnabled = enabled)
        }
        onStateChange?.invoke(_state.value, false)
    }

    fun setSyncEnabled(enabled: Boolean) {
        val wasEnabled = _state.value.syncEnabled
        if (wasEnabled == enabled) return
        prefs.edit().putBoolean(KEY_SYNC, enabled).apply()
        _state.value = _state.value.copy(syncEnabled = enabled)
        onStateChange?.invoke(_state.value, wasEnabled && !enabled)
    }

    /**
     * Apply a snapshot received from %settings. Bypasses the
     * onStateChange callback so we don't pingpong back to the ship.
     */
    fun applyRemote(
        provider: Provider,
        apiKey: String,
        model: String?,
        baseUrl: String?,
        catchMeUpEnabled: Boolean,
        emojiReactEnabled: Boolean,
        entityActionsEnabled: Boolean,
        semanticSearchEnabled: Boolean,
        topicClustersEnabled: Boolean,
        importantMessagesEnabled: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODEL, model?.takeIf { it.isNotBlank() })
            .putString(KEY_BASE_URL, baseUrl?.takeIf { it.isNotBlank() })
            .putBoolean(Feature.CatchMeUp.key, catchMeUpEnabled)
            .putBoolean(Feature.EmojiReact.key, emojiReactEnabled)
            .putBoolean(Feature.EntityActions.key, entityActionsEnabled)
            .putBoolean(Feature.SemanticSearch.key, semanticSearchEnabled)
            .putBoolean(Feature.TopicClusters.key, topicClustersEnabled)
            .putBoolean(Feature.ImportantMessages.key, importantMessagesEnabled)
            .putBoolean(KEY_SYNC, true)
            .apply()
        _state.value = Config(
            provider = provider,
            apiKey = apiKey,
            model = model?.takeIf { it.isNotBlank() },
            baseUrl = baseUrl?.takeIf { it.isNotBlank() },
            catchMeUpEnabled = catchMeUpEnabled,
            emojiReactEnabled = emojiReactEnabled,
            entityActionsEnabled = entityActionsEnabled,
            semanticSearchEnabled = semanticSearchEnabled,
            topicClustersEnabled = topicClustersEnabled,
            importantMessagesEnabled = importantMessagesEnabled,
            syncEnabled = true,
        )
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_PROVIDER)
            .remove(KEY_API_KEY)
            .remove(KEY_MODEL)
            .remove(KEY_BASE_URL)
            .remove(Feature.CatchMeUp.key)
            .remove(Feature.EmojiReact.key)
            .remove(KEY_SYNC)
            .apply()
        _state.value = Config(Provider.Anthropic, "", null)
        onStateChange?.invoke(_state.value, false)
    }

    private fun read(): Config {
        val savedName = prefs.getString(KEY_PROVIDER, null)
        val provider = savedName
            ?.let {
                runCatching { Provider.valueOf(it) }
                    .onFailure { e ->
                        // Don't silently mis-route the user's API key to
                        // a different provider. Log it loud so any
                        // future R8/keep-rule regression is noticed.
                        android.util.Log.w(
                            "AiSettings",
                            "couldn't resolve saved provider '$it'; " +
                                "falling back to Anthropic. ${e.message}",
                        )
                    }
                    .getOrNull()
            }
            ?: Provider.Anthropic
        val key = prefs.getString(KEY_API_KEY, "").orEmpty()
        val model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
        return Config(
            provider = provider,
            apiKey = key,
            model = model,
            baseUrl = baseUrl,
            catchMeUpEnabled = prefs.getBoolean(Feature.CatchMeUp.key, true),
            emojiReactEnabled = prefs.getBoolean(Feature.EmojiReact.key, true),
            entityActionsEnabled = prefs.getBoolean(Feature.EntityActions.key, false),
            semanticSearchEnabled = prefs.getBoolean(Feature.SemanticSearch.key, false),
            topicClustersEnabled = prefs.getBoolean(Feature.TopicClusters.key, false),
            importantMessagesEnabled = prefs.getBoolean(Feature.ImportantMessages.key, false),
            syncEnabled = prefs.getBoolean(KEY_SYNC, false),
        )
    }

    /**
     * `requiresCloudKey` distinguishes features that hit a configured
     * AI provider (need an API key) from on-device features that
     * stand alone. The Settings UI groups them so on-device toggles
     * show even when no key is configured.
     */
    enum class Feature(
        val key: String,
        val label: String,
        val description: String,
        val requiresCloudKey: Boolean,
    ) {
        CatchMeUp(
            "feat_catch_me_up",
            "Catch me up",
            "When you open a chat with unread messages, offer a summary.",
            requiresCloudKey = true,
        ),
        EmojiReact(
            "feat_emoji_react",
            "AI emoji react",
            "Long-press a message → AI emoji picks a reaction for you.",
            requiresCloudKey = true,
        ),
        EntityActions(
            "feat_entity_actions",
            "Action chips on messages",
            "Detect dates, addresses, phone numbers, and email addresses in chat messages and surface tap-through chips. On-device.",
            requiresCloudKey = false,
        ),
        SemanticSearch(
            "feat_semantic_search",
            "Smart search",
            "Search messages by meaning, not just keywords. Embeds your local chat history on-device for the index.",
            requiresCloudKey = false,
        ),
        TopicClusters(
            "feat_topic_clusters",
            "Topic clusters per chat",
            "Group a chat's messages by topic so you can scan what's been discussed. On-device.",
            requiresCloudKey = false,
        ),
        ImportantMessages(
            "feat_important_messages",
            "Highlight important messages",
            "Flag incoming messages that look similar to ones you've bookmarked. On-device, needs at least 5 bookmarks.",
            requiresCloudKey = false,
        ),
    }

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SYNC = "sync_enabled"
    }
}
