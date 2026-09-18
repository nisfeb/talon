package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.MessageMediaEntity

/**
 * Walks a parsed [MessageEntity]'s story and emits one
 * [MessageMediaEntity] per categorisable URL or first-class image.
 *
 * Rules apply in order; first match wins. Extension matching is
 * case-insensitive and ignores `?query` and `#fragment`.
 */
object MediaClassifier {

    private val IMAGE_EXTS = setOf(".jpg", ".jpeg", ".png", ".webp")
    private val VIDEO_EXTS = setOf(".mp4", ".webm", ".mov", ".m4v")
    private val AUDIO_EXTS = setOf(".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac")
    private val FILE_EXTS = setOf(
        ".pdf", ".zip", ".doc", ".docx", ".xls", ".xlsx",
        ".ppt", ".pptx", ".txt", ".csv", ".tar", ".gz", ".7z", ".rar",
    )
    private const val VOICE_PREFIX = "🎙"

    fun extractMedia(message: MessageEntity): List<MessageMediaEntity> {
        if (message.isDeleted) return emptyList()
        val story = parseStoryOrNull(message.contentJson) ?: return emptyList()
        val rows = mutableListOf<MessageMediaEntity>()
        val seenUrls = mutableSetOf<String>()
        for (part in story) {
            collect(part, message, rows, seenUrls)
        }
        return rows
    }

    private fun collect(
        part: StoryPart,
        message: MessageEntity,
        out: MutableList<MessageMediaEntity>,
        seen: MutableSet<String>,
    ) {
        when (part) {
            is StoryPart.Image -> {
                val src = part.src
                if (src in seen) return
                seen += src
                out += row(message, src, displayText = part.alt, category = categoryForImage(src))
            }
            is StoryPart.Text -> {
                val anns = part.text.getStringAnnotations(URL_TAG, 0, part.text.length)
                for (a in anns) {
                    val url = a.item
                    if (url in seen) continue
                    seen += url
                    val displayText = part.text.substring(a.start, a.end).takeIf { it != url }
                    out += row(message, url, displayText, categoryForUrl(url, displayText))
                }
            }
            is StoryPart.LinkPreview -> {
                val url = part.url
                if (url in seen) return
                seen += url
                out += row(message, url, part.title, categoryForUrl(url, part.title))
            }
            else -> Unit  // Code, Citation, widgets — never produce media rows
        }
    }

    /**
     * An image block is not always an image. Tlon sends a video as an
     * image block with the `.mp4` in its `src`, so the extension
     * decides and the block only says "here is a file". Calling one of
     * those a Photo filed every shared video under photos and left the
     * Videos filter empty.
     */
    private fun categoryForImage(url: String): String = when (canonicalExt(url)) {
        ".gif" -> MediaCategory.Gif.name
        in VIDEO_EXTS -> MediaCategory.Video.name
        in AUDIO_EXTS -> MediaCategory.Audio.name
        else -> MediaCategory.Photo.name
    }

    private fun categoryForUrl(url: String, displayText: String?): String {
        val ext = canonicalExt(url)
        return when {
            ext == ".gif" -> MediaCategory.Gif.name
            ext in IMAGE_EXTS -> MediaCategory.Photo.name
            ext in VIDEO_EXTS -> MediaCategory.Video.name
            ext in AUDIO_EXTS && displayText?.startsWith(VOICE_PREFIX) == true ->
                MediaCategory.Voice.name
            ext in AUDIO_EXTS -> MediaCategory.Audio.name
            ext in FILE_EXTS -> MediaCategory.File.name
            else -> MediaCategory.Link.name
        }
    }

    /**
     * Lowercase the URL and chop the query string + fragment, then
     * return the last `.foo` substring. Returns "" if none.
     */
    private fun canonicalExt(url: String): String {
        val cleaned = url.lowercase().substringBefore('?').substringBefore('#')
        val dot = cleaned.lastIndexOf('.')
        if (dot < 0) return ""
        val slash = cleaned.lastIndexOf('/')
        if (slash > dot) return ""  // dot is in the path, not the filename
        return cleaned.substring(dot)
    }

    private fun row(
        message: MessageEntity,
        url: String,
        displayText: String?,
        category: String,
    ) = MessageMediaEntity(
        whom = message.whom,
        messageId = message.id,
        url = url,
        category = category,
        displayText = displayText,
        sentMs = message.sentMs,
        author = message.author,
    )

    /**
     * Parse the message's contentJson into a list of StoryPart, or
     * null if it's malformed / empty. Wraps `Story.parse(JsonElement?)`
     * with safe-parse for the raw JSON string.
     */
    private fun parseStoryOrNull(contentJson: String): List<StoryPart>? {
        val element = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(contentJson)
        }.getOrNull() ?: return null
        return Story.parse(element).takeIf { it.isNotEmpty() }
    }
}
