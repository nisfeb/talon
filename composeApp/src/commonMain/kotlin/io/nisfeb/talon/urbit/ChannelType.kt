package io.nisfeb.talon.urbit

/**
 * Which kind of channel a nest represents. Derived from the nest
 * prefix — `chat/~host/slug` → Chat, `diary/~host/slug` → Bulletin,
 * `heap/~host/slug` → Gallery, `notes/~host/slug` → Notebook. Direct
 * messages (`~peer` or `0v…`) are also treated as Chat.
 *
 * Naming follows Tlon webapp v12: the blog-like WYSIWYG channel that
 * used to be called "Notebook" is now "Bulletin" (slated for eventual
 * deprecation), while "Notebook" now means the Markdown-powered,
 * folder-organized channel type. [Bulletin] stays on %channels like
 * Chat/Gallery; [Notebook] does not — it's served by the separate
 * %notes agent with its own scry/subscribe surface.
 */
enum class ChannelType {
    Chat,
    Bulletin,
    Gallery,
    Notebook;

    companion object {
        fun fromWhom(whom: String): ChannelType {
            // DMs and clubs have no slash / don't start with an app prefix.
            val prefix = whom.substringBefore('/', missingDelimiterValue = "")
            return when (prefix) {
                "diary" -> Bulletin
                "heap" -> Gallery
                "notes" -> Notebook
                else -> Chat
            }
        }

        /** Underlying agent/essay-kind string for this channel type. */
        fun agentKind(type: ChannelType): String = when (type) {
            Chat -> "/chat"
            Bulletin -> "/diary"
            Gallery -> "/heap"
            Notebook -> "/notes"
        }
    }
}
