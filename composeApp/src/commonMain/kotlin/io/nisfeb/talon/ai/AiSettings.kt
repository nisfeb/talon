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
        // All feature toggles default to true so a fresh install
        // starts with the full feature set on. Capability flags still
        // hide what a platform can't run (e.g. EntityActions on
        // desktop). Users who explicitly disable a feature keep that
        // choice across upgrades — applyRemote / setFeature persist
        // the explicit value, so the new defaults only apply when
        // the SharedPreferences key is absent.
        val catchMeUpEnabled: Boolean = true,
        val emojiReactEnabled: Boolean = true,
        val dailyDigestEnabled: Boolean = true,
        val entityActionsEnabled: Boolean = true,
        val semanticSearchEnabled: Boolean = true,
        val topicClustersEnabled: Boolean = true,
        val importantMessagesEnabled: Boolean = true,
        // Opt-in, unlike the others: the assistant is rolled out behind
        // rc releases, so it defaults OFF and stays invisible until the
        // user both configures a key and turns it on.
        val askUrbitEnabled: Boolean = false,
        // Phase 2 agentic mode — same opt-in, off-by-default treatment;
        // additionally every write it proposes is user-confirmed.
        val agentEnabled: Boolean = false,
        // Lets the agent reach the ship's MCP server (scry-agent + gated
        // poke-agent). Opt-in, off by default; the most dangerous MCP
        // tools (eval/install) stay hidden regardless. Only meaningful
        // when the agent is also on.
        val mcpEnabled: Boolean = false,
        val syncEnabled: Boolean = true,
    ) {
        fun hasKey(): Boolean = apiKey.isNotBlank()
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
        EmojiReact(
            "feat_emoji_react",
            "AI emoji react",
            "Long-press a message → AI emoji picks a reaction for you.",
            requiresCloudKey = true,
        ),
        DailyDigest(
            "feat_daily_digest",
            "AI digest summary",
            "Add an AI-written summary to the daily digest. The digest itself is enabled separately under \"Daily digest.\"",
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
        // One unified assistant (was Ask + Act). It answers questions
        // grounded in your real messages AND takes actions; anything that
        // changes data is confirmed first. Keeps key "feat_agent" so
        // existing agent-enabled installs carry over; setFeature/read
        // keep the legacy askUrbit flag in lockstep for migration.
        Agent(
            "feat_agent",
            "Assistant (beta)",
            "A chat assistant grounded in your real messages. Ask about your history or tell it to do things — search, send, reply, react, mark read. Anything that changes data is shown for your confirmation first.",
            requiresCloudKey = true,
        ),
        Mcp(
            "feat_mcp",
            "Reach the ship's tools (MCP, beta)",
            "If your ship runs an MCP server, let the assistant scry any agent to answer questions and (with your per-action confirmation) poke agents. Arbitrary-code tools stay off.",
            requiresCloudKey = true,
        ),
    }
}
