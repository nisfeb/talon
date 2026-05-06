package io.nisfeb.talon.ai

import io.nisfeb.talon.util.AppDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON-file-backed AI settings for desktop. Stored in the platform-
 * standard user-data dir (see [AppDirs]). No encryption — desktop's
 * threat model differs from mobile (whole-disk encryption + the user
 * controls the device); explicit encrypted storage is a future
 * Stage F follow-up if anyone asks for it.
 */
class DesktopAiSettings : AiSettingsRepository {

    private val file: File by lazy { File(AppDirs.userData, "ai_settings.json") }

    private val _state = MutableStateFlow(loadInitial())
    override val state: StateFlow<AiSettings.Config> = _state.asStateFlow()

    @Volatile
    override var onStateChange: ((AiSettings.Config, Boolean) -> Unit)? = null

    private fun loadInitial(): AiSettings.Config {
        if (!file.exists()) return defaultConfig()
        return runCatching { JSON.decodeFromString<AiSettings.Config>(file.readText()) }
            .getOrElse { defaultConfig() }
    }

    /**
     * Atomic write — temp file + ATOMIC_MOVE. A JVM crash mid-write
     * would otherwise leave a truncated config and the next launch
     * would fall back to defaults, silently dropping the user's
     * provider + API key.
     */
    private fun persist(cfg: AiSettings.Config) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(cfg))
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        _state.value = cfg
    }

    override fun update(
        provider: AiSettings.Provider,
        apiKey: String,
        model: String?,
        baseUrl: String?,
    ) {
        val cfg = _state.value.copy(
            provider = provider,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
        )
        persist(cfg)
        onStateChange?.invoke(cfg, false)
    }

    override fun setFeature(feature: AiSettings.Feature, enabled: Boolean) {
        val cur = _state.value
        val cfg = when (feature) {
            AiSettings.Feature.CatchMeUp -> cur.copy(catchMeUpEnabled = enabled)
            AiSettings.Feature.EmojiReact -> cur.copy(emojiReactEnabled = enabled)
            AiSettings.Feature.DailyDigest -> cur.copy(dailyDigestEnabled = enabled)
            AiSettings.Feature.EntityActions -> cur.copy(entityActionsEnabled = enabled)
            AiSettings.Feature.SemanticSearch -> cur.copy(semanticSearchEnabled = enabled)
            AiSettings.Feature.TopicClusters -> cur.copy(topicClustersEnabled = enabled)
            AiSettings.Feature.ImportantMessages -> cur.copy(importantMessagesEnabled = enabled)
        }
        persist(cfg)
        onStateChange?.invoke(cfg, false)
    }

    override fun setSyncEnabled(enabled: Boolean) {
        val cur = _state.value
        val transitionedOff = cur.syncEnabled && !enabled
        val cfg = cur.copy(syncEnabled = enabled)
        persist(cfg)
        onStateChange?.invoke(cfg, transitionedOff)
    }

    override fun applyRemote(config: AiSettings.Config) {
        // applyRemote is called from %settings sync; persist without
        // re-firing onStateChange so we don't pingpong back to the ship.
        file.writeText(JSON.encodeToString(config))
        _state.value = config
    }

    override fun clear() {
        val cfg = defaultConfig()
        persist(cfg)
        onStateChange?.invoke(cfg, false)
    }

    private fun defaultConfig(): AiSettings.Config = AiSettings.Config(
        provider = AiSettings.Provider.Anthropic,
        apiKey = "",
        model = null,
        baseUrl = null,
        // Defaults match AiSettings.Config — true across the board so
        // a fresh install starts with the full feature set enabled.
        // Capability flags hide unsupported features per-platform.
        catchMeUpEnabled = true,
        emojiReactEnabled = true,
        dailyDigestEnabled = true,
        entityActionsEnabled = true,
        semanticSearchEnabled = true,
        topicClustersEnabled = true,
        importantMessagesEnabled = true,
        syncEnabled = true,
    )

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
