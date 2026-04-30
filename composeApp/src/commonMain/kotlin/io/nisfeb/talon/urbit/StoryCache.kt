package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Process-wide cache of (messageId → rendered Story parts).
 * LazyColumn row recompositions are free after the first render.
 * The cache re-parses when a message's contentJson changes (e.g. after
 * an edit) — otherwise edits arrive in Room but never repaint.
 *
 * Bounded LRU (cap = [MAX_ENTRIES] per cache) so a long session over a
 * large archive doesn't accumulate every rendered message indefinitely.
 * Stale-detection is cheap: we hash the contentJson with [String.hashCode]
 * (effectively content-addressed for this purpose, since edits are rare
 * and a hash collision just causes a re-parse on the next render —
 * never an incorrect display).
 *
 * The hot apply path (PostIngest) calls [warm] with the JsonElement
 * it just built, so the first render skips the JSON re-parse. Only
 * messages we never warm (e.g. legacy rows loaded straight from the DB
 * without going through PostIngest) take the parse hit on first read.
 */
object StoryCache {
    private val json = Json { ignoreUnknownKeys = true }

    /** Per-cache cap. Each entry is roughly contentHash + List<StoryPart>;
     *  list contents are AnnotatedStrings so a few KB each on average. At
     *  4096 entries we're at single-digit MB. Tune up if a power user
     *  scrolls through hundreds of channels in one session. */
    private const val MAX_ENTRIES = 4096

    /** Synchronized LRU. LinkedHashMap with `accessOrder = true` evicts
     *  least-recently-touched on overflow; cheap, no extra deps. */
    private class Lru<V>(private val maxEntries: Int) :
        LinkedHashMap<String, V>(maxEntries, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, V>?): Boolean =
            size > maxEntries
    }

    private val parts: MutableMap<String, Pair<Int, List<StoryPart>>> = Lru(MAX_ENTRIES)
    private val previews: MutableMap<String, Pair<Int, String>> = Lru(MAX_ENTRIES)
    private val lock = Any()

    /**
     * Pre-warm the parts cache with an already-parsed [tree]. Called by
     * PostIngest the moment a new message arrives, so the first render
     * doesn't have to re-parse the JSON we just stringified for SQLite.
     * No-op when the cache already holds a current entry for [id].
     */
    fun warm(id: String, contentJson: String, tree: JsonElement) {
        val hash = contentJson.hashCode()
        synchronized(lock) {
            val existing = parts[id]
            if (existing != null && existing.first == hash) return
            val freshParts = runCatching { Story.parse(tree) }.getOrDefault(emptyList())
            parts[id] = hash to freshParts
            previews.remove(id)
        }
    }

    fun partsFor(id: String, contentJson: String): List<StoryPart> {
        val hash = contentJson.hashCode()
        synchronized(lock) {
            val cached = parts[id]
            if (cached != null && cached.first == hash) return cached.second
        }
        // Parse outside the lock — JSON parsing is the expensive part.
        val fresh = runCatching { Story.parse(json.parseToJsonElement(contentJson)) }
            .getOrDefault(emptyList())
        synchronized(lock) {
            parts[id] = hash to fresh
            previews.remove(id)
        }
        return fresh
    }

    fun textFor(id: String, contentJson: String): String {
        val hash = contentJson.hashCode()
        synchronized(lock) {
            val cached = previews[id]
            if (cached != null && cached.first == hash) return cached.second
        }
        val partsForId = partsFor(id, contentJson)
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
        synchronized(lock) {
            previews[id] = hash to text
        }
        return text
    }
}
