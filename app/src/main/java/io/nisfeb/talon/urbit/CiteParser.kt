package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure classifier for Tlon's `cite` blocks. Returns a lightweight
 * [CiteParse] that [Story.renderCite] turns into a [StoryPart.Citation]
 * or [StoryPart.LinkPreview]. Separated from Story.kt so tests can
 * exercise the schema translation without pulling in the Android /
 * Compose runtime that Story.Text's AnnotatedString demands.
 */

/** Where the cite should open when tapped, if anywhere. */
internal sealed interface CiteTarget {
    /** Channel-scoped cite — a specific post or reply in `<nest>`. */
    data class ChannelPost(
        val nest: String,
        val postDa: String,
        val replyDa: String?,
    ) : CiteTarget

    /** Group-scoped cite — a reference to a group flag. */
    data class Group(val flag: String) : CiteTarget

    /** A URL we'd like to open in an external browser. */
    data class Url(val url: String, val title: String?) : CiteTarget
}

/** Structured result of cite classification. */
internal data class CiteParse(
    val label: String,
    val target: CiteTarget?,
    /** For URL-bearing file/bait cites, optional pre-computed size text. */
    val sizeText: String? = null,
)

internal fun parseCite(cite: JsonObject): CiteParse {
    // ChanCite: {chan: {nest, where}}
    (cite["chan"] as? JsonObject)?.let { chan ->
        val nest = chan["nest"]?.jsonPrimitive?.contentIfStr()
        val where = chan["where"]?.jsonPrimitive?.contentIfStr().orEmpty()
        val parsed = parseChanCiteWhere(where)
        val label = if (nest != null) {
            "Post in #" + nest.substringAfterLast('/')
        } else {
            "Channel post"
        }
        return CiteParse(
            label = label,
            target = if (nest != null && parsed != null) {
                CiteTarget.ChannelPost(
                    nest = nest,
                    postDa = parsed.first,
                    replyDa = parsed.second,
                )
            } else null,
        )
    }

    // GroupCite: spec shape is `{group: "<flag>"}` per tlon-apps, but
    // we've also seen `{group: {flag: "…"}}` wrapped variants. Accept
    // both.
    (cite["group"] as? JsonPrimitive)?.contentIfStr()?.let { flag ->
        return CiteParse(
            label = "Group $flag",
            target = CiteTarget.Group(flag),
        )
    }
    (cite["group"] as? JsonObject)?.let { g ->
        val flag = g["flag"]?.jsonPrimitive?.contentIfStr()
            ?: g["id"]?.jsonPrimitive?.contentIfStr()
        if (flag != null) {
            return CiteParse(
                label = "Group $flag",
                target = CiteTarget.Group(flag),
            )
        }
    }

    // DeskCite: {desk: {flag, where}}
    (cite["desk"] as? JsonObject)?.let { desk ->
        val flag = desk["flag"]?.jsonPrimitive?.contentIfStr()
        return CiteParse(
            label = if (flag != null) "App $flag" else "App reference",
            target = null,
        )
    }

    // File/bait/upload/url cite variants — either inner object with a
    // url field, or a top-level url on the cite itself.
    for (key in listOf("file", "bait", "upload", "url")) {
        val inner = cite[key] as? JsonObject ?: continue
        val url = inner["url"]?.jsonPrimitive?.contentIfStr()
            ?: inner["href"]?.jsonPrimitive?.contentIfStr()
            ?: inner["src"]?.jsonPrimitive?.contentIfStr()
        val name = inner["name"]?.jsonPrimitive?.contentIfStr()
            ?: inner["title"]?.jsonPrimitive?.contentIfStr()
            ?: url?.substringAfterLast('/')?.substringBefore('?')
        val sizeText = inner["size"]?.jsonPrimitive?.longOrNull
            ?.let { humanFileSize(it) }
        if (url != null || name != null) {
            val label = buildString {
                append(name ?: "File upload")
                if (sizeText != null) append(" • ").append(sizeText)
            }
            return CiteParse(
                label = label,
                target = url?.let { CiteTarget.Url(it, name) },
                sizeText = sizeText,
            )
        }
    }

    val directUrl = cite["url"]?.jsonPrimitive?.contentIfStr()
        ?: cite["href"]?.jsonPrimitive?.contentIfStr()
    if (directUrl != null) {
        val title = cite["title"]?.jsonPrimitive?.contentIfStr()
            ?: directUrl.substringAfterLast('/').substringBefore('?')
        return CiteParse(
            label = title,
            target = CiteTarget.Url(directUrl, title),
        )
    }

    return CiteParse(
        label = "Reference",
        target = null,
    )
}

private fun humanFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

private fun JsonPrimitive.contentIfStr(): String? =
    if (isString) content else null
