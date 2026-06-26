package io.nisfeb.talon.ai

import kotlinx.serialization.Serializable

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

    @Serializable
    data class Config(
        val provider: Provider,
        val apiKey: String,
        val model: String?,
        val baseUrl: String? = null,
        // Feature toggles default to true so a fresh install starts with
        // the full feature set on. Capability flags still hide what a
        // platform can't run. Users who explicitly disable a feature keep
        // that choice across upgrades — applyRemote / setFeature persist
        // the explicit value, so the new defaults only apply when the
        // SharedPreferences key is absent.
        val catchMeUpEnabled: Boolean = true,
        val dailyDigestEnabled: Boolean = true,
        // One switch for the on-device embedder features: search-by-meaning,
        // topic clustering, and bookmark-similarity highlighting. They all
        // share the same on-device index, so they're enabled together.
        val smartFeaturesEnabled: Boolean = true,
        // Opt-in, unlike the others: the assistant is rolled out behind
        // rc releases, so it defaults OFF and stays invisible until the
        // user both configures a key and turns it on. The assistant
        // subsumes MCP (ship tools) and web access — there are no separate
        // toggles; both are active whenever the assistant is on (writes
        // and pokes are still confirmed; dangerous MCP tools stay hidden).
        val askUrbitEnabled: Boolean = false,
        val agentEnabled: Boolean = false,
        val syncEnabled: Boolean = true,
        // Brave Search API credential for the assistant's web search.
        // Optional (the assistant can open URLs without it). Travels with
        // the same syncEnabled gate as the LLM key (see SettingsSyncImpl).
        val braveApiKey: String = "",
    ) {
        fun hasKey(): Boolean = apiKey.isNotBlank()

        /** The unified assistant is on (current flag or the legacy one).
         *  Gates MCP + web access, which are now part of the assistant. */
        fun assistantOn(): Boolean = agentEnabled || askUrbitEnabled
    }

    /**
     * Per-feature toggles. SettingsScreen iterates this enum to render
     * the AI features section. Field-for-field copy of the production
     * enum at app/src/main/java/io/nisfeb/talon/ai/AiSettings.kt.
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
        DailyDigest(
            "feat_daily_digest",
            "AI digest summary",
            "Add an AI-written summary to the daily digest. The digest itself is enabled separately under \"Daily digest.\"",
            requiresCloudKey = true,
        ),
        // One toggle for the on-device embedder suite — search by meaning,
        // topic clusters, and important-message highlighting. They share
        // the same on-device index, so they live and die together.
        SmartFeatures(
            "feat_smart_features",
            "Smart features (on-device)",
            "Search your chats by meaning, group a chat's messages by topic, and highlight incoming messages similar to ones you've bookmarked. Runs entirely on-device.",
            requiresCloudKey = false,
        ),
        // One unified assistant (was Ask + Act). It answers questions
        // grounded in your real messages AND takes actions; anything that
        // changes data is confirmed first. Keeps key "feat_agent" so
        // existing agent-enabled installs carry over; setFeature/read
        // keep the legacy askUrbit flag in lockstep for migration. The
        // assistant subsumes MCP and web access — no separate toggles.
        Agent(
            "feat_agent",
            "Assistant (beta)",
            "A chat assistant grounded in your real messages. Ask about your history or tell it to do things — search, send, reply, react, mark read. It can also reach your ship's MCP tools and the public web. Anything that changes data is shown for your confirmation first.",
            requiresCloudKey = true,
        ),
    }
}
