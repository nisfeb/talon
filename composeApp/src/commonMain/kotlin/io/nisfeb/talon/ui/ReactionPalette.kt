package io.nisfeb.talon.ui

/**
 * Minimal v1 reaction palette. Tlon's wire format is a shortcode like
 * ":+1:" or a URL-escaped form like "%2B1" — we send and store the
 * shortcode and map to a display character only at render time.
 */
object ReactionPalette {

    /** Ordered list shown in the reaction picker. */
    val picker: List<Pair<String, String>> = listOf(
        ":+1:" to "👍",
        ":heart:" to "❤️",
        ":laughing:" to "😂",
        ":fire:" to "🔥",
        ":thinking:" to "🤔",
        ":eyes:" to "👀",
        ":tada:" to "🎉",
        ":-1:" to "👎",
    )

    private val table: Map<String, String> = buildMap {
        picker.forEach { (code, emoji) -> put(code, emoji) }
        // A few shortcode aliases Tlon emits in the wild.
        put(":thumbsup:", "👍")
        put(":thumbsdown:", "👎")
        put(":joy:", "😂")
        put(":heart_eyes:", "😍")
        put(":clap:", "👏")
        put(":100:", "💯")
        put(":pray:", "🙏")
        put(":party:", "🥳")
    }

    /**
     * Best-effort emoji for a raw TlonReact string. If the code isn't
     * known, return it verbatim so we don't swallow unknown reactions.
     */
    fun display(raw: String): String =
        table[raw] ?: EmojiCatalog.glyphFor(raw) ?: raw
}
