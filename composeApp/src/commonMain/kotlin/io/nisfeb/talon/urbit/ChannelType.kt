package io.nisfeb.talon.urbit

/**
 * Which kind of channel a nest represents. Derived from the nest
 * prefix — `chat/~host/slug` → Chat, `diary/~host/slug` → Notebook,
 * `heap/~host/slug` → Gallery. Direct messages (`~peer` or `0v…`) are
 * also treated as Chat.
 */
enum class ChannelType {
    Chat,
    Notebook,
    Gallery;

    companion object {
        fun fromWhom(whom: String): ChannelType {
            // DMs and clubs have no slash / don't start with an app prefix.
            val prefix = whom.substringBefore('/', missingDelimiterValue = "")
            return when (prefix) {
                "diary" -> Notebook
                "heap" -> Gallery
                else -> Chat
            }
        }

        /** Underlying agent/essay-kind string for this channel type. */
        fun agentKind(type: ChannelType): String = when (type) {
            Chat -> "/chat"
            Notebook -> "/diary"
            Gallery -> "/heap"
        }
    }
}
