package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide cache of (messageId → rendered Story parts).
 * LazyColumn row recompositions are free after the first render.
 */
object StoryCache {
    private val json = Json { ignoreUnknownKeys = true }
    private val parts = ConcurrentHashMap<String, List<StoryPart>>()
    private val previews = ConcurrentHashMap<String, String>()

    fun partsFor(id: String, contentJson: String): List<StoryPart> =
        parts.getOrPut(id) {
            runCatching { Story.parse(json.parseToJsonElement(contentJson)) }
                .getOrDefault(emptyList())
        }

    fun textFor(id: String, contentJson: String): String =
        previews.getOrPut(id) {
            // Walk the cached parts when available; otherwise parse fresh.
            val cached = parts[id]
            if (cached != null) {
                cached.joinToString("\n") { part ->
                    when (part) {
                        is StoryPart.Text -> part.text.text
                        is StoryPart.Image -> part.alt?.takeIf { it.isNotBlank() } ?: "[image]"
                        is StoryPart.Code -> "```\n${part.code}\n```"
                        is StoryPart.LinkPreview -> part.title ?: part.url
                        is StoryPart.Citation -> part.label
                    }
                }.trim()
            } else {
                runCatching { Story.plainText(json.parseToJsonElement(contentJson)) }
                    .getOrDefault("")
            }
        }
}
