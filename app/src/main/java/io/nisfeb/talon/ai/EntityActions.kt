package io.nisfeb.talon.ai

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.entityextraction.DateTimeEntity
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityAnnotation
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractor
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * On-device detection of actionable entities in message text — dates,
 * addresses, phone numbers, email addresses. Powers the small action
 * chips shown under each message.
 *
 * Uses ML Kit's Entity Extraction (English model). The model is small
 * (~12MB), downloads on first use, and runs entirely on-device — no
 * message text leaves the phone for entity detection.
 */
object EntityActions {

    private val extractor: EntityExtractor by lazy {
        EntityExtraction.getClient(
            EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()
        )
    }

    /** Stable per-text cache so the same message doesn't re-annotate. */
    private val cache = ConcurrentHashMap<String, List<DetectedAction>>()

    /**
     * Trigger the model download AND a throwaway annotate so the
     * TF-Lite runtime, XNNPACK delegate, and annotator model are all
     * fully initialized by the time the first chat opens. Without this
     * second pass, downloadModelIfNeeded only landed the file and the
     * native init happened lazily on the first real annotate — a
     * ~half-second hitch hitting the user precisely when they expected
     * the chat to paint smoothly. Best-effort; any failure leaves the
     * library in its previous state, so chips just appear later.
     */
    fun warmup(): Task<Void> = extractor.downloadModelIfNeeded()
        .addOnSuccessListener { runCatching { extractor.annotate(WARMUP_TEXT) } }
        // Return value still surfaces just the download outcome to
        // callers — the annotate is fire-and-forget.

    private const val WARMUP_TEXT = "warmup 123-456-7890 at 3pm"

    /**
     * Annotate [text] and return the actionable entities Talon
     * surfaces as chips. Returns immediately on cache hit; otherwise
     * suspends on the on-device annotator.
     *
     * Empty / very-short input short-circuits to an empty list — there's
     * nothing actionable in "ok" or "lol". Same for text with no digit
     * and no `@`: every kind we surface (date, time, phone, email,
     * street address) requires one or the other, so running ML Kit on
     * pure prose is wasted CPU plus an avoidable GC tail.
     *
     * The post-await processing runs on Dispatchers.Default so neither
     * the URL regex pass nor the cache write lands on the main thread
     * — important on chats where many rows resolve their annotations
     * back-to-back during a fling-scroll.
     */
    suspend fun forText(text: String): List<DetectedAction> {
        if (text.length < 4) return emptyList()
        cache[text]?.let { return it }
        if (text.none { it.isDigit() } && '@' !in text) {
            cache[text] = emptyList()
            return emptyList()
        }

        return withContext(Dispatchers.Default) {
            val annotations = runCatching { extractor.annotate(text).await() }
                .getOrElse {
                    cache[text] = emptyList()
                    return@withContext emptyList()
                }

            val urlRanges = URL_REGEX.findAll(text).map { it.range }.toList()
            val filtered = annotations.filterNot { ann ->
                // Drop the entity if its start index sits inside any
                // detected URL substring (covers `https://.../12345.jpg`
                // matching as a phone, etc).
                urlRanges.any { ann.start in it }
            }
            val actions = filtered.flatMap { it.toActions(text) }.distinct()
            cache[text] = actions
            actions
        }
    }

    /**
     * ML Kit happily matches arbitrary digit sequences as phone
     * numbers and entire URLs as email-or-similar. Dropping any
     * annotation whose start index falls inside an http(s) URL kills
     * the false positives without losing real entities.
     */
    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    private fun EntityAnnotation.toActions(fullText: String): List<DetectedAction> {
        val span = fullText.substring(start, end).trim()
        if (span.isBlank()) return emptyList()
        return entities.mapNotNull { entity ->
            when (entity.type) {
                Entity.TYPE_DATE_TIME -> {
                    if (!isCalendarWorthy(span)) return@mapNotNull null
                    DetectedAction(
                        kind = ActionKind.DateTime,
                        span = span,
                        timestampMillis = (entity as? DateTimeEntity)?.timestampMillis,
                    )
                }
                Entity.TYPE_ADDRESS -> DetectedAction(ActionKind.Address, span)
                Entity.TYPE_PHONE -> {
                    if (!isLikelyPhone(span)) return@mapNotNull null
                    DetectedAction(ActionKind.Phone, span)
                }
                Entity.TYPE_EMAIL -> DetectedAction(ActionKind.Email, span)
                else -> null
            }
        }
    }

    /**
     * ML Kit annotates every "now", "today", "tomorrow", etc. as a
     * DateTime — but those are almost always just filler ("hey, are
     * you free now?"), not anything worth opening a calendar for. A
     * useful event mention almost always carries a digit (3pm,
     * Tuesday at 3, next Friday at 9) or an explicit clock word
     * paired with a number — so gate chips on that.
     */
    private val DATE_FILLER = setOf(
        "now", "today", "tonight", "tomorrow", "yesterday",
        "soon", "later", "noon", "midnight",
        "morning", "afternoon", "evening", "night", "weekend",
    )

    private fun isCalendarWorthy(span: String): Boolean {
        val low = span.lowercase()
        if (low in DATE_FILLER) return false
        return span.any { it.isDigit() }
    }

    /**
     * ML Kit's phone matcher is generous — it'll trigger on anything
     * that looks vaguely numeric, including URL slugs (123-456.jpg)
     * and hex strings. Real phone numbers contain only digits and
     * standard separators (` -.+()`). Letters in the span almost
     * always mean we picked up part of a URL or filename.
     */
    private fun isLikelyPhone(span: String): Boolean {
        val digits = span.count { it.isDigit() }
        if (digits < 7) return false
        val invalid = span.count { !it.isDigit() && it !in " -.+()/" }
        return invalid == 0
    }
}

/** What kind of action a detected entity supports. */
enum class ActionKind { DateTime, Address, Phone, Email }

/** A single detected entity ready to be rendered as a chip. */
data class DetectedAction(
    val kind: ActionKind,
    val span: String,
    /** For DateTime entities, ML Kit's parsed millis since epoch (UTC). */
    val timestampMillis: Long? = null,
)

/** Bridge a Play-Services Task to a coroutine without pulling in the play-services-tasks-coroutines dep. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { e ->
        if (cont.isActive) cont.resumeWithException(e)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.cancel()
    }
}
