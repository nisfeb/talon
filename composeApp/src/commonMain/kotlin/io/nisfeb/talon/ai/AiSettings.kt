package io.nisfeb.talon.ai

/**
 * Portable data types for AI provider configuration.
 *
 * The Android implementation (backed by EncryptedSharedPreferences) lives in
 * app/src/main/java/io/nisfeb/talon/ai/AiSettings.kt. Desktop and other
 * targets will provide their own storage in Stage C.
 *
 * This file lives in commonMain so that AiClient (also commonMain) can
 * reference Config and Provider without pulling in Android APIs.
 */
object AiSettings {

    enum class Provider(val label: String) {
        Anthropic("Anthropic (Claude)"),
        OpenRouter("OpenRouter"),
        OpenAi("OpenAI"),
        Custom("Custom (OpenAI endpoint)"),
    }

    data class Config(
        val provider: Provider,
        val apiKey: String,
        val model: String?,
        val baseUrl: String? = null,
        val catchMeUpEnabled: Boolean = true,
        val emojiReactEnabled: Boolean = true,
        val dailyDigestEnabled: Boolean = true,
        val entityActionsEnabled: Boolean = false,
        val semanticSearchEnabled: Boolean = false,
        val topicClustersEnabled: Boolean = false,
        val importantMessagesEnabled: Boolean = false,
        val syncEnabled: Boolean = false,
    ) {
        fun hasKey(): Boolean = apiKey.isNotBlank()
    }
}
