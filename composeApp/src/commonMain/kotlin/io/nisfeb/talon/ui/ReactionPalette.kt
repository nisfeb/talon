package io.nisfeb.talon.ui

/**
 * Minimal v1 reaction palette. Tlon migrated reactions from shortcodes
 * to unicode glyphs in 2025, so the wire format is now a unicode
 * string like "👍". TlonChatRepo.react() normalizes shortcodes to
 * glyphs before sending and before the optimistic local upsert; the
 * picker map below is also used as a fallback display() table for any
 * legacy shortcode rows that remain in the local DB.
 *
 * Picker codes use Tlon's current shortcode vocabulary (:thumbsup: /
 * :thumbsdown:). The deprecated GitHub-style :+1: / :-1: are no longer
 * produced anywhere — they survive only as display aliases below so
 * historical rows that stored them still render as glyphs.
 */
object ReactionPalette {

    /** Ordered list shown in the reaction picker. */
    val picker: List<Pair<String, String>> = listOf(
        ":thumbsup:" to "👍",
        ":heart:" to "❤️",
        ":laughing:" to "😂",
        ":fire:" to "🔥",
        ":thinking:" to "🤔",
        ":eyes:" to "👀",
        ":tada:" to "🎉",
        ":thumbsdown:" to "👎",
    )

    private val table: Map<String, String> = buildMap {
        picker.forEach { (code, emoji) -> put(code, emoji) }
        // Legacy GitHub-style codes Tlon dropped in favor of
        // :thumbsup: / :thumbsdown: — kept so historical rows (or any
        // old client still emitting them) still render as glyphs.
        put(":+1:", "👍")
        put(":-1:", "👎")
        // A few more shortcode aliases Tlon emits in the wild.
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

    /**
     * Canonical grouping key for a reaction. Resolves any shortcode to
     * its glyph, then strips variation selectors (U+FE00–U+FE0F) so the
     * text-style "❤" (U+2764) and emoji-style "❤️" (U+2764 U+FE0F) — and
     * a legacy ":heart:" — all collapse to one reaction. Display always
     * uses EmojiFontFamily, which renders the bare codepoint in color
     * either way, so the stripped form is safe to show.
     *
     * Apply at the DB storage boundary (TlonChatRepo.react + the inbound
     * add-react handlers) and when de-duping the picker's suggestion row,
     * so `groupBy { it.emoji }` and the usage table unify the forms
     * without every call site having to know about variation selectors.
     */
    fun normalize(raw: String): String =
        display(raw).filterNot { it.code in 0xFE00..0xFE0F }
}
