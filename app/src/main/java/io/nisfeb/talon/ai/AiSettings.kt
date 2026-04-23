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
    }

    data class Config(
        val provider: Provider,
        val apiKey: String,
        // Optional model override. Each provider has a sensible default.
        val model: String?,
        // Per-feature toggles. Default on once a key is configured — if
        // the user set up AI, they probably want the features. Each can
        // be flipped off individually in Settings.
        val catchMeUpEnabled: Boolean = true,
        val emojiReactEnabled: Boolean = true,
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

    fun update(provider: Provider, apiKey: String, model: String?) {
        prefs.edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODEL, model?.takeIf { it.isNotBlank() })
            .apply()
        _state.value = _state.value.copy(
            provider = provider,
            apiKey = apiKey,
            model = model?.takeIf { it.isNotBlank() },
        )
        onStateChange?.invoke(_state.value, false)
    }

    fun setFeature(feature: Feature, enabled: Boolean) {
        prefs.edit().putBoolean(feature.key, enabled).apply()
        _state.value = when (feature) {
            Feature.CatchMeUp -> _state.value.copy(catchMeUpEnabled = enabled)
            Feature.EmojiReact -> _state.value.copy(emojiReactEnabled = enabled)
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
        catchMeUpEnabled: Boolean,
        emojiReactEnabled: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODEL, model?.takeIf { it.isNotBlank() })
            .putBoolean(Feature.CatchMeUp.key, catchMeUpEnabled)
            .putBoolean(Feature.EmojiReact.key, emojiReactEnabled)
            .putBoolean(KEY_SYNC, true)
            .apply()
        _state.value = Config(
            provider = provider,
            apiKey = apiKey,
            model = model?.takeIf { it.isNotBlank() },
            catchMeUpEnabled = catchMeUpEnabled,
            emojiReactEnabled = emojiReactEnabled,
            syncEnabled = true,
        )
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_PROVIDER)
            .remove(KEY_API_KEY)
            .remove(KEY_MODEL)
            .remove(Feature.CatchMeUp.key)
            .remove(Feature.EmojiReact.key)
            .remove(KEY_SYNC)
            .apply()
        _state.value = Config(Provider.Anthropic, "", null)
        onStateChange?.invoke(_state.value, false)
    }

    private fun read(): Config {
        val provider = prefs.getString(KEY_PROVIDER, null)
            ?.let { runCatching { Provider.valueOf(it) }.getOrNull() }
            ?: Provider.Anthropic
        val key = prefs.getString(KEY_API_KEY, "").orEmpty()
        val model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        return Config(
            provider = provider,
            apiKey = key,
            model = model,
            catchMeUpEnabled = prefs.getBoolean(Feature.CatchMeUp.key, true),
            emojiReactEnabled = prefs.getBoolean(Feature.EmojiReact.key, true),
            syncEnabled = prefs.getBoolean(KEY_SYNC, false),
        )
    }

    enum class Feature(val key: String, val label: String, val description: String) {
        CatchMeUp(
            "feat_catch_me_up",
            "Catch me up",
            "When you open a chat with unread messages, offer a summary.",
        ),
        EmojiReact(
            "feat_emoji_react",
            "AI emoji react",
            "Long-press a message → AI emoji picks a reaction for you.",
        ),
    }

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SYNC = "sync_enabled"
    }
}
