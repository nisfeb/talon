package io.nisfeb.talon.orrery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One message in, claims out, through whatever model the ladder gave.
 *
 * The prompt shows the model the bodies the ship knows and asks for
 * JSON in one shape; the grammar makes that shape the only thing a
 * llama.cpp rung can say, and the parse holds every rung to it: an
 * attr in orrery's charset, a subject the ship has or the author
 * themselves, a ref only to a listed body, conf capped at 80. What
 * fails is dropped, never guessed at.
 */
object ModelExtractor {
    const val MAX_TOKENS = 256
    const val MAX_CONF = 80
    private const val HOUR_MS = 60L * 60 * 1000

    /** JSON, and only the answer's shape of it, in llama.cpp's GBNF. */
    val GRAMMAR: String = """
        root ::= "{" ws "\"claims\"" ws ":" ws "[" ws (claim (ws "," ws claim)*)? ws "]" ws "}"
        claim ::= "{" ws "\"subject\"" ws ":" ws string ws "," ws "\"attr\"" ws ":" ws string ws "," ws "\"value\"" ws ":" ws value ws "," ws "\"conf\"" ws ":" ws number ws ("," ws "\"until_hours\"" ws ":" ws number ws)? "}"
        value ::= string | "null" | "{" ws "\"ref\"" ws ":" ws string ws "}"
        string ::= "\"" ([^"\\] | "\\" (["\\/bfnrt] | "u" hex hex hex hex))* "\""
        hex ::= [0-9a-fA-F]
        number ::= "-"? [0-9]+ ("." [0-9]+)?
        ws ::= [ \t\n]*
    """.trimIndent()

    val SYSTEM: String = """
        You read one chat message and write down what it states is true now about the listed bodies. Answer with JSON only, in this shape: {"claims":[{"subject":"<id>","attr":"<word>","value":<value>,"conf":<0-100>,"until_hours":<number, optional>}]}
        subject is copied exactly from the listed ids, or is the author's id. Only the author and the bodies the message names can be subjects. attr is one short lowercase word: status, location, phone, email. value is a short string; or {"ref":"<listed id>"} when it names a listed body; or null when something has stopped being true. conf is how sure you are. until_hours is how long a temporary claim holds, such as being somewhere. At most three claims.
        Claim only what the message states as fact about now. A question, a joke, a wish, a plan, or the past is nothing: {"claims":[]}.

        Example. Author: ~bus (person/bus). Message: lol did you see the game last night
        {"claims":[]}

        Example. Author: ~bus (person/bus). Message: my new number is 555-0142
        {"claims":[{"subject":"person/bus","attr":"phone","value":"555-0142","conf":90}]}
    """.trimIndent()

    fun user(bodies: List<KnownBody>, author: String, authorId: String, atIso: String, text: String): String = buildString {
        append("Bodies:\n")
        for (b in bodies.take(60)) {
            append("- ").append(b.id)
            b.name?.let { append(": ").append(it) }
            val extra = (b.aliases + listOfNotNull(b.ship)).filter { it != b.name }.distinct()
            if (extra.isNotEmpty()) append(" (").append(extra.take(6).joinToString(", ")).append(")")
            append('\n')
        }
        append("Author: ").append(author).append(" (").append(authorId).append(")\n")
        append("When: ").append(atIso).append('\n')
        append("Message: ").append(text.take(1200)).append('\n')
    }

    /** Ask [model] about one message. Empty on any failure to answer in shape. */
    suspend fun extract(model: LocalModel, index: NameIndex, bodies: List<KnownBody>, text: String, author: String, atMs: Long, ourShip: String, attrs: Map<String, List<String>> = emptyMap()): List<Noticed> {
        if (text.isBlank() || text.trimEnd().endsWith("?")) return emptyList()
        val authorId = index.authorId(author, ourShip)
        val answer = runCatching { model.complete(SYSTEM, user(bodies, author, authorId, isoUtc(atMs), text), GRAMMAR, MAX_TOKENS) }
            .getOrElse { io.nisfeb.talon.util.Log.w("ModelExtractor", "${model.rung} did not answer: ${it.message}", it); return emptyList() }
        // Only the author and what the message names may be claimed about: a
        // small model otherwise writes what it remembers, not what it read.
        val mentioned = index.find(text).map { it.first.id }.toSet() + authorId + (if (author == ourShip) setOf("person/me") else emptySet())
        return parse(answer, index, author, atMs, ourShip, mentioned, attrs, text)
    }

    /** The claims in [answer] that pass, each with the body a stranger's claim needs. */
    fun parse(
        answer: String,
        index: NameIndex,
        author: String,
        atMs: Long,
        ourShip: String,
        /** Subjects a claim may have; null for any the ship knows. */
        mentioned: Set<String>? = null,
        /** The ship's attrs per kind; a kind not listed accepts any attr. */
        attrs: Map<String, List<String>> = emptyMap(),
        /** The message, when a value must be found in its words to stand. */
        text: String? = null,
    ): List<Noticed> {
        val authorId = index.authorId(author, ourShip)
        val root = runCatching { Json.parseToJsonElement(answer.trim()).jsonObject }.getOrNull() ?: return emptyList()
        val claims = root["claims"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: return emptyList()
        val out = mutableListOf<Noticed>()
        for (c in claims) {
            val o = c as? JsonObject ?: continue
            val said = o["subject"]?.jsonPrimitive?.content?.trim() ?: continue
            // A small model writes the ship or the name as often as the id.
            val subject = when {
                said == authorId || said == "person/me" || index.has(said) -> said
                said == author -> authorId
                else -> index.resolveExact(said)?.id ?: continue
            }
            if (mentioned != null && subject !in mentioned) continue
            val attr = o["attr"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: continue
            if (!ATTR.matches(attr)) continue
            // A medical fact is `health` and a money fact is `income`,
            // both of which the policy keeps from keys, so this client
            // cannot write either. Under any other name it would write
            // them in plain sight of every key, so they are dropped.
            if (attr in SENSITIVE || attr in SENSITIVE_BY_ANOTHER_NAME) continue
            val known = attrs[subject.substringBefore('/')]
            if (known != null && attr !in known && attr !in ALWAYS) continue
            val value: JsonElement = when (val v = o["value"]) {
                null -> continue
                is JsonNull -> JsonNull
                is JsonPrimitive -> {
                    if (!v.isString || v.content.isBlank() || v.content.length > 500) continue
                    val str = v.content.trim()
                    // A name written as a string is the body it names.
                    val named = index.resolveExact(str)?.takeIf { it.id != subject }
                    if (named != null) buildJsonObject { put("ref", named.id) } else JsonPrimitive(str)
                }
                is JsonObject -> {
                    val said = v["ref"]?.jsonPrimitive?.content ?: continue
                    val ref = if (index.has(said)) said else index.resolveExact(said)?.id ?: continue
                    if (ref == subject) continue
                    buildJsonObject { put("ref", ref) }
                }
                else -> continue
            }
            // A value has to come from the words, or it came from the
            // model's memory: the string itself, or the named body's name
            // or an alias, must be in the message. A status is the one
            // attr whose whole point is a paraphrase.
            if (text != null && attr != "status" && !grounded(value, text, index)) continue
            val conf = (o["conf"]?.jsonPrimitive?.doubleOrNull ?: 50.0).toInt().coerceIn(0, MAX_CONF)
            if (conf < 30) continue
            val until = o["until_hours"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 && it <= 24 * 30 }?.let { atMs + (it * HOUR_MS).toLong() }
            val body = if (subject == authorId && subject != "person/me" && !index.has(subject)) OBody(subject, aliases = listOf(author)) else null
            out += Noticed(subject, attr, value, atMs, until, conf, body)
        }
        return out.distinctBy { it.subject to it.attr }
    }

    private fun grounded(value: JsonElement, text: String, index: NameIndex): Boolean = when (value) {
        is JsonNull -> true
        is JsonPrimitive -> text.contains(value.content, ignoreCase = true)
        is JsonObject -> {
            val id = value["ref"]?.jsonPrimitive?.content
            val found = index.find(text).map { it.first.id }
            id != null && id in found
        }
        else -> false
    }

    private val ATTR = Regex("[a-z0-9]([a-z0-9-]{0,46}[a-z0-9])?")
    /** Attrs any kind may carry whatever the schema lists. */
    private val ALWAYS = setOf("status", "location")

    /** The two names the ship keeps from keys (orrery's starter policy). */
    private val SENSITIVE = setOf("health", "income")

    /** What a model reaches for when it means one of those two. */
    private val SENSITIVE_BY_ANOTHER_NAME = setOf(
        "medical", "diagnosis", "illness", "sickness", "symptom", "symptoms", "medication",
        "meds", "prescription", "treatment", "therapy", "surgery", "condition",
        "salary", "pay", "wage", "wages", "earnings", "debt", "savings", "net-worth", "networth",
    )
}
