package io.nisfeb.talon.ai

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic AI settings repository. The Android implementation
 * is backed by EncryptedSharedPreferences (matches the production
 * app/AiSettings.kt one-for-one). The desktop implementation is
 * backed by a plain JSON file under ~/.config/talon — desktop has
 * different threat model than mobile (whole-disk encryption + the user
 * controls the device); explicit encrypted storage is a future Stage F
 * follow-up if anyone asks for it.
 */
interface AiSettingsRepository {
    val state: StateFlow<AiSettings.Config>

    /**
     * Optional callback fired after every mutation. The Boolean is
     * "transitioned off sync" — true when the mutation flipped
     * syncEnabled from true to false. Used by SettingsSync to push or
     * clear the on-ship config.
     */
    var onStateChange: ((AiSettings.Config, Boolean) -> Unit)?

    fun update(
        provider: AiSettings.Provider,
        apiKey: String,
        model: String?,
        baseUrl: String? = null,
    )

    fun setFeature(feature: AiSettings.Feature, enabled: Boolean)
    fun setSyncEnabled(enabled: Boolean)

    /** Set the Brave Search API credential (web_search tool). Separate from
     *  the LLM key set via [update]. */
    fun setBraveApiKey(key: String)

    /** Override one editable system-prompt part. Blank restores that part's
     *  built-in default. Synced like a preference, not a credential. */
    fun setPrompt(kind: AiSettings.PromptKind, value: String)

    /**
     * Apply a snapshot received from %settings. Bypasses onStateChange
     * so we don't pingpong back to the ship.
     */
    fun applyRemote(config: AiSettings.Config)

    fun clear()
}
