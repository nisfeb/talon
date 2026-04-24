package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide cache of (messageId → rendered Story parts).
 * LazyColumn row recompositions are free after the first render.
 * The cache re-parses when a message's contentJson changes (e.g. after
 * an edit) — otherwise edits arrive in Room but never repaint.
 */
object StoryCache {
    private val json = Json { ignoreUnknownKeys = true }
    private val parts = ConcurrentHashMap<String, Pair<String, List<StoryPart>>>()
    private val previews = ConcurrentHashMap<String, Pair<String, String>>()

    fun partsFor(id: String, contentJson: String): List<StoryPart> {
        val cached = parts[id]
        if (cached != null && cached.first == contentJson) return cached.second
        val fresh = runCatching { Story.parse(json.parseToJsonElement(contentJson)) }
            .getOrDefault(emptyList())
        parts[id] = contentJson to fresh
        // Edits change the text preview too — drop any stale entry.
        previews.remove(id)
        return fresh
    }

    fun textFor(id: String, contentJson: String): String {
        val cached = previews[id]
        if (cached != null && cached.first == contentJson) return cached.second
        val partsForId = parts[id]?.takeIf { it.first == contentJson }?.second
            ?: runCatching { Story.parse(json.parseToJsonElement(contentJson)) }
                .getOrDefault(emptyList())
                .also { parts[id] = contentJson to it }
        val text = partsForId.joinToString("\n") { part ->
            when (part) {
                is StoryPart.Text -> part.text.text
                is StoryPart.Image -> part.alt?.takeIf { it.isNotBlank() } ?: "[image]"
                is StoryPart.Code -> "```\n${part.code}\n```"
                is StoryPart.LinkPreview -> part.title ?: part.url
                is StoryPart.Citation -> part.label
                is StoryPart.TzWidget -> "[tz]"
                is StoryPart.CalWidget -> "📅 ${part.title}"
                is StoryPart.PollWidget -> "📊 ${part.question}"
                is StoryPart.LocWidget -> "📍 ${part.lat}, ${part.lng}"
            }
        }.trim()
        previews[id] = contentJson to text
        return text
    }
}
