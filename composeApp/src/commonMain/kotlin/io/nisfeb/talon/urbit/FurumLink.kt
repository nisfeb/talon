package io.nisfeb.talon.urbit

/**
 * A furum board, or one post on it. Furum names a board by its host and
 * name, unique per host only, so every form of a link carries the host.
 */
data class FurumRef(val host: String, val board: String, val post: Long? = null) {
    private val path get() = "$host/$board" + (post?.let { "/$it" } ?: "")

    /** The shorthand, `f/~host/board[/42]`: what the viewer's header shows. */
    val shorthand: String get() = "f/$path"

    /**
     * Furum's own page for it on [shipUrl], the reader's ship. Its furum
     * reads the board from the host over Ames and keeps it, as lattice
     * does an urb:// address.
     */
    fun pageUrl(shipUrl: String): String = "${shipUrl.trimEnd('/')}/apps/furum/b/$path"

    /** What the reader's furum knows of it, as JSON, without following the board. */
    fun previewUrl(shipUrl: String): String =
        "${shipUrl.trimEnd('/')}/apps/furum/preview?board=$host/$board" + (post?.let { "&post=$it" } ?: "")
}

/**
 * Finds furum in message text, in either form:
 *
 *  - `https://<any ship>/apps/furum/b/~host/board[/42]`: furum's page on
 *    whichever ship the sharer reads it through;
 *  - `f/~host/board[/42]`: the shorthand, after reddit's r/name. Not
 *    `~host/board`, which is how a Tlon group is written.
 *
 * Either opens on the reader's own ship ([FurumRef.pageUrl]), not the
 * sharer's, which would refuse the reader's cookie.
 */
object FurumLink {
    /** Loose on purpose, as UrbLink is: furum itself checks the @p. */
    private const val SHIP = "~[a-z]+(?:-[a-z]+)*"

    /** A @tas: no trailing hyphen, so `f/~zod/cats-` stops at cats. */
    private const val BOARD = "[a-z](?:[a-z0-9-]*[a-z0-9])?"

    // Only a board or a post: /submit, /mod and /edit are not things to show.
    private val PAGE = Regex("""^https?://[^/\s]+/apps/furum/b/($SHIP)/($BOARD)(?:/(\d+))?/?(?:[?#]\S*)?$""")

    // Not inside a word, a path or a URL; not running on into one either.
    private val SHORT = Regex("""(?<![\w/~.-])f/($SHIP)/($BOARD)(?:/(\d+))?(?![\w/])""")

    /** The board or post [s] is, whole: a furum page's URL or the shorthand. */
    fun parse(s: String): FurumRef? {
        val t = s.trim()
        val m = PAGE.matchEntire(t) ?: SHORT.matchEntire(t) ?: return null
        val (host, board, post) = m.destructured
        return FurumRef(host, board, post.toLongOrNull())
    }

    /** Where a shorthand starting at [i] in [text] ends (exclusive), or null where none starts there. */
    fun shorthandAt(text: String, i: Int): Int? =
        SHORT.matchAt(text, i)?.let { it.range.last + 1 }

    /** Every shorthand in [text], as inclusive ranges, left to right. */
    fun findRanges(text: String): List<IntRange> = SHORT.findAll(text).map { it.range }.toList()
}
