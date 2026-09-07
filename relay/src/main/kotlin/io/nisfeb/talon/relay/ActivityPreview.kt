package io.nisfeb.talon.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A one-line text preview of a post's content, for a system-rendered
 * alert. %activity hands the relay the same story the client renders;
 * this keeps the words and the ship names and drops the rest.
 */
object ActivityPreview {
    private const val MAX = 140

    fun of(event: JsonObject): String? {
        for (kind in arrayOf("dm-post", "chan-post", "club-post")) {
            val content = event[kind]?.jsonObject?.get("content") ?: continue
            val sb = StringBuilder()
            walk(content, sb)
            val text = sb.toString().replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return null
            return if (text.length > MAX) text.take(MAX - 1).trimEnd() + "…" else text
        }
        return null
    }

    private fun walk(el: JsonElement, out: StringBuilder) {
        if (out.length > MAX * 2) return
        when (el) {
            is JsonPrimitive -> if (el.isString) out.append(el.content)
            is JsonArray -> el.forEach { walk(it, out) }
            is JsonObject -> for ((k, v) in el) {
                when (k) {
                    "ship" -> out.append((v as? JsonPrimitive)?.content.orEmpty())
                    "break" -> out.append(' ')
                    "image" -> out.append("[image] ")
                    "code" -> out.append((v as? JsonObject)?.get("code")?.let { (it as? JsonPrimitive)?.content }.orEmpty())
                    "link" -> {
                        val o = v as? JsonObject
                        out.append((o?.get("content") ?: o?.get("href"))?.let { (it as? JsonPrimitive)?.content }.orEmpty())
                    }
                    "sect" -> out.append("@").append((v as? JsonPrimitive)?.content ?: "all")
                    "cite" -> out.append("[quote] ")
                    else -> walk(v, out)
                }
            }
        }
    }
}
